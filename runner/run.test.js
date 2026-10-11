'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const {
  applyApplicationContext,
  buildPlan,
  createRuntimeComposeOverride,
  dockerBuildCachePruneArguments,
  parseCleanupDockerPreserveImagesOption,
  parseExistingEnvironment,
  reconcileEnvironment,
  main,
  workerInstances,
} = require('./run');

function templateEnvironment() {
  return parseExistingEnvironment(
    fs.readFileSync(path.join(__dirname, '..', '.env.template'), 'utf8'),
  ).values;
}

function environmentFixture(context, template, existing) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-env-test-'));
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const templatePath = path.join(directory, '.env.template');
  const envPath = path.join(directory, '.env');
  fs.writeFileSync(templatePath, template);
  if (existing !== undefined) {
    fs.writeFileSync(envPath, existing);
  }
  return { directory, templatePath, envPath };
}

test('reconcileEnvironment updates dependency values and preserves unrelated configuration, secrets and CRLF', (context) => {
  const fixture = environmentFixture(context,
    'POSTGRES_IMAGE=postgres:18.6-alpine\nTOOL_VERSION=2.0\nPUBLIC_PORT=80\nAPP_ADMIN_PASSWORD=<GENERATE_APP_ADMIN_PASSWORD>\n',
    '# My configuration\r\nexport POSTGRES_IMAGE = "custom/postgres:old"\r\nTOOL_VERSION=1.0\r\nPUBLIC_PORT=8443\r\nAPP_ADMIN_PASSWORD=keep-this-secret\r\nPRIVATE_SETTING=local\r\n',
  );
  const result = reconcileEnvironment(fixture.templatePath, fixture.envPath);
  assert.deepEqual(result.updatedKeys, ['POSTGRES_IMAGE', 'TOOL_VERSION']);
  assert.deepEqual(result.addedKeys, []);
  assert.deepEqual(result.generatedSecrets, []);
  assert.equal(fs.readFileSync(fixture.envPath, 'utf8'),
    '# My configuration\r\nexport POSTGRES_IMAGE = postgres:18.6-alpine\r\nTOOL_VERSION=2.0\r\nPUBLIC_PORT=8443\r\nAPP_ADMIN_PASSWORD=keep-this-secret\r\nPRIVATE_SETTING=local\r\n');
  assert.deepEqual(reconcileEnvironment(fixture.templatePath, fixture.envPath).updatedKeys, []);
});

test('reconcileEnvironment opt-out preserves custom dependency values while adding missing settings', (context) => {
  const fixture = environmentFixture(context,
    'POSTGRES_IMAGE=postgres:18.6-alpine\nTOOL_VERSION=2.0\nNEW_IMAGE=example:1.0\nPUBLIC_PORT=80\n',
    'POSTGRES_IMAGE=custom/postgres@sha256:custom\nTOOL_VERSION=custom\nPUBLIC_PORT=8443\n',
  );
  const result = reconcileEnvironment(fixture.templatePath, fixture.envPath, { avoidUpdateDependencies: true });
  assert.deepEqual(result.updatedKeys, []);
  assert.deepEqual(result.addedKeys, ['NEW_IMAGE']);
  assert.equal(result.environment.get('POSTGRES_IMAGE'), 'custom/postgres@sha256:custom');
  assert.equal(result.environment.get('TOOL_VERSION'), 'custom');
  assert.equal(result.environment.get('PUBLIC_PORT'), '8443');
  assert.equal(result.environment.get('NEW_IMAGE'), 'example:1.0');
});

test('reconcileEnvironment applies updates to duplicate assignments without losing the BOM or final newline state', (context) => {
  const fixture = environmentFixture(context, 'TOOL_VERSION=2.0\n', '\uFEFFTOOL_VERSION=0.9\nTOOL_VERSION=1.0');
  const result = reconcileEnvironment(fixture.templatePath, fixture.envPath);
  assert.deepEqual(result.duplicates, ['TOOL_VERSION']);
  assert.equal(fs.readFileSync(fixture.envPath, 'utf8'), '\uFEFFTOOL_VERSION=2.0\nTOOL_VERSION=2.0');
});

test('reconcileEnvironment creates a complete environment even when dependency updates are disabled', (context) => {
  const fixture = environmentFixture(context, 'TOOL_VERSION=2.0\nPASSWORD=<GENERATE_PASSWORD>\n');
  const result = reconcileEnvironment(fixture.templatePath, fixture.envPath, { avoidUpdateDependencies: true });
  assert.equal(result.created, true);
  assert.deepEqual(result.updatedKeys, []);
  assert.equal(result.environment.get('TOOL_VERSION'), '2.0');
  assert.match(result.environment.get('PASSWORD'), /^[A-Za-z0-9_-]{43}$/);
});

test('configure-only forwards --avoid-update-dependencies before environment reconciliation', async (context) => {
  const template = fs.readFileSync(path.join(__dirname, '..', '.env.template'), 'utf8');
  const fixture = environmentFixture(context, template);
  reconcileEnvironment(fixture.templatePath, fixture.envPath);
  fs.appendFileSync(fixture.envPath, 'BACKEND_BUILD_IMAGE=custom/maven:retained\nCREATE_PRIVATE_KEY_PART_CLASS=false\n');
  await main(['--configure-only', '--non-interactive', '--avoid-update-dependencies'], fixture.directory);
  assert.equal(parseExistingEnvironment(fs.readFileSync(fixture.envPath, 'utf8')).values.get('BACKEND_BUILD_IMAGE'), 'custom/maven:retained');
  await main(['--configure-only', '--non-interactive'], fixture.directory);
  assert.equal(parseExistingEnvironment(fs.readFileSync(fixture.envPath, 'utf8')).values.get('BACKEND_BUILD_IMAGE'), templateEnvironment().get('BACKEND_BUILD_IMAGE'));
});

const SKIP_ALL_OPTIONS = {
  skipKeycloak: true,
  skipRedis: true,
  skipDatabase: true,
  skipBackend: true,
  skipFrontend: true,
  skipPublisher: true,
  skipWorkerStandard: true,
  skipWorkerPlaywright: true,
  skipWorkerCodex: true,
};

test('dockerBuildCachePruneArguments preserves dependency cache mounts', () => {
  assert.deepEqual(dockerBuildCachePruneArguments(), [
    'buildx',
    'prune',
    '--all',
    '--force',
    '--filter',
    'type!=exec.cachemount',
  ]);
});

test('parseCleanupDockerPreserveImagesOption keeps cleanup disabled when omitted', () => {
  assert.deepEqual(parseCleanupDockerPreserveImagesOption([]), {
    enabled: false,
    imagePatterns: [],
  });
});

test('parseCleanupDockerPreserveImagesOption enables cleanup and deduplicates preserved patterns', () => {
  assert.deepEqual(
    parseCleanupDockerPreserveImagesOption([
      '--cleanup-docker-preserve-images=maven:*; monitoring-* ;maven:*',
    ]),
    {
      enabled: true,
      imagePatterns: ['maven:*', 'monitoring-*'],
    },
  );
});

test('parseCleanupDockerPreserveImagesOption requires non-empty preserved patterns', () => {
  assert.throws(
    () => parseCleanupDockerPreserveImagesOption(['--cleanup-docker-preserve-images']),
    /requires a non-empty semicolon-separated image pattern list/,
  );
  assert.throws(
    () => parseCleanupDockerPreserveImagesOption(['--cleanup-docker-preserve-images=']),
    /patterns must not be empty/,
  );
});

test('parseCleanupDockerPreserveImagesOption rejects duplicate options', () => {
  assert.throws(
    () => parseCleanupDockerPreserveImagesOption([
      '--cleanup-docker-preserve-images=maven:*',
      '--cleanup-docker-preserve-images=monitoring-*',
    ]),
    /may only be specified once/,
  );
});

test('workerInstances appends stable one-based suffixes when unique names are enabled', () => {
  assert.deepEqual(workerInstances('worker-standard', 'alertify-worker-standard', 2, true), [
    { serviceName: 'worker-standard-1', workerName: 'alertify-worker-standard-1' },
    { serviceName: 'worker-standard-2', workerName: 'alertify-worker-standard-2' },
  ]);
});

test('workerInstances preserves a shared logical name when unique names are disabled', () => {
  assert.deepEqual(workerInstances('worker-playwright', 'browser-worker', 2, false), [
    { serviceName: 'worker-playwright-1', workerName: 'browser-worker' },
    { serviceName: 'worker-playwright-2', workerName: 'browser-worker' },
  ]);
});

test('createRuntimeComposeOverride assigns one configured name to each generated worker service', (context) => {
  const projectDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-runner-test-'));
  context.after(() => fs.rmSync(projectDirectory, { recursive: true, force: true }));
  const plan = {
    workerStandardInstances: workerInstances('worker-standard', 'standard', 2, true),
    workerPlaywrightInstances: workerInstances('worker-playwright', 'playwright', 2, true),
  };

  const override = createRuntimeComposeOverride(plan, projectDirectory);
  context.after(() => fs.rmSync(override.directory, { recursive: true, force: true }));
  const content = fs.readFileSync(override.path, 'utf8');

  assert.match(content, /worker-standard-1:/);
  assert.match(content, /WORKER_NAME: "standard-1"/);
  assert.match(content, /worker-playwright-2:/);
  assert.match(content, /WORKER_NAME: "playwright-2"/);
  assert.equal((content.match(/replicas: 1/g) ?? []).length, 4);
});

test('createRuntimeComposeOverride publishes HTTPS and mounts only the publisher leaf volume', (context) => {
  const projectDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-runner-test-'));
  context.after(() => fs.rmSync(projectDirectory, { recursive: true, force: true }));
  const plan = {
    publisherTlsEnabled: true,
    publisherTlsServerName: 'alertify',
    workerStandardInstances: [],
    workerPlaywrightInstances: [],
  };

  const override = createRuntimeComposeOverride(plan, projectDirectory);
  context.after(() => fs.rmSync(override.directory, { recursive: true, force: true }));
  const content = fs.readFileSync(override.path, 'utf8');

  assert.match(content, /PUBLISHER_TLS_ENABLED: "true"/);
  assert.match(content, /PUBLIC_HTTP_PORT.*:8080/);
  assert.match(content, /PUBLIC_PORT.*:8443/);
  assert.match(content, /publisher_tls:\/run\/alertify-publisher-tls:ro/);
  assert.doesNotMatch(content, /publisher-tls-ca/);
});

test('buildPlan disables additional host ports by default', () => {
  const plan = buildPlan(applyApplicationContext(templateEnvironment()), SKIP_ALL_OPTIONS);

  assert.equal(plan.publishAdditionalPorts, false);
  assert.equal(plan.identityDatabaseHostPort, null);
  assert.equal(plan.keycloakAdminPort, null);
  assert.equal(plan.keycloakAdminUrl, null);
  assert.equal(plan.applicationDatabaseHostPort, null);
});

test('createRuntimeComposeOverride publishes enabled local service ports without disabled debug', (context) => {
  const environment = templateEnvironment();
  environment.set('PUBLISH_ADDITIONAL_PORTS', 'true');
  const plan = buildPlan(applyApplicationContext(environment), SKIP_ALL_OPTIONS);
  const projectDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-runner-test-'));
  context.after(() => fs.rmSync(projectDirectory, { recursive: true, force: true }));

  const override = createRuntimeComposeOverride(plan, projectDirectory);
  context.after(() => fs.rmSync(override.directory, { recursive: true, force: true }));
  const content = fs.readFileSync(override.path, 'utf8');

  assert.match(content, /IDENTITY_DB_HOST_PORT.*:5432/);
  assert.match(content, /KEYCLOAK_HTTP_PORT.*:8080/);
  assert.match(content, /DATABASE_HOST_PORT.*:5432/);
  assert.doesNotMatch(content, /BACKEND_DEBUG_PORT/);
});

test('buildPlan rejects remote debug when additional host ports are disabled', () => {
  const environment = templateEnvironment();
  environment.set('BACKEND_DEBUG_ENABLED', 'true');

  assert.throws(
    () => buildPlan(applyApplicationContext(environment), SKIP_ALL_OPTIONS),
    /BACKEND_DEBUG_ENABLED=true requires PUBLISH_ADDITIONAL_PORTS=true/,
  );
});

test('createRuntimeComposeOverride publishes remote debug when both switches are enabled', (context) => {
  const environment = templateEnvironment();
  environment.set('PUBLISH_ADDITIONAL_PORTS', 'true');
  environment.set('BACKEND_DEBUG_ENABLED', 'true');
  const plan = buildPlan(applyApplicationContext(environment), SKIP_ALL_OPTIONS);
  const projectDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-runner-test-'));
  context.after(() => fs.rmSync(projectDirectory, { recursive: true, force: true }));

  const override = createRuntimeComposeOverride(plan, projectDirectory);
  context.after(() => fs.rmSync(override.directory, { recursive: true, force: true }));
  const content = fs.readFileSync(override.path, 'utf8');

  assert.match(content, /BACKEND_DEBUG_PORT.*:.*BACKEND_DEBUG_PORT/);
});

test('additional host ports do not create overrides for external services', () => {
  const environment = templateEnvironment();
  environment.set('PUBLISH_ADDITIONAL_PORTS', 'true');
  environment.set('KEYCLOAK_MODE', 'external');
  environment.set('BACKEND_MODE', 'external');
  const plan = buildPlan(applyApplicationContext(environment), SKIP_ALL_OPTIONS);

  assert.equal(plan.identityDatabaseHostPort, null);
  assert.equal(plan.keycloakAdminPort, null);
  assert.equal(plan.applicationDatabaseHostPort, null);
  assert.equal(createRuntimeComposeOverride(plan, __dirname), null);
});

test('applyApplicationContext derives a path-free public origin independently of the configured context', () => {
  const template = parseExistingEnvironment(
    fs.readFileSync(path.join(__dirname, '..', '.env.template'), 'utf8'),
  ).values;
  template.set('APP_CONTEXT_PATH', '/tenant/alertify');
  template.set('APP_PUBLIC_URL', 'https://alertify.example:8443');

  const contextualEnvironment = applyApplicationContext(template);

  assert.equal(contextualEnvironment.get('APP_CONTEXT_PATH'), '/tenant/alertify');
  assert.equal(contextualEnvironment.get('APP_PUBLIC_URL'), 'https://alertify.example:8443/tenant/alertify');
  assert.equal(contextualEnvironment.get('APP_PUBLIC_ORIGIN'), 'https://alertify.example:8443');
  assert.equal(contextualEnvironment.get('AI_OAUTH_CALLBACK_URI'), 'http://127.0.0.1:80/tenant/alertify/api/ai/codex/oauth/callback');
  assert.equal(contextualEnvironment.get('AI_OAUTH_EXTENSION_CALLBACK_URI'), 'http://127.0.0.1:53682/tenant/alertify/api/ai/codex/oauth/callback');
});

test('buildPlan enforces one dedicated worker with AI and CODEX capabilities', () => {
  const environment = applyApplicationContext(templateEnvironment());
  environment.set('WORKER_CODEX_REPLICAS', '2');
  assert.throws(() => buildPlan(environment, SKIP_ALL_OPTIONS), /WORKER_CODEX_REPLICAS must be exactly 1/);

  environment.set('WORKER_CODEX_REPLICAS', '1');
  environment.set('WORKER_CODEX_CAPABILITIES', 'AI');
  assert.throws(() => buildPlan(environment, SKIP_ALL_OPTIONS), /must contain AI and CODEX/);
});

test('buildPlan requires externally configured initial AI settings', () => {
  const environment = applyApplicationContext(templateEnvironment());
  environment.delete('AI_DEFAULT_MODEL');

  assert.throws(() => buildPlan(environment, SKIP_ALL_OPTIONS), /AI_DEFAULT_MODEL/);
});

test('applyApplicationContext derives the public origin when the application is published at root', () => {
  const template = parseExistingEnvironment(
    fs.readFileSync(path.join(__dirname, '..', '.env.template'), 'utf8'),
  ).values;
  template.set('APP_CONTEXT_PATH', '/');
  template.set('APP_PUBLIC_URL', 'https://alertify.example');

  const contextualEnvironment = applyApplicationContext(template);

  assert.equal(contextualEnvironment.get('APP_CONTEXT_PATH'), '/');
  assert.equal(contextualEnvironment.get('APP_PUBLIC_URL'), 'https://alertify.example');
  assert.equal(contextualEnvironment.get('APP_PUBLIC_ORIGIN'), 'https://alertify.example');
});

test('buildPlan keeps localhost and subdomains of localhost on HTTP without publisher TLS', () => {
  const template = parseExistingEnvironment(
    fs.readFileSync(path.join(__dirname, '..', '.env.template'), 'utf8'),
  ).values;
  const skipOptions = {
    skipKeycloak: true,
    skipRedis: true,
    skipDatabase: true,
    skipBackend: true,
    skipFrontend: true,
    skipPublisher: true,
    skipWorkerStandard: true,
    skipWorkerPlaywright: true,
  };

  const localhostPlan = buildPlan(applyApplicationContext(template), skipOptions);
  assert.equal(localhostPlan.publisherTlsEnabled, false);
  assert.equal(localhostPlan.publicHttpPort, null);

  template.set('APP_PUBLIC_URL', 'http://tenant.localhost');
  template.set('BACKEND_PUBLIC_URL', 'http://tenant.localhost');
  template.set('KEYCLOAK_PUBLIC_URL', 'http://tenant.localhost/identity');
  template.set('OIDC_ISSUER_URI', 'http://tenant.localhost/identity/realms/monitoring');
  const subdomainPlan = buildPlan(applyApplicationContext(template), skipOptions);
  assert.equal(subdomainPlan.publisherTlsEnabled, false);
  assert.equal(subdomainPlan.publicHttpPort, null);
});

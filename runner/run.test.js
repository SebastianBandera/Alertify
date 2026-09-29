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
  workerInstances,
} = require('./run');

function templateEnvironment() {
  return parseExistingEnvironment(
    fs.readFileSync(path.join(__dirname, '..', '.env.template'), 'utf8'),
  ).values;
}

const SKIP_ALL_OPTIONS = {
  skipKeycloak: true,
  skipRedis: true,
  skipDatabase: true,
  skipBackend: true,
  skipFrontend: true,
  skipPublisher: true,
  skipWorkerStandard: true,
  skipWorkerPlaywright: true,
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

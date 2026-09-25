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
  parseExistingEnvironment,
  workerInstances,
} = require('./run');

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

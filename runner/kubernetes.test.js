'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const http = require('node:http');
const { parseOptions, renderTemplate, createResources, isSecretEnvironment, redactUrl, normalizedImageReference, importedImageDigests, kubernetesBackendUpstream, verifyLocalPublicApi } = require('./kubernetes');

test('Kubernetes selection rejects skips and cleanup while preserving explicit target', () => {
  assert.throws(() => parseOptions(['--skip-backend']), /does not accept/);
  assert.throws(() => parseOptions(['--cleanup-docker-preserve-images=x']), /does not accept/);
  assert.throws(() => parseOptions(['--kube-context=']), /nonempty/);
  assert.throws(() => parseOptions(['--kube-context=a', '--kube-context=b']), /exactly one/);
  assert.equal(parseOptions(['--kube-context=local', '--non-interactive']).context, 'local');
});

test('template substitution escapes credentials and rejects unresolved values', () => {
  assert.deepEqual(renderTemplate('{"data":{{DATA}}}', { DATA: { password: 'a"\\\nb' } }), { data: { password: 'a"\\\nb' } });
  assert.deepEqual(renderTemplate('{"data":{{DATA}}}', { DATA: '{{env.EXAMPLE}}' }), { data: '{{env.EXAMPLE}}' });
  assert.throws(() => renderTemplate('{{UNKNOWN}}', {}), /Unknown/);
  assert.throws(() => renderTemplate('"{{not-valid}}"', {}), /Unresolved/);
});

test('credentials in URL values are protected and display URLs are redacted', () => {
  assert.equal(isSecretEnvironment('UPSTREAM', 'https://user:password@example.test'), true);
  assert.equal(isSecretEnvironment('UPSTREAM', 'https://example.test?token=private'), true);
  assert.equal(isSecretEnvironment('UPSTREAM', 'https://example.test/path'), false);
  assert.equal(isSecretEnvironment('KEY_ENV_PART', 'private'), true);
  const redacted = redactUrl('https://user:password@example.test/path?token=private&public=value');
  assert.equal(redacted.includes('password'), false);
  assert.equal(redacted.includes('private'), false);
  assert.equal(redacted.includes('public=value'), true);
});

test('kind image reuse checks normalized references and actual manifest digests', () => {
  assert.equal(normalizedImageReference('monitoring-backend:latest'), 'docker.io/library/monitoring-backend:latest');
  assert.equal(normalizedImageReference('docker.io/postgres:18.4-alpine'), 'docker.io/library/postgres:18.4-alpine');
  assert.equal(normalizedImageReference('team/backend'), 'docker.io/team/backend:latest');
  assert.equal(normalizedImageReference('localhost:5000/team/backend:v1'), 'localhost:5000/team/backend:v1');
  const digest = `sha256:${'a'.repeat(64)}`;
  const actual = importedImageDigests(`REF TYPE DIGEST SIZE PLATFORMS LABELS\ndocker.io/library/monitoring-backend:latest application/vnd.oci.image.manifest.v1+json ${digest} 161.8 MiB linux/amd64 managed\n`);
  assert.equal(actual.get('docker.io/library/monitoring-backend:latest'), digest);
  assert.equal(actual.has('REF'), false);
});

test('dynamic Nginx backend resolution uses a full namespace and cluster DNS suffix', () => {
  assert.equal(kubernetesBackendUpstream('http://backend:8080', 'alertify', 'cluster.local'), 'http://backend.alertify.svc.cluster.local:8080');
  assert.equal(kubernetesBackendUpstream('http://backend:8080', 'demo', 'internal.example'), 'http://backend.demo.svc.internal.example:8080');
  assert.equal(kubernetesBackendUpstream('https://backend.example:8443', 'demo', 'cluster.local'), 'https://backend.example:8443');
});

test('public API readiness preserves the configured host and context and rejects proxy failures', async () => {
  let status = 401;
  const requests = [];
  const server = http.createServer((request, response) => {
    requests.push({ host: request.headers.host, path: request.url, authorization: request.headers.authorization });
    response.writeHead(status); response.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const port = server.address().port;
    const plan = { publisherUrl: `http://alertify.invalid:${port}/alertify` };
    assert.equal(await verifyLocalPublicApi(plan, __dirname), 401);
    assert.deepEqual(requests[0], { host: `alertify.invalid:${port}`, path: '/alertify/api/alert-executions', authorization: undefined });
    status = 502;
    await assert.rejects(verifyLocalPublicApi(plan, __dirname), /HTTP 502/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('resources isolate secrets, persist databases and discover both worker types', () => {
  const templates = Object.fromEntries(['namespace', 'configmap', 'secret', 'pvc', 'deployment', 'statefulset', 'service'].map((name) => [name, fs.readFileSync(path.join(__dirname, '..', 'kubernetes', `${name}.yaml.template`), 'utf8')]));
  templates.publisherNginx = fs.readFileSync(path.join(__dirname, '..', 'publisher', 'default.conf.template'), 'utf8');
  templates.keycloakRealm = fs.readFileSync(path.join(__dirname, '..', 'identity', 'realm-template.json'), 'utf8');
  templates.keycloakAdmin = fs.readFileSync(path.join(__dirname, '..', 'identity', 'configure-permanent-admin.sh'), 'utf8');
  const environment = new Map(Object.entries({
    APP_CONTEXT_PATH: '/alertify', APP_PUBLIC_URL: 'https://alertify/alertify', APP_PUBLIC_ORIGIN: 'https://alertify',
    WORKER_GRPC_HOST: 'worker', WORKER_GRPC_PORT: '9090', WORKER_GRPC_TLS_ENABLED: 'true',
    WORKER_STANDARD_UNIQUE_NAMES: 'true', WORKER_PLAYWRIGHT_UNIQUE_NAMES: 'true',
    KUBERNETES_DATABASE_STORAGE: '5Gi', PUBLISHER_TLS_SERVER_NAME: 'alertify',
    KUBERNETES_CLUSTER_DNS: '10.96.0.10',
    KUBERNETES_INGRESS_ENABLED: 'false',
  }));
  const resources = createResources({ services: {
    database: { image: 'database', environment: { POSTGRES_PASSWORD: 'private', POSTGRES_USER: 'postgres' } },
    backend: { image: 'backend', environment: { KEY_ENV_PART: 'private' } },
    identity: { image: 'identity', environment: {} },
    publisher: { image: 'publisher', environment: {} },
    'worker-standard': { image: 'worker-standard', environment: { WORKER_NAME: 'base' }, deploy: { replicas: 2 } },
    'worker-playwright': { image: 'worker-playwright', environment: { WORKER_NAME: 'base' } },
  } }, environment, 'test', templates);
  const find = (kind, name) => resources.find((resource) => resource.kind === kind && resource.metadata.name === name);
  assert.equal(find('ConfigMap', 'database-config').data.POSTGRES_PASSWORD, undefined);
  assert.equal(find('Secret', 'database-credentials').stringData.POSTGRES_PASSWORD, 'private');
  assert.equal(find('PersistentVolumeClaim', 'database-data').spec.resources.requests.storage, '5Gi');
  assert.equal(find('Service', 'worker').spec.clusterIP, 'None');
  const backend = find('Deployment', 'backend').spec.template.spec;
  assert.deepEqual(backend.volumes.map((volume) => volume.secret.secretName), ['grpc-backend-tls']);
  const publisher = find('Deployment', 'publisher').spec.template.spec;
  assert.deepEqual(publisher.volumes.filter((volume) => volume.secret).map((volume) => volume.secret.secretName), ['publisher-tls']);
  assert.match(find('ConfigMap', 'publisher-routing-template').data['default.conf.template'], /resolver 10\.96\.0\.10/);
  assert.equal(find('ConfigMap', 'publisher-routing-template').data['default.conf.template'].includes('resolver 127.0.0.11'), false);
  const worker = find('Deployment', 'worker-standard');
  assert.equal(worker.spec.replicas, 2);
  assert.equal(worker.spec.template.spec.containers[0].env[0].valueFrom.fieldRef.fieldPath, 'metadata.name');
  assert.equal(worker.spec.template.metadata.labels['app.alertify/worker'], 'true');
  assert.equal(find('Deployment', 'worker-playwright').spec.template.metadata.labels['app.alertify/worker'], 'true');
  assert.equal(backend.automountServiceAccountToken, false);
  assert.match(find('StatefulSet', 'database').spec.template.spec.containers[0].readinessProbe.exec.command[2], /database-migrations-complete/);
  assert.equal(find('Deployment', 'backend').spec.template.spec.containers[0].readinessProbe.httpGet.path, '/alertify/actuator/health');
  assert.deepEqual(find('Service', 'publisher').spec.ports.map((port) => port.port), [8080, 8443]);
  assert.match(find('ConfigMap', 'identity-initialization').data['realm-template.json'], /"webOrigins": \["\$\{APP_PUBLIC_ORIGIN\}"\]/);
  assert.match(find('ConfigMap', 'identity-initialization').data['configure-permanent-admin.sh'], /webOrigins=\[\\"\$\{APP_PUBLIC_ORIGIN\}\\"\]/);
  assert.match(find('ConfigMap', 'identity-initialization').data['configure-permanent-admin.sh'], /redirectUris=\[\\"\$\{app_public_url\}\/\*\\"\]/);
  const changedTemplates = { ...templates, publisherNginx: `${templates.publisherNginx}\n# updated routing\n`, keycloakAdmin: `${templates.keycloakAdmin}\n# updated admin\n` };
  const changed = createResources({ services: {
    publisher: { image: 'publisher', environment: {} }, identity: { image: 'identity', environment: {} },
  } }, environment, 'test', changedTemplates);
  for (const name of ['publisher', 'identity']) {
    const changedDeployment = changed.find((resource) => resource.kind === 'Deployment' && resource.metadata.name === name);
    assert.notEqual(changedDeployment.spec.template.metadata.annotations['app.alertify/config-sha256'], find('Deployment', name).spec.template.metadata.annotations['app.alertify/config-sha256']);
  }

  environment.set('APP_CONTEXT_PATH', '/');
  environment.set('APP_PUBLIC_URL', 'http://localhost');
  environment.set('WORKER_GRPC_TLS_ENABLED', 'false');
  environment.set('WORKER_STANDARD_UNIQUE_NAMES', 'false');
  environment.set('KUBERNETES_REGISTRY', 'registry.example.test/alertify');
  const httpResources = createResources({ services: {
    backend: { image: 'backend', environment: {} },
    publisher: { image: 'publisher', environment: {} },
    'worker-standard': { image: 'standard', environment: { WORKER_NAME: 'shared' } },
  } }, environment, 'http-test', templates);
  const httpBackend = httpResources.find((resource) => resource.kind === 'Deployment' && resource.metadata.name === 'backend').spec.template.spec;
  assert.equal(httpBackend.volumes, undefined);
  assert.equal(httpBackend.containers[0].readinessProbe.httpGet.path, '/actuator/health');
  assert.equal(httpBackend.containers[0].imagePullPolicy, 'Always');
  const httpPublisher = httpResources.find((resource) => resource.kind === 'Deployment' && resource.metadata.name === 'publisher').spec.template.spec;
  assert.equal(httpPublisher.volumes.some((volume) => volume.secret), false);
  assert.deepEqual(httpResources.find((resource) => resource.kind === 'Service' && resource.metadata.name === 'publisher').spec.ports.map((port) => port.port), [8080]);
  assert.equal(httpResources.find((resource) => resource.kind === 'ConfigMap' && resource.metadata.name === 'worker-standard-config').data.WORKER_NAME, 'shared');
});

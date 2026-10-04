'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { renderTemplate } = require('./kubernetes');
const { ingressOptions, createIngressResources, ingressCertificateResources, stopLegacyForwarding } = require('./kubernetes-ingress');

const templates = Object.fromEntries(['traefik', 'ingress'].map((name) => [name, fs.readFileSync(path.join(__dirname, '..', 'kubernetes', `${name}.yaml.template`), 'utf8')]));
const environment = () => new Map(Object.entries({
  APP_PUBLIC_URL: 'https://alertify.dev/alertify', PUBLIC_PORT: '443', PUBLIC_HTTP_PORT: '80',
}));

test('Ingress routes root and contextual paths without rewriting and verifies publisher TLS', () => {
  const resources = createIngressResources(environment(), 'demo', templates, renderTemplate);
  const find = (kind, name) => resources.find((resource) => resource.kind === kind && resource.metadata.name === name);
  const ingress = find('Ingress', 'alertify');
  assert.equal(ingress.spec.rules[0].host, 'alertify.dev');
  assert.equal(ingress.spec.rules[0].http.paths[0].path, '/');
  assert.equal(ingress.spec.rules[0].http.paths[0].backend.service.port.number, 8443);
  assert.equal(ingress.spec.tls[0].secretName, 'traefik-public-tls');
  assert.deepEqual(find('Service', 'traefik').spec.ports.map((port) => port.port), [80, 443]);
  const pod = find('Deployment', 'traefik').spec.template.spec;
  assert.equal(pod.containers[0].args.includes('--providers.kubernetesingress.namespaces=demo'), true);
  assert.equal(pod.containers[0].args.includes('--api.dashboard=false'), true);
  assert.deepEqual(pod.volumes.filter((volume) => volume.secret).map((volume) => volume.secret.secretName), ['traefik-custom-tls', 'traefik-publisher-ca']);
  const transport = JSON.parse(find('ConfigMap', 'traefik-config').data['config.yaml']).http.serversTransports.publisher;
  assert.equal(transport.serverName, 'alertify.dev');
  assert.equal(transport.insecureSkipVerify, undefined);
  assert.equal(find('Role', 'traefik').metadata.namespace, 'demo');
  assert.equal(find('ClusterRole', 'demo-traefik').rules.some((rule) => rule.resources.includes('secrets')), false);
});

test('local routes and leaf certificates remain independently owned across deployments', () => {
  const resources = createIngressResources(environment(), 'demo', templates, renderTemplate);
  const pod = resources.find((resource) => resource.kind === 'Deployment').spec.template.spec;
  const dynamic = pod.volumes.find((volume) => volume.name === 'dynamic');
  assert.deepEqual(dynamic.projected.sources, [
    { configMap: { name: 'traefik-config' } },
    { configMap: { name: 'traefik-custom-routes', optional: true } },
  ]);
  assert.equal(pod.volumes.find((volume) => volume.name === 'custom-tls').secret.optional, true);
  assert.equal(pod.containers[0].args.includes('--providers.file.directory=/etc/traefik/dynamic'), true);
  assert.equal(pod.containers[0].args.some((arg) => arg.startsWith('--providers.file.filename=')), false);
  assert.equal(resources.some((resource) => ['traefik-custom-routes', 'traefik-custom-tls'].includes(resource.metadata.name)), false);
});

test('Ingress preserves localhost HTTP and supports custom class, image and public ports', () => {
  const values = environment();
  values.set('APP_PUBLIC_URL', 'http://localhost');
  values.set('PUBLIC_PORT', '8081');
  values.set('KUBERNETES_INGRESS_CLASS', 'demo-ingress');
  values.set('KUBERNETES_TRAEFIK_SERVICE_TYPE', 'NodePort');
  const resources = createIngressResources(values, 'demo', templates, renderTemplate);
  const ingress = resources.find((resource) => resource.kind === 'Ingress');
  assert.equal(ingress.spec.ingressClassName, 'demo-ingress');
  assert.equal(ingress.spec.tls, undefined);
  assert.equal(ingress.spec.rules[0].http.paths[0].backend.service.port.number, 8080);
  const service = resources.find((resource) => resource.kind === 'Service');
  assert.equal(service.spec.type, 'NodePort');
  assert.deepEqual(service.spec.ports.map((port) => port.port), [8081]);
  const container = resources.find((resource) => resource.kind === 'Deployment').spec.template.spec.containers[0];
  assert.equal(container.args.some((arg) => arg.includes('redirections')), false);
  assert.equal(resources.some((resource) => resource.kind === 'Secret'), false);
  values.set('KUBERNETES_INGRESS_ENABLED', 'false');
  assert.deepEqual(createIngressResources(values, 'demo', {}, renderTemplate), []);
});

test('Ingress rejects unsupported service types, unpinned images and invalid classes', () => {
  for (const [key, value] of [['KUBERNETES_TRAEFIK_SERVICE_TYPE', 'ExternalName'], ['KUBERNETES_TRAEFIK_IMAGE', 'traefik:latest'], ['KUBERNETES_INGRESS_CLASS', '../other'], ['KUBERNETES_INGRESS_ENABLED', 'yes']]) {
    const values = environment(); values.set(key, value);
    assert.throws(() => ingressOptions(values), new RegExp(key));
  }
});

test('Traefik certificate reconciliation copies only the leaf key and public CA', () => {
  const resources = ingressCertificateResources('demo', { data: { 'publisher-server.crt': 'certificate', 'publisher-server.key': 'leaf-key' } }, { data: { 'alertify-local-ca.crt': 'public-ca', 'alertify-local-ca.key': 'private-ca-key' } });
  assert.equal(resources[0].type, 'kubernetes.io/tls');
  assert.deepEqual(resources[0].data, { 'tls.crt': 'certificate', 'tls.key': 'leaf-key' });
  assert.deepEqual(resources[1].data, { 'alertify-local-ca.crt': 'public-ca' });
  assert.equal(JSON.stringify(resources).includes('private-ca-key'), false);
  assert.throws(() => ingressCertificateResources('demo', { data: {} }, { data: {} }), /incomplete/);
});

test('retired forwarding is stopped by its unique marker without signaling a PID', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-forward-test-'));
  try {
    const stateDirectory = path.join(directory, '.alertify', 'kubernetes');
    fs.mkdirSync(stateDirectory, { recursive: true });
    const state = path.join(stateDirectory, 'demo-forward.json');
    fs.writeFileSync(state, JSON.stringify({ pid: process.pid, marker: '../other' }));
    await assert.rejects(stopLegacyForwarding(directory, 'demo'), /invalid/);
    const marker = '00000000-0000-4000-8000-000000000000';
    fs.writeFileSync(state, JSON.stringify({ pid: process.pid, marker }));
    assert.equal(await stopLegacyForwarding(directory, 'demo'), true);
    assert.equal(fs.existsSync(path.join(stateDirectory, `${marker}.stop`)), true);
    assert.equal(fs.existsSync(state), false);
    assert.equal(await stopLegacyForwarding(directory, 'demo'), false);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function ingressOptions(environment) {
  const value = (key) => (environment.get(key) ?? '').replace(/^(["'])(.*)\1$/, '$2');
  const enabled = value('KUBERNETES_INGRESS_ENABLED') || 'true';
  if (!['true', 'false'].includes(enabled)) throw new Error('KUBERNETES_INGRESS_ENABLED must be true or false.');
  const ingressClass = value('KUBERNETES_INGRESS_CLASS') || 'alertify-traefik';
  if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(ingressClass)) throw new Error('KUBERNETES_INGRESS_CLASS must be a DNS label.');
  const serviceType = value('KUBERNETES_TRAEFIK_SERVICE_TYPE') || 'LoadBalancer';
  if (!['LoadBalancer', 'NodePort', 'ClusterIP'].includes(serviceType)) throw new Error('KUBERNETES_TRAEFIK_SERVICE_TYPE must be LoadBalancer, NodePort or ClusterIP.');
  const image = value('KUBERNETES_TRAEFIK_IMAGE') || 'traefik:v3.7.13';
  if (!/^[a-zA-Z0-9][a-zA-Z0-9./:@_-]+$/.test(image) || image.endsWith(':latest') || (!image.includes('@sha256:') && !image.split('/').at(-1).includes(':'))) {
    throw new Error('KUBERNETES_TRAEFIK_IMAGE must specify a pinned image tag or digest.');
  }
  return { enabled: enabled === 'true', ingressClass, serviceType, image };
}

function createIngressResources(environment, namespace, templates, renderTemplate) {
  const options = ingressOptions(environment);
  if (!options.enabled) return [];
  const value = (key) => (environment.get(key) ?? '').replace(/^(["'])(.*)\1$/, '$2');
  const hostname = new URL(value('APP_PUBLIC_URL')).hostname;
  const secure = !/^localhost$|\.localhost$/i.test(hostname);
  const dynamic = secure ? { http: { serversTransports: { publisher: {
    serverName: hostname, rootCAs: ['/etc/traefik/ca/alertify-local-ca.crt'],
  } } } } : {};
  const args = [
    '--entrypoints.web.address=:8000', '--entrypoints.websecure.address=:8443',
    '--entrypoints.traefik.address=:9000', '--ping=true', '--ping.entrypoint=traefik',
    '--providers.kubernetesingress=true', `--providers.kubernetesingress.namespaces=${namespace}`,
    `--providers.kubernetesingress.ingressclass=${options.ingressClass}`,
    `--providers.kubernetesingress.ingressendpoint.publishedservice=${namespace}/traefik`,
    '--providers.file.directory=/etc/traefik/dynamic', '--providers.file.watch=true', '--api.dashboard=false',
    '--global.checknewversion=false', '--global.sendanonymoususage=false',
  ];
  if (secure) args.push('--entrypoints.web.http.redirections.entrypoint.scheme=https',
    `--entrypoints.web.http.redirections.entrypoint.to=:${value('PUBLIC_PORT') || '443'}`);
  // Local routes and leaf certificates are owned outside the Alertify deployment.
  const volumes = [
    { name: 'dynamic', projected: { sources: [
      { configMap: { name: 'traefik-config' } },
      { configMap: { name: 'traefik-custom-routes', optional: true } },
    ] } },
    { name: 'custom-tls', secret: { secretName: 'traefik-custom-tls', optional: true, defaultMode: 292 } },
  ];
  const volumeMounts = [
    { name: 'dynamic', mountPath: '/etc/traefik/dynamic', readOnly: true },
    { name: 'custom-tls', mountPath: '/etc/traefik/custom-tls', readOnly: true },
  ];
  if (secure) {
    volumes.push({ name: 'publisher-ca', secret: { secretName: 'traefik-publisher-ca', defaultMode: 292 } });
    volumeMounts.push({ name: 'publisher-ca', mountPath: '/etc/traefik/ca', readOnly: true });
  }
  const labels = { 'app.kubernetes.io/name': 'traefik', 'app.kubernetes.io/part-of': 'alertify' };
  const spec = {
    replicas: 1, strategy: { type: 'Recreate' }, selector: { matchLabels: { 'app.kubernetes.io/name': 'traefik' } },
    template: {
      metadata: { labels, annotations: { 'app.alertify/config-sha256': crypto.createHash('sha256').update(JSON.stringify([args, dynamic])).digest('hex') } },
      spec: {
        serviceAccountName: 'traefik', securityContext: { runAsNonRoot: true, runAsUser: 65532, runAsGroup: 65532 }, volumes,
        containers: [{
          name: 'traefik', image: options.image, imagePullPolicy: value('KUBERNETES_REGISTRY') ? 'Always' : 'IfNotPresent', args, volumeMounts,
          ports: [{ name: 'web', containerPort: 8000 }, { name: 'websecure', containerPort: 8443 }, { name: 'health', containerPort: 9000 }],
          securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ['ALL'] } },
          resources: { requests: { cpu: '50m', memory: '64Mi' }, limits: { memory: '256Mi' } },
          readinessProbe: { httpGet: { path: '/ping', port: 'health' }, periodSeconds: 5 },
          livenessProbe: { httpGet: { path: '/ping', port: 'health' }, periodSeconds: 10 },
        }],
      },
    },
  };
  const ports = secure
    ? [{ name: 'web', port: Number(value('PUBLIC_HTTP_PORT') || 80), targetPort: 'web' }, { name: 'websecure', port: Number(value('PUBLIC_PORT') || 443), targetPort: 'websecure' }]
    : [{ name: 'web', port: Number(value('PUBLIC_PORT') || 80), targetPort: 'web' }];
  const values = {
    NAMESPACE: namespace, CLASS_NAME: options.ingressClass, CLUSTER_ROLE: `${namespace}-traefik`,
    CONFIG: { 'config.yaml': JSON.stringify(dynamic) }, DEPLOYMENT_SPEC: spec,
    SERVICE_SPEC: { type: options.serviceType, selector: { 'app.kubernetes.io/name': 'traefik' }, ports },
    INGRESS_SPEC: {
      ingressClassName: options.ingressClass,
      ...(secure ? { tls: [{ hosts: [hostname], secretName: 'traefik-public-tls' }] } : {}),
      rules: [{ host: hostname, http: { paths: [{ path: '/', pathType: 'Prefix', backend: {
        service: { name: 'publisher', port: { number: secure ? 8443 : 8080 } },
      } }] } }],
    },
    INGRESS_ANNOTATIONS: {
      'kubernetes.io/ingress.class': options.ingressClass,
      'traefik.ingress.kubernetes.io/router.entrypoints': secure ? 'websecure' : 'web',
      ...(secure ? { 'traefik.ingress.kubernetes.io/router.tls': 'true' } : {}),
    },
  };
  return [...renderTemplate(templates.traefik, values).items, renderTemplate(templates.ingress, values)];
}

function ingressCertificateResources(namespace, publisherTls, publisherCa) {
  const certificate = publisherTls.data?.['publisher-server.crt'];
  const key = publisherTls.data?.['publisher-server.key'];
  const ca = publisherCa.data?.['alertify-local-ca.crt'];
  if (!certificate || !key || !ca) throw new Error('Publisher certificates are incomplete; Traefik TLS was not changed.');
  return [
    { apiVersion: 'v1', kind: 'Secret', metadata: { name: 'traefik-public-tls', namespace }, type: 'kubernetes.io/tls', data: { 'tls.crt': certificate, 'tls.key': key } },
    { apiVersion: 'v1', kind: 'Secret', metadata: { name: 'traefik-publisher-ca', namespace }, type: 'Opaque', data: { 'alertify-local-ca.crt': ca } },
  ];
}

async function stopLegacyForwarding(projectDirectory, namespace) {
  const directory = path.join(projectDirectory, '.alertify', 'kubernetes');
  const stateFile = path.join(directory, `${namespace}-forward.json`);
  if (!fs.existsSync(stateFile)) return false;
  const state = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
  if (!/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(state.marker ?? '')) {
    throw new Error('Legacy forwarding marker is invalid; no process was stopped.');
  }
  // The retained helper observes this unique marker. Never kill a possibly reused PID.
  fs.writeFileSync(path.join(directory, `${state.marker}.stop`), 'stop');
  await new Promise((resolve) => setTimeout(resolve, 1500));
  fs.unlinkSync(stateFile);
  return true;
}

module.exports = { ingressOptions, createIngressResources, ingressCertificateResources, stopLegacyForwarding };

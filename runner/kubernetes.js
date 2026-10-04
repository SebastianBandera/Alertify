'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const readline = require('node:readline/promises');
const http = require('node:http');
const https = require('node:https');
const { spawnSync, spawn } = require('node:child_process');
const { ingressOptions, createIngressResources, ingressCertificateResources, stopLegacyForwarding } = require('./kubernetes-ingress');

function parseOptions(argv) {
  if (argv.some((value) => value.startsWith('--skip-') || value.startsWith('--cleanup-docker'))) {
    throw new Error('Kubernetes mode does not accept --skip-* or Docker cleanup options.');
  }
  const option = (prefix) => {
    const matches = argv.filter((value) => value.startsWith(prefix));
    if (matches.length > 1 || (matches.length && !matches[0].slice(prefix.length))) {
      throw new Error(`${prefix} requires exactly one nonempty value.`);
    }
    return matches[0]?.slice(prefix.length);
  };
  return {
    kubeconfig: option('--kubeconfig='), context: option('--kube-context='),
    configureOnly: argv.includes('--configure-only'), nonInteractive: argv.includes('--non-interactive'),
  };
}

function renderTemplate(source, values) {
  if (/\{\{[^]*?\}\}/.test(source.replace(/\{\{([A-Z][A-Z0-9_]*)\}\}/g, ''))) {
    throw new Error('Unresolved Kubernetes placeholder.');
  }
  const rendered = source.replace(/\{\{([A-Z][A-Z0-9_]*)\}\}/g, (_, key) => {
    if (!Object.hasOwn(values, key)) throw new Error(`Unknown Kubernetes placeholder: ${key}.`);
    return JSON.stringify(values[key]);
  });
  return JSON.parse(rendered);
}

function value(environment, key) {
  const raw = environment.get(key) ?? '';
  return raw.replace(/^(["'])(.*)\1$/, '$2');
}

function redactUrl(raw) {
  const parsed = new URL(raw);
  if (parsed.username) parsed.username = 'redacted';
  if (parsed.password) parsed.password = 'redacted';
  for (const key of parsed.searchParams.keys()) {
    if (/PASSWORD|SECRET|TOKEN|KEY/i.test(key)) parsed.searchParams.set(key, 'redacted');
  }
  return parsed.toString().replace(/\/$/, '');
}

function isSecretEnvironment(key, entry) {
  if (/PASSWORD|SECRET|TOKEN|KEY_ENV_PART/.test(key)) return true;
  try {
    const url = new URL(String(entry));
    return Boolean(url.username || url.password || [...url.searchParams.keys()].some((parameter) => /PASSWORD|SECRET|TOKEN|KEY/i.test(parameter)));
  } catch { return false; }
}

function invoke(command, args, cwd, environment, inherit = false) {
  const result = spawnSync(command, args, {
    cwd, encoding: 'utf8', shell: false, maxBuffer: 32 * 1024 * 1024,
    env: environment ? { ...process.env, ...Object.fromEntries([...environment].map(([key]) => [key, value(environment, key)])) } : process.env,
    stdio: inherit ? 'inherit' : 'pipe', windowsHide: true,
  });
  // Captured diagnostics may contain rendered credentials; only report command and exit status.
  if (result.error || result.status !== 0) throw new Error(`${command} ${args[0]} failed (${result.error?.code ?? result.status}); no sensitive diagnostics were displayed.`);
  return result.stdout;
}

function affinity(role) {
  return { nodeAffinity: { preferredDuringSchedulingIgnoredDuringExecution: [{
    weight: 100, preference: { matchExpressions: [{ key: 'alertify-role', operator: 'In', values: [role] }] },
  }] } };
}

function normalizedImageReference(source) {
  const parts = source.split('/');
  if (parts.length === 1) parts.unshift('docker.io', 'library');
  else if (!/[.:]/.test(parts[0]) && parts[0] !== 'localhost') parts.unshift('docker.io');
  if (parts[0] === 'docker.io' && parts.length === 2) parts.splice(1, 0, 'library');
  const reference = parts.join('/');
  return source.includes('@') || parts.at(-1).includes(':') ? reference : `${reference}:latest`;
}

function importedImageDigests(output) {
  return new Map(output.split(/\r?\n/).map((line) => line.trim().split(/\s+/)).filter((columns) => /^sha256:[a-f0-9]{64}$/.test(columns[2] ?? '')).map((columns) => [columns[0], columns[2]]));
}

function kubernetesBackendUpstream(raw, namespace, domain) {
  const upstream = new URL(raw);
  if (upstream.hostname === 'backend') upstream.hostname = `backend.${namespace}.svc.${domain}`;
  return upstream.toString().replace(/\/$/, '');
}

function verifyLocalPublicApi(plan, projectDirectory) {
  return new Promise((resolve, reject) => {
    const endpoint = new URL(`${plan.publisherUrl.replace(/\/$/, '')}/api/alert-executions`);
    const secure = endpoint.protocol === 'https:';
    const request = (secure ? https : http).get(endpoint, {
      ...(secure ? { ca: fs.readFileSync(path.join(projectDirectory, '.alertify', 'certificates', 'alertify-local-ca.crt')) } : {}),
      agent: false,
      lookup: (_hostname, options, callback) => options.all
        ? callback(null, [{ address: '127.0.0.1', family: 4 }])
        : callback(null, '127.0.0.1', 4),
    }, (response) => {
      response.resume();
      if ([401, 403].includes(response.statusCode)) resolve(response.statusCode);
      else reject(new Error(`Public API readiness returned HTTP ${response.statusCode}; expected an authentication challenge (401/403).`));
    });
    request.setTimeout(10000, () => request.destroy(new Error('Public API readiness timed out.')));
    request.on('error', () => reject(new Error('Public API readiness failed; verify Ingress publication, TLS trust and backend routing.')));
  });
}

function importImageArchive(archive, nodeName, platform, projectDirectory) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', ['exec', '-i', nodeName, 'ctr', '--namespace', 'k8s.io', 'images', 'import', '--platform', platform, '-'], {
      cwd: projectDirectory, shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
    });
    const input = fs.createReadStream(archive, { highWaterMark: 1024 * 1024 });
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      input.destroy();
      if (error) { child.kill(); reject(error); } else resolve();
    };
    child.stdout.resume();
    child.stderr.resume();
    child.on('error', () => finish(new Error('Docker image import process could not start.')));
    child.on('close', (code) => finish(code === 0 ? null : new Error(`kind image import failed (${code}); no sensitive diagnostics were displayed.`)));
    child.stdin.on('error', () => finish(new Error('Docker image import input failed.')));
    input.on('error', () => finish(new Error('Temporary Docker image archive could not be read.')));
    input.pipe(child.stdin);
  });
}

function createResources(compose, environment, namespace, templates) {
  const resources = [];
  const render = (file, parameters) => renderTemplate(templates[file], { NAMESPACE: namespace, ...parameters });
  resources.push(render('namespace', {}));
  const context = value(environment, 'APP_CONTEXT_PATH').replace(/\/$/, '');
  for (const [name, service] of Object.entries(compose.services)) {
    const role = name.startsWith('worker-') ? name : ['cache', 'identity', 'database', 'identity-database'].includes(name) ? 'infra' : 'application';
    const labels = { 'app.kubernetes.io/name': name, 'app.kubernetes.io/part-of': 'alertify' };
    if (name.startsWith('worker-')) labels['app.alertify/worker'] = 'true';
    const publicEnvironment = {};
    const secrets = {};
    const mountedConfiguration = [];
    for (const [key, entry] of Object.entries(service.environment ?? {})) {
      const mapped = name === 'publisher' && key === 'PUBLISHER_BACKEND_UPSTREAM'
        ? kubernetesBackendUpstream(String(entry), namespace, value(environment, 'KUBERNETES_CLUSTER_DOMAIN') || 'cluster.local')
        : String(entry ?? '');
      (isSecretEnvironment(key, mapped) ? secrets : publicEnvironment)[key] = mapped;
    }
    if (name.startsWith('worker-') && value(environment, name === 'worker-standard' ? 'WORKER_STANDARD_UNIQUE_NAMES' : 'WORKER_PLAYWRIGHT_UNIQUE_NAMES') === 'true') {
      delete publicEnvironment.WORKER_NAME;
    }
    if (name === 'publisher') {
      publicEnvironment.PUBLISHER_TLS_ENABLED = String(!/^localhost$|\.localhost$/i.test(new URL(value(environment, 'APP_PUBLIC_URL')).hostname));
      publicEnvironment.PUBLISHER_TLS_SERVER_NAME = value(environment, 'PUBLISHER_TLS_SERVER_NAME');
      publicEnvironment.PUBLISHER_KUBERNETES_DNS_RESOLVER = value(environment, 'KUBERNETES_CLUSTER_DNS');
    }
    if (name === 'identity') publicEnvironment.APP_PUBLIC_ORIGIN = value(environment, 'APP_PUBLIC_ORIGIN');
    resources.push(render('configmap', { NAME: `${name}-config`, DATA: publicEnvironment }));
    resources.push(render('secret', { NAME: `${name}-credentials`, DATA: secrets }));
    const container = {
      name, image: service.image, imagePullPolicy: value(environment, 'KUBERNETES_REGISTRY') ? 'Always' : 'IfNotPresent',
      envFrom: [{ configMapRef: { name: `${name}-config` } }, { secretRef: { name: `${name}-credentials` } }],
    };
    if (service.mem_limit) container.resources = { limits: { memory: String(service.mem_limit) }, requests: { memory: '128Mi' } };
    if (service.command) container.args = service.command;
    if (name.startsWith('worker-') && !Object.hasOwn(publicEnvironment, 'WORKER_NAME')) {
      container.env = [{ name: 'WORKER_NAME', valueFrom: { fieldRef: { fieldPath: 'metadata.name' } } }];
    }
    const podSpec = { automountServiceAccountToken: false, affinity: affinity(role), containers: [container] };
    const mountSecret = (secretName, mountPath, current = true) => {
      podSpec.volumes ??= [];
      container.volumeMounts ??= [];
      podSpec.volumes.push({ name: secretName, secret: { secretName, defaultMode: 292 } });
      container.volumeMounts.push({ name: secretName, mountPath: `${mountPath}${current ? '/current' : ''}`, readOnly: true });
    };
    if (value(environment, 'WORKER_GRPC_TLS_ENABLED') === 'true') {
      if (name === 'backend') mountSecret('grpc-backend-tls', '/run/alertify-grpc-tls');
      if (name.startsWith('worker-')) mountSecret('grpc-worker-tls', '/run/alertify-grpc-tls');
    }
    if (name === 'publisher' && publicEnvironment.PUBLISHER_TLS_ENABLED === 'true') mountSecret('publisher-tls', '/run/alertify-publisher-tls');
    if (name === 'publisher') {
      const routingTemplate = templates.publisherNginx.replace('resolver 127.0.0.11', `resolver ${value(environment, 'KUBERNETES_CLUSTER_DNS')}`);
      mountedConfiguration.push(routingTemplate);
      resources.push(render('configmap', { NAME: 'publisher-routing-template', DATA: { 'default.conf.template': routingTemplate } }));
      podSpec.volumes ??= [];
      container.volumeMounts ??= [];
      podSpec.volumes.push({ name: 'routing-template', configMap: { name: 'publisher-routing-template' } });
      container.volumeMounts.push({ name: 'routing-template', mountPath: '/etc/nginx/templates/default.conf.template', subPath: 'default.conf.template', readOnly: true });
    }
    if (name === 'worker-standard') container.securityContext = { capabilities: { add: ['SYS_ADMIN'] } };
    if (name === 'identity') {
      container.lifecycle = { postStart: { exec: { command: ['/bin/bash', '/opt/keycloak/config/configure-permanent-admin.sh'] } } };
      const realmTemplate = templates.keycloakRealm.replace('"webOrigins": ["${APP_PUBLIC_URL}"]', '"webOrigins": ["${APP_PUBLIC_ORIGIN}"]');
      const adminScript = templates.keycloakAdmin.replace('webOrigins=[\\"${app_public_url}\\"]', 'webOrigins=[\\"${APP_PUBLIC_ORIGIN}\\"]');
      mountedConfiguration.push(realmTemplate, adminScript);
      resources.push(render('configmap', { NAME: 'identity-initialization', DATA: {
        'realm-template.json': realmTemplate, 'configure-permanent-admin.sh': adminScript,
      } }));
      podSpec.volumes ??= [];
      container.volumeMounts ??= [];
      podSpec.volumes.push({ name: 'initialization', configMap: { name: 'identity-initialization' } });
      for (const file of ['realm-template.json', 'configure-permanent-admin.sh']) {
        container.volumeMounts.push({ name: 'initialization', mountPath: `/opt/keycloak/config/${file}`, subPath: file, readOnly: true });
      }
    }
    const probes = {
      backend: { httpGet: { path: `${context}/actuator/health`, port: 8080 } },
      frontend: { httpGet: { path: '/health', port: 8080 } },
      publisher: { httpGet: { path: '/publisher-health', port: 8080 } },
      database: { exec: { command: ['/bin/bash', '-ec', 'test -f /tmp/database-migrations-complete && pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'] } },
      'identity-database': { exec: { command: ['/bin/sh', '-ec', 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'] } },
      cache: { exec: { command: ['/bin/sh', '-ec', 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping | grep -q PONG'] } },
      identity: { exec: { command: ['/bin/bash', '-ec', 'test -f /tmp/permanent-admin-ready; exec 3<>/dev/tcp/127.0.0.1/9000; printf "GET %s/health/ready HTTP/1.0\\r\\n\\r\\n" "$KC_HTTP_RELATIVE_PATH" >&3; grep -q "200 OK" <&3'] } },
    };
    if (probes[name]) {
      container.readinessProbe = { ...probes[name], periodSeconds: 5, timeoutSeconds: 5 };
      container.startupProbe = { ...probes[name], periodSeconds: 5, timeoutSeconds: 5, failureThreshold: 120 };
    } else container.readinessProbe = { tcpSocket: { port: Number(value(environment, 'WORKER_GRPC_PORT')) }, periodSeconds: 5 };
    const stateful = name === 'database' || name === 'identity-database';
    if (stateful) {
      const claim = { accessModes: ['ReadWriteOnce'], resources: { requests: { storage: value(environment, 'KUBERNETES_DATABASE_STORAGE') } } };
      if (value(environment, 'KUBERNETES_STORAGE_CLASS')) claim.storageClassName = value(environment, 'KUBERNETES_STORAGE_CLASS');
      resources.push(render('pvc', { NAME: `${name}-data`, SPEC: claim }));
      podSpec.volumes = [{ name: 'data', persistentVolumeClaim: { claimName: `${name}-data` } }];
      container.volumeMounts = [{ name: 'data', mountPath: '/var/lib/postgresql' }];
    }
    const spec = {
      replicas: name.startsWith('worker-') ? Number(service.deploy?.replicas ?? 1) : 1,
      selector: { matchLabels: { 'app.kubernetes.io/name': name } },
      template: { metadata: { labels, annotations: { 'app.alertify/config-sha256': crypto.createHash('sha256').update(JSON.stringify([publicEnvironment, secrets, mountedConfiguration])).digest('hex') } }, spec: podSpec },
    };
    if (stateful) spec.serviceName = name;
    else spec.strategy = { type: 'Recreate' };
    resources.push(render(stateful ? 'statefulset' : 'deployment', { NAME: name, SPEC: spec }));
    if (!name.startsWith('worker-')) {
      const port = stateful ? 5432 : name === 'cache' ? 6379 : 8080;
      const ports = [{ name: 'http', port, targetPort: port }];
      if (name === 'publisher' && publicEnvironment.PUBLISHER_TLS_ENABLED === 'true') ports.push({ name: 'https', port: 8443, targetPort: 8443 });
      resources.push(render('service', { NAME: name, SPEC: { selector: { 'app.kubernetes.io/name': name }, ports } }));
    }
  }
  resources.push(render('service', { NAME: value(environment, 'WORKER_GRPC_HOST'), SPEC: {
    clusterIP: 'None', selector: { 'app.alertify/worker': 'true' },
    ports: [{ name: 'grpc', port: Number(value(environment, 'WORKER_GRPC_PORT')), targetPort: Number(value(environment, 'WORKER_GRPC_PORT')) }],
  } }));
  const ingress = createIngressResources(environment, namespace, templates, renderTemplate);
  if (ingress.length && !/^localhost$|\.localhost$/i.test(new URL(value(environment, 'APP_PUBLIC_URL')).hostname)) {
    const publisher = resources.find((resource) => resource.kind === 'Service' && resource.metadata.name === 'publisher');
    publisher.metadata.annotations = { 'traefik.ingress.kubernetes.io/service.serverstransport': 'publisher@file' };
  }
  resources.push(...ingress);
  return resources;
}

async function deploy(environment, plan, options, projectDirectory, certificateHelpers) {
  const namespace = value(environment, 'KUBERNETES_NAMESPACE');
  if (!/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(namespace)) throw new Error('KUBERNETES_NAMESPACE must be a DNS label.');
  if (!/^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/.test(value(environment, 'KUBERNETES_CLUSTER_DOMAIN') || 'cluster.local')) {
    throw new Error('KUBERNETES_CLUSTER_DOMAIN must be a DNS name.');
  }
  for (const key of ['KUBERNETES_PUSH_IMAGES', 'KUBERNETES_VERIFY_LOCAL_PUBLIC_URL']) {
    if (!['true', 'false'].includes(value(environment, key))) throw new Error(`${key} must be true or false.`);
  }
  const ingress = ingressOptions(environment);
  if (!/^[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(value(environment, 'WORKER_GRPC_HOST'))) {
    throw new Error('WORKER_GRPC_HOST must be a Kubernetes Service DNS label in Kubernetes mode.');
  }
  if (['backend', 'frontend', 'publisher', 'cache', 'identity', 'database', 'identity-database'].includes(value(environment, 'WORKER_GRPC_HOST'))) {
    throw new Error('WORKER_GRPC_HOST conflicts with another Kubernetes Service.');
  }
  const baseArgs = [];
  if (options.kubeconfig) baseArgs.push('--kubeconfig', path.resolve(options.kubeconfig));
  if (options.context) baseArgs.push('--context', options.context);
  const kubectl = (args, display = false) => {
    const output = invoke('kubectl', [...baseArgs, ...args], projectDirectory);
    if (display && output.trim()) console.log(output.trim());
    return output;
  };
  const context = options.context ?? kubectl(['config', 'current-context']).trim();
  // Pin the resolved context for every operation and for the detached supervisor.
  if (!options.context) baseArgs.push('--context', context);
  const config = JSON.parse(kubectl(['config', 'view', '--minify', '-o', 'json']));
  const nodes = JSON.parse(kubectl(['get', 'nodes', '-o', 'json'])).items;
  const dnsServices = JSON.parse(kubectl(['-n', 'kube-system', 'get', 'services', '-l', 'k8s-app=kube-dns', '-o', 'json'])).items;
  const clusterDns = dnsServices.find((service) => service.spec.clusterIP && service.spec.clusterIP !== 'None')?.spec.clusterIP;
  if (!clusterDns) throw new Error('Kubernetes cluster DNS Service could not be discovered (k8s-app=kube-dns).');
  environment = new Map(environment);
  environment.set('KUBERNETES_CLUSTER_DNS', clusterDns);
  for (const role of ['infra', 'application', 'worker-standard', 'worker-playwright']) {
    if (!nodes.some((node) => node.metadata.labels['alertify-role'] === role)) console.warn(`Warning: no node has alertify-role=${role}; preferred affinity permits other nodes.`);
  }
  const registry = value(environment, 'KUBERNETES_REGISTRY').replace(/\/$/, '');
  if (registry && (registry.includes('://') || !/^[A-Za-z0-9][A-Za-z0-9.\-:/]*$/.test(registry))) throw new Error('KUBERNETES_REGISTRY must be a registry prefix without a URL scheme.');
  if (!registry && value(environment, 'KUBERNETES_PUSH_IMAGES') === 'true') throw new Error('KUBERNETES_PUSH_IMAGES requires KUBERNETES_REGISTRY.');
  const timeout = Number(value(environment, 'KUBERNETES_ROLLOUT_TIMEOUT_SECONDS'));
  if (!Number.isInteger(timeout) || timeout < 1) throw new Error('KUBERNETES_ROLLOUT_TIMEOUT_SECONDS must be a positive integer.');
  console.log(`Kubernetes context: ${context}\nAPI server: ${redactUrl(config.clusters[0].cluster.server)}\nNamespace: ${namespace}\nRegistry: ${registry || '(local Docker images)'}\nPublic URL: ${redactUrl(plan.publisherUrl)}`);
  console.log(`Publication: ${ingress.enabled ? `Traefik ${ingress.image}, IngressClass ${ingress.ingressClass}, Service ${ingress.serviceType}` : 'external controller (managed Traefik disabled)'}`);
  if (ingress.enabled) {
    const existingClass = kubectl(['get', 'ingressclass', ingress.ingressClass, '--ignore-not-found', '-o', 'json']).trim();
    if (existingClass && JSON.parse(existingClass).spec.controller !== 'traefik.io/ingress-controller') {
      throw new Error(`IngressClass ${ingress.ingressClass} belongs to another controller; choose a separate KUBERNETES_INGRESS_CLASS.`);
    }
  }
  if (!options.nonInteractive && !options.configureOnly) {
    const prompt = readline.createInterface({ input: process.stdin, output: process.stdout });
    try { if ((await prompt.question('Apply this Kubernetes deployment? [y/N] ')).trim().toLowerCase() !== 'y') throw new Error('Deployment cancelled.'); }
    finally { prompt.close(); }
  }
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-kubernetes-'));
  try {
    const templates = Object.fromEntries(['namespace', 'configmap', 'secret', 'pvc', 'deployment', 'statefulset', 'service'].map((name) => [name, fs.readFileSync(path.join(projectDirectory, 'kubernetes', `${name}.yaml.template`), 'utf8')]));
    for (const name of ['traefik', 'ingress']) templates[name] = fs.readFileSync(path.join(projectDirectory, 'kubernetes', `${name}.yaml.template`), 'utf8');
    templates.publisherNginx = fs.readFileSync(path.join(projectDirectory, 'publisher', 'default.conf.template'), 'utf8');
    templates.keycloakRealm = fs.readFileSync(path.join(projectDirectory, 'identity', 'realm-template.json'), 'utf8');
    templates.keycloakAdmin = fs.readFileSync(path.join(projectDirectory, 'identity', 'configure-permanent-admin.sh'), 'utf8');
    const composeArgs = ['compose', '--profile', '*', 'config', '--format', 'json'];
    const compose = JSON.parse(invoke('docker', composeArgs, projectDirectory, environment));
    for (const key of ['KEYCLOAK_MODE', 'KEYCLOAK_DATABASE_MODE', 'REDIS_MODE', 'DATABASE_MODE', 'BACKEND_MODE', 'FRONTEND_MODE']) {
      if (value(environment, key) !== 'local') throw new Error(`Kubernetes all-inclusive mode requires ${key}=local.`);
    }
    const sourceImages = [...new Set([...Object.values(compose.services).map((service) => service.image), ...(ingress.enabled ? [ingress.image] : [])])];
    const imagePairs = sourceImages.map((source) => [source, registry ? `${registry}/${source.split('/').pop()}` : source]);
    for (const service of Object.values(compose.services)) service.image = imagePairs.find(([source]) => source === service.image)[1];
    const resources = createResources(compose, environment, namespace, templates);
    if (ingress.enabled) resources.find((resource) => resource.kind === 'Deployment' && resource.metadata.name === 'traefik').spec.template.spec.containers[0].image = imagePairs.find(([source]) => source === ingress.image)[1];
    const writeResource = (resource) => {
      const target = path.join(temporaryDirectory, `${resource.kind}-${resource.metadata.name}.yaml`);
      fs.writeFileSync(target, JSON.stringify(resource, null, 2), { mode: 0o600 });
      return target;
    };
    resources.forEach(writeResource);
    kubectl(['apply', '--dry-run=client', '-f', temporaryDirectory], true);
    if (options.configureOnly) {
      console.log('Kubernetes configuration validated; no resources or images were changed.');
      return;
    }
    invoke('docker', ['compose', '--profile', '*', 'build'], projectDirectory, environment, true);
    for (const [source, target] of imagePairs) {
      // The PostgreSQL base image has no build section; ensure it is available too.
      const present = spawnSync('docker', ['image', 'inspect', source], { stdio: 'ignore', windowsHide: true });
      if (present.status !== 0) invoke('docker', ['pull', source], projectDirectory, null, true);
      if (source !== target) invoke('docker', ['tag', source, target], projectDirectory);
      if (value(environment, 'KUBERNETES_PUSH_IMAGES') === 'true') invoke('docker', ['push', target], projectDirectory, null, true);
    }
    for (const resource of resources.filter((resource) => ['Deployment', 'StatefulSet'].includes(resource.kind))) {
      const image = resource.spec.template.spec.containers[0].image;
      const architecture = invoke('docker', ['image', 'inspect', image, '--format', '{{.Architecture}}'], projectDirectory).trim();
      // Docker Desktop's unqualified Id can be an OCI index including fresh
      // attestations from an unchanged rebuild. The platform manifest is stable.
      const descriptor = JSON.parse(invoke('docker', ['image', 'inspect', '--platform', `linux/${architecture}`, image, '--format', '{{json .Descriptor}}'], projectDirectory));
      resource.spec.template.metadata.annotations['app.alertify/image-sha256'] = descriptor.digest;
      writeResource(resource);
    }
    if (!registry) {
      const kindNodes = nodes.filter((node) => {
        const check = spawnSync('docker', ['inspect', node.metadata.name, '--format', '{{index .Config.Labels "io.x-k8s.kind.role"}}'], { encoding: 'utf8', windowsHide: true });
        return check.status === 0 && ['control-plane', 'worker'].includes(check.stdout.trim());
      });
      if (kindNodes.length) {
        const schedulable = kindNodes.filter((node) => !node.spec.taints?.some((taint) => taint.effect === 'NoSchedule'));
        for (const architecture of new Set(schedulable.map((node) => node.status.nodeInfo.architecture))) {
          const platform = `linux/${architecture}`;
          const expected = new Map(sourceImages.map((source) => {
            const descriptor = JSON.parse(invoke('docker', ['image', 'inspect', '--platform', platform, source, '--format', '{{json .Descriptor}}'], projectDirectory));
            return [normalizedImageReference(source), descriptor.digest];
          }));
          const needingImport = schedulable.filter((node) => node.status.nodeInfo.architecture === architecture).filter((node) => {
            const present = importedImageDigests(invoke('docker', ['exec', node.metadata.name, 'ctr', '--namespace', 'k8s.io', 'images', 'list'], projectDirectory));
            return [...expected].some(([reference, digest]) => present.get(reference) !== digest);
          });
          if (!needingImport.length) {
            console.log(`Local kind images already match Docker digests for ${platform}; import reused.`);
            continue;
          }
          const archive = path.join(temporaryDirectory, 'images.tar');
          // Explicit platform export avoids sparse multi-platform indexes from Docker Desktop.
          invoke('docker', ['save', '--platform', platform, '-o', archive, ...sourceImages], projectDirectory, null, true);
          for (const node of needingImport) {
            console.log(`Importing local application images into ${node.metadata.name}...`);
            // Stream directly into containerd. Docker cp is slow on Windows and can
            // write beneath kind's /tmp tmpfs rather than into its visible mount.
            await importImageArchive(archive, node.metadata.name, platform, projectDirectory);
          }
          fs.unlinkSync(archive);
        }
      }
    }
    // Namespace must exist for server-side validation of namespaced resources.
    kubectl(['apply', '-f', path.join(temporaryDirectory, `Namespace-${namespace}.yaml`)], true);
    const secretExists = (name) => {
      return kubectl(['-n', namespace, 'get', 'secret', name, '--ignore-not-found', '-o', 'name']).trim() !== '';
    };
    const certificateSetExists = (names) => {
      const existing = names.filter(secretExists);
      if (existing.length && existing.length !== names.length) {
        throw new Error(`Incomplete Kubernetes certificate set (${names.join(', ')}). Existing Secrets were preserved; restore the missing certificate Secrets before redeploying.`);
      }
      return existing.length === names.length;
    };
    const exportVolumeSecret = (secretName, volumeSuffix, files) => {
      const data = {};
      for (const file of files) {
        const pem = invoke('docker', ['run', '--rm', '--volume', `${value(environment, 'COMPOSE_PROJECT_NAME')}-${volumeSuffix}:/data:ro`, '--entrypoint', 'cat', value(environment, 'GRPC_OPENSSL_IMAGE'), `/data/${file}`], projectDirectory);
        const fileName = path.basename(file);
        fs.writeFileSync(path.join(temporaryDirectory, `${secretName}-${fileName}`), pem, { mode: 0o600 });
        data[fileName] = Buffer.from(pem).toString('base64');
      }
      const resource = { apiVersion: 'v1', kind: 'Secret', metadata: { name: secretName, namespace }, type: 'Opaque', data };
      writeResource(resource);
    };
    if (plan.workerGrpcTlsEnabled && !certificateSetExists(['grpc-ca', 'grpc-backend-tls', 'grpc-worker-tls'])) {
      certificateHelpers.prepareGrpcCertificates(plan, environment, projectDirectory);
      exportVolumeSecret('grpc-ca', 'grpc-ca', ['backend-client-ca.key', 'backend-client-ca.crt', 'worker-server-ca.key', 'worker-server-ca.crt']);
      exportVolumeSecret('grpc-backend-tls', 'grpc-backend-tls', ['current/backend-client.key', 'current/backend-client.crt', 'current/worker-server-ca.crt']);
      exportVolumeSecret('grpc-worker-tls', 'grpc-worker-tls', ['current/worker-server.key', 'current/worker-server.crt', 'current/backend-client-ca.crt']);
    }
    if (plan.publisherTlsEnabled && !certificateSetExists(['publisher-ca', 'publisher-tls'])) {
      certificateHelpers.preparePublisherTlsCertificate(plan, environment, projectDirectory);
      exportVolumeSecret('publisher-ca', 'publisher-tls-ca', ['alertify-local-ca.key', 'alertify-local-ca.crt']);
      exportVolumeSecret('publisher-tls', 'publisher-tls', ['current/publisher-server.key', 'current/publisher-server.crt']);
    } else if (plan.publisherTlsEnabled) {
      const secret = JSON.parse(kubectl(['-n', namespace, 'get', 'secret', 'publisher-ca', '-o', 'json']));
      const exportPath = path.join(projectDirectory, '.alertify', 'certificates', 'alertify-local-ca.crt');
      fs.mkdirSync(path.dirname(exportPath), { recursive: true });
      fs.writeFileSync(exportPath, Buffer.from(secret.data['alertify-local-ca.crt'], 'base64'));
    }
    if (ingress.enabled && plan.publisherTlsEnabled) {
      const certificate = (name) => {
        const pending = path.join(temporaryDirectory, `Secret-${name}.yaml`);
        return fs.existsSync(pending) ? JSON.parse(fs.readFileSync(pending, 'utf8'))
          : JSON.parse(kubectl(['-n', namespace, 'get', 'secret', name, '-o', 'json']));
      };
      for (const resource of ingressCertificateResources(namespace, certificate('publisher-tls'), certificate('publisher-ca'))) writeResource(resource);
    }
    // Apply only rendered manifests, never certificate files alongside them.
    const manifestPaths = fs.readdirSync(temporaryDirectory).filter((name) => name.endsWith('.yaml')).map((name) => path.join(temporaryDirectory, name));
    for (const manifestPath of manifestPaths) kubectl(['apply', '--dry-run=server', '-f', manifestPath], true);
    for (const manifestPath of manifestPaths) kubectl(['apply', '-f', manifestPath], true);
    for (const resource of resources.filter((resource) => ['Deployment', 'StatefulSet'].includes(resource.kind))) {
      kubectl(['-n', namespace, 'rollout', 'status', `${resource.kind.toLowerCase()}/${resource.metadata.name}`, `--timeout=${timeout}s`], true);
    }
    kubectl(['-n', namespace, 'wait', '--for=condition=Ready', 'pods', '-l', 'app.kubernetes.io/part-of=alertify', `--timeout=${timeout}s`], true);
    if (await stopLegacyForwarding(projectDirectory, namespace)) console.log('Legacy publisher port-forward supervisor stopped.');
    if (ingress.enabled && ingress.serviceType === 'LoadBalancer') {
      kubectl(['-n', namespace, 'wait', '--for=jsonpath={.status.loadBalancer.ingress}', 'service/traefik', `--timeout=${timeout}s`], true);
    }
    if (value(environment, 'KUBERNETES_VERIFY_LOCAL_PUBLIC_URL') === 'true') {
      let apiStatus;
      const deadline = Date.now() + timeout * 1000;
      while (!apiStatus) {
        try { apiStatus = await verifyLocalPublicApi(plan, projectDirectory); }
        catch (error) {
          if (Date.now() >= deadline) throw error;
          console.log('Waiting for local Ingress publication and backend routing...');
          await new Promise((resolve) => setTimeout(resolve, 5000));
        }
      }
      console.log(`Public API routing and TLS verified: HTTP ${apiStatus} without credentials.`);
    }
    console.log(`Kubernetes deployment ready: ${redactUrl(plan.publisherUrl)}`);
  } finally {
    // Only our unique temporary directory is removed. Cluster resources survive.
    fs.rmSync(temporaryDirectory, { recursive: true, force: true });
  }
}

module.exports = { parseOptions, renderTemplate, affinity, createResources, deploy, isSecretEnvironment, redactUrl, normalizedImageReference, importedImageDigests, kubernetesBackendUpstream, verifyLocalPublicApi };

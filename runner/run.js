'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const SECRET_PATTERN = /<GENERATE_([A-Z][A-Z0-9_]*)>/g;
const ASSIGNMENT_PATTERN = /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/;
const PRIVATE_KEY_PART_SOURCE = path.join(
  'backend', 'core', 'src', 'main', 'java', 'app', 'alertify', 'services', 'secret', 'key',
  'PrivateKeyPart.java',
);

function parseDocument(content, sourceName) {
  const lines = content.split(/\r?\n/);
  const assignments = [];
  const pendingLines = [];
  const seenKeys = new Set();

  for (let index = 0; index < lines.length; index += 1) {
    const comparableLine = index === 0 ? lines[index].replace(/^\uFEFF/, '') : lines[index];
    const match = comparableLine.match(ASSIGNMENT_PATTERN);

    if (!match || comparableLine.trimStart().startsWith('#')) {
      pendingLines.push(lines[index]);
      continue;
    }

    const key = match[1];
    if (seenKeys.has(key)) {
      throw new Error(`${sourceName} contains the duplicate variable ${key}.`);
    }

    seenKeys.add(key);
    assignments.push({
      key,
      rawValue: match[2].trim(),
      line: lines[index],
      prefixLines: pendingLines.splice(0),
    });
  }

  return { assignments, lines };
}

function parseExistingEnvironment(content) {
  const values = new Map();
  const duplicates = new Set();

  for (const [index, line] of content.split(/\r?\n/).entries()) {
    const comparableLine = index === 0 ? line.replace(/^\uFEFF/, '') : line;
    const match = comparableLine.match(ASSIGNMENT_PATTERN);
    if (!match || comparableLine.trimStart().startsWith('#')) {
      continue;
    }

    if (values.has(match[1])) {
      duplicates.add(match[1]);
    }
    values.set(match[1], match[2].trim());
  }

  return { values, duplicates: [...duplicates] };
}

function placeholders(rawValue) {
  return [...rawValue.matchAll(SECRET_PATTERN)].map((match) => match[1]);
}

function generateSecret() {
  return crypto.randomBytes(32).toString('base64url');
}

function renderPrivateKeyPartSource(value) {
  return `package app.alertify.services.secret.key;

final class PrivateKeyPart {

    private static final String KEY_PART = "${value}";

    private PrivateKeyPart() {
    }
}
`;
}

function ensurePrivateKeyPartClass(environment, projectDirectory) {
  if (mode(environment, 'BACKEND_MODE') !== 'local') {
    return { status: 'not-applicable', path: null };
  }

  const sourcePath = path.join(projectDirectory, PRIVATE_KEY_PART_SOURCE);
  if (!booleanValue(environment, 'CREATE_PRIVATE_KEY_PART_CLASS')) {
    return {
      status: fs.existsSync(sourcePath) ? 'preserved' : 'disabled',
      path: sourcePath,
    };
  }

  if (fs.existsSync(sourcePath)) {
    return { status: 'reused', path: sourcePath };
  }

  fs.mkdirSync(path.dirname(sourcePath), { recursive: true });
  fs.writeFileSync(sourcePath, renderPrivateKeyPartSource(generateSecret()), {
    encoding: 'utf8',
    flag: 'wx',
    mode: 0o600,
  });
  return { status: 'created', path: sourcePath };
}

function printPrivateKeyPartClassResult(result, projectDirectory) {
  if (result.status === 'not-applicable') {
    return;
  }

  const displayPath = path.relative(projectDirectory, result.path);
  if (result.status === 'created') {
    console.log(
      `Private symmetric-key source created at ${displayPath} without displaying its value.`,
    );
  } else if (result.status === 'reused') {
    console.log(`Private symmetric-key source already exists at ${displayPath}; its value was preserved.`);
  } else if (result.status === 'preserved') {
    console.log(
      `CREATE_PRIVATE_KEY_PART_CLASS=false; existing ${displayPath} was preserved and will still be compiled.`,
    );
  } else {
    console.log('CREATE_PRIVATE_KEY_PART_CLASS=false; no private symmetric-key source was created.');
  }
}

function resolveSecretValues(templateAssignments, existingValues) {
  const keysBySecret = new Map();

  for (const assignment of templateAssignments) {
    for (const secretName of placeholders(assignment.rawValue)) {
      const expectedPlaceholder = `<GENERATE_${secretName}>`;
      if (assignment.rawValue !== expectedPlaceholder) {
        throw new Error(
          `The placeholder ${expectedPlaceholder} must be the entire value of ${assignment.key}.`,
        );
      }

      const keys = keysBySecret.get(secretName) ?? [];
      keys.push(assignment.key);
      keysBySecret.set(secretName, keys);
    }
  }

  const resolved = new Map();
  const generated = [];
  const reused = [];

  for (const [secretName, keys] of keysBySecret.entries()) {
    const placeholder = `<GENERATE_${secretName}>`;
    const candidates = new Set();

    for (const key of keys) {
      if (!existingValues.has(key)) {
        continue;
      }

      const rawValue = existingValues.get(key);
      if (rawValue === '' || rawValue.includes(placeholder)) {
        throw new Error(
          `${key} already exists in .env but does not contain a resolved secret. ` +
            'It was not modified because reconciliation never overwrites existing variables.',
        );
      }
      candidates.add(rawValue);
    }

    if (candidates.size > 1) {
      throw new Error(
        `Variables associated with ${placeholder} have different values in .env. ` +
          'None of them were modified.',
      );
    }

    if (candidates.size === 1) {
      resolved.set(secretName, [...candidates][0]);
      reused.push(secretName);
    } else {
      resolved.set(secretName, generateSecret());
      generated.push(secretName);
    }
  }

  return { resolved, generated, reused };
}

function resolveLine(line, secrets) {
  return line.replace(SECRET_PATTERN, (_, name) => {
    const value = secrets.get(name);
    if (value === undefined) {
      throw new Error(`The GENERATE_${name} secret could not be resolved.`);
    }
    return value;
  });
}

function trimOuterBlankLines(lines) {
  const copy = [...lines];
  while (copy.length > 0 && copy[0].trim() === '') {
    copy.shift();
  }
  while (copy.length > 0 && copy[copy.length - 1].trim() === '') {
    copy.pop();
  }
  return copy;
}

function assignmentLocations(lines) {
  const locations = new Map();

  for (const [index, line] of lines.entries()) {
    const comparableLine = index === 0 ? line.replace(/^\uFEFF/, '') : line;
    const match = comparableLine.match(ASSIGNMENT_PATTERN);
    if (match && !comparableLine.trimStart().startsWith('#')) {
      locations.set(match[1], index);
    }
  }

  return locations;
}

function commentBlockStart(lines, assignmentIndex) {
  let index = assignmentIndex;
  while (index > 0 && lines[index - 1].trimStart().startsWith('#')) {
    index -= 1;
  }
  return index;
}

function resolvedTemplateBlock(assignment, secrets) {
  return [
    ...trimOuterBlankLines(assignment.prefixLines),
    resolveLine(assignment.line, secrets),
  ];
}

function insertMissingAssignments(envContent, templateAssignments, missing, secrets) {
  const lineEnding = envContent.includes('\r\n') ? '\r\n' : '\n';
  const lines = envContent === '' ? [] : envContent.split(/\r?\n/);
  const missingKeys = new Set(missing.map(({ key }) => key));

  for (const [templateIndex, assignment] of templateAssignments.entries()) {
    if (!missingKeys.has(assignment.key)) {
      continue;
    }

    const locations = assignmentLocations(lines);
    const nextAssignment = templateAssignments
      .slice(templateIndex + 1)
      .find(({ key }) => locations.has(key));
    const insertionIndex = nextAssignment
      ? commentBlockStart(lines, locations.get(nextAssignment.key))
      : lines.length;
    const block = resolvedTemplateBlock(assignment, secrets);
    const insertedLines = [];

    if (insertionIndex > 0 && lines[insertionIndex - 1].trim() !== '') {
      insertedLines.push('');
    }
    insertedLines.push(...block);
    if (insertionIndex < lines.length && lines[insertionIndex].trim() !== '') {
      insertedLines.push('');
    }

    lines.splice(insertionIndex, 0, ...insertedLines);
  }

  const rendered = lines.join(lineEnding);
  return rendered.endsWith(lineEnding) ? rendered : `${rendered}${lineEnding}`;
}

function reconcileEnvironment(templatePath, envPath) {
  const templateContent = fs.readFileSync(templatePath, 'utf8');
  const template = parseDocument(templateContent, '.env.template');
  const envExists = fs.existsSync(envPath);
  const envContent = envExists ? fs.readFileSync(envPath, 'utf8') : '';
  const existing = parseExistingEnvironment(envContent);
  const secretResult = resolveSecretValues(template.assignments, existing.values);
  const missing = template.assignments.filter(({ key }) => !existing.values.has(key));
  const missingSecretNames = new Set(
    missing.flatMap(({ rawValue }) => placeholders(rawValue)),
  );

  if (!envExists) {
    const rendered = resolveLine(templateContent, secretResult.resolved);
    fs.writeFileSync(envPath, rendered.endsWith('\n') ? rendered : `${rendered}\n`, {
      encoding: 'utf8',
      flag: 'wx',
      mode: 0o600,
    });
  } else if (missing.length > 0) {
    const reconciled = insertMissingAssignments(
      envContent,
      template.assignments,
      missing,
      secretResult.resolved,
    );
    fs.writeFileSync(envPath, reconciled, 'utf8');
  }

  const finalContent = fs.readFileSync(envPath, 'utf8');
  const finalEnvironment = parseExistingEnvironment(finalContent);

  return {
    created: !envExists,
    addedKeys: missing.map(({ key }) => key),
    duplicates: finalEnvironment.duplicates,
    environment: finalEnvironment.values,
    generatedSecrets: secretResult.generated.filter((name) => missingSecretNames.has(name)),
    reusedSecrets: secretResult.reused.filter((name) => missingSecretNames.has(name)),
  };
}

function plainValue(rawValue) {
  if (rawValue === undefined) {
    return undefined;
  }

  const trimmed = rawValue.trim();
  if (
    trimmed.length >= 2 &&
    ((trimmed.startsWith('"') && trimmed.endsWith('"')) ||
      (trimmed.startsWith("'") && trimmed.endsWith("'")))
  ) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function required(environment, key) {
  const value = plainValue(environment.get(key));
  if (!value) {
    throw new Error(`${key} must have a value in .env.`);
  }
  return value;
}

function mode(environment, key) {
  const value = required(environment, key).toLowerCase();
  if (!['local', 'external'].includes(value)) {
    throw new Error(`${key} must be local or external; received: ${value}.`);
  }
  return value;
}

function booleanValue(environment, key) {
  const value = required(environment, key).toLowerCase();
  if (!['true', 'false'].includes(value)) {
    throw new Error(`${key} must be true or false; received: ${value}.`);
  }
  return value === 'true';
}

function allowedValue(environment, key, allowedValues) {
  const value = required(environment, key).toLowerCase();
  if (!allowedValues.includes(value)) {
    throw new Error(
      `${key} must be one of ${allowedValues.join(', ')}; received: ${value}.`,
    );
  }
  return value;
}

function portValue(environment, key) {
  const rawValue = required(environment, key);
  if (!/^\d+$/.test(rawValue)) {
    throw new Error(`${key} must be an integer between 1 and 65535; received: ${rawValue}.`);
  }
  const value = Number(rawValue);
  if (!Number.isSafeInteger(value) || value < 1 || value > 65535) {
    throw new Error(`${key} must be an integer between 1 and 65535; received: ${rawValue}.`);
  }
  return value;
}

function positiveIntegerValue(environment, key) {
  const rawValue = required(environment, key);
  if (!/^\d+$/.test(rawValue)) {
    throw new Error(`${key} must be a positive integer; received: ${rawValue}.`);
  }
  const value = Number(rawValue);
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${key} must be a positive integer; received: ${rawValue}.`);
  }
  return value;
}

function workerCapabilitiesValue(environment, key) {
  const rawValue = required(environment, key);
  const values = rawValue.split(',').map((value) => value.trim().toUpperCase()).filter(Boolean);
  const allowedValues = new Set(['STANDARD', 'PLAYWRIGHT']);
  if (values.length === 0 || values.some((value) => !allowedValues.has(value))) {
    throw new Error(`${key} must contain STANDARD and/or PLAYWRIGHT; received: ${rawValue}.`);
  }
  return [...new Set(values)];
}

function dnsNameValue(environment, key) {
  const value = required(environment, key);
  if (!/^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$/.test(value)) {
    throw new Error(`${key} must be a valid DNS name; received: ${value}.`);
  }
  return value;
}

function applicationContextPath(environment) {
  const value = required(environment, 'APP_CONTEXT_PATH');
  if (value === '/') {
    return '';
  }
  if (!/^\/(?:[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*)$/.test(value)) {
    throw new Error(
      `APP_CONTEXT_PATH must be / or a path such as /alertify without a trailing slash; received: ${value}.`,
    );
  }
  return value;
}

function applyApplicationContext(environment) {
  const contextualEnvironment = new Map(environment);
  const contextPath = applicationContextPath(environment);
  contextualEnvironment.set('APP_CONTEXT_PATH', contextPath || '/');
  contextualEnvironment.set('KEYCLOAK_RELATIVE_PATH', `${contextPath}/identity`);

  if (!contextPath) {
    return contextualEnvironment;
  }

  const contextualUrlKeys = [
    'APP_PUBLIC_URL',
    'BACKEND_PUBLIC_URL',
    'KEYCLOAK_PUBLIC_URL',
    'KEYCLOAK_ADMIN_URL',
    'OIDC_ISSUER_URI',
    'OIDC_JWK_SET_URI',
  ];
  for (const key of contextualUrlKeys) {
    const rawValue = required(environment, key);
    let url;
    try {
      url = new URL(rawValue);
    } catch {
      throw new Error(`${key} must be an absolute URL; received: ${rawValue}.`);
    }
    const configuredPath = url.pathname === '/' ? '' : url.pathname.replace(/\/$/, '');
    url.pathname = `${contextPath}${configuredPath}`;
    contextualEnvironment.set(key, url.toString().replace(/\/$/, ''));
  }

  return contextualEnvironment;
}

function validateUrlPort(environment, key, expectedPort) {
  const rawValue = required(environment, key);
  let url;
  try {
    url = new URL(rawValue);
  } catch {
    throw new Error(`${key} must be an absolute URL; received: ${rawValue}.`);
  }

  const effectivePort = url.port
    ? Number(url.port)
    : url.protocol === 'http:'
      ? 80
      : url.protocol === 'https:'
        ? 443
        : null;
  if (effectivePort !== expectedPort) {
    throw new Error(
      `${key} must use the publication port ${expectedPort}; received: ${rawValue}.`,
    );
  }
}

function redactUrl(value) {
  try {
    const parsed = new URL(value);
    if (parsed.username) parsed.username = '***';
    if (parsed.password) parsed.password = '***';
    return parsed.toString().replace(/\/$/, '');
  } catch {
    return value.replace(/\/\/[^/@\s]+@/, '//***@');
  }
}

function buildPlan(environment, options = {}) {
  const redisMode = mode(environment, 'REDIS_MODE');
  const keycloakMode = mode(environment, 'KEYCLOAK_MODE');
  const backendMode = mode(environment, 'BACKEND_MODE');
  const frontendMode = mode(environment, 'FRONTEND_MODE');
  const publicPort = portValue(environment, 'PUBLIC_PORT');
  const skipKeycloak = options.skipKeycloak === true;
  const skipRedis = options.skipRedis === true;
  const skipDatabase = options.skipDatabase === true;
  const skipBackend = options.skipBackend === true;
  const skipWorkerStandard = options.skipWorkerStandard === true;
  const skipWorkerPlaywright = options.skipWorkerPlaywright === true;
  const plan = {
    redisMode,
    redisUrl: required(environment, 'REDIS_URL'),
    skipRedis,
    keycloakMode,
    keycloakUrl: required(environment, 'KEYCLOAK_PUBLIC_URL'),
    skipKeycloak,
    backendMode,
    backendUrl: required(environment, 'BACKEND_PUBLIC_URL'),
    skipBackend,
    skipDatabase,
    frontendMode,
    frontendUrl: required(environment, 'APP_PUBLIC_URL'),
    publicPort,
    publisherUrl: required(environment, 'APP_PUBLIC_URL'),
    keycloakAdminUrl: null,
    backendDebugEnabled: false,
    backendDebugPort: null,
    backendDebugSuspend: null,
    workerGrpcHost: null,
    workerGrpcPort: null,
    workerGrpcTlsEnabled: false,
    workerGrpcTlsServerName: null,
    grpcCertificateValidityDays: null,
    rotateGrpcCertificates: false,
    skipWorkerStandard,
    skipWorkerPlaywright,
    workerStandardReplicas: null,
    workerStandardCapabilities: null,
    workerPlaywrightReplicas: null,
    workerPlaywrightCapabilities: null,
    identityDatabaseMode: null,
    applicationDatabaseMode: null,
    services: [],
  };

  required(environment, 'FRONTEND_RUNTIME_IMAGE');
  required(environment, 'PUBLISHER_IMAGE');
  required(environment, 'PUBLISHER_FRONTEND_UPSTREAM');
  required(environment, 'PUBLISHER_BACKEND_UPSTREAM');
  required(environment, 'PUBLISHER_IDENTITY_UPSTREAM');
  required(environment, 'PUBLISHER_CONTAINER_MEMORY');
  validateUrlPort(environment, 'APP_PUBLIC_URL', publicPort);

  if (redisMode === 'local') {
    required(environment, 'CACHE_PASSWORD');
    required(environment, 'CACHE_MAX_MEMORY');
    required(environment, 'CACHE_CONTAINER_MEMORY');
    if (!skipRedis) {
      plan.services.push({ name: 'cache', build: true });
    }
  }

  if (keycloakMode === 'local') {
    plan.identityDatabaseMode = mode(environment, 'KEYCLOAK_DATABASE_MODE');
    required(environment, 'IDENTITY_DB_HOST');
    required(environment, 'IDENTITY_DB_PORT');
    required(environment, 'IDENTITY_DB_NAME');
    required(environment, 'IDENTITY_DB_USER');
    required(environment, 'IDENTITY_DB_PASSWORD');
    required(environment, 'OIDC_REALM');
    const keycloakAdminPort = portValue(environment, 'KEYCLOAK_HTTP_PORT');
    plan.keycloakAdminUrl = required(environment, 'KEYCLOAK_ADMIN_URL');
    validateUrlPort(environment, 'KEYCLOAK_ADMIN_URL', keycloakAdminPort);
    validateUrlPort(environment, 'KEYCLOAK_PUBLIC_URL', publicPort);

    if (plan.identityDatabaseMode === 'local' && !skipKeycloak) {
      plan.services.push({ name: 'identity-database', build: false });
    }
    if (!skipKeycloak) {
      plan.services.push({ name: 'identity', build: true });
    }
  }

  if (backendMode === 'local') {
    plan.applicationDatabaseMode = mode(environment, 'DATABASE_MODE');
    required(environment, 'DATABASE_HOST');
    required(environment, 'DATABASE_PORT');
    required(environment, 'DATABASE_NAME');
    required(environment, 'DATABASE_USER');
    required(environment, 'DATABASE_PASSWORD');
    required(environment, 'KEY_ENV_PART');
    booleanValue(environment, 'CREATE_PRIVATE_KEY_PART_CLASS');
    required(environment, 'REDIS_HOST');
    required(environment, 'REDIS_PORT');
    booleanValue(environment, 'REDIS_SSL_ENABLED');
    required(environment, 'OIDC_ISSUER_URI');
    required(environment, 'OIDC_JWK_SET_URI');
    required(environment, 'OIDC_BACKEND_AUDIENCE');
    required(environment, 'WORKER_STANDARD_IMAGE');
    required(environment, 'WORKER_STANDARD_CONTAINER_MEMORY');
    required(environment, 'GRPC_CERTIFICATE_GENERATOR_IMAGE');
    required(environment, 'GRPC_OPENSSL_IMAGE');
    plan.workerGrpcHost = required(environment, 'WORKER_GRPC_HOST');
    plan.workerGrpcPort = portValue(environment, 'WORKER_GRPC_PORT');
    plan.workerGrpcTlsEnabled = booleanValue(environment, 'WORKER_GRPC_TLS_ENABLED');
    if (!plan.workerGrpcTlsEnabled) {
      throw new Error('WORKER_GRPC_TLS_ENABLED must be true for local backend and worker communication.');
    }
    plan.workerGrpcTlsServerName = dnsNameValue(environment, 'WORKER_GRPC_TLS_SERVER_NAME');
    plan.grpcCertificateValidityDays = positiveIntegerValue(environment, 'GRPC_CERTIFICATE_VALIDITY_DAYS');
    plan.rotateGrpcCertificates = !skipBackend && !skipWorkerStandard && !skipWorkerPlaywright;
    booleanValue(environment, 'WORKER_DISCOVERY_ENABLED');
    required(environment, 'WORKER_DISCOVERY_INTERVAL');
    required(environment, 'WORKER_HEALTH_TIMEOUT');
    plan.workerStandardReplicas = positiveIntegerValue(environment, 'WORKER_STANDARD_REPLICAS');
    plan.workerStandardCapabilities = workerCapabilitiesValue(environment, 'WORKER_STANDARD_CAPABILITIES');
    required(environment, 'WORKER_PLAYWRIGHT_IMAGE');
    required(environment, 'WORKER_PLAYWRIGHT_CONTAINER_MEMORY');
    plan.workerPlaywrightReplicas = positiveIntegerValue(environment, 'WORKER_PLAYWRIGHT_REPLICAS');
    plan.workerPlaywrightCapabilities = workerCapabilitiesValue(environment, 'WORKER_PLAYWRIGHT_CAPABILITIES');
    validateUrlPort(environment, 'BACKEND_PUBLIC_URL', publicPort);
    if (keycloakMode === 'local') {
      validateUrlPort(environment, 'OIDC_ISSUER_URI', publicPort);
    }
    plan.backendDebugEnabled = booleanValue(environment, 'BACKEND_DEBUG_ENABLED');
    plan.backendDebugPort = required(environment, 'BACKEND_DEBUG_PORT');
    plan.backendDebugSuspend = allowedValue(
      environment,
      'BACKEND_DEBUG_SUSPEND',
      ['y', 'n'],
    );

    if (plan.applicationDatabaseMode === 'local') {
      required(environment, 'DATABASE_IMAGE');
      required(environment, 'DATABASE_BOOTSTRAP_NAME');
      const bootstrapUser = required(environment, 'DATABASE_BOOTSTRAP_USER');
      required(environment, 'DATABASE_BOOTSTRAP_PASSWORD');
      if (bootstrapUser === required(environment, 'DATABASE_USER')) {
        throw new Error('DATABASE_BOOTSTRAP_USER must be different from DATABASE_USER.');
      }
      if (!skipDatabase) {
        plan.services.push({ name: 'database', build: true });
      }
    }
    if (!skipWorkerStandard) {
      plan.services.push({ name: 'worker-standard', build: true, scale: plan.workerStandardReplicas });
    }
    if (!skipWorkerPlaywright) {
      plan.services.push({ name: 'worker-playwright', build: true, scale: plan.workerPlaywrightReplicas });
    }
    if (!skipBackend) {
      plan.services.push({ name: 'backend', build: true });
    }
  }

  if (frontendMode === 'local') {
    required(environment, 'FRONTEND_BUILD_IMAGE');
    required(environment, 'FRONTEND_RUNTIME_IMAGE');
    required(environment, 'FRONTEND_IMAGE');
    required(environment, 'KEYCLOAK_PUBLIC_URL');
    required(environment, 'OIDC_REALM');
    required(environment, 'OIDC_FRONTEND_CLIENT_ID');
    required(environment, 'FRONTEND_CONTAINER_MEMORY');
    plan.services.push({ name: 'frontend', build: true });
  }

  plan.services.push({ name: 'publisher', build: true });

  const serviceOrder = new Map([
    ['identity-database', 10],
    ['database', 20],
    ['cache', 30],
    ['worker-standard', 35],
    ['worker-playwright', 36],
    ['identity', 40],
    ['backend', 50],
    ['frontend', 60],
    ['publisher', 70],
  ]);
  plan.services.sort(
    (left, right) => serviceOrder.get(left.name) - serviceOrder.get(right.name),
  );

  return plan;
}

function printPlan(plan, environment) {
  console.log('\nSelected components:');
  if (plan.redisMode === 'local') {
    if (plan.skipRedis) {
      console.log(
        `  - Redis: local, reusing the existing container without rebuild or restart ` +
          `(${redactUrl(plan.redisUrl)})`,
      );
    } else {
      console.log(`  - Redis: local (${redactUrl(plan.redisUrl)})`);
    }
  } else {
    console.log(`  - Redis: external, reusing ${redactUrl(plan.redisUrl)}`);
  }

  if (plan.keycloakMode === 'external') {
    console.log(`  - Keycloak: external, reusing ${redactUrl(plan.keycloakUrl)}`);
    console.log('  - Keycloak database: not managed because Keycloak is external');
  } else {
    if (plan.skipKeycloak) {
      console.log(
        `  - Keycloak: local, reusing the existing container without rebuild or restart ` +
          `(${redactUrl(plan.keycloakUrl)})`,
      );
    } else {
      console.log(`  - Keycloak: local (${redactUrl(plan.keycloakUrl)})`);
    }
    if (plan.identityDatabaseMode === 'local') {
      if (plan.skipKeycloak) {
        console.log(
          '  - Keycloak database: local PostgreSQL, reusing the existing container without restart',
        );
      } else {
        console.log('  - Keycloak database: local PostgreSQL');
      }
    } else {
      const host = required(environment, 'IDENTITY_DB_HOST');
      const port = required(environment, 'IDENTITY_DB_PORT');
      const database = required(environment, 'IDENTITY_DB_NAME');
      console.log(`  - Keycloak database: external (${host}:${port}/${database})`);
    }
  }

  if (plan.backendMode === 'external') {
    console.log(`  - Backend: external, reusing ${redactUrl(plan.backendUrl)}`);
    console.log('  - Application database: not managed because the backend is external');
  } else {
    console.log(`  - Backend: local (${redactUrl(plan.backendUrl)})`);
    if (plan.skipBackend) {
      console.log('  - Backend process: reusing the existing container without rebuild or restart');
    }
    console.log(
      `  - Standard worker: ${plan.workerStandardReplicas} local gRPC ` +
        `${plan.workerStandardReplicas === 1 ? 'replica' : 'replicas'} ` +
        `published under ${plan.workerGrpcHost}:${plan.workerGrpcPort} ` +
        `(capabilities: ${plan.workerStandardCapabilities.join(', ')}; ` +
        `${plan.skipWorkerStandard ? 'reused without restart' : 'rebuilt and restarted'})`,
    );
    console.log(
      `  - Playwright worker: ${plan.workerPlaywrightReplicas} local gRPC ` +
        `${plan.workerPlaywrightReplicas === 1 ? 'replica' : 'replicas'} ` +
        `published under ${plan.workerGrpcHost}:${plan.workerGrpcPort} ` +
        `(capabilities: ${plan.workerPlaywrightCapabilities.join(', ')}; ` +
        `${plan.skipWorkerPlaywright ? 'reused without restart' : 'rebuilt and restarted'})`,
    );
    console.log(
      `  - Worker gRPC security: mutual TLS, server name ${plan.workerGrpcTlsServerName}, ` +
        `certificate validity ${plan.grpcCertificateValidityDays} days; ` +
        `${plan.rotateGrpcCertificates ? 'leaf certificates will be renewed' : 'existing certificates will be reused without modification'}`,
    );
    if (plan.backendDebugEnabled) {
      console.log(
        `  - Java remote debug: enabled on port ${plan.backendDebugPort} ` +
          `(suspend=${plan.backendDebugSuspend})`,
      );
    } else {
      console.log('  - Java remote debug: disabled');
    }
    if (plan.applicationDatabaseMode === 'local') {
      if (plan.skipDatabase) {
        console.log(
          '  - Application database: local PostgreSQL, reusing the existing container without rebuild or restart',
        );
      } else {
        console.log('  - Application database: local PostgreSQL');
      }
    } else {
      const host = required(environment, 'DATABASE_HOST');
      const port = required(environment, 'DATABASE_PORT');
      const database = required(environment, 'DATABASE_NAME');
      console.log(`  - Application database: external (${host}:${port}/${database})`);
    }
  }

  if (plan.frontendMode === 'local') {
    console.log(`  - Frontend: local (${redactUrl(plan.frontendUrl)})`);
  } else {
    console.log(`  - Frontend: external, reusing ${redactUrl(plan.frontendUrl)}`);
  }

  console.log(`  - HTTP publisher: local (${redactUrl(plan.publisherUrl)})`);
  if (plan.keycloakAdminUrl) {
    console.log(
      `  - Keycloak master administration: direct local access (${redactUrl(plan.keycloakAdminUrl)})`,
    );
  }
}

function runCommand(command, args, cwd, label, environment) {
  console.log(`\n${label}`);
  const childEnvironment = environment
    ? {
        ...process.env,
        ...Object.fromEntries(
          [...environment.entries()].map(([key, value]) => [key, plainValue(value)]),
        ),
      }
    : process.env;
  const result = spawnSync(command, args, {
    cwd,
    stdio: 'inherit',
    shell: false,
    env: childEnvironment,
  });
  if (result.error) {
    throw new Error(`${label}: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`${label}: command exited with code ${result.status}.`);
  }
}

function commandSucceeds(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, stdio: 'ignore', shell: false });
  return !result.error && result.status === 0;
}

function prepareGrpcCertificates(plan, environment, projectDirectory) {
  if (plan.backendMode !== 'local' || !plan.workerGrpcTlsEnabled) {
    return;
  }

  const generatorImage = required(environment, 'GRPC_CERTIFICATE_GENERATOR_IMAGE');
  const opensslImage = required(environment, 'GRPC_OPENSSL_IMAGE');
  const projectName = required(environment, 'COMPOSE_PROJECT_NAME');
  const volumes = {
    ca: `${projectName}-grpc-ca`,
    backend: `${projectName}-grpc-backend-tls`,
    worker: `${projectName}-grpc-worker-tls`,
  };

  runCommand(
    'docker',
    [
      'build',
      '--build-arg', `GRPC_OPENSSL_IMAGE=${opensslImage}`,
      '--file', path.join('grpc', 'Dockerfile'),
      '--tag', generatorImage,
      'grpc',
    ],
    projectDirectory,
    'Preparing the gRPC certificate generator...',
  );

  if (plan.rotateGrpcCertificates) {
    for (const [role, volume] of Object.entries(volumes)) {
      runCommand('docker', ['volume', 'create', volume], projectDirectory, `Preparing the gRPC ${role} certificate volume...`);
    }
  } else {
    const missingVolumes = Object.values(volumes).filter(
      (volume) => !commandSucceeds('docker', ['volume', 'inspect', volume], projectDirectory),
    );
    if (missingVolumes.length > 0) {
      throw new Error(
        'gRPC mTLS certificates cannot be reused because their Docker volumes do not exist. ' +
          'Run once without --skip-backend, --skip-worker-standard or --skip-worker-playwright.',
      );
    }
  }

  runCommand(
    'docker',
    [
      'run', '--rm',
      '--volume', `${volumes.ca}:/ca`,
      '--volume', `${volumes.backend}:/backend`,
      '--volume', `${volumes.worker}:/worker`,
      '--env', `GRPC_CERTIFICATE_VALIDITY_DAYS=${plan.grpcCertificateValidityDays}`,
      '--env', `WORKER_GRPC_TLS_SERVER_NAME=${plan.workerGrpcTlsServerName}`,
      generatorImage,
      plan.rotateGrpcCertificates ? 'rotate' : 'validate',
    ],
    projectDirectory,
    plan.rotateGrpcCertificates
      ? 'Renewing backend and worker gRPC mTLS certificates...'
      : 'Validating existing gRPC mTLS certificates without modifying them...',
  );
}

function startLocalServices(plan, environment, projectDirectory) {
  if (plan.services.length === 0) {
    console.log('\nNo local application components need to be started.');
    return;
  }

  runCommand('docker', ['compose', 'version'], projectDirectory, 'Checking Docker Compose...', environment);
  runCommand(
    'docker',
    ['compose', '--env-file', '.env', 'config', '--quiet'],
    projectDirectory,
    'Validating compose.yaml...',
    environment,
  );

  for (const service of plan.services) {
    const args = ['compose', '--env-file', '.env', 'up', '--detach'];
    if (service.build) args.push('--build');
    if (service.scale) args.push('--scale', `${service.name}=${service.scale}`);
    args.push('--wait', service.name);
    runCommand('docker', args, projectDirectory, `Starting ${service.name}...`, environment);
  }
}

function cleanupDockerResources(projectDirectory) {
  runCommand(
    'docker',
    ['image', 'prune', '-a', '-f'],
    projectDirectory,
    'Removing unused Docker images...',
  );
  runCommand(
    'docker',
    ['buildx', 'prune', '--all', '--force', '--filter', 'type!=exec.cachemount'],
    projectDirectory,
    'Removing Docker build cache while preserving dependency cache mounts...',
  );
}

function printHelp() {
  console.log(`Usage: run.bat [options]\n       ./run.sh [options]\n\n` +
    '  No options          Reconcile .env and start local components.\n' +
    '  --configure-only   Reconcile .env and show the plan without starting services.\n' +
    '  --skip-keycloak    Do not rebuild or restart Keycloak or its local database.\n' +
    '  --skip-redis       Do not rebuild or restart the local Redis service.\n' +
    '  --skip-database    Do not rebuild or restart the local application database.\n' +
    '  --skip-backend     Do not rebuild or restart the local backend.\n' +
    '  --skip-worker-standard    Do not rebuild or restart standard workers.\n' +
    '  --skip-worker-playwright  Do not rebuild or restart Playwright workers.\n' +
    '  --cleanup-docker   Remove unused Docker images and build cache, preserving dependency cache mounts.\n' +
    '  --help             Show this help.');
}

function main(argv = process.argv.slice(2), projectDirectory = path.resolve(__dirname, '..')) {
  const nodeMajorVersion = Number.parseInt(process.versions.node.split('.')[0], 10);
  if (nodeMajorVersion < 18) {
    throw new Error(`Node.js 18 or later is required; detected version: ${process.versions.node}.`);
  }

  const allowed = new Set([
    '--configure-only',
    '--skip-keycloak',
    '--skip-redis',
    '--skip-database',
    '--skip-backend',
    '--skip-worker-standard',
    '--skip-worker-playwright',
    '--cleanup-docker',
    '--help',
  ]);
  const unknown = argv.filter((argument) => !allowed.has(argument));
  if (unknown.length > 0) {
    throw new Error(`Unknown option: ${unknown.join(', ')}. Use --help to list the available options.`);
  }
  if (argv.includes('--help')) {
    printHelp();
    return;
  }
  if (argv.includes('--configure-only') && argv.includes('--cleanup-docker')) {
    throw new Error('--cleanup-docker cannot be combined with --configure-only.');
  }

  const templatePath = path.join(projectDirectory, '.env.template');
  const envPath = path.join(projectDirectory, '.env');
  if (!fs.existsSync(templatePath)) {
    throw new Error(`${templatePath} was not found.`);
  }

  const result = reconcileEnvironment(templatePath, envPath);
  if (result.created) {
    console.log(`.env was created with ${result.addedKeys.length} variables.`);
  } else if (result.addedKeys.length > 0) {
    console.log(`Added to .env: ${result.addedKeys.join(', ')}.`);
  } else {
    console.log('.env already contains every variable from .env.template; no changes were made.');
  }

  if (result.generatedSecrets.length > 0) {
    console.log(
      `Secrets generated without displaying their values: ${result.generatedSecrets.join(', ')}.`,
    );
  }
  if (result.reusedSecrets.length > 0) {
    console.log(
      `Existing secrets reused for missing variables: ${result.reusedSecrets.join(', ')}.`,
    );
  }
  if (result.duplicates.length > 0) {
    console.warn(
      `Warning: .env contains duplicate keys; the last value takes precedence: ${result.duplicates.join(', ')}.`,
    );
  }

  const effectiveEnvironment = applyApplicationContext(result.environment);
  const plan = buildPlan(effectiveEnvironment, {
    skipKeycloak: argv.includes('--skip-keycloak'),
    skipRedis: argv.includes('--skip-redis'),
    skipDatabase: argv.includes('--skip-database'),
    skipBackend: argv.includes('--skip-backend'),
    skipWorkerStandard: argv.includes('--skip-worker-standard'),
    skipWorkerPlaywright: argv.includes('--skip-worker-playwright'),
  });
  const privateKeyPartClassResult = ensurePrivateKeyPartClass(effectiveEnvironment, projectDirectory);
  printPrivateKeyPartClassResult(privateKeyPartClassResult, projectDirectory);
  printPlan(plan, effectiveEnvironment);

  if (argv.includes('--configure-only')) {
    console.log('\nConfiguration completed; no application containers were started.');
    return;
  }

  prepareGrpcCertificates(plan, effectiveEnvironment, projectDirectory);
  startLocalServices(plan, effectiveEnvironment, projectDirectory);
  if (argv.includes('--cleanup-docker')) {
    cleanupDockerResources(projectDirectory);
  }
  console.log('\nStartup completed successfully.');
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`ERROR: ${error.message}`);
    process.exitCode = 1;
  }
}

module.exports = {
  applyApplicationContext,
  buildPlan,
  ensurePrivateKeyPartClass,
  main,
  parseExistingEnvironment,
  prepareGrpcCertificates,
  reconcileEnvironment,
  resolveSecretValues,
};

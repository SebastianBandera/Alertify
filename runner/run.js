'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const SECRET_PATTERN = /<GENERATE_([A-Z][A-Z0-9_]*)>/g;
const ASSIGNMENT_PATTERN = /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/;

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

function buildPlan(environment) {
  const redisMode = mode(environment, 'REDIS_MODE');
  const keycloakMode = mode(environment, 'KEYCLOAK_MODE');
  const backendMode = mode(environment, 'BACKEND_MODE');
  const frontendMode = mode(environment, 'FRONTEND_MODE');
  const plan = {
    redisMode,
    redisUrl: required(environment, 'REDIS_URL'),
    keycloakMode,
    keycloakUrl: required(environment, 'KEYCLOAK_PUBLIC_URL'),
    backendMode,
    backendUrl: required(environment, 'BACKEND_PUBLIC_URL'),
    frontendMode,
    frontendUrl: required(environment, 'APP_PUBLIC_URL'),
    backendDebugEnabled: false,
    backendDebugPort: null,
    backendDebugSuspend: null,
    identityDatabaseMode: null,
    applicationDatabaseMode: null,
    services: [],
  };

  if (redisMode === 'local') {
    required(environment, 'CACHE_PASSWORD');
    required(environment, 'CACHE_MAX_MEMORY');
    required(environment, 'CACHE_CONTAINER_MEMORY');
    plan.services.push({ name: 'cache', build: true });
  }

  if (keycloakMode === 'local') {
    plan.identityDatabaseMode = mode(environment, 'KEYCLOAK_DATABASE_MODE');
    required(environment, 'IDENTITY_DB_HOST');
    required(environment, 'IDENTITY_DB_PORT');
    required(environment, 'IDENTITY_DB_NAME');
    required(environment, 'IDENTITY_DB_USER');
    required(environment, 'IDENTITY_DB_PASSWORD');

    if (plan.identityDatabaseMode === 'local') {
      plan.services.push({ name: 'identity-database', build: false });
    }
    plan.services.push({ name: 'identity', build: true });
  }

  if (backendMode === 'local') {
    plan.applicationDatabaseMode = mode(environment, 'DATABASE_MODE');
    required(environment, 'DATABASE_HOST');
    required(environment, 'DATABASE_PORT');
    required(environment, 'DATABASE_NAME');
    required(environment, 'DATABASE_USER');
    required(environment, 'DATABASE_PASSWORD');
    required(environment, 'KEY_ENV_PART');
    required(environment, 'REDIS_HOST');
    required(environment, 'REDIS_PORT');
    booleanValue(environment, 'REDIS_SSL_ENABLED');
    required(environment, 'OIDC_ISSUER_URI');
    required(environment, 'OIDC_JWK_SET_URI');
    required(environment, 'OIDC_BACKEND_AUDIENCE');
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
      plan.services.push({ name: 'database', build: true });
    }
    plan.services.push({ name: 'backend', build: true });
  }

  if (frontendMode === 'local') {
    required(environment, 'FRONTEND_BUILD_IMAGE');
    required(environment, 'FRONTEND_RUNTIME_IMAGE');
    required(environment, 'FRONTEND_IMAGE');
    required(environment, 'FRONTEND_HTTP_PORT');
    required(environment, 'KEYCLOAK_PUBLIC_URL');
    required(environment, 'OIDC_REALM');
    required(environment, 'OIDC_FRONTEND_CLIENT_ID');
    required(environment, 'FRONTEND_CONTAINER_MEMORY');
    plan.services.push({ name: 'frontend', build: true });
  }

  const serviceOrder = new Map([
    ['identity-database', 10],
    ['database', 20],
    ['cache', 30],
    ['identity', 40],
    ['backend', 50],
    ['frontend', 60],
  ]);
  plan.services.sort(
    (left, right) => serviceOrder.get(left.name) - serviceOrder.get(right.name),
  );

  return plan;
}

function printPlan(plan, environment) {
  console.log('\nSelected components:');
  if (plan.redisMode === 'local') {
    console.log(`  - Redis: local (${redactUrl(plan.redisUrl)})`);
  } else {
    console.log(`  - Redis: external, reusing ${redactUrl(plan.redisUrl)}`);
  }

  if (plan.keycloakMode === 'external') {
    console.log(`  - Keycloak: external, reusing ${redactUrl(plan.keycloakUrl)}`);
    console.log('  - Keycloak database: not managed because Keycloak is external');
  } else {
    console.log(`  - Keycloak: local (${redactUrl(plan.keycloakUrl)})`);
    if (plan.identityDatabaseMode === 'local') {
      console.log('  - Keycloak database: local PostgreSQL');
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
    if (plan.backendDebugEnabled) {
      console.log(
        `  - Java remote debug: enabled on port ${plan.backendDebugPort} ` +
          `(suspend=${plan.backendDebugSuspend})`,
      );
    } else {
      console.log('  - Java remote debug: disabled');
    }
    if (plan.applicationDatabaseMode === 'local') {
      console.log('  - Application database: local PostgreSQL');
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
}

function runCommand(command, args, cwd, label) {
  console.log(`\n${label}`);
  const result = spawnSync(command, args, { cwd, stdio: 'inherit', shell: false });
  if (result.error) {
    throw new Error(`${label}: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`${label}: command exited with code ${result.status}.`);
  }
}

function startLocalServices(plan, projectDirectory) {
  if (plan.services.length === 0) {
    console.log('\nNo local application components need to be started.');
    return;
  }

  runCommand('docker', ['compose', 'version'], projectDirectory, 'Checking Docker Compose...');
  runCommand(
    'docker',
    ['compose', '--env-file', '.env', 'config', '--quiet'],
    projectDirectory,
    'Validating compose.yaml...',
  );

  for (const service of plan.services) {
    const args = ['compose', '--env-file', '.env', 'up', '--detach'];
    if (service.build) args.push('--build');
    args.push('--wait', service.name);
    runCommand('docker', args, projectDirectory, `Starting ${service.name}...`);
  }
}

function printHelp() {
  console.log(`Usage: run.bat [--configure-only]\n       ./run.sh [--configure-only]\n\n` +
    '  No options            Reconcile .env and start local components.\n' +
    '  --configure-only     Reconcile .env and show the plan without starting services.\n' +
    '  --help               Show this help.');
}

function main(argv = process.argv.slice(2), projectDirectory = path.resolve(__dirname, '..')) {
  const nodeMajorVersion = Number.parseInt(process.versions.node.split('.')[0], 10);
  if (nodeMajorVersion < 18) {
    throw new Error(`Node.js 18 or later is required; detected version: ${process.versions.node}.`);
  }

  const allowed = new Set(['--configure-only', '--help']);
  const unknown = argv.filter((argument) => !allowed.has(argument));
  if (unknown.length > 0) {
    throw new Error(`Unknown option: ${unknown.join(', ')}. Use --help to list the available options.`);
  }
  if (argv.includes('--help')) {
    printHelp();
    return;
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

  const plan = buildPlan(result.environment);
  printPlan(plan, result.environment);

  if (argv.includes('--configure-only')) {
    console.log('\nConfiguration completed; no application containers were started.');
    return;
  }

  startLocalServices(plan, projectDirectory);
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
  buildPlan,
  main,
  parseExistingEnvironment,
  reconcileEnvironment,
  resolveSecretValues,
};

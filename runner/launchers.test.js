'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const test = require('node:test');

const windows = process.platform === 'win32';
const root = path.resolve(__dirname, '..');

function shellQuote(value) {
  return `'${value.replace(/'/g, `'"'"'`)}'`;
}

function fixture(t, interactive) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-launcher-test-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const project = path.join(directory, 'project with spaces');
  const bin = path.join(directory, 'bin');
  const log = path.join(directory, 'commands.jsonl');
  fs.mkdirSync(project);
  fs.mkdirSync(bin);
  const launcher = path.join(project, windows ? 'run.bat' : 'run.sh');
  let source = fs.readFileSync(path.join(root, windows ? 'run.bat' : 'run.sh'), 'utf8');
  if (windows && interactive) {
    // A piped test process has no Windows console. Mock only that prerequisite;
    // the batch menu, choices, argument forwarding and dispatch execute normally.
    const consoleCheck = 'powershell -NoProfile -Command "if ([Console]::IsInputRedirected -or [Console]::IsOutputRedirected) { exit 1 } else { exit 0 }"';
    assert.ok(source.includes(consoleCheck));
    source = source.replace(consoleCheck, 'ver >nul');
  }
  fs.writeFileSync(launcher, source);
  if (windows) {
    const recorder = path.join(directory, 'record.cjs');
    fs.writeFileSync(recorder, `
      const fs = require('node:fs');
      const args = process.argv.slice(2);
      fs.appendFileSync(process.env.ALERTIFY_LAUNCHER_TEST_LOG, JSON.stringify(args) + '\\n');
      process.exitCode = args[0] === 'docker' ? 37 : 0;
    `);
    for (const command of ['node', 'docker']) {
      fs.writeFileSync(path.join(bin, `${command}.cmd`),
        `@echo off\r\n"${process.execPath}" "${recorder}" ${command} %*\r\n`);
    }
  } else {
    // Shell-only mocks ensure no real Node, Docker or Kubernetes command runs.
    for (const command of ['node', 'docker']) {
      const executable = path.join(bin, command);
      fs.writeFileSync(executable,
        `#!/bin/sh\nprintf '%s\\0' '${command}' "$@" >> "$ALERTIFY_LAUNCHER_TEST_LOG"\nexit ${command === 'docker' ? 37 : 0}\n`);
      fs.chmodSync(executable, 0o755);
    }
  }
  const environment = {
    ...process.env,
    PATH: `${bin}${path.delimiter}${process.env.PATH}`,
    ALERTIFY_LAUNCHER_TEST_LOG: log,
  };
  return {
    launcher,
    async run(args, input = '') {
      const options = { env: environment, input, encoding: 'utf8', timeout: 10_000 };
      let result;
      if (windows) {
        const command = `""${launcher}" ${args.map((argument) => `"${argument}"`).join(' ')}"`;
        const executable = process.env.ComSpec || 'cmd.exe';
        const commandArgs = ['/d', '/s', '/c', command];
        if (interactive) {
          // Send each answer only after its prompt. Windows SET /P may discard
          // subsequent lines when they arrive together in one piped buffer.
          result = await new Promise((resolve, reject) => {
            const child = spawn(executable, commandArgs, { env: environment, windowsVerbatimArguments: true });
            const answers = input.split('\n').slice(0, -1);
            let stdout = '';
            let stderr = '';
            let sent = 0;
            const timeout = setTimeout(() => {
              child.kill();
              reject(new Error('Launcher test timed out'));
            }, 10_000);
            child.stdout.on('data', (chunk) => {
              stdout += chunk;
              const prompts = stdout.split('Choice [1-3, Enter for Compose]: ').length - 1;
              while (sent < prompts && sent < answers.length) {
                child.stdin.write(`${answers[sent++]}\n`);
              }
              if (sent === answers.length) child.stdin.end();
            });
            child.stderr.on('data', (chunk) => { stderr += chunk; });
            child.on('error', (error) => { clearTimeout(timeout); reject(error); });
            child.on('close', (status) => { clearTimeout(timeout); resolve({ status, stdout, stderr }); });
          });
        } else {
          result = spawnSync(executable, commandArgs, { ...options, windowsVerbatimArguments: true });
        }
      } else if (interactive) {
        const command = ['/bin/sh', launcher, ...args].map(shellQuote).join(' ');
        result = spawnSync('script', ['-q', '-e', '-c', command, '/dev/null'], options);
      } else {
        result = spawnSync('/bin/sh', [launcher, ...args], options);
      }
      assert.ifError(result.error);
      const content = fs.existsSync(log) ? fs.readFileSync(log, 'utf8') : '';
      const commands = windows
        ? content.trim().split('\n').filter(Boolean).map((line) => JSON.parse(line))
        : content.split('\0').filter(Boolean);
      return { ...result, commands };
    },
  };
}

test('interactive Kubernetes deployment dispatches on the host before Docker checks', async (t) => {
  const app = fixture(t, true);
  const result = await app.run([], '2\n');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const commands = windows ? result.commands.flat() : result.commands;
  assert.deepEqual(commands, ['node', path.join(path.dirname(app.launcher), 'runner', 'run.js'), '--deploy-kubernetes']);
});

test('interactive Kubernetes validation forwards configure-only and launcher options', async (t) => {
  const app = fixture(t, true);
  const result = await app.run(['--replace-stale-runner'], '3\n');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const commands = windows ? result.commands.flat() : result.commands;
  assert.deepEqual(commands, ['node', path.join(path.dirname(app.launcher), 'runner', 'run.js'), '--replace-stale-runner', '--deploy-kubernetes', '--configure-only']);
});

test('interactive invalid input retries before dispatching', async (t) => {
  const app = fixture(t, true);
  const result = await app.run([], 'invalid\n2\n');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /Please select 1, 2, or 3/);
  const commands = windows ? result.commands.flat() : result.commands;
  assert.equal(commands[0], 'node');
  assert.equal(commands.at(-1), '--deploy-kubernetes');
});

test('interactive Enter preserves Compose as the default', async (t) => {
  const result = await fixture(t, true).run([], '\n');
  const commands = windows ? result.commands.flat() : result.commands;
  assert.deepEqual(commands, ['docker', 'info']);
});

test('explicit Kubernetes target bypasses the menu and Docker checks', async (t) => {
  const app = fixture(t, false);
  const args = ['--deploy-kubernetes', '--non-interactive', '--kube-context=test', '--kubeconfig=config with spaces', '--configure-only'];
  const result = await app.run(args);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const commands = windows ? result.commands.flat() : result.commands;
  assert.deepEqual(commands, ['node', path.join(path.dirname(app.launcher), 'runner', 'run.js'), ...args]);
  assert.doesNotMatch(result.stdout, /Select deployment mode/);
});

test('explicit Compose flags bypass the deployment menu', async (t) => {
  const result = await fixture(t, false).run(['--skip-backend', '--non-interactive']);
  const commands = windows ? result.commands.flat() : result.commands;
  assert.deepEqual(commands, ['docker', 'info']);
  assert.doesNotMatch(result.stdout, /Select deployment mode/);
});

test('missing interactive terminal is rejected before Docker or Node runs', async (t) => {
  const result = await fixture(t, false).run([]);
  assert.equal(result.status, 1, result.stdout + result.stderr);
  assert.match(result.stderr, /Interactive mode requires a terminal/);
  assert.deepEqual(result.commands, []);
});

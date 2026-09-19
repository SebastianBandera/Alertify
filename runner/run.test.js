'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { createWorkerComposeOverride, workerInstances } = require('./run');

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

test('createWorkerComposeOverride assigns one configured name to each generated service', (context) => {
  const projectDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'alertify-runner-test-'));
  context.after(() => fs.rmSync(projectDirectory, { recursive: true, force: true }));
  const plan = {
    workerStandardInstances: workerInstances('worker-standard', 'standard', 2, true),
    workerPlaywrightInstances: workerInstances('worker-playwright', 'playwright', 2, true),
  };

  const override = createWorkerComposeOverride(plan, projectDirectory);
  context.after(() => fs.rmSync(override.directory, { recursive: true, force: true }));
  const content = fs.readFileSync(override.path, 'utf8');

  assert.match(content, /worker-standard-1:/);
  assert.match(content, /WORKER_NAME: "standard-1"/);
  assert.match(content, /worker-playwright-2:/);
  assert.match(content, /WORKER_NAME: "playwright-2"/);
  assert.equal((content.match(/replicas: 1/g) ?? []).length, 4);
});

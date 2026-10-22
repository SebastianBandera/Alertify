'use strict';

const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { randomUUID } = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { setTimeout: delay } = require('node:timers/promises');

// Run in a disposable Node/Docker CLI container with access to the Docker socket.
const enabled = process.env.ALERTIFY_DOCKER_TESTS === 'true';
const image = process.env.ALERTIFY_NGINX_TEST_IMAGE || 'nginx:stable-alpine';

for (const context of ['/', '/alertify']) {
  test(`identity routing follows a replacement IP and preserves the URI (${context})`, { skip: !enabled, timeout: 120_000 }, async () => {
    const prefix = `alertify-routing-test-${randomUUID().slice(0, 8)}`;
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
    const containers = [];
    let networkCreated = false;
    const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30_000 }).trim();
    const create = (suffix, args, files) => {
      const name = `${prefix}-${suffix}`;
      docker('create', '--rm', '--init', '--name', name, '--network', prefix, ...args, image);
      containers.push(name);
      const sourceRoot = path.join(directory, suffix);
      for (const [destination, content] of Object.entries(files)) {
        const source = path.join(sourceRoot, destination.slice(1));
        fs.mkdirSync(path.dirname(source), { recursive: true });
        fs.writeFileSync(source, content, { mode: destination.endsWith('.envsh') ? 0o755 : 0o644 });
      }
      docker('cp', `${sourceRoot}/.`, `${name}:/`);
      docker('start', name);
      return name;
    };
    const stub = (suffix, address, aliases, status = 200) => create(suffix, ['--ip', address, ...aliases.flatMap(alias => ['--network-alias', alias])], {
      '/etc/nginx/conf.d/default.conf': `server { listen 8080; location / { return ${status} "${suffix}:$request_uri"; } }`,
    });
    const read = (container, uri) => {
      try { return docker('exec', container, 'wget', '-q', '-O', '-', `http://127.0.0.1:8080${uri}`); }
      catch { return null; }
    };
    const eventually = async (container, uri, expected) => {
      const deadline = Date.now() + 15_000;
      let actual;
      do {
        actual = read(container, uri);
        if (actual === expected) return;
        await delay(250);
      } while (Date.now() < deadline);
      assert.equal(actual, expected, 'The publisher must route to the current identity service without a reload');
    };
    try {
      docker('network', 'create', prefix);
      networkCreated = true;
      const subnet = JSON.parse(docker('network', 'inspect', prefix))[0].IPAM.Config[0].Subnet;
      const base = subnet.split('/')[0].split('.').map(Number);
      const address = offset => [...base.slice(0, 3), base[3] + offset].join('.');
      const previous = stub('previous', address(10), ['identity', 'frontend']);
      const proxy = create('proxy', [
        '-e', `APP_CONTEXT_PATH=${context}`, '-e', 'PUBLIC_PORT=80', '-e', 'PUBLISHER_TLS_ENABLED=false',
        '-e', 'PUBLISHER_BACKEND_UPSTREAM=http://identity:8080',
        '-e', 'PUBLISHER_FRONTEND_UPSTREAM=http://frontend:8080',
        '-e', 'PUBLISHER_IDENTITY_UPSTREAM=http://identity:8080',
      ], {
        '/etc/nginx/templates/default.conf.template': fs.readFileSync(path.join(__dirname, 'default.conf.template'), 'utf8'),
        '/docker-entrypoint.d/19-application-context.envsh': fs.readFileSync(path.join(__dirname, '19-application-context.envsh'), 'utf8'),
      });
      const uri = `${context === '/' ? '' : context}/identity/realms/fixture/.well-known/openid-configuration?probe=keep%2Fquery`;
      await eventually(proxy, uri, `previous:${uri}`);
      docker('stop', previous);
      // Reusing the old IP for a different service reproduces the observed public 401.
      stub('unrelated', address(10), ['unrelated'], 401);
      stub('replacement', address(11), ['identity']);
      await eventually(proxy, uri, `replacement:${uri}`);
      const guarded = `${context === '/' ? '' : context}/identity/realms/master/`;
      assert.equal(read(proxy, guarded), null, 'The master realm must remain unavailable through the publisher');
    } finally {
      for (const name of containers.reverse()) {
        try { docker('rm', '--force', name); } catch { /* Already removed by --rm. */ }
      }
      if (networkCreated) docker('network', 'rm', prefix);
      fs.rmSync(directory, { recursive: true, force: true });
    }
  });
}

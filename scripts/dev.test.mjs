import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, statSync, writeFileSync, rmSync, copyFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:net';
import { initialize, loadConfig, assertFree, start, signalGroup } from './dev.mjs';

const base = join(tmpdir(), 'codex');
mkdirSync(base, { recursive: true });

test('setup preserves existing credentials and creates isolated private configuration', () => {
  const dir = mkdtempSync(join(base, 'iwork-dev-test-'));
  const path = join(dir, '.env.development');
  try {
    assert.equal(initialize(path, dir), true);
    assert.equal(statSync(path).mode & 0o777, 0o600);
    const original = readFileSync(path, 'utf8');
    const config = loadConfig(path);
    assert.notEqual(config.IWORK_DB_PASSWORD, config.IWORK_MYSQL_ROOT_PASSWORD);
    assert.notEqual(config.IWORK_DB_PASSWORD, config.IWORK_BOOTSTRAP_PASSWORD);
    assert.match(config.COMPOSE_PROJECT_NAME, /^iwork-dev-/);
    assert.equal(initialize(path, dir), false);
    assert.equal(readFileSync(path, 'utf8'), original);
    writeFileSync(path, original.replace("SERVER_PORT='8080'", "SERVER_PORT='0'"));
    assert.throws(() => loadConfig(path), /端口/);
    writeFileSync(path, original.replace(/COMPOSE_PROJECT_NAME='[^']+'/g, "COMPOSE_PROJECT_NAME='iwork'"));
    assert.throws(() => loadConfig(path), /隔离/);
    writeFileSync(path, original.replace("IWORK_DB_HOST='127.0.0.1'", "IWORK_DB_HOST='production'"));
    assert.throws(() => loadConfig(path), /127.0.0.1/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('occupied port fails without killing its owner', async () => {
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  try {
    await assert.rejects(assertFree(port), /已占用/);
    assert.equal(server.listening, true);
  } finally { await new Promise(resolve => server.close(resolve)); }
  await assertFree(port);
});

test('shutdown reaches descendants in the spawned process group', async () => {
  const job = start(process.execPath, ['-e', `
    const {spawn} = require('node:child_process');
    const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)']);
    console.log(child.pid);
    setInterval(() => {}, 1000);
  `], 'test', process.env);
  const pid = await new Promise(resolve => job.child.stdout.once('data', data => resolve(Number(data.toString().trim()))));
  signalGroup(job.child, 'SIGTERM');
  await assert.rejects(job.done);
  for (let i = 0; i < 50; i++) {
    try { process.kill(pid, 0); } catch { return; }
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  signalGroup(job.child, 'SIGKILL');
  assert.fail('descendant survived process-group shutdown');
});


test('repeated terminal signals still finish cleanup during npm setup', async () => {
  const dir = mkdtempSync(join(base, 'iwork-dev-signals-'));
  mkdirSync(join(dir, 'scripts'));
  mkdirSync(join(dir, 'bin'));
  copyFileSync(new URL('./dev.mjs', import.meta.url), join(dir, 'scripts/dev.mjs'));
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ engines: { node: process.versions.node, npm: '11.20.0' } }));
  writeFileSync(join(dir, 'bin/npm'), '#!/bin/sh\nif [ "$1" = "--version" ]; then echo 11.20.0; else echo $$ > "$DEV_TEST_PID"; exec sleep 60; fi\n', { mode: 0o700 });
  const marker = join(dir, 'child.pid');
  const job = start(process.execPath, [join(dir, 'scripts/dev.mjs'), 'setup'], 'signals',
    { ...process.env, PATH: `${join(dir, 'bin')}:${process.env.PATH}`, DEV_TEST_PID: marker }, dir);
  let pid;
  try {
    for (let i = 0; i < 100 && !existsSync(marker); i++) await new Promise(resolve => setTimeout(resolve, 20));
    assert.ok(existsSync(marker), 'setup child started');
    pid = Number(readFileSync(marker, 'utf8').trim());
    for (const signal of ['SIGINT', 'SIGINT', 'SIGHUP']) {
      job.child.kill(signal);
      await new Promise(resolve => setTimeout(resolve, 30));
    }
    await assert.rejects(job.done, /130/);
    assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
  } finally {
    signalGroup(job.child, 'SIGKILL');
    if (pid) { try { process.kill(-pid, 'SIGKILL'); } catch (e) { if (e.code !== 'ESRCH') throw e; } }
    rmSync(dir, { recursive: true, force: true });
  }
});

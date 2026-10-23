'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {createDesktop} = require('./desktop');

async function fixture(t, options = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'wasp-desktop-'));
  const libRoot = path.join(root, 'Includes', 'WaspLib'), draftRoot = path.join(root, 'drafts');
  await fs.mkdir(path.join(libRoot, 'tools', 'agents'), {recursive: true});
  await fs.writeFile(path.join(libRoot, 'main.simba'), 'function IsLoggedIn(): Boolean;\n');
  await fs.writeFile(path.join(libRoot, 'tools', 'agents', 'find_client.simba'), '// fixture, not real Simba');
  const simbaExe = path.join(root, 'simba-fixture');
  await fs.writeFile(simbaExe, `#!/usr/bin/env node
const fs = require('node:fs');
const p = process.argv.at(-1);
if (process.argv.includes('--compile')) {
 const source = fs.readFileSync(p, 'utf8');
 if (source.includes('SLOW')) { setInterval(() => {}, 1000); }
 else if (source.includes('SPOOF')) console.log('Unknown declaration Succesfully compiled in 1.00 milliseconds.');
 else if (source.includes('GOOD')) console.log('Succesfully compiled in 1.00 milliseconds.');
 else console.log('Unknown declaration "broken" at line 2');
} else if (p.endsWith('find_client.simba')) {
 const file = require('node:path').join(__dirname, 'pid');
 console.log('123\\tRuneLite\\t' + (fs.existsSync(file) ? fs.readFileSync(file, 'utf8').trim() : '99') + '\\tTest client');
} else console.log('MCP_LOGGED_IN=False');
`, {mode: 0o700});
  const adapter = createDesktop({libRoot, draftRoot, simbaExe, allowCompiler: true, allowProbe: true,
    runTimeoutMs: 1000, ...options});
  t.after(async () => { adapter.close(); await fs.rm(root, {recursive: true, force: true}); });
  return {adapter, root, libRoot, draftRoot, simbaExe};
}
test('source search uses installed files and returns real lines', async t => {
  const f = await fixture(t);
  const result = await f.adapter.find('IsLoggedIn');
  assert.equal(result.matches[0].line, 1);
  assert.equal(result.matches[0].path, 'main.simba');
  assert.match((await f.adapter.read('main.simba')).source, /IsLoggedIn/);
});
test('traversal, absolute paths and escaping symlinks rejected', async t => {
  const f = await fixture(t);
  await fs.writeFile(path.join(f.root, 'outside.simba'), 'private');
  await fs.symlink(path.join(f.root, 'outside.simba'), path.join(f.libRoot, 'outside.simba'));
  for (const p of ['../outside.simba', '/etc/passwd', 'C:\\secret.simba', 'outside.simba']) {
    await assert.rejects(f.adapter.read(p));
  }
});
test('empty source and invalid draft names rejected', async t => {
  const f = await fixture(t);
  await assert.rejects(f.adapter.stage('../escape', 'begin end.'));
  await assert.rejects(f.adapter.stage('valid', '  '));
});
test('staging is deterministic and does not execute code', async t => {
  const f = await fixture(t);
  const one = await f.adapter.stage('draft', 'GOOD'), two = await f.adapter.stage('draft', 'GOOD');
  assert.equal(one.filename, two.filename); assert.equal(one.executed, false);
  assert.equal(one.sha256.length, 64);
});
test('tampered immutable draft is rejected before compilation', async t => {
  const f = await fixture(t); const draft = await f.adapter.stage('draft', 'GOOD');
  await fs.writeFile(path.join(f.draftRoot, draft.filename), 'TAMPERED');
  await assert.rejects(f.adapter.compile(draft.filename), /changed/);
});
test('exit zero without Simba success marker is a compilation failure', async t => {
  const f = await fixture(t); const draft = await f.adapter.stage('bad', 'BROKEN');
  const result = await f.adapter.compile(draft.filename);
  assert.equal(result.exitCode, 0); assert.equal(result.compiled, false); assert.equal(result.executed, false);
});
test('actual success marker is required and recognized', async t => {
  const f = await fixture(t); const draft = await f.adapter.stage('good', 'GOOD');
  assert.equal((await f.adapter.compile(draft.filename)).compiled, true);
});
test('compiler is disabled unless explicitly enabled locally', async t => {
  const f = await fixture(t, {allowCompiler: false}); const draft = await f.adapter.stage('good', 'GOOD');
  await assert.rejects(f.adapter.compile(draft.filename), /disabled/);
});
test('timed-out compiler is terminated and never reports success', async t => {
  const f = await fixture(t, {runTimeoutMs: 100}); const draft = await f.adapter.stage('slow', 'SLOW');
  await assert.rejects(f.adapter.compile(draft.filename), /timed out/);
});
test('cancelled compiler is terminated', async t => {
  const f = await fixture(t); const draft = await f.adapter.stage('slow', 'SLOW');
  const controller = new AbortController();
  const done = f.adapter.compile(draft.filename, controller.signal);
  const timer = setTimeout(() => controller.abort(), 50);
  await assert.rejects(done, /cancelled|aborted/); clearTimeout(timer);
});
test('fixed read-only probe can return logged-out state without inventory reads', async t => {
  const f = await fixture(t);
  const result = await f.adapter.clients();
  assert.equal(result.clients[0].pid, 99);
  assert.equal((await f.adapter.probe('123')).readOnly, true);
  const names = await fs.readdir(f.draftRoot);
  assert.match(await fs.readFile(path.join(f.draftRoot, names[0]), 'utf8'), /if Client.IsLoggedIn\(\) then/);
});
test('reused handle with a different PID is rejected', async t => {
  const f = await fixture(t); await f.adapter.clients();
  await fs.writeFile(path.join(f.root, 'pid'), '100');
  await assert.rejects(f.adapter.probe('123'), /identity changed/);
});

test('an error containing success-like text does not spoof compilation', async t => {
  const f = await fixture(t); const draft = await f.adapter.stage('spoof', 'SPOOF');
  assert.equal((await f.adapter.compile(draft.filename)).compiled, false);
});

'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs/promises');
const {spawn} = require('node:child_process');
const {Client, InMemoryTransport} = require('@modelcontextprotocol/client');
const {StdioClientTransport} = require('@modelcontextprotocol/client/stdio');
const {createBridge, envConfig} = require('./bridge');
const TOKEN = 'protocol-test-01234567890123456789012345';

async function connected(t) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'wasp-protocol-'));
  const bridge = createBridge({token: TOKEN, port: 0, libRoot: root, draftRoot: path.join(root, 'drafts'), simbaExe: path.join(root, 'missing')});
  const client = new Client({name: 'wasp-test-client', version: '1.0.0'});
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await bridge.mcp.connect(serverTransport);
  await client.connect(clientTransport);
  t.after(async () => { await client.close(); await bridge.close(); await fs.rm(root, {recursive: true, force: true}); });
  return {client, bridge};
}
test('official SDK client negotiates and discovers all fifteen tools', async t => {
  const {client} = await connected(t);
  const {tools} = await client.listTools();
  assert.equal(tools.length, 15);
  assert.equal(tools.find(x => x.name === 'wasp_tap').annotations.destructiveHint, true);
  assert.equal(tools.find(x => x.name === 'wasp_source_read').annotations.readOnlyHint, true);
  const result = await client.callTool({name: 'wasp_devices', arguments: {}});
  assert.equal(result.isError, undefined);
  assert.deepEqual(JSON.parse(result.content[0].text), []);
});
test('SDK validates required fields and rejects extra arguments', async t => {
  const {client} = await connected(t);
  for (const args of [{deviceId: 'phone01', x: .5, y: .5}, {deviceId: 'phone01', frameId: 'bad', x: .5, y: .5},
      {deviceId: 'phone01', frameId: 'd58db33d-fccd-4af0-a3f1-0187440fdc1b', x: .5, y: .5, execute: 'shell'}]) {
    const result = await client.callTool({name: 'wasp_tap', arguments: args});
    assert.equal(result.isError, true);
  }
});
test('compiler remains opt-in through the actual MCP protocol', async t => {
  const {client} = await connected(t);
  const staged = await client.callTool({name: 'wasp_stage_script', arguments: {name: 'first', source: 'begin end.'}});
  const draft = JSON.parse(staged.content[0].text);
  const compiled = await client.callTool({name: 'wasp_compile_script', arguments: {filename: draft.filename}});
  assert.equal(compiled.isError, true);
  assert.match(compiled.content[0].text, /disabled/);
});
test('actual stdio process serves an official SDK client', {timeout: 8000}, async t => {
  const transport = new StdioClientTransport({command: process.execPath,
    args: [path.join(__dirname, 'bridge.js')], env: {...process.env, WASP_DEVICE_TOKEN: TOKEN, WASP_BRIDGE_PORT: '0'}, stderr: 'pipe'});
  const client = new Client({name: 'stdio-integration', version: '1.0.0'});
  t.after(() => client.close());
  await client.connect(transport);
  assert.equal((await client.listTools()).tools.length, 15);
  assert.deepEqual(JSON.parse((await client.callTool({name: 'wasp_devices', arguments: {}})).content[0].text), []);
});
test('stdio EOF shuts down relay without orphaning the process', {timeout: 6000}, async t => {
  const proc = spawn(process.execPath, [path.join(__dirname, 'bridge.js')], {
    env: {...process.env, WASP_DEVICE_TOKEN: TOKEN, WASP_BRIDGE_PORT: '0'}, stdio: ['pipe', 'pipe', 'pipe']});
  t.after(() => { if (proc.exitCode === null) proc.kill('SIGKILL'); });
  await new Promise((resolve, reject) => { proc.stderr.once('data', resolve); proc.once('error', reject); });
  const closed = new Promise((resolve, reject) => { proc.once('exit', resolve); proc.once('error', reject); });
  proc.stdin.end();
  assert.equal(await closed, 0);
});
test('invalid port and token configuration fail closed', () => {
  assert.throws(() => envConfig({WASP_BRIDGE_PORT: 'nope'}));
  assert.throws(() => createBridge({token: 'short'}));
});

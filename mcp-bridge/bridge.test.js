'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const jpeg = require('jpeg-js');
const {createRelay, GAME_PACKAGE} = require('./relay');
const TOKEN = 'test-only-012345678901234567890123456789';
const SESSION = 'session-first';
const frame = jpeg.encode({width: 64, height: 32, data: Buffer.alloc(64 * 32 * 4, 150)}, 60).data.toString('base64');

async function fixture(t, options = {}) {
  let time = 0;
  const relay = createRelay({token: TOKEN, now: () => time, commandMs: 1000, ...options});
  await new Promise(resolve => relay.server.listen(0, '127.0.0.1', resolve));
  t.after(() => relay.close());
  const base = `http://127.0.0.1:${relay.server.address().port}`;
  const state = {deviceId: 'phone01', sessionId: SESSION, remoteEnabled: true,
    foreground: GAME_PACKAGE, windowKey: 'window-42-1024-512-rotation0', width: 1024, height: 512};
  async function post(route, data, headers = {}) {
    const response = await fetch(base + route, {method: 'POST', headers: {
      authorization: 'Bearer ' + TOKEN, 'content-type': 'application/json', ...headers}, body: JSON.stringify(data)});
    return {status: response.status, body: await response.json()};
  }
  const poll = extra => post('/device/poll', {...state, ...extra});
  const result = (id, extra = {}) => post('/device/result', {deviceId: state.deviceId, sessionId: state.sessionId,
    id, ok: true, status: 'COMPLETE', ...extra});
  async function observe(extra = {}) {
    const done = relay.request(state.deviceId, 'OBSERVE');
    const delivery = await poll();
    const id = delivery.body.command.id;
    const ack = await result(id, {windowKey: state.windowKey, width: state.width, height: state.height,
      imageWidth: 64, imageHeight: 32, frame, ...extra});
    return {id, ack, completion: await done};
  }
  return {relay, state, post, poll, result, observe, advance: ms => {time += ms;}};
}

test('invalid startup credentials are rejected', () => {
  for (const token of ['', 'short', 'a'.repeat(129), 'a'.repeat(32) + '\n']) assert.throws(() => createRelay({token}));
});
test('authentication, browser-origin and content-type checks', async t => {
  const f = await fixture(t);
  assert.equal((await f.post('/device/poll', f.state, {authorization: ''})).status, 401);
  assert.equal((await f.post('/device/poll', f.state, {origin: 'https://attacker.invalid'})).status, 403);
  assert.equal((await f.post('/device/poll', f.state, {'content-type': 'text/plain'})).status, 415);
  assert.equal((await f.post('/device/poll', f.state, {'content-type': 'application/jsonp'})).status, 415);
});
test('malformed device/session and non-object bodies rejected', async t => {
  const f = await fixture(t);
  for (const data of [null, [], 3, {...f.state, deviceId: '../x'}, {...f.state, sessionId: ''}]) {
    assert.equal((await f.post('/device/poll', data)).status, 400);
  }
});
test('offline, disabled and wrong-window actions fail without input', async t => {
  const f = await fixture(t);
  assert.match((await f.relay.request('phone01', 'OBSERVE')).error, /offline/);
  await f.poll({remoteEnabled: false});
  assert.match((await f.relay.request('phone01', 'OBSERVE')).error, /disabled/);
  await f.poll({foreground: 'com.android.settings'});
  assert.match((await f.relay.request('phone01', 'OBSERVE')).error, /foreground/);
});
test('fresh screenshot returns valid JPEG and single-use frame metadata', async t => {
  const f = await fixture(t); await f.poll();
  const {id, completion} = await f.observe();
  assert.equal(completion.ok, true);
  const result = f.relay.getFrame('phone01');
  assert.equal(JSON.parse(result.content[0].text).frameId, id);
  assert.equal(result.content[1].data, frame);
});
test('JPEG marker-only bytes are not accepted as an actual image', async t => {
  const f = await fixture(t); await f.poll();
  const {completion} = await f.observe({frame: '/9j/2Q=='});
  assert.equal(completion.ok, false);
  assert.throws(() => f.relay.getFrame('phone01'));
});
test('missing frame never substitutes previously captured pixels', async t => {
  const f = await fixture(t); await f.poll(); await f.observe();
  const {completion} = await f.observe({frame: undefined});
  assert.equal(completion.ok, false);
  assert.throws(() => f.relay.getFrame('phone01'));
});
test('mismatched image or game-window geometry rejected', async t => {
  const f = await fixture(t); await f.poll();
  for (const extra of [{windowKey: 'different'}, {width: 200}, {imageHeight: 12}, {frame: '!!!!'}]) {
    assert.equal((await f.observe(extra)).completion.ok, false);
  }
});
test('tap requires exact fresh frame and finite bounded coordinates', async t => {
  const f = await fixture(t); await f.poll();
  assert.match((await f.relay.request('phone01', 'TAP', {x: .5, y: .5})).error, /frameId/);
  const {id} = await f.observe();
  for (const [x, y] of [[NaN, .5], [.5, Infinity], [1.1, .5], ['0.5', .5]]) {
    assert.equal((await f.relay.request('phone01', 'TAP', {frameId: id, x, y})).ok, false);
  }
  assert.equal((await f.relay.request('phone01', 'TAP', {frameId: 'wrong', x: .5, y: .5})).ok, false);
});
test('tap consumes the observation even before outcome is known', async t => {
  const f = await fixture(t); await f.poll(); const {id} = await f.observe();
  const done = f.relay.request('phone01', 'TAP', {frameId: id, x: .5, y: .5});
  assert.throws(() => f.relay.getFrame('phone01'));
  const command = (await f.poll()).body.command;
  assert.equal(command.params.frameId, id);
  await f.result(command.id); assert.equal((await done).ok, true);
  assert.equal((await f.relay.request('phone01', 'TAP', {frameId: id, x: .5, y: .5})).ok, false);
});
test('result replay and unknown command IDs rejected', async t => {
  const f = await fixture(t); await f.poll(); const {id} = await f.observe();
  assert.equal((await f.result(id)).status, 409);
  assert.equal((await f.result('invented')).status, 409);
});
test('Pause preempts queued work rather than waiting for its timeout', async t => {
  const f = await fixture(t); await f.poll();
  const observation = f.relay.request('phone01', 'OBSERVE');
  const pause = f.relay.request('phone01', 'PAUSE');
  assert.match((await observation).error, /Superseded/);
  const command = (await f.poll()).body.command;
  assert.equal(command.type, 'PAUSE');
  await f.result(command.id); assert.equal((await pause).ok, true);
});
test('Pause reports potentially executed delivered input honestly', async t => {
  const f = await fixture(t); await f.poll(); const {id} = await f.observe();
  const tap = f.relay.request('phone01', 'TAP', {frameId: id, x: .5, y: .5});
  const delivered = (await f.poll()).body.command;
  const pause = f.relay.request('phone01', 'PAUSE');
  assert.equal((await tap).mayHaveExecuted, true);
  assert.equal((await f.result(delivered.id)).status, 409);
  const command = (await f.poll()).body.command;
  await f.result(command.id); await pause;
});
test('MCP cancellation removes queued action without replay', async t => {
  const f = await fixture(t); await f.poll();
  const controller = new AbortController();
  const done = f.relay.request('phone01', 'OBSERVE', {}, controller.signal);
  controller.abort(); assert.match((await done).error, /cancelled/);
  assert.equal((await f.poll()).body.command, null);
});
test('session replacement cancels pending commands and clears images', async t => {
  const f = await fixture(t); await f.poll(); await f.observe();
  const pending = f.relay.request('phone01', 'OBSERVE');
  await f.poll({sessionId: 'session-replaced'});
  assert.equal((await pending).ok, false);
  assert.throws(() => f.relay.getFrame('phone01'));
});
test('window or display changes invalidate cached frames', async t => {
  const f = await fixture(t); await f.poll(); await f.observe();
  await f.poll({windowKey: 'rotated', width: 512, height: 1024});
  assert.throws(() => f.relay.getFrame('phone01'));
});
test('explicit disconnect revokes images and pending jobs', async t => {
  const f = await fixture(t); await f.poll(); await f.observe();
  const done = f.relay.request('phone01', 'OBSERVE');
  await f.post('/device/disconnect', {deviceId: 'phone01', sessionId: SESSION});
  assert.equal((await done).ok, false);
  assert.throws(() => f.relay.getFrame('phone01'));
});
test('heartbeat and frame age expire using a monotonic clock', async t => {
  const f = await fixture(t, {frameMs: 50}); await f.poll(); await f.observe();
  f.advance(51); assert.throws(() => f.relay.getFrame('phone01'));
  f.advance(10000); assert.equal(f.relay.list()[0].online, false);
});
test('late completion rejected even if event-loop timer has not fired', async t => {
  const f = await fixture(t); await f.poll();
  const done = f.relay.request('phone01', 'OBSERVE');
  const command = (await f.poll()).body.command;
  f.advance(1001);
  assert.equal((await f.result(command.id)).status, 409);
  assert.equal((await done).ok, false);
});
test('multiple commands cannot enter the same phone input path', async t => {
  const f = await fixture(t); await f.poll();
  const done = f.relay.request('phone01', 'OBSERVE');
  assert.match((await f.relay.request('phone01', 'OBSERVE')).error, /in-flight/);
  const command = (await f.poll()).body.command;
  assert.equal((await f.poll()).body.command, null);
  await f.result(command.id, {ok: false}); await done;
});
test('bounded registered device count', async t => {
  const f = await fixture(t, {maxDevices: 1}); await f.poll();
  assert.equal((await f.poll({deviceId: 'phone02'})).status, 400);
});
test('shutdown resolves pending jobs and releases listener', async t => {
  const f = await fixture(t); await f.poll();
  const done = f.relay.request('phone01', 'OBSERVE');
  await f.relay.close();
  assert.match((await done).error, /closed/);
  assert.equal(f.relay.server.listening, false);
});

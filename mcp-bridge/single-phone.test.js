'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {createRelay, GAME_PACKAGE} = require('./relay');
const {envConfig} = require('./bridge');

const TOKEN = 'one-phone-test-012345678901234567890123456';
function setup(t, options = {}) {
  let clock = 20000;
  const relay = createRelay({token: TOKEN, now: () => clock, onlineMs: 10000, ...options});
  t.after(() => relay.close());
  const origin = () => 'http://127.0.0.1:' + relay.server.address().port;
  const headers = {'content-type': 'application/json', authorization: 'Bearer ' + TOKEN};
  const pollBody = (deviceId, sessionId, extras = {}) => ({
    deviceId, sessionId, remoteEnabled: true,
    foreground: GAME_PACKAGE, windowKey: 'osrs-foreground-window',
    width: 1024, height: 472, inputAllowed: true, ...extras
  });
  const started = new Promise(resolve => relay.server.listen(0, '127.0.0.1', resolve));
  async function poll(deviceId, sessionId, extras) {
    await started;
    const response = await fetch(origin() + '/device/poll', {
      method: 'POST', headers, body: JSON.stringify(pollBody(deviceId, sessionId, extras))
    });
    return {status: response.status, json: await response.json()};
  }
  async function disconnect(deviceId, sessionId) {
    await started;
    const response = await fetch(origin() + '/device/disconnect', {
      method: 'POST', headers, body: JSON.stringify({deviceId, sessionId})
    });
    return {status: response.status, json: await response.json()};
  }
  return {relay, poll, disconnect, advance: ms => {clock += ms;}};
}

test('one device is allowed by default; another phone cannot register', async t => {
  const f = setup(t);
  assert.equal((await f.poll('s23','session-a')).status, 200);
  const rejected = await f.poll('second-phone','session-b');
  assert.equal(rejected.status, 400);
  assert.match(rejected.json.error, /single-phone/i);
  assert.equal(f.relay.list().length, 1);
  assert.equal(f.relay.list()[0].id, 's23');
  assert.equal(f.relay.list()[0].singleDeviceMode, true);
});

test('a second device stays blocked after offline cleanup', async t => {
  const f = setup(t);
  await f.poll('s23','session-a');
  f.advance(150000);
  assert.equal(f.relay.list().length, 0);
  assert.equal((await f.poll('second-phone','session-b')).status,400);
  assert.equal((await f.poll('s23','session-new')).status,200);
});

test('another consent session cannot take over an active phone', async t => {
  const f = setup(t);
  await f.poll('s23','session-a');
  const rejected = await f.poll('s23','session-b');
  assert.equal(rejected.status,400);
  assert.match(rejected.json.error,/active session/i);
  assert.equal((await f.poll('s23','session-a')).status,200);
});

test('the same device may reconnect after explicit disconnect', async t => {
  const f = setup(t);
  await f.poll('s23','session-a');
  assert.equal((await f.disconnect('s23','session-a')).status,200);
  assert.equal((await f.poll('s23','session-b')).status,200);
  assert.equal(f.relay.list().length,1);
});

test('same device may reconnect only after old session lease expires', async t => {
  const f = setup(t);
  await f.poll('s23','session-a');
  f.advance(10001);
  assert.equal((await f.poll('s23','session-b')).status,200);
});

test('explicit phone ID refuses the wrong first device', async t => {
  const f = setup(t,{allowedDeviceId:'my-s23'});
  const rejected = await f.poll('other-phone','session-a');
  assert.equal(rejected.status,400);
  assert.equal(f.relay.list().length,0);
  assert.equal((await f.poll('my-s23','session-a')).status,200);
});

test('single-device-only config rejects multi-device overrides and malformed IDs', () => {
  assert.throws(()=>createRelay({token:TOKEN,maxDevices:2}),/single-phone/i);
  assert.throws(()=>createRelay({token:TOKEN,allowedDeviceId:'../phone'}),/WASP_ALLOWED_DEVICE_ID/);
  const config=envConfig({WASP_DEVICE_TOKEN:TOKEN,WASP_ALLOWED_DEVICE_ID:'my-s23',WASP_BRIDGE_PORT:'7788'});
  assert.equal(config.allowedDeviceId,'my-s23');
  assert.equal(config.maxDevices,1);
});

test('rejected second phone cannot read mailbox or schedule input', async t => {
  const f = setup(t);
  await f.poll('s23','session-a',{outbox:[{id:'request-a',text:'Inspect only'}]});
  assert.equal((await f.poll('attacker','session-b')).status,400);
  assert.throws(()=>f.relay.inbox('attacker',0),/online phone session/i);
  const denied = await f.relay.request('attacker','OBSERVE');
  assert.equal(denied.ok,false);
  assert.match(denied.error,/offline/i);
});

'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {createAgentDesk, planSchema} = require('./agent-desk');
const cases = require('../contracts/mobile-plan-v1-cases.json');
const {createRelay, GAME_PACKAGE} = require('./relay');
const plan = cases.find(c => c.valid).plan;
for (const c of cases) test('shared mobile plan contract: ' + c.name, () => {
  assert.equal(planSchema.safeParse(c.plan).success, c.valid);
});
function fixture(options = {}) {
  let time = 0;
  const desk = createAgentDesk({now: () => time, ...options});
  const device = {id: 'phone01', session: 'session01'};
  return {desk, device, advance: ms => {time += ms;}};
}
test('phone outbox is idempotent and never interpreted as an action', () => {
  const {desk, device} = fixture(); const message = {id: 'request01', text: 'Chop a tree'};
  assert.deepEqual(desk.receive(device, {outbox: [message]}), ['request01']);
  desk.receive(device, {outbox: [message]});
  assert.equal(desk.inbox(device).messages.length, 1);
  assert.equal(desk.view(device).proposal, null);
});
test('message ID cannot be reused with different content', () => {
  const {desk, device} = fixture(); desk.receive(device, {outbox: [{id: 'request01', text: 'one'}]});
  assert.throws(() => desk.receive(device, {outbox: [{id: 'request01', text: 'two'}]}), /reused/);
});
test('malformed batch is rejected before accepting first message', () => {
  const {desk, device} = fixture();
  assert.throws(() => desk.receive(device, {outbox: [{id: 'good', text: 'hello'}, {id: '../bad', text: 'bad'}]}));
  assert.equal(desk.inbox(device).messages.length, 0);
});
test('oversized messages, duplicates and too many batch entries rejected', () => {
  const {desk, device} = fixture();
  for (const outbox of [[{id: 'x', text: 'x'.repeat(1001)}], [{id: 'x', text: 'x'}, {id: 'x', text: 'x'}],
      [{id: 'a', text: 'x'}, {id: 'b', text: 'x'}, {id: 'c', text: 'x'}]]) assert.throws(() => desk.receive(device, {outbox}));
});
test('reply must reference a real current-session user request', () => {
  const {desk, device} = fixture();
  assert.throws(() => desk.reply(device, {replyTo: 'unknown', messageId: 'reply1', text: 'hello'}), /user request/);
  desk.receive(device, {outbox: [{id: 'request1', text: 'hello'}]});
  const one = desk.reply(device, {replyTo: 'request1', messageId: 'reply1', text: 'Ready'});
  assert.deepEqual(desk.reply(device, {replyTo: 'request1', messageId: 'reply1', text: 'Ready'}), one);
  assert.throws(() => desk.reply(device, {replyTo: 'request1', messageId: 'reply1', text: 'Changed'}), /collision/);
});
test('reconnect invalidates transcript, approvals and old reply targets', () => {
  const {desk, device} = fixture(); desk.receive(device, {outbox: [{id: 'request1', text: 'hello'}]});
  desk.offer(device, plan, 'request1');
  device.session = 'new-session';
  assert.equal(desk.inbox(device).messages.length, 0);
  assert.equal(desk.view(device).proposal, null);
  assert.throws(() => desk.reply(device, {replyTo: 'request1', messageId: 'reply1', text: 'stale'}));
});
test('proposal is immutable, hashed, and waits for local approval', () => {
  const {desk, device} = fixture(); const mutable = structuredClone(plan);
  const offered = desk.offer(device, mutable);
  mutable.title = 'Changed after submission';
  const received = desk.view(device).proposal;
  assert.equal(offered.status, 'OFFERED'); assert.equal(offered.approvalRequired, true);
  assert.equal(JSON.parse(received.planJson).title, plan.title);
  assert.equal(crypto.createHash('sha256').update(received.planJson).digest('hex'), received.sha256);
});
test('second proposal cannot silently replace a pending plan', () => {
  const {desk, device} = fixture(); desk.offer(device, plan);
  assert.throws(() => desk.offer(device, plan), /already/);
});
test('local rejection permits a revised plan with a new identity', () => {
  const {desk, device} = fixture(); const first = desk.offer(device, plan);
  desk.receive(device, {planStatus: {...first, status: 'REJECTED', step: 0, detail: 'No'}});
  assert.equal(desk.inbox(device).plan.status, 'REJECTED');
  assert.notEqual(desk.offer(device, plan).id, first.id);
});
test('expired proposal does not refresh its TTL on repeated polls', () => {
  const {desk, device, advance} = fixture(); desk.offer(device, plan);
  advance(50000); assert.equal(desk.view(device).proposal.ttlMs, 70000);
  advance(70001); assert.equal(desk.view(device).proposal, null);
  assert.equal(desk.inbox(device).plan.status, 'EXPIRED');
});
test('cancel is terminal and late completion cannot revive the plan', () => {
  const {desk, device} = fixture(); const offered = desk.offer(device, plan);
  desk.stop(device);
  desk.receive(device, {planStatus: {...offered, status: 'COMPLETED', step: 1}});
  assert.equal(desk.inbox(device).plan.status, 'CANCELLED');
});
test('very short completed plan can report completion without an intermediate poll', () => {
  const {desk, device} = fixture(); const offered = desk.offer(device, plan);
  desk.receive(device, {planStatus: {...offered, status: 'COMPLETED', step: 999}});
  assert.equal(desk.inbox(device).plan.status, 'COMPLETED');
  assert.equal(desk.inbox(device).plan.step, plan.steps.length);
});
test('wrong digest cannot update plan state', () => {
  const {desk, device} = fixture(); const offered = desk.offer(device, plan);
  desk.receive(device, {planStatus: {...offered, sha256: '0'.repeat(64), status: 'RUNNING'}});
  assert.equal(desk.inbox(device).plan.status, 'OFFERED');
});
test('mailbox memory is bounded and exposes truncation cursor', () => {
  const {desk, device} = fixture({maxMessages: 3});
  for (let i = 0; i < 6; i++) desk.receive(device, {outbox: [{id: 'message-' + i, text: 'hello'}]});
  assert.equal(desk.inbox(device).messages.length, 3);
  assert.equal(desk.inbox(device).droppedBeforeSeq, 4);
});
test('agent activity is reported as a recent read, not a running dot guarantee', () => {
  const {desk, device, advance} = fixture();
  assert.equal(desk.view(device).agentActive, false); desk.inbox(device);
  assert.equal(desk.view(device).agentActive, true); advance(30001);
  assert.equal(desk.view(device).agentActive, false);
});
test('authenticated HTTP poll carries user messages, replies and local-approval plan without input commands', async t => {
  const token = 'desk-test-012345678901234567890123456789';
  const relay = createRelay({token});
  await new Promise(resolve => relay.server.listen(0, '127.0.0.1', resolve));
  t.after(() => relay.close());
  const base = `http://127.0.0.1:${relay.server.address().port}`;
  const state = {deviceId: 'phone01', sessionId: 'session01', foreground: GAME_PACKAGE,
    windowKey: 'window01', width: 1024, height: 512, remoteEnabled: true, inputAllowed: false};
  async function poll(extra = {}) {
    const response = await fetch(base + '/device/poll', {method: 'POST',
      headers: {'authorization': 'Bearer ' + token, 'content-type': 'application/json'}, body: JSON.stringify({...state, ...extra})});
    assert.equal(response.status, 200); return response.json();
  }
  const first = await poll({outbox: [{id: 'request01', text: 'Watch the game'}]});
  assert.deepEqual(first.desk.ackMessages, ['request01']);
  assert.equal(relay.inbox('phone01', 0).messages[0].text, 'Watch the game');
  relay.reply('phone01', {replyTo: 'request01', messageId: 'reply01', text: 'Review this observation plan'});
  const offered = relay.offerPlan('phone01', plan, 'request01');
  const second = await poll({afterSeq: 1});
  assert.equal(second.desk.messages[0].role, 'agent');
  assert.equal(second.desk.proposal.id, offered.id);
  assert.equal(second.command, null);
  assert.equal((await relay.request('phone01', 'TAP', {})).ok, false);
});

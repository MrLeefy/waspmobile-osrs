'use strict';
// Wasp Mobile remote control plane — Node.js 18+, no dependencies.
// Run only behind HTTPS/authenticated private access. Bind localhost by default.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { validatePolicy, createNextGoal, isCompletedForGoal } = require('./planner');

const PORT = Number(process.env.CONTROL_PORT || 7788);
const HOST = process.env.CONTROL_HOST || '127.0.0.1';
const DATA_DIR = path.resolve(process.env.CONTROL_DATA_DIR || path.join(__dirname, 'data'));
const ADMIN_TOKEN = process.env.CONTROL_ADMIN_TOKEN || '';
const WORKER_TOKENS = JSON.parse(process.env.CONTROL_WORKER_TOKENS || '{}');
const MAX_CONNECTED_WORKERS = Math.max(1, Number(process.env.CONTROL_MAX_CONNECTED_WORKERS) || 2);
const VALID_ID = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const SKILLS = new Set(['woodcutting', 'mining']);
const COMMANDS = new Set(['START', 'PAUSE', 'RESUME', 'HOP', 'LOGOUT_STOP']);
const LIVE_FOR_MS = 90000;
const telemetry = new Map();

function secureEquals(actual, expected) {
  const hash = value => crypto.createHash('sha256').update(String(value || '')).digest();
  return crypto.timingSafeEqual(hash(actual), hash(expected));
}
function authentication(req, type, accountId) {
  const got = req.headers[type === 'admin' ? 'x-admin-token' : 'x-worker-token'];
  const account = state?.accounts?.[accountId];
  const legacy = account?.workerTokenHash ? null : WORKER_TOKENS[accountId];
  const expected = type === 'admin' ? ADMIN_TOKEN : legacy || account?.workerTokenHash;
  if (typeof got !== 'string' || !expected) return false;
  return type === 'worker' && !legacy
    ? secureEquals(crypto.createHash('sha256').update(got).digest('hex'), expected)
    : secureEquals(got, expected);
}
function load() {
  try { return JSON.parse(fs.readFileSync(path.join(DATA_DIR, 'state.json'), 'utf8')); }
  catch (err) {
    if (err.code !== 'ENOENT') throw err;
    return { accounts: {}, commands: {}, proposals: {} };
  }
}
let state;
function persist() {
  fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
  const next = path.join(DATA_DIR, 'state.json.tmp-' + process.pid);
  fs.writeFileSync(next, JSON.stringify(state, null, 2), { mode: 0o600 });
  fs.renameSync(next, path.join(DATA_DIR, 'state.json'));
}
function json(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(body);
}
function error(res, status, message) { return json(res, status, { error: message }); }
async function body(req) {
  let text = '';
  for await (const chunk of req) {
    text += chunk;
    if (Buffer.byteLength(text) > 1500000) throw Object.assign(new Error('Request too large'), { status: 413 });
  }
  try { const result = text ? JSON.parse(text) : {}; if (!result || typeof result !== 'object' || Array.isArray(result)) throw Error('Expected JSON object'); return result; }
  catch { throw Object.assign(new Error('Invalid JSON object'), { status: 400 }); }
}
function goalPayload(value) {
  if (!value || typeof value !== 'object' || !SKILLS.has(value.skill)) throw Object.assign(new Error('Skill must be woodcutting or mining'), { status: 400 });
  if (!['bank', 'power_drop'].includes(value.mode)) throw Object.assign(new Error('Mode must be bank or power_drop'), { status: 400 });
  const minutes = Number(value.durationMinutes);
  if (!Number.isInteger(minutes) || minutes < 5 || minutes > 180) throw Object.assign(new Error('Duration must be 5–180 minutes'), { status: 400 });
  const level = value.targetLevel === null || value.targetLevel === undefined || value.targetLevel === '' ? null : Number(value.targetLevel);
  if (level !== null && (!Number.isInteger(level) || level < 1 || level > 99)) throw Object.assign(new Error('Level must be 1–99'), { status: 400 });
  return { skill: value.skill, mode: value.mode, durationMinutes: minutes, targetLevel: level };
}
function accountView(id, account) {
  const live = telemetry.get(id);
  const now = Date.now();
  const age = live ? now - live.receivedAt : null;
  return {
    ...Object.fromEntries(Object.entries(account).filter(([key]) => key !== 'workerTokenHash')),
    id,
    workerConnected: !!live && age <= LIVE_FOR_MS,
    telemetryAgeSeconds: age === null ? null : Math.round(age / 1000),
    gameTelemetryFresh: !!live && !!live.telemetry && !!live.telemetry.timestamp && (now - live.telemetry.timestamp <= LIVE_FOR_MS),
    telemetry: live ? live.telemetry : null,
    supervisor: live ? live.supervisor : null,
    screenshotAvailable: fs.existsSync(path.join(DATA_DIR, id + '.png')),
    command: state.commands[id] || null,
    proposal: state.proposals[id] || null
  };
}
function validAccount(id) { return VALID_ID.test(id) && state.accounts[id]; }
function validateTelemetry(source) {
  const t = source && typeof source === 'object' ? source : {};
  const num = (v, min, max) => typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : null;
  const str = value => typeof value === 'string' ? value.slice(0, 100) : '';
  return {
    rsn: str(t.rsn), script: str(t.script), state: str(t.state),
    attention: str(t.attention), loggedIn: t.loggedIn === true,
    actions: num(t.actions, 0, 1e12), invCount: num(t.invCount, 0, 28),
    runEnergy: num(t.runEnergy, 0, 100),
    runtimeMs: num(t.runtimeMs, 0, 1e12),
    posX: num(t.posX, 0, 20000), posY: num(t.posY, 0, 20000),
    timestamp: Date.now()
  };
}
function sanitizeSupervisor(value) {
  if (!value || typeof value !== 'object') return null;
  const text = x => typeof x === 'string' ? x.slice(0, 100) : null;
  return { workerSupervisor: text(value.workerSupervisor), skill: text(value.skill),
    goalId: text(value.goalId), pid: Number.isInteger(value.pid) && value.pid > 0 ? value.pid : null,
    launchCount: Number.isInteger(value.launchCount) && value.launchCount >= 0 ? value.launchCount : null,
    lastExit: text(value.lastExit) };
}
function readScreenshot(encoded, id) {
  if (typeof encoded !== 'string' || encoded.length > 1300000) throw Object.assign(new Error('Invalid screenshot payload'), { status: 400 });
  const data = Buffer.from(encoded, 'base64');
  if (data.length < 24 || data.length > 900000 || data.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') {
    throw Object.assign(new Error('Screenshot must be PNG under 900 kB'), { status: 400 });
  }
  fs.writeFileSync(path.join(DATA_DIR, id + '.png'), data, { mode: 0o600 });
}
async function handler(req, res) {
  const pathname = new URL(req.url, 'http://localhost').pathname;
  if (pathname === '/healthz') return json(res, 200, { ready: true });
  if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' });
    return fs.createReadStream(path.join(__dirname, 'public', 'index.html')).pipe(res);
  }
  if (req.method === 'GET' && pathname === '/manifest.webmanifest') {
    res.writeHead(200, { 'Content-Type': 'application/manifest+json; charset=utf-8', 'Cache-Control': 'no-store' });
    return fs.createReadStream(path.join(__dirname, 'public', 'manifest.webmanifest')).pipe(res);
  }
  if (req.method === 'GET' && pathname === '/sw.js') {
    res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-store', 'Service-Worker-Allowed': '/' });
    return fs.createReadStream(path.join(__dirname, 'public', 'sw.js')).pipe(res);
  }
  if (req.method === 'GET' && pathname === '/icon.svg') {
    res.writeHead(200, { 'Content-Type': 'image/svg+xml; charset=utf-8', 'Cache-Control': 'no-store' });
    return fs.createReadStream(path.join(__dirname, 'public', 'icon.svg')).pipe(res);
  }
  if (pathname.startsWith('/api/worker/')) {
    const id = req.headers['x-account-id'];
    if (typeof id !== 'string' || !validAccount(id) || !authentication(req, 'worker', id)) return error(res, 401, 'Invalid worker credentials');
    if (pathname === '/api/worker/heartbeat' && req.method === 'POST') {
      const data = await body(req);
      // Admission is checked AFTER parsing and immediately before setting the live session.
      // No await occurs between check and reservation, avoiding concurrent first-heartbeat races.
      const alreadyConnected = telemetry.has(id) && Date.now() - telemetry.get(id).receivedAt <= LIVE_FOR_MS;
      if (!alreadyConnected) {
        const currentlyConnected = [...telemetry.values()].filter(t => Date.now() - t.receivedAt <= LIVE_FOR_MS).length;
        if (currentlyConnected >= MAX_CONNECTED_WORKERS) return error(res, 429, 'Concurrent worker limit reached; raise CONTROL_MAX_CONNECTED_WORKERS after capacity testing');
      }
      if (data.screenshot) readScreenshot(data.screenshot, id);
      const cleaned = data.telemetry ? validateTelemetry(data.telemetry) : null;
      telemetry.set(id, { receivedAt: Date.now(), telemetry: cleaned, supervisor: sanitizeSupervisor(data.supervisor) });
      const account = state.accounts[id];
      if (account.autopilot?.enabled && isCompletedForGoal(cleaned, account.goal)) {
        if (cleaned.state.includes('MAX_LEVEL')) {
          account.autopilot.enabled = false; // Respect the configured level ceiling.
          account.lastCompletion = { goalId: account.goal.id, result: 'target-level-reached', at: new Date().toISOString() };
        } else {
          const previous = account.goal;
          const next = createNextGoal(account.autopilot, previous);
          account.goal = next.goal;
          account.autopilot = next.policy;
          account.lastCompletion = { goalId: previous.id, result: 'session-complete', at: new Date().toISOString() };
        }
        persist();
      }
      return json(res, 200, { ok: true, goal: account.goal || null });
    }
    if (pathname === '/api/worker/next' && req.method === 'GET') {
      return json(res, 200, { command: state.commands[id]?.status === 'pending' ? state.commands[id] : null, goal: state.accounts[id].goal || null });
    }
    if (pathname === '/api/worker/ack' && req.method === 'POST') {
      const data = await body(req), cmd = state.commands[id];
      if (!cmd || cmd.id !== data.id || cmd.status !== 'pending') return error(res, 409, 'Command no longer pending');
      cmd.status = data.delivered === true ? 'delivered' : 'failed';
      cmd.note = typeof data.note === 'string' ? data.note.slice(0, 180) : '';
      cmd.acknowledgedAt = new Date().toISOString();
      persist();
      return json(res, 200, { ok: true });
    }
    return error(res, 404, 'Worker endpoint not found');
  }
  if (!pathname.startsWith('/api/') || !authentication(req, 'admin')) return error(res, 401, 'Administrator token required');
  if (pathname === '/api/accounts' && req.method === 'GET') {
    return json(res, 200, { registeredCount: Object.keys(state.accounts).length, maxConnectedWorkers: MAX_CONNECTED_WORKERS, accounts: Object.entries(state.accounts).map(([id, account]) => accountView(id, account)) });
  }
  if (pathname === '/api/accounts' && req.method === 'POST') {
    const data = await body(req);
    const id = typeof data.id === 'string' ? data.id.trim() : '';
    if (!VALID_ID.test(id)) return error(res, 400, 'Account ID must be lowercase letters, digits, underscore or hyphen');
    if (state.accounts[id]) return error(res, 409, 'Account ID already exists');
    const generatedToken = crypto.randomBytes(32).toString('hex');
    const label = typeof data.label === 'string' ? data.label.trim().slice(0, 48) : '';
    state.accounts[id] = { label: label || id, createdAt: new Date().toISOString(), goal: null, workerTokenHash: crypto.createHash('sha256').update(generatedToken).digest('hex') };
    persist();
    return json(res, 201, { account: accountView(id, state.accounts[id]), workerToken: generatedToken, warning: 'Copy this worker token now: it is returned only once.' });
  }
  const match = pathname.match(/^\/api\/accounts\/([a-z0-9_-]{1,32})(?:\/(goal|proposal|approve|command|screenshot|rotate-token|autopilot))?$/);
  if (!match || !validAccount(match[1])) return error(res, 404, 'Account not found');
  const [, id, action] = match;
  if (action === 'screenshot' && req.method === 'GET') {
    const file = path.join(DATA_DIR, id + '.png');
    if (!fs.existsSync(file)) return error(res, 404, 'No real screenshot received');
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    return fs.createReadStream(file).pipe(res);
  }
  if (action === 'rotate-token' && req.method === 'POST') {
    const rotatedToken = crypto.randomBytes(32).toString('hex');
    state.accounts[id].workerTokenHash = crypto.createHash('sha256').update(rotatedToken).digest('hex');
    telemetry.delete(id);
    persist();
    return json(res, 200, { workerToken: rotatedToken, warning: 'Previous worker token invalidated. Save the new token now.' });
  }
  if (!action && req.method === 'DELETE') {
    if (telemetry.has(id) && Date.now() - telemetry.get(id).receivedAt <= LIVE_FOR_MS) return error(res, 409, 'Worker is still connected; stop the worker before removing this profile');
    // Administrative profile removal does NOT stop an already-running external game.
    delete state.accounts[id]; delete state.commands[id]; delete state.proposals[id];
    telemetry.delete(id);
    const image = path.join(DATA_DIR, id + '.png');
    try { fs.unlinkSync(image); } catch (err) { if (err.code !== 'ENOENT') throw err; }
    persist();
    return json(res, 200, { removed: id, warning: 'Profile removed; any external game process must be stopped separately.' });
  }
  if (action === 'autopilot' && req.method === 'POST') {
    let policy;
    try { policy = validatePolicy(await body(req)); }
    catch (err) { return error(res, 400, err.message); }
    state.accounts[id].autopilot = policy;
    if (policy.enabled) {
      const next = createNextGoal(policy, null);
      state.accounts[id].goal = next.goal;
      state.accounts[id].autopilot = next.policy;
    }
    persist();
    return json(res, 200, { autopilot: state.accounts[id].autopilot, goal: state.accounts[id].goal });
  }
  if (action === 'goal' && req.method === 'POST') {
    const requested = goalPayload(await body(req));
    state.accounts[id].goal = { id: crypto.randomUUID(), ...requested, status: 'pending-script-integration', savedAt: new Date().toISOString() };
    if (state.accounts[id].autopilot) state.accounts[id].autopilot.enabled = false;
    delete state.proposals[id];
    persist();
    return json(res, 200, { goal: state.accounts[id].goal });
  }
  if (action === 'proposal' && req.method === 'POST') {
    const proposed = goalPayload(await body(req));
    state.proposals[id] = { id: crypto.randomUUID(), ...proposed, createdAt: new Date().toISOString() };
    persist();
    return json(res, 201, { proposal: state.proposals[id], requiresApproval: true });
  }
  if (action === 'approve' && req.method === 'POST') {
    const proposed = state.proposals[id];
    if (!proposed) return error(res, 404, 'No proposed goal awaiting approval');
    state.accounts[id].goal = { ...proposed, status: 'pending-script-integration', approvedAt: new Date().toISOString() };
    if (state.accounts[id].autopilot) state.accounts[id].autopilot.enabled = false;
    delete state.proposals[id];
    persist();
    return json(res, 200, { goal: state.accounts[id].goal });
  }
  if (action === 'command' && req.method === 'POST') {
    const data = await body(req);
    if (!COMMANDS.has(data.command)) return error(res, 400, 'Unsupported command');
    if (state.commands[id]?.status === 'pending') return error(res, 409, 'Another command is awaiting delivery');
    state.commands[id] = { id: crypto.randomUUID(), command: data.command, status: 'pending', queuedAt: new Date().toISOString() };
    if (data.command === 'LOGOUT_STOP' && state.accounts[id].autopilot) state.accounts[id].autopilot.enabled = false;
    persist();
    return json(res, 202, { command: state.commands[id], note: 'Queued for the worker; not an execution confirmation' });
  }
  return error(res, 404, 'Endpoint not found');
}
function createServer() {
  if (ADMIN_TOKEN.length < 32) throw new Error('CONTROL_ADMIN_TOKEN must be at least 32 characters');
  const values = Object.values(WORKER_TOKENS);
  if (values.some(v => typeof v !== 'string' || v.length < 32) || new Set(values).size !== values.length) {
    throw new Error('If CONTROL_WORKER_TOKENS are supplied, each token must be unique and at least 32 characters');
  }
  fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
  state = load();
  state.accounts ||= {}; state.commands ||= {}; state.proposals ||= {};
  return http.createServer((req, res) => {
    handler(req, res).catch(err => {
      if (!res.headersSent) error(res, err.status || 500, err.status ? err.message : 'Internal server error');
    });
  });
}
if (require.main === module) createServer().listen(PORT, HOST, () => {
  console.log('Wasp control plane listening on ' + HOST + ':' + PORT);
});
module.exports = { createServer };

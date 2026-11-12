'use strict';
// Outbound-only bridge from an existing Simba/WaspLib Swarm folder.
// This NEVER launches a game or accepts shell commands. Node.js 18+.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const BASE = process.env.SWARM_DIR || (process.platform === 'win32' ? 'C:\\Simba\\Swarm' : path.resolve('./Swarm'));
const BOT_ID = process.env.WASP_BOT_ID || '';
const ACCOUNT_ID = process.env.CONTROL_ACCOUNT_ID || BOT_ID;
const SERVER = (process.env.CONTROL_URL || '').replace(/\/+$/, '');
const TOKEN = process.env.CONTROL_WORKER_TOKEN || '';
const ALLOWED_COMMANDS = new Set(['START', 'PAUSE', 'RESUME', 'HOP', 'LOGOUT_STOP']);
const ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const TICK_MS = 5000;
let lastScreenMtime = 0;
let lastHeartbeat = 0;
let busy = false;

if (!ID_PATTERN.test(BOT_ID) || !ID_PATTERN.test(ACCOUNT_ID) || !TOKEN || !SERVER) {
  throw new Error('Set WASP_BOT_ID, CONTROL_ACCOUNT_ID, CONTROL_URL and CONTROL_WORKER_TOKEN');
}
if (!SERVER.startsWith('https://') && !/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/.test(SERVER)) {
  throw new Error('Remote CONTROL_URL must use HTTPS; HTTP is accepted only on localhost');
}
const headers = { 'X-Account-ID': ACCOUNT_ID, 'X-Worker-Token': TOKEN };
const teleFile = path.join(BASE, 'telemetry', BOT_ID + '.json');
const screenFile = path.join(BASE, 'telemetry', BOT_ID + '.png');
const cmdFile = path.join(BASE, 'commands', BOT_ID + '.cmd');
const goalFile = path.join(BASE, 'goals', BOT_ID + '.json');
const simbaGoalFile = path.join(BASE, 'goals', BOT_ID + '.goal');
const supervisorFile = path.join(BASE, 'status', BOT_ID + '.json');
const supervisorCommandDir = path.join(BASE, 'supervisor');

async function request(route, payload) {
  const response = await fetch(SERVER + route, {
    method: payload === undefined ? 'GET' : 'POST',
    headers: { ...headers, ...(payload === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: payload === undefined ? undefined : JSON.stringify(payload),
    signal: AbortSignal.timeout(10000)
  });
  const data = await response.json();
  if (!response.ok) throw new Error(route + ': HTTP ' + response.status + ' (' + (data.error || 'request failed') + ')');
  return data;
}
function readTelemetry() {
  try {
    const info = fs.statSync(teleFile);
    if (Date.now() - info.mtimeMs > 90000) return null;
    const value = JSON.parse(fs.readFileSync(teleFile, 'utf8'));
    // Never send credentials or file paths. Only explicit, approved telemetry fields.
    const fields = ['rsn', 'script', 'state', 'attention', 'loggedIn', 'actions', 'invCount', 'runEnergy', 'runtimeMs', 'posX', 'posY'];
    return Object.fromEntries(fields.filter(k => value[k] !== undefined).map(k => [k, value[k]]));
  } catch { return null; }
}
function screenshot() {
  try {
    const info = fs.statSync(screenFile);
    if (info.mtimeMs <= lastScreenMtime || info.size > 900000 || info.size < 24) return null;
    const data = fs.readFileSync(screenFile);
    if (data.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') return null;
    return { base64: data.toString('base64'), mtime: info.mtimeMs };
  } catch { return null; }
}
function saveGoal(goal) {
  if (!goal || typeof goal.id !== 'string' || !['woodcutting','mining'].includes(goal.skill) ||
      !['bank','power_drop'].includes(goal.mode) || !Number.isInteger(goal.durationMinutes) ||
      goal.durationMinutes < 5 || goal.durationMinutes > 180 ||
      (goal.targetLevel !== null && goal.targetLevel !== undefined &&
       (!Number.isInteger(goal.targetLevel) || goal.targetLevel < 1 || goal.targetLevel > 99))) return;
  fs.mkdirSync(path.dirname(goalFile), { recursive: true });
  let old;
  try { old = JSON.parse(fs.readFileSync(goalFile, 'utf8')); } catch {}
  if (old?.id === goal.id && fs.existsSync(simbaGoalFile)) return;
  const next = goalFile + '.tmp';
  fs.writeFileSync(next, JSON.stringify(goal, null, 2));
  fs.renameSync(next, goalFile);
  const line = [goal.id, goal.skill, goal.mode, String(goal.durationMinutes), String(goal.targetLevel || 0)].join('|') + '\n';
  const nativeTmp = simbaGoalFile + '.tmp';
  fs.writeFileSync(nativeTmp, line);
  fs.renameSync(nativeTmp, simbaGoalFile);
  console.log('Goal handed to local Simba director: ' + goal.skill + ' (' + goal.id + ')');
}
function readSupervisor() {
  try {
    const stat = fs.statSync(supervisorFile);
    if (Date.now() - stat.mtimeMs > 90000) return null;
    return JSON.parse(fs.readFileSync(supervisorFile, 'utf8'));
  } catch { return null; }
}
async function heartbeat() {
  const screen = screenshot();
  const payload = { telemetry: readTelemetry(), supervisor: readSupervisor() };
  if (screen) payload.screenshot = screen.base64;
  const response = await request('/api/worker/heartbeat', payload);
  if (screen) lastScreenMtime = screen.mtime;
  saveGoal(response.goal);
  lastHeartbeat = Date.now();
}
async function pollCommand() {
  const response = await request('/api/worker/next');
  saveGoal(response.goal);
  const cmd = response.command;
  if (!cmd || !ALLOWED_COMMANDS.has(cmd.command)) return;
  fs.mkdirSync(path.dirname(cmdFile), { recursive: true });
  fs.mkdirSync(supervisorCommandDir, { recursive: true });
  // START only wakes the local supervisor; it never injects arbitrary OS commands.
  if (cmd.command === 'START') {
    fs.writeFileSync(path.join(supervisorCommandDir, BOT_ID + '.start'), 'START\n');
    await request('/api/worker/ack', { id: cmd.id, delivered: true, note: 'Local supervisor start request written; launch not confirmed' });
    return;
  }
  if (cmd.command === 'LOGOUT_STOP')
    fs.writeFileSync(path.join(supervisorCommandDir, BOT_ID + '.stop'), 'STOP\n');
  if (cmd.command === 'RESUME')
    fs.writeFileSync(path.join(supervisorCommandDir, BOT_ID + '.start'), 'START\n');
  let note;
  try {
    // Preserve existing command. Never overwrite an instruction Simba has not read.
    fs.writeFileSync(cmdFile, cmd.command + '\n', { encoding: 'utf8', flag: 'wx' });
    note = 'Written to Simba command mailbox; execution not confirmed';
  } catch (err) {
    if (err.code !== 'EEXIST') throw err;
    const existing = fs.readFileSync(cmdFile, 'utf8').trim();
    if (existing !== cmd.command) {
      console.warn('Existing command awaiting Simba consumption; not overwriting ' + cmdFile);
      return;
    }
    note = 'Identical command already pending in Simba mailbox';
  }
  await request('/api/worker/ack', { id: cmd.id, delivered: true, note });
  console.log('Delivered ' + cmd.command + ' to Simba (' + ACCOUNT_ID + ')');
}
async function tick() {
  if (busy) return;
  busy = true;
  try {
    if (Date.now() - lastHeartbeat >= 10000) await heartbeat();
    await pollCommand();
  } catch (err) {
    console.error(new Date().toISOString() + ' Worker connection issue: ' + err.message);
  } finally { busy = false; }
}
console.log('Wasp bridge: ' + ACCOUNT_ID + ' on ' + os.hostname() + ', local Simba identity ' + BOT_ID);
console.log('Waiting for real Simba telemetry under ' + path.join(BASE, 'telemetry'));
tick();
setInterval(tick, TICK_MS);

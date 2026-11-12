'use strict';
// Windows/desktop per-user supervisor: starts ONLY explicitly configured local commands.
// Run inside an interactive user session, NOT a Windows Session 0 service.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const SKILLS = new Set(['woodcutting', 'mining']);
const ID = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const RETRY_WINDOW_MS = 15 * 60 * 1000;
const RETRY_LIMIT = 3;
const STOP_GRACE_MS = 20000;
const SCAN_INTERVAL_MS = 2500;

const requireAbsolute = (label, value) => {
  if (typeof value !== 'string' || !path.isAbsolute(value)) throw Error(label + ' must be an absolute path');
  return value;
};

function readConfig(filename) {
  const data = JSON.parse(fs.readFileSync(filename, 'utf8'));
  if (!data || typeof data !== 'object' || !Array.isArray(data.accounts) || !data.accounts.length) {
    throw Error('Configuration must have a nonempty accounts array');
  }
  const ids = new Set();
  for (const acc of data.accounts) {
    if (!acc || !ID.test(acc.id) || ids.has(acc.id)) throw Error('Duplicate or invalid account ID');
    ids.add(acc.id);
    requireAbsolute('swarmDir', acc.swarmDir);
    if (typeof acc.launchers !== 'object' || !acc.launchers || Array.isArray(acc.launchers)) throw Error('Missing launchers');
    for (const [skill, def] of Object.entries(acc.launchers)) {
      if (!SKILLS.has(skill) || !def || typeof def !== 'object') throw Error('Unsupported skill launcher');
      requireAbsolute('launcher command', def.executable);
      if (!fs.existsSync(def.executable)) throw Error('Launcher executable does not exist: ' + def.executable);
      if (!Array.isArray(def.args) || def.args.some(arg => typeof arg !== 'string' || arg.length > 1000)) {
        throw Error('Launcher args must be static strings');
      }
      if (def.cwd) requireAbsolute('launcher cwd', def.cwd);
    }
  }
  return data;
}

function getGoal(acc) {
  const file = path.join(acc.swarmDir, 'goals', acc.id + '.json');
  try {
    const value = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!value || typeof value.id !== 'string' ||
        !SKILLS.has(value.skill) || !['bank', 'power_drop'].includes(value.mode) ||
        !Number.isInteger(value.durationMinutes) || value.durationMinutes < 5 || value.durationMinutes > 180 ||
        (value.targetLevel != null && (!Number.isInteger(value.targetLevel) || value.targetLevel < 1 || value.targetLevel > 99))) return null;
    return value;
  } catch { return null; }
}

function writeGoalForSimba(acc, goal) {
  // Simple, strict line format so Lape need not depend on uncertain JSON parser APIs.
  const file = path.join(acc.swarmDir, 'goals', acc.id + '.goal');
  const contents = [goal.id, goal.skill, goal.mode, String(goal.durationMinutes), String(goal.targetLevel || 0)].join('|') + '\n';
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = file + '.temp';
  fs.writeFileSync(temp, contents, 'utf8');
  fs.renameSync(temp, file);
}
function writeSupervisorState(acc, info) {
  const file = path.join(acc.swarmDir, 'status', acc.id + '.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temp = file + '.temp';
  fs.writeFileSync(temp, JSON.stringify({ ...info, updatedAt: new Date().toISOString() }, null, 2));
  fs.renameSync(temp, file);
}
function createSupervisor(config, options = {}) {
  const spawnProcess = options.spawnProcess || spawn;
  const clock = options.now || Date.now;
  const log = options.log || console.log;
  const controllers = new Map();
  let stopping = false;

  for (const acc of config.accounts) controllers.set(acc.id, {
    acc, process: null, state: 'idle', goalId: null, restartHistory: [],
    lastExit: null, nextRetryAt: 0, stopAt: 0, launchCount: 0
  });

  function publish(c) {
    writeSupervisorState(c.acc, {
      workerSupervisor: c.state,
      pid: c.process?.pid || null, goalId: c.goalId, skill: c.skill || null,
      lastExit: c.lastExit, launchCount: c.launchCount
    });
  }

  function terminate(c) {
    if (!c.process) return;
    c.state = 'stopping'; c.stopAt = clock() + STOP_GRACE_MS;
    // Prefer graceful Simba mailbox logout, then stop tracked launcher if necessary.
    const commandFile = path.join(c.acc.swarmDir, 'commands', c.acc.id + '.cmd');
    fs.mkdirSync(path.dirname(commandFile), { recursive: true });
    try { fs.writeFileSync(commandFile, 'LOGOUT_STOP\n', { flag: 'wx' }); }
    catch (err) { if (err.code !== 'EEXIST') log('Unable to queue stop for ' + c.acc.id + ': ' + err.message); }
    publish(c);
  }

  function launch(c, goal) {
    const def = c.acc.launchers[goal.skill];
    if (!def || !c.acc.autoLaunch) {
      c.state = 'awaiting-local-launch';
      c.goalId = goal.id; c.skill = goal.skill; publish(c); return;
    }
    // A goal may select a LOCAL preapproved launcher. Remote data NEVER becomes an executable or argument.
    c.restartHistory = c.restartHistory.filter(ts => clock() - ts < RETRY_WINDOW_MS);
    if (c.restartHistory.length >= RETRY_LIMIT) {
      c.state = 'crash-loop-blocked'; publish(c); return;
    }
    const now = clock();
    c.restartHistory.push(now); c.goalId = goal.id; c.skill = goal.skill;
    try {
      const child = spawnProcess(def.executable, def.args, {
        cwd: def.cwd || path.dirname(def.executable),
        stdio: 'ignore', shell: false, windowsHide: false,
        env: { ...process.env, WASP_BOT_ID: c.acc.id, SWARM_DIR: c.acc.swarmDir }
      });
      c.process = child;
      c.state = 'launch-requested'; c.launchCount++;
      child.once('spawn', () => { if (c.process === child && c.state === 'launch-requested') { c.state = 'process-running'; publish(c); } });
      child.once('error', err => { if (c.process !== child) return; c.lastExit = 'spawn error: ' + err.message; c.process = null; c.state = 'failed'; publish(c); });
      child.once('exit', (code, signal) => {
        if (c.process !== child) return;
        c.lastExit = 'exit code ' + code + (signal ? ', signal ' + signal : '');
        c.process = null;
        if (c.state === 'stopping' || stopping) { c.state = 'stopped'; }
        else { c.state = 'exited'; c.nextRetryAt = clock() + 30000; }
        publish(c);
      });
      publish(c);
      log('Started configured launcher for ' + c.acc.id + ': ' + goal.skill);
    } catch (err) {
      c.process = null; c.state = 'failed'; c.lastExit = err.message; publish(c);
    }
  }

  function tick() {
    for (const c of controllers.values()) {
      const supervisorDir = path.join(c.acc.swarmDir, 'supervisor');
      const stopFlag = path.join(supervisorDir, c.acc.id + '.stop');
      const startFlag = path.join(supervisorDir, c.acc.id + '.start');
      if (fs.existsSync(startFlag)) {
        fs.unlinkSync(startFlag);
        try { fs.unlinkSync(stopFlag); } catch (err) { if (err.code !== 'ENOENT') throw err; }
        // Explicit START re-arms an account previously stopped by its operator.
        c.goalId = null;
        c.restartHistory = [];
        c.nextRetryAt = 0;
      }
      if (fs.existsSync(stopFlag)) {
        if (c.process && c.state !== 'stopping') terminate(c);
        if (!c.process && c.state !== 'manual-stop') { c.state = 'manual-stop'; publish(c); }
        continue;
      }
      if (c.state === 'stopping' && c.process && clock() >= c.stopAt) {
        try { c.process.kill('SIGTERM'); } catch {}
        c.stopAt = clock() + 60000; // do not spin on repeated kill
        publish(c);
      }
      const goal = getGoal(c.acc);
      if (goal) writeGoalForSimba(c.acc, goal);
      if (stopping || !goal) continue;

      // Do not hijack an unrelated local game, or claim a GUI launcher means logged-in.
      if (c.process && goal.id !== c.goalId && c.state !== 'stopping') {
        terminate(c);
        continue;
      }
      if (c.process || c.state === 'stopping') continue;
      if (c.goalId !== goal.id) {
        c.restartHistory = []; c.nextRetryAt = 0; launch(c, goal);
      } else if (c.state === 'exited' && c.acc.restartOnFailure === true && clock() >= c.nextRetryAt) {
        launch(c, goal);
      }
    }
  }
  function stop() {
    stopping = true;
    for (const c of controllers.values()) {
      if (c.process) terminate(c);
      else { c.state = 'stopped'; publish(c); }
    }
  }
  return { tick, stop, controllers, getGoal: id => getGoal(controllers.get(id).acc) };
}

if (require.main === module) {
  const file = process.env.WASP_SUPERVISOR_CONFIG || path.resolve('worker-host.json');
  const config = readConfig(file);
  const supervisor = createSupervisor(config);
  supervisor.tick();
  const timer = setInterval(() => supervisor.tick(), SCAN_INTERVAL_MS);
  function shutdown() { clearInterval(timer); supervisor.stop(); }
  process.once('SIGINT', shutdown); process.once('SIGTERM', shutdown);
}
module.exports = { readConfig, getGoal, writeGoalForSimba, createSupervisor };

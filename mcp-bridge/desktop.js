'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawn} = require('node:child_process');
const {performance} = require('node:perf_hooks');
const hash = s => crypto.createHash('sha256').update(s).digest('hex');
const allowed = /\.(simba|pas|inc|md)$/i;

function createDesktop({libRoot, draftRoot, simbaExe, allowCompiler = false, allowProbe = false,
  runTimeoutMs = 20000} = {}) {
  libRoot = path.resolve(libRoot);
  draftRoot = path.resolve(draftRoot);
  simbaExe = path.resolve(simbaExe);
  let child = null;
  let closed = false;
  const known = new Map();
  const inside = (root, file) => {
    const rel = path.relative(root, file);
    return rel !== '' && !path.isAbsolute(rel) && rel !== '..' && !rel.startsWith('..' + path.sep);
  };
  async function sourceFile(rel) {
    if (typeof rel !== 'string' || rel.length > 300 || !rel || /[\\:\x00]/.test(rel) ||
        path.isAbsolute(rel) || rel.split('/').some(x => x === '..' || x === '.')) throw new Error('Invalid relative path');
    const root = await fs.realpath(libRoot);
    const file = await fs.realpath(path.join(root, rel));
    if (!inside(root, file) || !allowed.test(file)) throw new Error('Outside permitted source tree');
    const stat = await fs.stat(file);
    if (!stat.isFile() || stat.size > 250000) throw new Error('Source file too large or not a regular file');
    return file;
  }
  async function read(rel) {
    const source = await fs.readFile(await sourceFile(rel), 'utf8');
    return {path: rel, sha256: hash(source), source};
  }
  async function find(query, signal) {
    if (typeof query !== 'string' || query.length < 2 || query.length > 80) throw new Error('query must contain 2..80 characters');
    const root = await fs.realpath(libRoot);
    const todo = [{dir: root, depth: 0}];
    const matches = [];
    let filesExamined = 0, bytesRead = 0, entriesExamined = 0;
    outer: while (todo.length) {
      const {dir, depth} = todo.pop();
      for (const ent of await fs.readdir(dir, {withFileTypes: true})) {
        signal?.throwIfAborted();
        if (++entriesExamined > 5000 || filesExamined >= 1500 || bytesRead >= 8000000 || matches.length >= 30) break outer;
        if (ent.isSymbolicLink() || ent.name.startsWith('.')) continue;
        const file = path.join(dir, ent.name);
        if (ent.isDirectory() && depth < 8) { todo.push({dir: file, depth: depth + 1}); continue; }
        if (!ent.isFile() || !allowed.test(file)) continue;
        filesExamined++;
        const stat = await fs.stat(file);
        if (stat.size > 250000 || bytesRead + stat.size > 8000000) continue;
        const source = await fs.readFile(file, 'utf8');
        bytesRead += Buffer.byteLength(source);
        const lines = source.split(/\r?\n/);
        for (let i = 0; i < lines.length && matches.length < 30; i++) {
          if (lines[i].toLowerCase().includes(query.toLowerCase())) matches.push({
            path: path.relative(root, file).split(path.sep).join('/'), line: i + 1, text: lines[i].trim().slice(0, 220)});
        }
      }
    }
    return {matches, filesExamined, bytesRead, truncated: !!todo.length || matches.length === 30 || entriesExamined > 5000 || filesExamined >= 1500};
  }
  async function stage(name, source) {
    if (typeof name !== 'string' || !/^[A-Za-z0-9_-]{1,60}$/.test(name) ||
        typeof source !== 'string' || !source.trim() || Buffer.byteLength(source) > 200000) throw new Error('Invalid draft name or source');
    await fs.mkdir(draftRoot, {recursive: true, mode: 0o700});
    const sha256 = hash(source), filename = `${name}-${sha256}.simba`;
    const file = path.join(draftRoot, filename);
    try { await fs.writeFile(file, source, {flag: 'wx', mode: 0o600}); }
    catch (e) { if (e.code !== 'EEXIST') throw e; }
    await verifyDraft(filename);
    return {filename, sha256, staged: true, executed: false};
  }
  async function verifyDraft(filename) {
    if (typeof filename !== 'string' || !/^[A-Za-z0-9_-]{1,60}-[a-f0-9]{64}\.simba$/.test(filename)) throw new Error('Invalid staged filename');
    const file = path.join(draftRoot, filename);
    const stat = await fs.lstat(file);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 200000) throw new Error('Draft is not an allowed regular file');
    if (hash(await fs.readFile(file)) !== filename.slice(-70, -6)) throw new Error('Draft contents changed after staging');
    return file;
  }
  async function run(args, signal) {
    if (closed || child) throw new Error(closed ? 'Desktop adapter closed' : 'Another Simba operation is running');
    signal?.throwIfAborted();
    await fs.access(simbaExe);
    // Recheck after asynchronous access to prevent concurrent spawning.
    if (closed || child) throw new Error('Desktop adapter busy or closed');
    signal?.throwIfAborted();
    return new Promise((resolve, reject) => {
      let stdout = '', stderr = '', failure = null, settled = false, escalation;
      const proc = spawn(simbaExe, args, {cwd: path.dirname(simbaExe), windowsHide: true,
        shell: false, stdio: ['ignore', 'pipe', 'pipe']});
      child = proc;
      const stop = reason => {
        failure = reason;
        proc.kill();
        escalation ??= setTimeout(() => proc.kill('SIGKILL'), 1000);
        escalation.unref();
      };
      const abort = () => stop('Simba operation cancelled');
      const timer = setTimeout(() => stop('Simba operation timed out'), runTimeoutMs);
      function done(err, code, sig) {
        if (settled) return;
        settled = true;
        clearTimeout(timer); clearTimeout(escalation);
        signal?.removeEventListener('abort', abort);
        if (child === proc) child = null;
        if (err || failure) reject(err || new Error(failure));
        else resolve({exitCode: code, signal: sig, stdout, stderr});
      }
      proc.stdout.on('data', data => { stdout = (stdout + data.toString()).slice(-32000); });
      proc.stderr.on('data', data => { stderr = (stderr + data.toString()).slice(-32000); });
      proc.on('error', err => done(err));
      proc.on('close', (code, sig) => done(null, code, sig));
      signal?.addEventListener('abort', abort, {once: true});
      if (signal?.aborted) abort();
    });
  }
  async function compile(filename, signal) {
    if (!allowCompiler) throw new Error('Compiler disabled; enable WASP_ALLOW_COMPILER locally');
    const file = await verifyDraft(filename);
    const result = await run(['--compile', file], signal);
    // Simba can exit zero on missing-file/compile-error paths. Require its actual marker,
    // including the upstream spelling "Succesfully", rather than trusting exit code alone.
    const output = (result.stdout + '\n' + result.stderr).replace(/\x1b\[[0-9;]*m/g, '');
    const compiled = result.exitCode === 0 && /^Succes(?:s)?fully compiled in [0-9.]+ milliseconds\.\s*$/im.test(output);
    return {...result, filename, compiled, executed: false};
  }
  async function clients(signal) {
    if (!allowProbe) throw new Error('Desktop probe disabled; enable WASP_ALLOW_DESKTOP_PROBE locally');
    const finder = await sourceFile('tools/agents/find_client.simba');
    const result = await run(['--run', finder], signal);
    if (result.exitCode !== 0) throw new Error('WaspLib discovery process failed');
    const resultClients = [];
    for (const line of result.stdout.split(/\r?\n/)) {
      const parts = line.split('\t');
      if (parts.length < 4 || !/^(?:0x)?[0-9a-fA-F]{1,18}$/.test(parts[0]) || !/^\d+$/.test(parts[2])) continue;
      const pid = Number(parts[2]);
      if (!Number.isSafeInteger(pid) || pid <= 0) continue;
      resultClients.push({handle: parts[0], client: parts[1].slice(0, 80), pid, title: parts.slice(3).join('\t').slice(0, 180)});
    }
    known.clear();
    for (const item of resultClients) known.set(item.handle, {item, at: performance.now()});
    return {clients: resultClients, diagnostics: resultClients.length ? '' : result.stdout.slice(0, 500)};
  }
  async function probe(handle, signal) {
    const previous = known.get(handle);
    if (!previous || performance.now() - previous.at > 30000) throw new Error('Select a freshly discovered client first');
    await clients(signal);
    const current = known.get(handle);
    if (!current || JSON.stringify(current.item) !== JSON.stringify(previous.item)) throw new Error('Client identity changed; select again');
    const expected = await fs.realpath(path.join(path.dirname(simbaExe), 'Includes', 'WaspLib'));
    if (expected !== await fs.realpath(libRoot)) throw new Error('Probe requires WaspLib from the configured Simba installation');
    const staged = await stage('mcp-readonly-probe', '{$I WaspLib/tools/agents/probe.simba}\nbegin\n' +
      "  WriteLn('MCP_LOGGED_IN=' + ToStr(Client.IsLoggedIn()));\n" +
      "  if Client.IsLoggedIn() then WriteLn('MCP_INVENTORY_COUNT=' + ToStr(Inventory.Slots.Count()));\nend.\n");
    const result = await run(['--target=' + handle, '--run', await verifyDraft(staged.filename)], signal);
    if (result.exitCode !== 0 || !/^MCP_LOGGED_IN=(?:True|False)\s*$/im.test(result.stdout)) throw new Error('Read-only probe did not return its success marker');
    return {...result, handle, readOnly: true};
  }
  function close() { closed = true; child?.kill('SIGKILL'); known.clear(); }
  return {read, find, stage, compile, clients, probe, close};
}
module.exports = {createDesktop};

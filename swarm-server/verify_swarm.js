/**
 * Simba Swarm Comprehensive End-to-End Verification Suite
 *
 * Programmatically validates:
 * 1. Simba 2.0 Pascal scripts compilation (all 5 scripts compile with code 0).
 * 2. Swarm HTTP server lifecycle on Port 7777.
 * 3. HTTP 200 and schema validity for all static assets, APK distribution, and REST routes:
 *    - GET /
 *    - GET /manifest.json
 *    - GET /sw.js
 *    - GET /WaspMobile.apk
 *    - GET /api/status
 *    - GET /api/bots
 *    - GET /api/schedule
 *    - GET /api/rsn/generate
 * 4. Strict RSN invariant: 150+ generated & mutated names are <= 12 characters.
 * 5. Dynamic SVG screenshot fallback rendering when bot is offline.
 * 6. Isolated profile provisioning (-Duser.home=C:\Simba\Swarm\profiles\<botId>).
 * 7. SOCKS5 proxy kill-switch: blocks launch with 502 on unreachable proxy port.
 * 8. Remote command dispatch: writes commands/<botId>.cmd.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { execSync, spawn } = require('child_process');

const PORT = 7777;
const BASE_URL = `http://localhost:${PORT}`;
const SWARM_ROOT = path.resolve('C:\\Simba\\Swarm');
const PUBLIC_DIR = path.join(SWARM_ROOT, 'public');
const COMMANDS_DIR = path.join(SWARM_ROOT, 'commands');
const PROFILES_DIR = path.join(SWARM_ROOT, 'profiles');

let testsPassed = 0;
let testsFailed = 0;

function assert(condition, message) {
  if (!condition) {
    console.error(`  [FAIL] ${message}`);
    testsFailed++;
    throw new Error(`Assertion Failed: ${message}`);
  } else {
    console.log(`  [PASS] ${message}`);
    testsPassed++;
  }
}

function httpGet(pathUrl) {
  return new Promise((resolve, reject) => {
    http.get(`${BASE_URL}${pathUrl}`, (res) => {
      let body = '';
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => {
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body
        });
      });
    }).on('error', reject);
  });
}

function httpDownload(pathUrl) {
  return new Promise((resolve, reject) => {
    http.get(`${BASE_URL}${pathUrl}`, (res) => {
      let byteCount = 0;
      res.on('data', chunk => { byteCount += chunk.length; });
      res.on('end', () => {
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          byteCount
        });
      });
    }).on('error', reject);
  });
}

function httpPost(pathUrl, data) {
  return new Promise((resolve, reject) => {
    const postData = JSON.stringify(data || {});
    const req = http.request(`${BASE_URL}${pathUrl}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(postData)
      }
    }, (res) => {
      let body = '';
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => {
        resolve({
          statusCode: res.statusCode,
          headers: res.headers,
          body
        });
      });
    });
    req.on('error', reject);
    req.write(postData);
    req.end();
  });
}

async function runVerification() {
  console.log('================================================================');
  console.log(' SIMBA SWARM COMMAND CENTER — AUTOMATED VERIFICATION SUITE');
  console.log('================================================================\n');

  // --------------------------------------------------------------------------
  // Step 1: Verify Simba 2.0 Pascal Scripts Compilation
  // --------------------------------------------------------------------------
  console.log('--> Step 1: Verifying Simba 2.0 Script Compilation...');
  const scripts = [
    'Scripts/human_core.simba',
    'Scripts/tutorial_island_human.simba',
    'Scripts/dynamic_woodcutter_rotator.simba',
    'Scripts/dynamic_miner_rotator.simba',
    'Scripts/master_account_director.simba'
  ];

  for (const script of scripts) {
    try {
      execSync(`C:\\Simba\\Simba.exe --compile ${script}`, {
        cwd: 'C:\\Simba',
        stdio: 'pipe',
        timeout: 20000
      });
      assert(true, `Compiled cleanly with ExitCode 0: ${script}`);
    } catch (err) {
      assert(false, `Compilation failed for ${script}: ${err.message}`);
    }
  }

  // --------------------------------------------------------------------------
  // Step 2: Start server.js in background if not already running
  // --------------------------------------------------------------------------
  console.log('\n--> Step 2: Verifying Server Port 7777 Readiness...');
  let serverProcess = null;
  let serverAlreadyRunning = false;

  try {
    const checkRes = await httpGet('/api/status');
    if (checkRes.statusCode === 200) {
      serverAlreadyRunning = true;
      console.log('  [INFO] Swarm server is already running on port 7777.');
    }
  } catch (_) {
    console.log('  [INFO] Starting fresh instance of C:\\Simba\\Swarm\\server.js...');
    serverProcess = spawn(process.execPath, [path.join(SWARM_ROOT, 'server.js')], {
      cwd: SWARM_ROOT,
      stdio: 'inherit'
    });

    // Wait for server socket to open
    let ready = false;
    for (let i = 0; i < 20; i++) {
      await new Promise(r => setTimeout(r, 250));
      try {
        const ping = await httpGet('/api/status');
        if (ping.statusCode === 200) {
          ready = true;
          break;
        }
      } catch (_) {}
    }
    assert(ready, 'Server started and responded on port 7777');
  }

  // --------------------------------------------------------------------------
  // Step 3: Validate Static PWA Routes
  // --------------------------------------------------------------------------
  console.log('\n--> Step 3: Verifying Static PWA Asset Delivery...');
  const rootRes = await httpGet('/');
  assert(rootRes.statusCode === 200, 'GET / returned HTTP 200');
  assert(rootRes.headers['content-type'].includes('text/html'), 'GET / Content-Type is text/html');
  assert(rootRes.body.includes('SIMBA SWARM PWA'), 'GET / contains "SIMBA SWARM PWA" branding');
  assert(rootRes.body.includes('manifest.json'), 'GET / references manifest.json');
  assert(rootRes.body.includes('WaspMobile.apk'), 'GET / contains 1-tap WaspMobile.apk download link');

  const manifestRes = await httpGet('/manifest.json');
  assert(manifestRes.statusCode === 200, 'GET /manifest.json returned HTTP 200');
  const manifest = JSON.parse(manifestRes.body);
  assert(manifest.short_name === 'SimbaSwarm', 'Manifest short_name matches "SimbaSwarm"');
  assert(manifest.start_url === '/', 'Manifest start_url is "/"');

  const swRes = await httpGet('/sw.js');
  assert(swRes.statusCode === 200, 'GET /sw.js returned HTTP 200');
  assert(swRes.body.includes('simba-swarm'), 'sw.js contains cache identifier');

  // --------------------------------------------------------------------------
  // Step 3b: Validate WaspMobile APK Distribution Endpoint
  // --------------------------------------------------------------------------
  console.log('\n--> Step 3b: Verifying WaspMobile APK Distribution & MIME Headers...');
  const apkFilePath = path.join(PUBLIC_DIR, 'WaspMobile.apk');
  assert(fs.existsSync(apkFilePath), `WaspMobile.apk exists on disk at ${apkFilePath}`);
  const expectedDiskSize = fs.statSync(apkFilePath).size;
  assert(expectedDiskSize > 10 * 1024 * 1024, `WaspMobile.apk size is valid (${(expectedDiskSize / (1024 * 1024)).toFixed(2)} MB > 10 MB)`);

  const apkRes = await httpDownload('/WaspMobile.apk');
  assert(apkRes.statusCode === 200, 'GET /WaspMobile.apk returned HTTP 200');
  assert(apkRes.headers['content-type'] === 'application/vnd.android.package-archive', 'GET /WaspMobile.apk Content-Type is application/vnd.android.package-archive');
  assert(apkRes.headers['content-disposition'] === 'attachment; filename="WaspMobile.apk"', 'GET /WaspMobile.apk Content-Disposition is attachment; filename="WaspMobile.apk"');
  assert(parseInt(apkRes.headers['content-length'], 10) === expectedDiskSize, `GET /WaspMobile.apk Content-Length (${apkRes.headers['content-length']}) matches disk file size (${expectedDiskSize})`);
  assert(apkRes.byteCount === expectedDiskSize, `GET /WaspMobile.apk streamed payload size (${apkRes.byteCount}) matches disk file size (${expectedDiskSize})`);

  // --------------------------------------------------------------------------
  // Step 4: Validate Hardware Status & Circadian Schedule APIs
  // --------------------------------------------------------------------------
  console.log('\n--> Step 4: Verifying Host Hardware Status & Circadian Schedule...');
  const statusRes = await httpGet('/api/status');
  assert(statusRes.statusCode === 200, 'GET /api/status returned HTTP 200');
  const statusData = JSON.parse(statusRes.body);
  assert(statusData.hostHardware.cpuModel.includes('i5-11400H'), 'Hardware CPU model identifies i5-11400H');
  assert(statusData.hostHardware.physicalCores === 6, 'Hardware reports 6 physical cores');
  assert(statusData.hostHardware.logicalThreads === 12, 'Hardware reports 12 logical threads');
  assert(statusData.hostHardware.totalRamGB === 32, 'Hardware reports 32 GB RAM');
  assert(statusData.hostHardware.maxConcurrentHeadedBots === 6, 'Concurrent headed bot limit is 6');

  const schedRes = await httpGet('/api/schedule');
  assert(schedRes.statusCode === 200, 'GET /api/schedule returned HTTP 200');
  const schedData = JSON.parse(schedRes.body);
  assert(Array.isArray(schedData.shifts) && schedData.shifts.length === 4, 'Schedule defines exactly 4 Circadian shifts');
  assert(schedData.currentShiftIndex >= 1 && schedData.currentShiftIndex <= 4, 'Current shift index is valid (1-4)');
  assert(schedData.shifts.every(s => s.concurrentLimit === 6), 'Every shift caps concurrent clients at 6');

  // --------------------------------------------------------------------------
  // Step 5: Validate Bots & Telemetry Listing
  // --------------------------------------------------------------------------
  console.log('\n--> Step 5: Verifying Bot Profiles & Telemetry Pipeline...');
  const botsRes = await httpGet('/api/bots');
  assert(botsRes.statusCode === 200, 'GET /api/bots returned HTTP 200');
  const botsData = JSON.parse(botsRes.body);
  assert(Array.isArray(botsData.bots) && botsData.bots.length > 0, 'Bots list returned registered bot profiles');
  const sampleBot = botsData.bots[0];
  assert(sampleBot.botId && sampleBot.rsn, `Bot has valid identity: ${sampleBot.botId} (${sampleBot.rsn})`);
  assert(typeof sampleBot.invCount === 'number', 'Bot telemetry reports numeric invCount');

  // --------------------------------------------------------------------------
  // Step 6: Validate Dynamic SVG Screenshot Fallback
  // --------------------------------------------------------------------------
  console.log('\n--> Step 6: Verifying Dynamic SVG Screenshot Fallback...');
  const screenRes = await httpGet('/api/screenshot/nonexistent_test_bot_99');
  assert(screenRes.statusCode === 200, 'GET /api/screenshot/nonexistent_test_bot_99 returned HTTP 200');
  assert(screenRes.headers['content-type'].includes('image/svg+xml'), 'Offline bot screenshot returns image/svg+xml');
  assert(screenRes.body.includes('<svg') && screenRes.body.includes('SIMBA SWARM TELEMETRY'), 'Generated SVG contains HUD telemetry graphics');

  // --------------------------------------------------------------------------
  // Step 7: Strict RSN Generator & Mutator Invariant (< 12 Chars)
  // --------------------------------------------------------------------------
  console.log('\n--> Step 7: Verifying 150+ Human RSNs & Taken-Name Mutations...');
  // Test clean generation
  const rsnRes1 = await httpGet('/api/rsn/generate?count=80&mutate=false');
  assert(rsnRes1.statusCode === 200, 'GET /api/rsn/generate?count=80 returned HTTP 200');
  const rsnData1 = JSON.parse(rsnRes1.body);
  assert(rsnData1.count === 80, 'Generated exactly 80 clean RSNs');
  assert(rsnData1.allValidLength === true, 'Server verifies all clean RSNs <= 12 characters');

  // Test mutated generation
  const rsnRes2 = await httpGet('/api/rsn/generate?count=80&mutate=true');
  assert(rsnRes2.statusCode === 200, 'GET /api/rsn/generate?count=80&mutate=true returned HTTP 200');
  const rsnData2 = JSON.parse(rsnRes2.body);
  assert(rsnData2.count === 80, 'Generated exactly 80 mutated RSNs');

  const allNames = [...rsnData1.names, ...rsnData2.names];
  assert(allNames.length === 160, 'Combined sample pool contains 160 RSNs');

  let maxLenObserved = 0;
  let illegalCharsFound = 0;
  for (const item of allNames) {
    assert(item.rsn.length >= 1 && item.rsn.length <= 12, `RSN "${item.rsn}" length ${item.rsn.length} is strictly <= 12 chars`);
    if (item.rsn.length > maxLenObserved) maxLenObserved = item.rsn.length;
    // OSRS valid display name charset: a-z, A-Z, 0-9, spaces, underscores, hyphens
    if (!/^[a-zA-Z0-9_\- ]+$/.test(item.rsn)) {
      illegalCharsFound++;
    }
  }
  assert(maxLenObserved <= 12, `Max observed length across 160 names is ${maxLenObserved} <= 12`);
  assert(illegalCharsFound === 0, 'Zero illegal characters found across all generated & mutated RSNs');

  // --------------------------------------------------------------------------
  // Step 8: Bot Provisioning & Isolated Home Directory (-Duser.home)
  // --------------------------------------------------------------------------
  console.log('\n--> Step 8: Verifying Bot Profile Provisioning & Profile Isolation...');
  const testBotId = 'bot_verify_test_42';
  const createRes = await httpPost('/api/bots/create', {
    botId: testBotId,
    rsn: 'FlintRaven',
    script: 'DynamicWoodcutter',
    proxyHost: '127.0.0.1',
    proxyPort: 59999, // Dead port for kill-switch test
    shift: 2,
    role: 'Verification Test Bot'
  });
  assert(createRes.statusCode === 200, 'POST /api/bots/create returned HTTP 200');
  const isolatedDir = path.join(PROFILES_DIR, testBotId);
  assert(fs.existsSync(isolatedDir), `Isolated profile directory created at ${isolatedDir}`);
  assert(fs.existsSync(path.join(isolatedDir, 'profile.json')), 'profile.json exists inside isolated directory');
  assert(fs.existsSync(path.join(isolatedDir, '.runelite')), '.runelite directory exists in isolated home directory');

  // --------------------------------------------------------------------------
  // Step 9: SOCKS5 Proxy Kill-Switch Block
  // --------------------------------------------------------------------------
  console.log('\n--> Step 9: Verifying Pre-Launch SOCKS5 Proxy Kill-Switch...');
  const launchFailRes = await httpPost(`/api/bots/${testBotId}/launch`, {});
  assert(launchFailRes.statusCode === 502, `Unreachable proxy (port 59999) returned HTTP 502 Bad Gateway (got ${launchFailRes.statusCode})`);
  const launchFailData = JSON.parse(launchFailRes.body);
  assert(launchFailData.killSwitchEngaged === true, 'Response confirms killSwitchEngaged === true');
  assert(launchFailData.error.includes('Proxy reachability check failed'), 'Error explains proxy failure to protect residential IP');

  // --------------------------------------------------------------------------
  // Step 10: Remote Command Dispatch & Stop Verification
  // --------------------------------------------------------------------------
  console.log('\n--> Step 10: Verifying Remote Command Pipeline & Bot Stop...');
  const cmdRes = await httpPost('/api/command', {
    botId: testBotId,
    command: 'HOP'
  });
  assert(cmdRes.statusCode === 200, 'POST /api/command returned HTTP 200');
  const cmdFile = path.join(COMMANDS_DIR, `${testBotId}.cmd`);
  assert(fs.existsSync(cmdFile), `Command file created at ${cmdFile}`);
  const cmdContent = fs.readFileSync(cmdFile, 'utf-8').trim();
  assert(cmdContent === 'HOP', `Command file content matches "HOP" (got "${cmdContent}")`);

  const stopRes = await httpPost(`/api/bots/${testBotId}/stop`, {});
  assert(stopRes.statusCode === 200, 'POST /api/bots/:botId/stop returned HTTP 200');
  const stopContent = fs.readFileSync(cmdFile, 'utf-8').trim();
  assert(stopContent === 'LOGOUT_STOP', `Stop command updated file to "LOGOUT_STOP" (got "${stopContent}")`);

  // Cleanup test artifacts
  try {
    if (fs.existsSync(cmdFile)) fs.unlinkSync(cmdFile);
    if (fs.existsSync(isolatedDir)) fs.rmSync(isolatedDir, { recursive: true, force: true });
  } catch (_) {}

  // Clean up server process if we launched it
  if (serverProcess) {
    serverProcess.kill();
  }

  console.log('\n================================================================');
  console.log(` VERIFICATION SUMMARY: ${testsPassed} PASSED, ${testsFailed} FAILED`);
  console.log('================================================================');

  if (testsFailed > 0) {
    process.exit(1);
  } else {
    process.exit(0);
  }
}

runVerification().catch(err => {
  console.error('\nVerification suite crashed:', err);
  process.exit(1);
});

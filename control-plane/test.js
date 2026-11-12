'use strict';
// Run: node control-plane/test.js — local only, no game clients required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wasp-control-test-'));
process.env.CONTROL_ADMIN_TOKEN = 'admin-' + '0'.repeat(60);
process.env.CONTROL_MAX_CONNECTED_WORKERS = '2';
process.env.CONTROL_DATA_DIR = dir;
process.env.CONTROL_WORKER_TOKENS = '{}';
const { createServer } = require('./server');

async function main() {
  const server = createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  let tests = 0;
  const tokens = new Map();

  async function req(method, route, body, kind = 'admin', account = '') {
    const headers = {};
    if (kind === 'admin') headers['X-Admin-Token'] = process.env.CONTROL_ADMIN_TOKEN;
    if (kind === 'worker') { headers['X-Worker-Token'] = tokens.get(account); headers['X-Account-ID'] = account; }
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const res = await fetch(base + route, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: res.status, data: await res.json() };
  }

  try {
    const health = await req('GET', '/healthz', undefined, 'anonymous');
    assert.equal(health.status, 200); tests++;
    assert.equal((await req('GET', '/api/accounts', undefined, 'anonymous')).status, 401); tests++;
    assert.equal((await req('GET', '/api/accounts', undefined, 'worker', 'invalid')).status, 401); tests++;
    assert.equal((await req('POST', '/api/accounts', { id: '../evil' })).status, 400); tests++;

    for (let i=0; i<6; i++) {
      const id = 'account-' + i;
      const response = await req('POST', '/api/accounts', { id, label: 'Test account ' + i });
      assert.equal(response.status, 201); tests++;
      assert.equal(response.data.account.id, id); tests++;
      assert.match(response.data.workerToken, /^[a-f0-9]{64}$/); tests++;
      tokens.set(id, response.data.workerToken);
    }
    const listing = await req('GET', '/api/accounts');
    assert.equal(listing.data.registeredCount, 6); tests++;
    assert.equal(listing.data.maxConnectedWorkers, 2); tests++;
    assert.equal(JSON.stringify(listing.data).includes(tokens.get('account-0')), false); tests++;
    assert.equal(JSON.stringify(listing.data).includes('workerTokenHash'), false); tests++;
    assert.equal((await req('POST', '/api/accounts', {id:'account-0'})).status, 409); tests++;
    assert.equal((await req('POST', '/api/accounts', null)).status, 400); tests++;
    const oldToken = tokens.get('account-5');
    const rotated = await req('POST','/api/accounts/account-5/rotate-token',{});
    assert.equal(rotated.status,200); tests++;
    assert.notEqual(rotated.data.workerToken,oldToken); tests++;
    assert.equal((await req('GET','/api/worker/next',undefined,'worker','account-5')).status,401); tests++;
    tokens.set('account-5',rotated.data.workerToken);
    assert.equal((await req('GET','/api/worker/next',undefined,'worker','account-5')).status,200); tests++;

    const goal = {skill:'woodcutting',mode:'bank',durationMinutes:35,targetLevel:22};
    assert.equal((await req('POST','/api/accounts/account-0/goal',goal)).status,200); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/goal',{...goal,durationMinutes:999999})).status,400); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/proposal',{skill:'mining',mode:'power_drop',durationMinutes:90})).status,201); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/approve',{})).status,200); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/command',{command:'EXEC rm -rf /'})).status,400); tests++;

    const auto = await req('POST','/api/accounts/account-0/autopilot',
      {enabled:true,skills:['woodcutting','mining'],mode:'bank',sessionMinutes:35,targetLevel:70});
    assert.equal(auto.status,200); tests++;
    assert.equal(auto.data.goal.skill,'woodcutting'); tests++;
    const firstAutoId = auto.data.goal.id;
    const invalidAuto = await req('POST','/api/accounts/account-0/autopilot',
      {enabled:true,skills:['random_skill'],mode:'bank',sessionMinutes:35});
    assert.equal(invalidAuto.status,400); tests++;
    for(let i=0;i<2;i++) {
      const id='account-'+i;
      const hb=await req('POST','/api/worker/heartbeat',{telemetry:{rsn:'Name'+i,loggedIn:true,script:'woodcutting',invCount:17,
        state:i===0?firstAutoId+'|BLOCK_WOODCUT_AND_FLETCH -> MAX_TIME':'GATHER'}},'worker',id);
      if(i===0){ assert.equal(hb.data.goal.skill,'mining'); tests++; }
      assert.equal(hb.status,200); tests++;
    }
    const afterRotation = await req('GET','/api/accounts');
    const secondGoalId = afterRotation.data.accounts[0].goal.id;
    assert.notEqual(secondGoalId,firstAutoId); tests++;
    const completedTarget = await req('POST','/api/worker/heartbeat',
      {telemetry:{rsn:'Name0',loggedIn:true,script:'mining',invCount:17,state:secondGoalId+'|BLOCK_MINING_AND_GEMS -> MAX_LEVEL'}},
      'worker','account-0');
    assert.equal(completedTarget.status,200); tests++;
    assert.equal((await req('GET','/api/accounts')).data.accounts[0].autopilot.enabled,false); tests++;
    assert.equal((await req('DELETE','/api/accounts/account-0',{})).status,409); tests++;
    const third=await req('POST','/api/worker/heartbeat',{telemetry:{loggedIn:true}},'worker','account-2');
    assert.equal(third.status,429); tests++;
    const status=await req('GET','/api/accounts');
    assert.equal(status.data.accounts[0].gameTelemetryFresh,true); tests++;
    assert.equal(status.data.accounts[2].workerConnected,false); tests++;

    const command=await req('POST','/api/accounts/account-0/command',{command:'PAUSE'});
    assert.equal(command.status,202); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/command',{command:'HOP'})).status,409); tests++;
    const pending=await req('GET','/api/worker/next',undefined,'worker','account-0');
    assert.equal(pending.data.command.id,command.data.command.id); tests++;
    assert.equal((await req('POST','/api/worker/ack',{id:command.data.command.id,delivered:true},'worker','account-0')).status,200); tests++;
    assert.equal((await req('GET','/api/worker/next',undefined,'worker','account-0')).data.command,null); tests++;

    const written=JSON.parse(fs.readFileSync(path.join(dir,'state.json'),'utf8'));
    assert.equal(written.accounts['account-0'].workerTokenHash.length,64); tests++;
    assert.equal(JSON.stringify(written).includes(tokens.get('account-0')),false); tests++;

    const html=await fetch(base+'/');
    assert.equal(html.status,200); tests++;
    assert.match(await html.text(),/\+ Add account/); tests++;
    assert.equal((await fetch(base+'/manifest.webmanifest')).status,200); tests++;
    assert.equal((await fetch(base+'/sw.js')).status,200); tests++;
    assert.equal((await fetch(base+'/icon.svg')).status,200); tests++;
    // Integration test: actual outbound worker process consumes fake local Simba telemetry,
    // delivers commands, and stores an approved goal handoff without launching a game.
    const swarm = path.join(dir, 'fake-simba');
    fs.mkdirSync(path.join(swarm, 'telemetry'), { recursive: true });
    fs.writeFileSync(path.join(swarm, 'telemetry', 'account-0.json'), JSON.stringify({
      rsn: 'IntegrationUser', script: 'Woodcutter', state: 'GATHER', loggedIn: true, actions: 7, invCount: 12, runEnergy: 86
    }));
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lZ4AAAAASUVORK5CYII=', 'base64');
    fs.writeFileSync(path.join(swarm, 'telemetry', 'account-0.png'), png);
    const nextCommand = await req('POST','/api/accounts/account-0/command',{command:'HOP'});
    assert.equal(nextCommand.status, 202); tests++;
    const worker = spawn(process.execPath, [path.join(__dirname,'worker.js')], {
      env: { ...process.env, WASP_BOT_ID:'account-0', CONTROL_ACCOUNT_ID:'account-0',
        CONTROL_WORKER_TOKEN: tokens.get('account-0'), CONTROL_URL: base,
        SWARM_DIR: swarm }, stdio:'ignore'
    });
    try {
      let delivered = false;
      for (let i=0;i<45;i++) {
        const commandFile=path.join(swarm,'commands','account-0.cmd');
        const goalFile=path.join(swarm,'goals','account-0.json');
        const online = await req('GET','/api/accounts');
        if (fs.existsSync(commandFile) && fs.existsSync(goalFile)
            && online.data.accounts[0].telemetry?.rsn === 'IntegrationUser') {
          delivered = true; break;
        }
        if (worker.exitCode !== null) break;
        await new Promise(resolve=>setTimeout(resolve,100));
      }
      assert.equal(delivered,true,'Worker should publish fake Simba telemetry, goal, and mailbox command'); tests++;
      assert.equal(fs.readFileSync(path.join(swarm,'commands','account-0.cmd'),'utf8').trim(),'HOP'); tests++;
      assert.equal(JSON.parse(fs.readFileSync(path.join(swarm,'goals','account-0.json'),'utf8')).skill,'mining'); tests++;
      const after = await req('GET','/api/accounts');
      assert.equal(after.data.accounts[0].telemetry?.invCount,12); tests++;
      const screenshotRes=await fetch(base+'/api/accounts/account-0/screenshot',{headers:{'X-Admin-Token':process.env.CONTROL_ADMIN_TOKEN}});
      assert.equal(screenshotRes.status,200); tests++;
      assert.equal(screenshotRes.headers.get('content-type'),'image/png'); tests++;
      // START and STOP carry local supervisor flags without remote shell execution.
      const starting = await req('POST','/api/accounts/account-0/command',{command:'START'});
      assert.equal(starting.status,202); tests++;
      let seenStart = false;
      for(let i=0;i<90;i++){
        if(fs.existsSync(path.join(swarm,'supervisor','account-0.start'))){seenStart=true;break;}
        await new Promise(resolve=>setTimeout(resolve,100));
      }
      assert.equal(seenStart,true); tests++;
      const latest=await req('GET','/api/accounts');
      assert.equal(latest.data.accounts[0].command.status,'delivered'); tests++;
      fs.unlinkSync(path.join(swarm,'commands','account-0.cmd')); // Simulate real Simba consumption.
      const stopping = await req('POST','/api/accounts/account-0/command',{command:'LOGOUT_STOP'});
      assert.equal(stopping.status,202); tests++;
      let seenStop = false;
      for(let i=0;i<90;i++){
        if(fs.existsSync(path.join(swarm,'supervisor','account-0.stop'))){seenStop=true;break;}
        await new Promise(resolve=>setTimeout(resolve,100));
      }
      assert.equal(seenStop,true); tests++;

    } finally { worker.kill('SIGTERM'); }

    assert.equal((await req('DELETE','/api/accounts/account-5',{})).status,200); tests++;
    assert.equal((await req('GET','/api/accounts')).data.registeredCount,5); tests++;
    assert.equal((await req('GET','/api/worker/next',undefined,'worker','account-5')).status,401); tests++;
    console.log('PASS: '+tests+' checks including bounded autopilot rotation, worker start/stop flags, six provisioned accounts and two connected workers.');
  } finally {
    await new Promise(resolve=>server.close(resolve));
    fs.rmSync(dir,{recursive:true,force:true});
  }
}
main().catch(error=>{console.error('FAIL:',error.message);process.exitCode=1});

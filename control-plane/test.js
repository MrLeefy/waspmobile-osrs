'use strict';
// Run: node control-plane/test.js — local only, no game clients required.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

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

    const goal = {skill:'woodcutting',mode:'bank',durationMinutes:35,targetLevel:22};
    assert.equal((await req('POST','/api/accounts/account-0/goal',goal)).status,200); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/goal',{...goal,durationMinutes:999999})).status,400); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/proposal',{skill:'mining',mode:'power_drop',durationMinutes:90})).status,201); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/approve',{})).status,200); tests++;
    assert.equal((await req('POST','/api/accounts/account-0/command',{command:'EXEC rm -rf /'})).status,400); tests++;

    for(let i=0;i<2;i++) {
      const id='account-'+i;
      const hb=await req('POST','/api/worker/heartbeat',{telemetry:{rsn:'Name'+i,loggedIn:true,script:'woodcutting',invCount:17}},'worker',id);
      assert.equal(hb.status,200); tests++;
    }
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
    console.log('PASS: '+tests+' checks, six registered accounts, two admitted workers, isolated credentials and command queue.');
  } finally {
    await new Promise(resolve=>server.close(resolve));
    fs.rmSync(dir,{recursive:true,force:true});
  }
}
main().catch(error=>{console.error('FAIL:',error.message);process.exitCode=1});

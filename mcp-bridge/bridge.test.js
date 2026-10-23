'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'wasp-mcp-'));
const lib = path.join(temp, 'WaspLib');
const drafts = path.join(temp, 'drafts');
fs.mkdirSync(lib);
fs.writeFileSync(path.join(lib,'main.simba'), 'function IsLoggedIn(): Boolean;\nbegin\n Result := True;\nend;\n');
process.env.WASP_DEVICE_TOKEN = 'test-only-secret-01234567890123456789';
process.env.WASP_LIB_ROOT = lib;
process.env.WASP_DRAFT_ROOT = drafts;
process.env.WASP_SIMBA_EXE = path.join(temp,'missing-simba.exe');

const bridge = require('./bridge');
const AUTH = {authorization:'Bearer '+process.env.WASP_DEVICE_TOKEN,'content-type':'application/json'};
let base;

async function post(endpoint,payload,headers=AUTH){
  const r=await fetch(base+endpoint,{method:'POST',headers,body:JSON.stringify(payload)});
  return {status:r.status,json:await r.json()};
}

test.before(async ()=>{
  await new Promise((resolve,reject)=>{
    bridge.server.once('error',reject);
    bridge.server.listen(0,'127.0.0.1',resolve);
  });
  base='http://127.0.0.1:'+bridge.server.address().port;
});

test.after(async ()=>{
  await new Promise(resolve=>bridge.server.close(resolve));
  fs.rmSync(temp,{force:true,recursive:true});
});

test('unauthenticated and malformed requests cannot poll',async()=>{
  const unauthorized=await post('/device/poll',{deviceId:'phone01'},{'content-type':'application/json'});
  assert.equal(unauthorized.status,401);
  const invalid=await post('/device/poll',{deviceId:'../invalid'});
  assert.equal(invalid.status,400);
});

test('phone is initially offline; no commands can be sent',async()=>{
  const r=await bridge.handleTool('wasp_tap',{deviceId:'phone01',x:0.5,y:0.5});
  assert.equal(r.isError,true);
  assert.match(r.content[0].text,/offline/);
});

test('the phone must opt in and confirm OSRS foreground',async()=>{
  await post('/device/poll',{deviceId:'phone01',remoteEnabled:false,foreground:'com.jagex.oldscape.android'});
  let r=await bridge.handleTool('wasp_tap',{deviceId:'phone01',x:0.5,y:0.5});
  assert.match(r.content[0].text,/disabled/);

  await post('/device/poll',{deviceId:'phone01',remoteEnabled:true,foreground:'com.example.settings'});
  r=await bridge.handleTool('wasp_tap',{deviceId:'phone01',x:0.5,y:0.5});
  assert.match(r.content[0].text,/foreground/);
});

test('remote screenshots are delivered through one authenticated bounded job',async()=>{
  await post('/device/poll',{
    deviceId:'phone01',remoteEnabled:true,foreground:'com.jagex.oldscape.android',
    width:1024,height:472,status:'READY'
  });
  const promised=bridge.handleTool('wasp_observe',{deviceId:'phone01'});
  const polled=await post('/device/poll',{deviceId:'phone01',remoteEnabled:true,
    foreground:'com.jagex.oldscape.android',width:1024,height:472});
  assert.equal(polled.status,200);
  const cmd=polled.json.command;
  assert.equal(cmd.type,'OBSERVE');
  const fakeJpeg=Buffer.from([0xff,0xd8,0xff,0xd9]).toString('base64');
  const returned=await post('/device/result',{
    deviceId:'phone01',id:cmd.id,ok:true,status:'CAPTURED',frame:fakeJpeg
  });
  assert.equal(returned.status,200);
  const r=await promised;
  assert.equal(r.content[1].mimeType,'image/jpeg');
  assert.equal(r.content[1].data,fakeJpeg);

  const replay=await post('/device/result',{deviceId:'phone01',id:cmd.id,ok:true});
  assert.equal(replay.status,409);
});

test('bad coordinates cannot enqueue a gesture',async()=>{
  const result=await bridge.handleTool('wasp_tap',{deviceId:'phone01',x:NaN,y:0.5});
  assert.equal(result.isError,true);
  const result2=await bridge.handleTool('wasp_tap',{deviceId:'phone01',x:1.5,y:0.5});
  assert.equal(result2.isError,true);
});

test('only one command per device can be in flight',async()=>{
  const first=bridge.requestDevice('phone01','PAUSE',{});
  const second=await bridge.handleTool('wasp_tap',{deviceId:'phone01',x:0.5,y:0.5});
  assert.match(second.content[0].text,/in-flight/);
  const poll=await post('/device/poll',{deviceId:'phone01',remoteEnabled:true,
    foreground:'com.jagex.oldscape.android'});
  await post('/device/result',{deviceId:'phone01',id:poll.json.command.id,ok:true});
  assert.equal((await first).ok,true);
});

test('source lookup and draft staging never execute scripts',async()=>{
  const find=await bridge.handleTool('wasp_source_find',{query:'IsLoggedIn'});
  const matches=JSON.parse(find.content[0].text).matches;
  assert.equal(matches[0].path,'main.simba');

  const denied=await bridge.handleTool('wasp_source_read',{path:'../secrets.txt'});
  assert.equal(denied.isError,true);

  const staged=await bridge.handleTool('wasp_stage_script',{
    name:'audit-test',source:'begin\n Writeln(1);\nend.'
  });
  const result=JSON.parse(staged.content[0].text);
  assert.equal(result.executed,false);
  assert.ok(fs.existsSync(result.path));

  const compiled=await bridge.handleTool('wasp_compile_script',{filename:path.basename(result.path)});
  assert.equal(compiled.isError,true);
  assert.match(compiled.content[0].text,/not found/);
});

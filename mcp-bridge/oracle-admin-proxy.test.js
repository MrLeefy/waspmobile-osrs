'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {
 ALLOWED,safeServerToken,parseResult,validateTool,mcpCall,preserveImage
}=require('./oracle-admin-proxy');

const TOKEN='some-unit-test-token-012345678901234567890';

test('all 15 exact WaspMobile MCP tools are allowed and unknown tools fail',()=>{
 assert.equal(ALLOWED.size,15);
 for(const n of ['osrs_devices','run_shell','fetch_url','wasp_forbidden','wasp_script_execute'])
   assert.throws(()=>validateTool(n,{}),/Unknown or unauthorized/);
});
test('MCP credentials are read internally and never accepted through tool args',()=>{
 assert.equal(safeServerToken('WASP_DEVICE_TOKEN=other\nWASP_MCP_LOCAL_TOKEN='+TOKEN),TOKEN);
 assert.throws(()=>safeServerToken('WASP_DEVICE_TOKEN='+TOKEN),/not configured/);
});
test('read-only tool accepts an empty payload, reply gets a unique messageId',()=>{
 assert.deepEqual(validateTool('wasp_devices',{}),{});
 const r=validateTool('wasp_reply',{deviceId:'one-phone',replyTo:'request-id',text:'Hi'});
 assert.ok(/^[a-f0-9-]{36}$/.test(r.messageId));
 assert.equal(r.text,'Hi');
});
test('MCP JSON and SSE both require a matching authenticated request id',()=>{
 const id=crypto.randomUUID();
 const record={jsonrpc:'2.0',id,result:{content:[{type:'text',text:'[]'}]}};
 assert.deepEqual(parseResult(JSON.stringify(record),'application/json',id),record.result);
 const sse='event: message\ndata: '+JSON.stringify(record)+'\n\n';
 assert.deepEqual(parseResult(sse,'text/event-stream',id),record.result);
 assert.throws(()=>parseResult(sse,'text/event-stream','other-id'),/matching request ID/);
});
test('an HTTP MCP request sends the private bearer token only to localhost',async()=>{
 let request;
 const body={jsonrpc:'2.0',id:'set later'};
 const fakeFetch=async(url,opts)=>{
  request={url,opts};
  assert.ok(url.startsWith('http://127.0.0.1:7790/'));
  assert.equal(opts.headers.Authorization,'Bearer '+TOKEN);
  const parsed=JSON.parse(opts.body);
  assert.equal(parsed.method,'tools/call');
  assert.equal(parsed.params.name,'wasp_devices');
  return {ok:true,status:200,headers:{get:n=>n==='content-type'?'application/json':null},
    text:async()=>JSON.stringify({jsonrpc:'2.0',id:parsed.id,result:{content:[{type:'text',text:'[]'}]}})};
 };
 const result=await mcpCall('wasp_devices',{},{
  httpFetch:fakeFetch,readConfig:async()=>('WASP_MCP_LOCAL_TOKEN='+TOKEN)
 });
 assert.deepEqual(result.content[0].text,'[]');
 assert.ok(!JSON.stringify(result).includes(TOKEN));
});
test('MCP failures are surfaced without retrying input commands',async()=>{
 let calls=0;
 await assert.rejects(mcpCall('wasp_tap',{deviceId:'one',frameId:crypto.randomUUID(),x:0.5,y:0.5},{
   readConfig:async()=>('WASP_MCP_LOCAL_TOKEN='+TOKEN),
   httpFetch:async()=>{calls++;return {ok:false,status:401}}
 }),/HTTP 401/);
 assert.equal(calls,1);
});
test('image frames are written outside stdout with metadata only',async t=>{
 const fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path');
 const folder=await fs.mkdtemp(path.join(os.tmpdir(),'wasp-proxy-img-'));
 t.after(()=>fs.rm(folder,{force:true,recursive:true}));
 const fake=Buffer.from([0xff,0xd8,0xff,0xe0,0,1,0,0,0,0,0xff,0xd9]);
 const input={content:[
  {type:'text',text:JSON.stringify({frameId:crypto.randomUUID(),message:'screenshot captured'})},
  {type:'image',mimeType:'image/jpeg',data:fake.toString('base64')}
 ]};
 const result=await preserveImage('wasp_observe',input,{saveDir:folder});
 assert.equal(result.ok,true);
 assert.equal(result.image.bytes,fake.length);
 assert.equal(result.data.message,'screenshot captured');
 assert.ok(result.image.path.startsWith(folder));
 assert.deepEqual(await fs.readFile(result.image.path),fake);
 assert.ok(!JSON.stringify(result).includes(fake.toString('base64')));
});

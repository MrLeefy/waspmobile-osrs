'use strict';

const test=require('node:test');
const assert=require('node:assert/strict');
const {Client,StreamableHTTPClientTransport}=require('@modelcontextprotocol/client');
const {spawn}=require('node:child_process');
const {GAME_PACKAGE}=require('./relay');
const z=require('zod/v4');
const {createOracleDaemon}=require('./oracle-daemon');
const fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const TOKEN='vm-unit-phone-token-012345678901234567890123';
const LOCAL='vm-unit-mcp-token-01234567890123456789012345';

async function fixture(t,{events=false}={}){
 const dir=await fs.mkdtemp(path.join(os.tmpdir(),'osrs-vm-'));
 const config={token:TOKEN,port:0,libRoot:path.join(dir,'empty-lib'),draftRoot:path.join(dir,'drafts'),
   simbaExe:path.join(dir,'missing-simba'),maxDevices:1,eventsEnabled:events,
   eventsStorageKey:events?Buffer.alloc(32,13).toString('base64'):undefined,
   eventsStorageFile:path.join(dir,'events.enc'),
   eventsAllowedHosts:['openai.com']};
 await fs.mkdir(config.libRoot);
 const daemon=createOracleDaemon({config,mcpHttpPort:0,mcpLocalToken:LOCAL});
 const addr=await daemon.start();
 t.after(async()=>{await daemon.close();await fs.rm(dir,{recursive:true,force:true});});
 const base='http://127.0.0.1:'+addr.mcpPort;
 return {daemon,addr,base};
}
async function mcpClient(f,t,modern=false) {
 const transport=new StreamableHTTPClientTransport(new URL(f.base+'/mcp'),{
   requestInit:{headers:{Authorization:'Bearer '+LOCAL}}
 });
 const opts=modern?{versionNegotiation:{mode:{pin:'2026-07-28'}}}:{};
 const client=new Client({name:'test-oracle-http',version:'1.0.0'},opts);
 await client.connect(transport);
 t.after(()=>client.close());
 return client;
}

test('local daemon has two separate loopback listener ports and health checks',async t=>{
 const f=await fixture(t);
 assert.notEqual(f.addr.phonePort,f.addr.mcpPort);
 assert.equal(f.daemon.server.address().address,'127.0.0.1');
 assert.equal(f.daemon.bridge.relay.server.address().address,'127.0.0.1');
 const health=await fetch(f.base+'/healthz');
 assert.equal(health.status,200);
 assert.deepEqual((await health.json()).phoneRelay,true);
 assert.equal((await fetch(f.base+'/device/poll')).status,404);
});
test('HTTP MCP requires its own independent bearer token',async t=>{
 const f=await fixture(t);
 const invalid=await fetch(f.base+'/mcp',{method:'POST',headers:{'content-type':'application/json'},body:'{}'});
 assert.equal(invalid.status,401);
 const wrong=await fetch(f.base+'/mcp',{method:'POST',headers:{'Authorization':'Bearer '+TOKEN},body:'{}'});
 assert.equal(wrong.status,401);
});
test('browser origins and foreign methods rejected before MCP dispatch',async t=>{
 const f=await fixture(t);
 const headers={'Authorization':'Bearer '+LOCAL};
 assert.equal((await fetch(f.base+'/mcp',{method:'POST',headers:{...headers,Origin:'https://evil.example.com'}})).status,403);
 assert.equal((await fetch(f.base+'/mcp',{method:'PUT',headers})).status,405);
});
test('HTTP MCP basic legacy initialization and tools work',async t=>{
 const f=await fixture(t), c=await mcpClient(f,t);
 assert.equal((await c.listTools()).tools.length,15);
 const result=await c.callTool({name:'wasp_devices',arguments:{}});
 assert.deepEqual(JSON.parse(result.content[0].text),[]);
});
test('HTTP MCP 2.0 modern discovery, tools and events work',async t=>{
 const f=await fixture(t), c=await mcpClient(f,t,true);
 const discovery=await c.request({method:'server/discover',params:{}},v=>v);
 assert.equal(c.getProtocolEra(),'modern');
 assert.equal((await c.listTools()).tools.length,15);
 assert.equal(!!discovery.capabilities.tools,true);
});
test('different service ports avoid collisions with existing Oracle services',()=>{
 assert.throws(()=>createOracleDaemon({mcpLocalToken:'short',bridge:{}}),/WASP_MCP_LOCAL_TOKEN/);
});

test('Modern Oracle MCP advertises optional Work/dot events on its private endpoint',async t=>{
 const f=await fixture(t,{events:true});
 const c=await mcpClient(f,t,true);
 const result=await c.request({method:'events/list',params:{}},z.unknown());
 assert.equal(result.events.length,1);
 assert.equal(result.events[0].name,'overlay.message.created');
 const discover=await c.request({method:'server/discover',params:{}},z.unknown());
 assert.equal(typeof discover.capabilities.events,'object');
});
test('One phone can poll the persistent relay and becomes visible to the MCP client',async t=>{
 const f=await fixture(t);
 const response=await fetch('http://127.0.0.1:'+f.addr.phonePort+'/device/poll',{
   method:'POST',headers:{Authorization:'Bearer '+TOKEN,'Content-Type':'application/json'},
   body:JSON.stringify({deviceId:'one-s23',sessionId:'phone-session',remoteEnabled:true,
     foreground:GAME_PACKAGE,windowKey:'test-foreground-osrs',width:1000,height:500,inputAllowed:false})
 });
 assert.equal(response.status,200);
 const c=await mcpClient(f,t);
 const v=await c.callTool({name:'wasp_devices',arguments:{}});
 const devices=JSON.parse(v.content[0].text);
 assert.equal(devices.length,1);
 assert.equal(devices[0].id,'one-s23');
 assert.equal(devices[0].inputAllowed,false);
});
test('Actual Oracle headless process survives closed stdin and shuts down on SIGTERM', {timeout:12000},async t=>{
 const command=spawn(process.execPath,[path.join(__dirname,'oracle-daemon.js')],{
   cwd:__dirname,env:{...process.env,WASP_DEVICE_TOKEN:TOKEN,WASP_MCP_LOCAL_TOKEN:LOCAL,
     WASP_BRIDGE_PORT:'0',WASP_MCP_HTTP_PORT:'0',WASP_EVENTS_ENABLED:'false'},
   stdio:['pipe','ignore','pipe']});
 t.after(()=>{if(command.exitCode===null)command.kill('SIGKILL')});
 let ready=false;
 const connected=new Promise((resolve,reject)=>{
   command.stderr.on('data',chunk=>{
     if(chunk.toString().includes('WaspMobile Oracle bridge ready')) {ready=true;resolve()}
   });
   command.once('exit',code=>reject(new Error('Oracle daemon exited before ready: '+code)));
   command.once('error',reject);
 });
 command.stdin.end();
 await connected;
 assert.equal(ready,true);
 assert.equal(command.exitCode,null);
 const stopped=new Promise((resolve,reject)=>{
   command.once('exit',resolve);command.once('error',reject);
 });
 command.kill('SIGTERM');
 assert.equal(await stopped,0);
});

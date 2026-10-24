'use strict';

const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const {Client}=require('@modelcontextprotocol/client');
const {StdioClientTransport}=require('@modelcontextprotocol/client/stdio');
const z=require('zod/v4');
const TOKEN='stdio-modern-event-test-012345678901234567890';
const KEY=Buffer.alloc(32,47).toString('base64');

test('actual stdio bridge negotiates MCP 2.0 with event subscriptions and all 15 tools', {timeout:9000},async t=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'waspmobile-modern-'));
  t.after(()=>fs.rm(dir,{recursive:true,force:true}));
  const transport=new StdioClientTransport({command:process.execPath,args:[path.join(__dirname,'bridge.js')],
    env:{...process.env,WASP_DEVICE_TOKEN:TOKEN,WASP_BRIDGE_PORT:'0',
      WASP_EVENTS_ENABLED:'true',WASP_EVENTS_STORAGE_KEY:KEY,
      WASP_EVENTS_STORAGE_FILE:path.join(dir,'subscriptions.enc')},stderr:'pipe'});
  const client=new Client({name:'osrs-events-modern-test',version:'1.0.0'},
    {versionNegotiation:{mode:{pin:'2026-07-28'}}});
  t.after(()=>client.close());
  await client.connect(transport);
  assert.equal(client.getProtocolEra(),'modern');
  const discovery = await client.request({method: "server/discover",params:{}},z.unknown());
  assert.deepEqual(discovery.capabilities.events,{});
  assert.equal((await client.listTools()).tools.length,15);
  const events=await client.request({method:'events/list',params:{}},z.unknown());
  assert.equal(events.events.length,1);
  assert.equal(events.events[0].name,'overlay.message.created');
  assert.deepEqual(events.events[0].delivery,['webhook']);
  const devices=await client.callTool({name:'wasp_devices',arguments:{}});
  assert.deepEqual(JSON.parse(devices.content[0].text),[]);
});

test('modern subscribe rejects unpaired device before contacting callback URL', {timeout:9000},async t=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'waspmobile-subscribe-'));
  t.after(()=>fs.rm(dir,{recursive:true,force:true}));
  const transport=new StdioClientTransport({command:process.execPath,args:[path.join(__dirname,'bridge.js')],
    env:{...process.env,WASP_DEVICE_TOKEN:TOKEN,WASP_BRIDGE_PORT:'0',
      WASP_EVENTS_ENABLED:'true',WASP_EVENTS_STORAGE_KEY:KEY,
      WASP_EVENTS_STORAGE_FILE:path.join(dir,'subscriptions.enc')},stderr:'pipe'});
  const client=new Client({name:'osrs-events-subscribe-test',version:'1.0.0'},
    {versionNegotiation:{mode:{pin:'2026-07-28'}}});
  t.after(()=>client.close());
  await client.connect(transport);
  await assert.rejects(client.request({method:'events/subscribe',params:{
    name:'overlay.message.created',arguments:{device_id:'unpaired-phone'},
    delivery:{mode:'webhook',url:'https://hooks.work.openai.com/private',
      secret:'whsec_'+Buffer.alloc(32,21).toString('base64')},
    cursor:null
  }},z.unknown()),/Pair or pin/);
});

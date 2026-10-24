'use strict';

const {test}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const fs=require('node:fs/promises');
const path=require('node:path');
const {Client}=require('@modelcontextprotocol/client');
const {StdioClientTransport}=require('@modelcontextprotocol/client/stdio');
const z=require('zod/v4');
const {parseToken,normalTools,createGateway,EVENT}=require('./oracle-admin-events-gateway');

const TEST_TOKEN='gateway-test-mcp-local-token-01234567890123456789';
const basicSchema={type:'object',properties:{},additionalProperties:false};

test('validates private credentials and prevents accidental token printing',()=>{
  assert.equal(parseToken('WASP_MCP_LOCAL_TOKEN='+TEST_TOKEN+'\n'),TEST_TOKEN);
  assert.throws(()=>parseToken('WASP_DEVICE_TOKEN='+TEST_TOKEN+'\n'),/missing/);
  assert.throws(()=>parseToken('WASP_MCP_LOCAL_TOKEN=123\n'),/missing/);
});

test('rejects duplicate or unexpected native MCP tool schemas',()=>{
  assert.deepEqual(normalTools({tools:[{name:'wasp_devices',inputSchema:basicSchema}]}).map(t=>t.name),['wasp_devices']);
  assert.throws(()=>normalTools({tools:[{name:'x',inputSchema:basicSchema},{name:'x',inputSchema:basicSchema}]}),/duplicate/);
  assert.throws(()=>normalTools({tools:[{name:'wasp_devices',inputSchema:'unsafe'}]}),/Invalid/);
});

test('preserves native tool metadata and raw payloads for both backends', async()=>{
  const adminTools=[
    {name:'server_info',description:'unchanged admin information',annotations:{readOnlyHint:true},inputSchema:basicSchema},
    {name:'execute',description:'unchanged dangerous shell',annotations:{readOnlyHint:false},inputSchema:basicSchema},
    {name:'read_file',description:'unchanged read file',annotations:{readOnlyHint:true},inputSchema:basicSchema}
  ];
  const nativeWasp=[{name:'wasp_devices',description:'native single S23',annotations:{readOnlyHint:true},inputSchema:basicSchema}];
  const forwarded=[];
  const admin={
    listTools:async()=>({tools:adminTools}),
    callTool:async(p)=>{forwarded.push({backend:'admin',params:p});return {content:[{type:'text',text:'admin reply'}]};}
  };
  const wasp={
    listTools:async()=>({tools:nativeWasp}),
    callTool:async(p)=>{forwarded.push({backend:'osrs',params:p});return {content:[{type:'text',text:'osrs reply'}]};},
    request:async(p)=>{forwarded.push({backend:'event',params:p});return {events:[{name:EVENT}]};}
  };
  const gateway=await createGateway({adminClient:admin,waspRequest:callback=>callback(wasp)});
  assert.deepEqual(gateway.adminTools,adminTools.map(x=>x.name));
  assert.deepEqual(gateway.waspTools,['wasp_devices']);
  const [remote,local]=require('@modelcontextprotocol/client').InMemoryTransport.createLinkedPair();
  const server=gateway.makeServer();
  await server.connect(local);
  const client=new Client({name:'local-gateway-test',version:'1.0.0'});
  await client.connect(remote);
  try{
    const names=(await client.listTools()).tools;
    assert.deepEqual(names,adminTools.concat(nativeWasp));
    assert.equal((await client.callTool({name:'server_info',arguments:{}})).content[0].text,'admin reply');
    assert.equal((await client.callTool({name:'wasp_devices',arguments:{}})).content[0].text,'osrs reply');
    assert.deepEqual(forwarded.map(x=>x.backend),['admin','osrs']);
  } finally {await client.close();await server.close();await gateway.close();}
});

test('actual legacy stdio gateway keeps the original nine root-admin tools', {timeout:10000},async t=>{
  const orig=new StdioClientTransport({command:'/opt/oracle-chat-mcp/.venv/bin/python',args:['/opt/oracle-chat-mcp/server.py'],stderr:'pipe'});
  const gw=new StdioClientTransport({command:process.execPath,args:[path.join(__dirname,'oracle-admin-events-gateway.js')],stderr:'pipe'});
  const a=new Client({name:'original-tools-reference',version:'1'});
  const b=new Client({name:'gateway-compat-reference',version:'1'});
  t.after(async()=>{await Promise.all([a.close().catch(()=>{}),b.close().catch(()=>{})]);});
  await a.connect(orig);await b.connect(gw);
  const old=(await a.listTools()).tools;
  const updated=(await b.listTools()).tools;
  assert.equal(old.length,9);
  assert.equal(updated.length,24);
  for(const tool of old) {
    const found=updated.find(x=>x.name===tool.name);
    assert.deepEqual(found,tool,'Native Oracle Admin schema/annotations must remain unchanged');
  }
  const result=await b.callTool({name:'server_info',arguments:{}});
  assert.notEqual(result.isError,true);
  assert.match(result.content[0].text,/root_access/);
  const d=await b.callTool({name:'wasp_devices',arguments:{}});
  assert.notEqual(d.isError,true);
});

test('actual modern MCP 2.0 stdio connection exposes WaspMobile event methods', {timeout:10000},async t=>{
  const transport=new StdioClientTransport({command:process.execPath,args:[path.join(__dirname,'oracle-admin-events-gateway.js')],stderr:'pipe'});
  const client=new Client({name:'modern-wasp-events-reference',version:'1'},
    {versionNegotiation:{mode:{pin:'2026-07-28'}}});
  t.after(()=>client.close().catch(()=>{}));
  await client.connect(transport);
  assert.equal(client.getProtocolEra(),'modern');
  const catalog=await client.request({method:'events/list',params:{}},z.unknown());
  assert.equal(catalog.events[0].name,EVENT);
  const discovery=await client.request({method:'server/discover',params:{}},z.unknown());
  assert.deepEqual(discovery.capabilities.events,{});
  const modernTools=(await client.listTools()).tools;
  assert.equal(modernTools.length,17);
  assert.ok(modernTools.some(t=>t.name==='server_info'));
  assert.ok(modernTools.some(t=>t.name==='check'));
  assert.equal(modernTools.some(t=>t.name==='execute'),false);
  assert.equal(modernTools.some(t=>t.name==='read_file'),false);
  assert.equal(modernTools.some(t=>t.name==='brain_write'),false);
  await assert.rejects(client.callTool({name:'execute',arguments:{command:'printf should_not_execute'}}),/Privileged Oracle Admin actions/);
  await assert.rejects(client.request({method:'events/subscribe',params:{
    name:EVENT,arguments:{device_id:'not-paired'},
    delivery:{mode:'webhook',url:'https://events.openai.com/cb/test',
      secret:'whsec_'+Buffer.alloc(32,32).toString('base64')},cursor:null
  }},z.unknown()));
});

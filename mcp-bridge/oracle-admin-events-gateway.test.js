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

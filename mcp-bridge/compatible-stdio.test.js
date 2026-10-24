'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {PassThrough} = require('node:stream');
const {Server, ProtocolError} = require('@modelcontextprotocol/server');
const z = require('zod/v4');
const {serveCompatibleStdio, requestVersion, LEGACY, MODERN} = require('./compatible-stdio');

test('explicit legacy protocol wins over prior modern discovery', () => {
  assert.equal(requestVersion({method:'tools/call',params:{_meta:{'io.modelcontextprotocol/protocolVersion':LEGACY}}},MODERN), LEGACY);
  assert.equal(requestVersion({method:'events/list'},LEGACY), MODERN);
});
test('mixed MCP2 discovery then old ChatGPT tool call succeeds on SAME stdio connection', async t => {
  const input = new PassThrough(), output = new PassThrough();
  let calls = 0, raw = '';
  output.on('data', chunk => { raw += chunk; });
  const adapter = serveCompatibleStdio(async ({era}) => {
    const server = new Server({name:'osrs-recovery-test',version:'1'}, {
      supportedProtocolVersions:[LEGACY,MODERN], capabilities:{tools:{},events:{}}
    });
    server.setRequestHandler('tools/list',{params:z.object({}).passthrough()}, async()=>({tools:[]}));
    server.setRequestHandler('tools/call',{params:z.object({name:z.string(),arguments:z.object({}).passthrough().optional()}).passthrough()}, async params=>{
      if (params.name === 'execute' && era === 'modern') throw new ProtocolError(-32602,'Privileged operation blocked in event session');
      calls++; return {content:[{type:'text',text:'ok'}]};
    });
    server.setRequestHandler('events/list',{params:z.object({}).passthrough()}, async()=>({events:[]}));
    return server;
  },{input,output});
  t.after(()=>adapter.close());
  await adapter.dispatch({jsonrpc:'2.0',id:1,method:'server/discover',params:{}});
  await adapter.dispatch({jsonrpc:'2.0',id:2,method:'tools/call',params:{name:'wasp_devices',arguments:{},_meta:{'io.modelcontextprotocol/protocolVersion':LEGACY}}});
  await adapter.dispatch({jsonrpc:'2.0',id:3,method:'events/list',params:{}});
  await adapter.dispatch({jsonrpc:'2.0',id:4,method:'tools/call',params:{name:'execute',arguments:{},_meta:{'io.modelcontextprotocol/protocolVersion':MODERN}}});
  await adapter.dispatch({jsonrpc:'2.0',id:5,method:'tools/call',params:{name:'execute',arguments:{},_meta:{'io.modelcontextprotocol/protocolVersion':LEGACY}}});
  const responses = raw.trim().split('\n').map(JSON.parse);
  assert.equal(responses.find(x=>x.id===2)?.result?.content[0].text,'ok',JSON.stringify(responses));
  assert.deepEqual(responses.find(x=>x.id===3)?.result?.events,[]);
  assert.match(responses.find(x=>x.id===4)?.error?.message,/Privileged/);
  assert.match(responses.find(x=>x.id===5)?.error?.message,/Privileged/,'An explicit old protocol cannot authorize root actions after event discovery');
  assert.equal(calls,1,'No input should be repeated while handling protocol mismatch');
});

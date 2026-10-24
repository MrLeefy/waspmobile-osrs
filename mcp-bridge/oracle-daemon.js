'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const {Readable} = require('node:stream');
const {pipeline} = require('node:stream/promises');
const {createMcpHandler} = require('@modelcontextprotocol/server');
const {createBridge,envConfig} = require('./bridge');

const validSecret = value => typeof value === 'string' && /^[A-Za-z0-9_-]{32,128}$/.test(value);
const smallJson = (res,status,object)=>{
  const data=JSON.stringify(object);
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store',
    'X-Content-Type-Options':'nosniff','Content-Length':Buffer.byteLength(data)});
  res.end(data);
};

function createOracleDaemon({config=envConfig(),mcpHttpPort=7790,mcpLocalToken,
  bridge=createBridge(config),maxActive=8}={}) {
  if(!validSecret(mcpLocalToken)) throw new Error('WASP_MCP_LOCAL_TOKEN must be 32–128 URL-safe characters');
  if(!Number.isInteger(mcpHttpPort)||mcpHttpPort<0||mcpHttpPort>65535)
    throw new Error('Invalid MCP HTTP port');
  if(config.port && mcpHttpPort===config.port)throw new Error('MCP HTTP and phone relay ports must differ');
  const expected=Buffer.from(mcpLocalToken);
  let started=false,stopping=false,active=0;
  const handler=createMcpHandler(async()=>bridge.makeMcp(),{legacy:'stateless',responseMode:'auto',maxRequestBodySize:1024*1024});
  const inFlight=new Set();
  const authorized=req=>{
    const v=req.headers.authorization;
    if(typeof v!=='string'||!v.startsWith('Bearer ')||v.length>165)return false;
    const supplied=Buffer.from(v.slice(7));
    return supplied.length===expected.length&&crypto.timingSafeEqual(supplied,expected);
  };
  const loopback=req=>{
    const ip=req.socket.remoteAddress;
    return ip==='127.0.0.1'||ip==='::1'||ip==='::ffff:127.0.0.1';
  };
  const server=http.createServer(async(req,res)=>{
    if(stopping)return smallJson(res,503,{error:'Shutting down'});
    if(!loopback(req))return smallJson(res,403,{error:'Loopback only'});
    if(req.url==='/healthz' && req.method==='GET'){
      return smallJson(res,200,{status:started?'ready':'starting',
        mcpHttp:true,phoneRelay:bridge.relay.server.listening,eventsEnabled:config.eventsEnabled===true});
    }
    if(req.url!=='/mcp')return smallJson(res,404,{error:'Not found'});
    if(!authorized(req))return smallJson(res,401,{error:'Unauthorized'});
    if(req.headers.origin)return smallJson(res,403,{error:'Browser origins rejected'});
    if(!['POST','GET','DELETE'].includes(req.method))return smallJson(res,405,{error:'Method not allowed'});
    if(active>=maxActive)return smallJson(res,429,{error:'Too many MCP requests'});
    active++;
    const aborted=new AbortController();
    const closeOnAbort=()=>{if(!res.writableEnded)aborted.abort();};
    res.once('close',closeOnAbort);
    try {
      let length=0;
      const chunks=[];
      if(req.method==='POST'){
        for await(const chunk of req){
          length+=chunk.length;
          if(length>1024*1024)throw Object.assign(new Error('MCP request body too large'),{status:413});
          chunks.push(chunk);
        }
      }
      const buffer=Buffer.concat(chunks);
      const origin='http://127.0.0.1:'+mcpHttpPort;
      const headers=new Headers();
      for(const [name,value] of Object.entries(req.headers)){
        if(value!==undefined&&!['connection','host','transfer-encoding','content-length'].includes(name)){
          headers.set(name,Array.isArray(value)?value.join(','):value);
        }
      }
      headers.set('host','127.0.0.1:'+mcpHttpPort);
      const request=new Request(origin+'/mcp',{method:req.method,headers,
        ...(req.method==='POST'?{body:buffer}:{}) , signal:aborted.signal});
      const response=await handler.fetch(request);
      if(res.writableEnded||res.destroyed)return;
      const outHeaders={};
      response.headers.forEach((value,key)=>{
        if(!['connection','transfer-encoding','content-length','set-cookie'].includes(key))outHeaders[key]=value;
      });
      outHeaders['Cache-Control']='no-store';
      outHeaders['X-Content-Type-Options']='nosniff';
      res.writeHead(response.status,outHeaders);
      if(response.body)await pipeline(Readable.fromWeb(response.body),res).catch(()=>{
        if(!res.destroyed)res.destroy();
      });
      else res.end();
    }catch(error){
      if(!res.headersSent&&!res.destroyed)smallJson(res,error.status===413?413:500,
        {error:error.status===413?'Request too large':'MCP request failed'});
      else if(!res.destroyed)res.destroy();
    }finally {
      res.removeListener('close',closeOnAbort);
      aborted.abort();
      active--;
    }
  });
  server.requestTimeout=20000;
  server.headersTimeout=20000;
  server.keepAliveTimeout=1500;
  server.maxHeadersCount=28;
  server.on('clientError',(_,socket)=>socket.destroy());
  async function start(){
    if(started)throw new Error('Oracle daemon already started');
    await bridge.events.load();
    try{
      await new Promise((resolve,reject)=>{
        bridge.relay.server.once('error',reject);
        bridge.relay.server.listen(config.port,'127.0.0.1',resolve);
      });
      await new Promise((resolve,reject)=>{
        server.once('error',reject);
        server.listen(mcpHttpPort,'127.0.0.1',resolve);
      });
      started=true;
    }catch(error){
      await close().catch(()=>{});
      throw error;
    }
    return {phonePort:bridge.relay.server.address().port,mcpPort:server.address().port};
  }
  async function close(){
    if(stopping)return;
    stopping=true;
    if(server.listening){
      const done=new Promise(resolve=>server.close(resolve));
      server.closeAllConnections();
      await done;
    }
    await handler.close();
    await bridge.close();
  }
  return {bridge,server,start,close};
}

async function main(){
  const config=envConfig();
  const port=Number(process.env.WASP_MCP_HTTP_PORT||7790);
  const daemon=createOracleDaemon({config,mcpHttpPort:port,mcpLocalToken:process.env.WASP_MCP_LOCAL_TOKEN});
  let stopping=false;
  const shutdown=async()=>{
    if(stopping)return;
    stopping=true;
    await daemon.close();
    process.exitCode=0;
  };
  process.once('SIGTERM',()=>void shutdown());
  process.once('SIGINT',()=>void shutdown());
  const addr=await daemon.start();
  process.stderr.write('WaspMobile Oracle bridge ready: phone=127.0.0.1:'+addr.phonePort+
    ' mcp=127.0.0.1:'+addr.mcpPort+'\n');
}
if(require.main===module)main().catch(error=>{
  process.stderr.write('WaspMobile Oracle bridge startup failed: '+String(error.message).slice(0,180)+'\n');
  process.exitCode=1;
});
module.exports={createOracleDaemon};

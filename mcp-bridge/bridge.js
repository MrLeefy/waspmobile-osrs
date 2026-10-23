'use strict';

/**
 * WaspMobile MCP-to-Android bridge (dependency-free, stdio MCP + localhost HTTP relay).
 *
 * SECURITY:
 * - Listening only on 127.0.0.1; use Tailscale Serve HTTPS for phone access.
 * - Requires a 32+-character WASP_DEVICE_TOKEN for the Android polling API.
 * - MCP tools are exposed over stdio, not over the phone's HTTP listener.
 * - No arbitrary shell/Simba execution; generated source can only be staged.
 * - At most one queued action per device, bounded TTL, no replay after timeout.
 */

const http = require('node:http');
const readline = require('node:readline');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawn} = require('node:child_process');

const PORT = Number(process.env.WASP_BRIDGE_PORT || 7788);
const DEVICE_TOKEN = process.env.WASP_DEVICE_TOKEN || '';
const LIB_ROOT = path.resolve(process.env.WASP_LIB_ROOT || 'C:\\Simba\\Includes\\WaspLib');
const DRAFT_ROOT = path.resolve(process.env.WASP_DRAFT_ROOT || 'C:\\Simba\\Scripts\\ai-drafts');
const SIMBA_EXE = path.resolve(process.env.WASP_SIMBA_EXE || 'C:\\Simba\\Simba.exe');
const GAME_PACKAGE = 'com.jagex.oldscape.android';
const MAX_FRAME_CHARS = 900000;
const ONLINE_MS = 15000;
const REQUEST_MS = 14000;
const clients = new Map();
const pending = new Map();
const queues = new Map();

function deviceSummary(device) {
  return {
    id: device.id,
    online: Date.now() - device.seenAt < ONLINE_MS,
    foreground: device.foreground,
    remoteEnabled: device.remoteEnabled,
    screenWidth: device.width,
    screenHeight: device.height,
    status: device.status || '',
    lastSeen: new Date(device.seenAt).toISOString(),
    lastFrame: device.lastFrame ? new Date(device.lastFrame.at).toISOString() : null
  };
}

function validId(value) {
  return typeof value === 'string' && /^[a-zA-Z0-9_-]{1,60}$/.test(value);
}

function parsedArgs(args) {
  return args && typeof args === 'object' && !Array.isArray(args) ? args : {};
}

function jsonResult(result) {
  return { content: [{type:'text', text: JSON.stringify(result)}] };
}

function failResult(message) {
  return { isError: true, content: [{type:'text', text:String(message)}] };
}

function reply(res, code, value) {
  const body = JSON.stringify(value);
  res.writeHead(code, {'Content-Type':'application/json; charset=utf-8', 'Cache-Control':'no-store', 'Content-Length':Buffer.byteLength(body)});
  res.end(body);
}

async function bodyJson(req, limit) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > limit) throw new Error('body too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function timingSafeToken(actual) {
  if (typeof actual !== 'string' || !DEVICE_TOKEN) return false;
  const a = Buffer.from(actual);
  const b = Buffer.from(DEVICE_TOKEN);
  return a.length === b.length && crypto.timingSafeEqual(a,b);
}

const server = http.createServer(async (req,res) => {
  if (req.method !== 'POST' || !['/device/poll','/device/result'].includes(req.url)) {
    return reply(res,404,{error:'not found'});
  }
  const authorization = req.headers.authorization || '';
  if (!authorization.startsWith('Bearer ') || !timingSafeToken(authorization.slice(7))) {
    return reply(res,401,{error:'unauthorized'});
  }
  if (!(req.headers['content-type'] || '').startsWith('application/json')) {
    return reply(res,415,{error:'application/json required'});
  }
  let data;
  try { data = await bodyJson(req, req.url === '/device/result' ? 1100000 : 16000); }
  catch (_) { return reply(res,400,{error:'invalid request body'}); }
  if (!data || !validId(data.deviceId)) return reply(res,400,{error:'invalid deviceId'});

  if (req.url === '/device/poll') {
    const old = clients.get(data.deviceId) || {id:data.deviceId};
    const device = Object.assign(old,{
      seenAt:Date.now(),
      remoteEnabled:data.remoteEnabled === true,
      foreground:data.foreground === GAME_PACKAGE,
      width:Number.isSafeInteger(data.width) && data.width > 0 && data.width <= 8192 ? data.width : 0,
      height:Number.isSafeInteger(data.height) && data.height > 0 && data.height <= 8192 ? data.height : 0,
      status:typeof data.status === 'string' ? data.status.slice(0,160) : ''
    });
    clients.set(data.deviceId,device);
    const queue = queues.get(data.deviceId) || [];
    while (queue.length) {
      const item = queue.shift();
      if (!pending.has(item.id) || Date.now() > item.expiresAt) continue;
      if (!device.remoteEnabled || (item.type !== 'PAUSE' && !device.foreground)) {
        const task = pending.get(item.id);
        pending.delete(item.id);
        clearTimeout(task.timer);
        task.resolve({ok:false,error:'Phone disabled remote input or OSRS lost foreground'});
        continue;
      }
      return reply(res,200,{command:item});
    }
    return reply(res,200,{command:null});
  }

  const task = pending.get(data.id);
  if (!task || task.deviceId !== data.deviceId) return reply(res,409,{error:'expired or unknown command'});
  pending.delete(data.id);
  clearTimeout(task.timer);
  const response = {
    ok:data.ok === true,
    status:typeof data.status === 'string' ? data.status.slice(0,160) : '',
    error:typeof data.error === 'string' ? data.error.slice(0,160) : null
  };
  if (task.type === 'OBSERVE' && response.ok && typeof data.frame === 'string') {
    if (data.frame.length <= MAX_FRAME_CHARS &&
        /^[A-Za-z0-9+/]+={0,2}$/.test(data.frame)) {
      const device = clients.get(data.deviceId);
      if (device) device.lastFrame = {data:data.frame,at:Date.now()};
      response.hasFrame = true;
    } else {
      response.ok = false;
      response.error = 'Frame invalid or too large';
    }
  }
  task.resolve(response);
  return reply(res,200,{ok:true});
});

function requestDevice(deviceId,type,params) {
  const device = clients.get(deviceId);
  if (!device || Date.now() - device.seenAt > ONLINE_MS) return Promise.resolve({ok:false,error:'Device offline'});
  if (!device.remoteEnabled) return Promise.resolve({ok:false,error:'Remote control disabled on phone'});
  if (!device.foreground && type !== 'PAUSE') return Promise.resolve({ok:false,error:'OSRS is not confirmed foreground'});
  if (Array.from(pending.values()).some(t=>t.deviceId === deviceId)) {
    return Promise.resolve({ok:false,error:'Device already has an in-flight command'});
  }
  const id = crypto.randomUUID();
  const expiresAt = Date.now() + REQUEST_MS;
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      pending.delete(id);
      const queue = queues.get(deviceId) || [];
      queues.set(deviceId, queue.filter(x=>x.id !== id));
      resolve({ok:false,error:'Command expired; no automatic retry'});
    },REQUEST_MS);
    pending.set(id,{id,deviceId,type,resolve,timer});
    const queue = queues.get(deviceId) || [];
    queue.push({id,type,params,expiresAt});
    queues.set(deviceId,queue);
  });
}

function currentFrame(id) {
  const device = clients.get(id);
  if (!device || !device.lastFrame) return failResult('No captured frame; call wasp_observe first');
  const ageMs = Date.now() - device.lastFrame.at;
  return {content:[
    {type:'text',text:JSON.stringify({device:id,ageMs,warning:ageMs > 10000 ? 'Frame is stale' : null})},
    {type:'image',mimeType:'image/jpeg',data:device.lastFrame.data}
  ]};
}

const TOOLS = [
  {name:'wasp_devices',description:'List authenticated Android device sessions and health.',inputSchema:{type:'object',properties:{}}},
  {name:'wasp_observe',description:'Capture a current screenshot from the phone. Requires OSRS foreground and opt-in remote control.',inputSchema:{type:'object',properties:{deviceId:{type:'string'}},required:['deviceId']}},
  {name:'wasp_last_frame',description:'Read the latest captured screenshot, including its age.',inputSchema:{type:'object',properties:{deviceId:{type:'string'}},required:['deviceId']}},
  {name:'wasp_tap',description:'Tap one normalized display point (0 to 1), with local Android foreground checks; game actions are not guaranteed.',inputSchema:{type:'object',properties:{deviceId:{type:'string'},x:{type:'number',minimum:0.02,maximum:0.98},y:{type:'number',minimum:0.02,maximum:0.98}},required:['deviceId','x','y']}},
  {name:'wasp_pause',description:'Stop local Android automation (does not log out).',inputSchema:{type:'object',properties:{deviceId:{type:'string'}},required:['deviceId']}},
  {name:'wasp_source_find',description:'Search the installed WaspLib .simba source tree, bounded and read-only.',inputSchema:{type:'object',properties:{query:{type:'string',minLength:2,maxLength:80}},required:['query']}},
  {name:'wasp_source_read',description:'Read an installed WaspLib file by safe relative path; output is capped.',inputSchema:{type:'object',properties:{path:{type:'string'}},required:['path']}},
  {name:'wasp_stage_script',description:'Stage Pascal script text under ai-drafts with a content hash. Never compiles or executes it.',inputSchema:{type:'object',properties:{name:{type:'string'},source:{type:'string'}},required:['name','source']}},
  {name:'wasp_compile_script',description:'Compile a previously staged .simba draft using the configured local Simba executable. Never runs gameplay.',inputSchema:{type:'object',properties:{filename:{type:'string'}},required:['filename']}}
];

function sourcePath(rel) {
  if (typeof rel !== 'string' || !rel || path.isAbsolute(rel) || rel.includes('..') || rel.includes(':') || rel.includes('\\')) throw new Error('Invalid relative path');
  const root = fs.realpathSync(LIB_ROOT);
  const file = fs.realpathSync(path.resolve(root,rel));
  if (!file.startsWith(root+path.sep) || !fs.statSync(file).isFile()) throw new Error('Outside WaspLib root');
  if (!/\.(simba|pas|inc|md)$/i.test(file)) throw new Error('Unsupported file type');
  return file;
}

function findSources(query) {
  const q = query.toLowerCase();
  if (q.length < 2 || q.length > 80) throw new Error('query must have 2..80 characters');
  const root = fs.realpathSync(LIB_ROOT);
  const found=[];
  const todo=[{dir:root,depth:0}];
  let examined=0;
  while(todo.length && examined < 2500 && found.length < 30){
    const {dir,depth}=todo.pop();
    for (const entry of fs.readdirSync(dir,{withFileTypes:true})){
      if (entry.isSymbolicLink() || entry.name.startsWith('.')) continue;
      const full=path.join(dir,entry.name);
      if(entry.isDirectory() && depth < 9){todo.push({dir:full,depth:depth+1});continue;}
      if(!entry.isFile() || !/\.(simba|pas|inc|md)$/i.test(entry.name))continue;
      examined++;
      if(fs.statSync(full).size > 1500000)continue;
      const content=fs.readFileSync(full,'utf8');
      const lines=content.split(/\r?\n/);
      for(let i=0;i<lines.length && found.length<30;i++){
        if(lines[i].toLowerCase().includes(q))found.push({path:path.relative(root,full).split(path.sep).join('/'),line:i+1,text:lines[i].trim().slice(0,220)});
      }
    }
  }
  return {matches:found,filesExamined:examined,truncated:found.length===30 || examined===2500};
}

function compileStaged(filename) {
  if (typeof filename !== 'string' || !/^[a-zA-Z0-9_-]{1,60}-[a-f0-9]{12}\.simba$/.test(filename)) {
    return Promise.reject(new Error('Invalid staged draft filename'));
  }
  const file = path.join(DRAFT_ROOT,filename);
  if (!fs.existsSync(file) || fs.lstatSync(file).isSymbolicLink() || !fs.statSync(file).isFile()) {
    return Promise.reject(new Error('Draft not found'));
  }
  if (!fs.existsSync(SIMBA_EXE)) return Promise.reject(new Error('Simba.exe not found; configure WASP_SIMBA_EXE'));
  return new Promise((resolve,reject)=>{
    const child = spawn(SIMBA_EXE,['--compile',file],{
      cwd:path.dirname(SIMBA_EXE), windowsHide:true, stdio:['ignore','pipe','pipe']
    });
    let stdout='',stderr='',ended=false;
    const cap=(value,part)=> (value+part).slice(-16000);
    const timer=setTimeout(()=>{child.kill();reject(new Error('Compilation timed out after 20s'));},20000);
    child.stdout.on('data',d=>{stdout=cap(stdout,d.toString());});
    child.stderr.on('data',d=>{stderr=cap(stderr,d.toString());});
    child.on('error',e=>{if(ended)return;ended=true;clearTimeout(timer);reject(e);});
    child.on('close',(code,signal)=>{
      if(ended)return;ended=true;clearTimeout(timer);
      resolve({filename,compiled:code===0,exitCode:code,signal,stdout,stderr,executed:false});
    });
  });
}

async function handleTool(name,input) {
  const a=parsedArgs(input);
  try {
    if(name==='wasp_devices')return jsonResult(Array.from(clients.values()).map(deviceSummary));
    if(!validId(a.deviceId) && ['wasp_observe','wasp_last_frame','wasp_tap','wasp_pause'].includes(name))
      return failResult('Invalid deviceId');
    if(name==='wasp_observe'){
      const r=await requestDevice(a.deviceId,'OBSERVE',{});
      return !r.ok || !r.hasFrame ? failResult(r.error || 'Fresh capture returned no image') : currentFrame(a.deviceId);
    }
    if(name==='wasp_last_frame') return currentFrame(a.deviceId);
    if(name==='wasp_tap'){
      if(typeof a.x!=='number'||typeof a.y!=='number'||!Number.isFinite(a.x)||!Number.isFinite(a.y)||
         a.x<0.02||a.x>0.98||a.y<0.02||a.y>0.98)return failResult('Normalized x,y must be in [0.02,0.98]');
      const r=await requestDevice(a.deviceId,'TAP',{x:a.x,y:a.y});
      return r.ok?jsonResult(r):failResult(r.error||'tap failed');
    }
    if(name==='wasp_pause'){
      const r=await requestDevice(a.deviceId,'PAUSE',{});
      return r.ok?jsonResult(r):failResult(r.error||'pause failed');
    }
    if(name==='wasp_source_find')return jsonResult(findSources(String(a.query||'')));
    if(name==='wasp_source_read'){
      const file=sourcePath(a.path);
      const size=fs.statSync(file).size;
      if(size>250000)return failResult('File too large to read');
      return jsonResult({path:a.path,sha256:crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'),source:fs.readFileSync(file,'utf8')});
    }
    if(name==='wasp_compile_script'){
      const result=await compileStaged(a.filename);
      return result.compiled?jsonResult(result):failResult(JSON.stringify(result));
    }
    if(name==='wasp_stage_script'){
      if(!validId(a.name)||typeof a.source!=='string'||!a.source.trim()||Buffer.byteLength(a.source)>200000)
        return failResult('Invalid name or source (max 200 KB)');
      fs.mkdirSync(DRAFT_ROOT,{recursive:true});
      const hash=crypto.createHash('sha256').update(a.source).digest('hex');
      const basename=a.name+'-'+hash.slice(0,12)+'.simba';
      const target=path.join(DRAFT_ROOT,basename);
      try{fs.writeFileSync(target,a.source,{flag:'wx',mode:0o600});}catch(e){if(e.code!=='EEXIST')throw e;}
      return jsonResult({staged:true,path:target,sha256:hash,executed:false});
    }
    return failResult('Unknown MCP tool');
  }catch(e){return failResult(e.message);}
}

const protocol = readline.createInterface({input:process.stdin,crlfDelay:Infinity});
protocol.on('line',async line=>{
  let msg;
  try{msg=JSON.parse(line);}catch(_){return;}
  if(!msg || msg.jsonrpc!=='2.0'||typeof msg.method!=='string')return;
  if(msg.id===undefined)return; // notifications have no response
  let result=null, error=null;
  try{
    switch(msg.method){
      case 'initialize':
        result={protocolVersion:'2025-11-25',capabilities:{tools:{listChanged:false}},serverInfo:{name:'waspmobile-android-bridge',version:'0.1.0'},instructions:'Only use OSRS screen observations that are current. Remote control must be enabled locally on the phone. Do not assume actions succeeded without observing.'};break;
      case 'ping':result={};break;
      case 'tools/list':result={tools:TOOLS};break;
      case 'tools/call':{
        const p=msg.params||{};
        if(!TOOLS.some(t=>t.name===p.name)){result=failResult('Unknown tool');break;}
        result=await handleTool(p.name,p.arguments);break;
      }
      default:error={code:-32601,message:'Method not found'};
    }
  }catch(e){error={code:-32603,message:String(e.message||e)};}
  const response={jsonrpc:'2.0',id:msg.id};
  if(error)response.error=error;else response.result=result;
  process.stdout.write(JSON.stringify(response)+'\n');
});

if(require.main===module){
  if(DEVICE_TOKEN.length<32 || !Number.isInteger(PORT) || PORT<1 || PORT>65535){
    process.stderr.write('WASP_DEVICE_TOKEN must be at least 32 characters and WASP_BRIDGE_PORT must be valid.\n');
    process.exitCode=1;
    protocol.close();
  }else{
    server.listen(PORT,'127.0.0.1',()=>process.stderr.write('Wasp MCP phone relay listening on 127.0.0.1:'+PORT+'\n'));
  }
  process.on('SIGTERM',()=>server.close());
  process.on('SIGINT',()=>server.close());
}
module.exports={server,clients,pending,queues,requestDevice,handleTool,deviceSummary,validId,findSources};

'use strict';

/**
 * Oracle Admin <-> WaspMobile MCP bridge.
 *
 * Invoked on the Oracle VM by the EXISTING Oracle Admin execute tool. It does
 * NOT open another port, create another OpenAI tunnel, modify the Oracle Admin
 * MCP server, or provide unsolicited notifications to ChatGPT conversations.
 */
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');

const PRIVATE_MCP_URL='http://127.0.0.1:7790/mcp';
const ENV_FILE='/etc/wasp-osrs/bridge.env';
const OBSERVATIONS='/var/lib/wasp-osrs/observations';
const MAX_RESPONSE=4*1024*1024;
const MAX_SOURCE_ARGS=200000;
const imageOps=new Set(['wasp_observe','wasp_last_frame']);
const ALLOWED=new Set([
 'wasp_devices','wasp_observe','wasp_last_frame','wasp_tap','wasp_pause',
 'wasp_inbox','wasp_reply','wasp_offer_plan','wasp_cancel_plan',
 'wasp_source_find','wasp_source_read','wasp_stage_script',
 'wasp_compile_script','wasp_pc_clients','wasp_pc_probe'
]);

function safeServerToken(envText) {
  if(typeof envText!=='string')throw Error('OSRS service configuration unavailable');
  const lines=envText.split(/\r?\n/);
  const line=lines.find(x=>x.startsWith('WASP_MCP_LOCAL_TOKEN='));
  const token=line?.slice('WASP_MCP_LOCAL_TOKEN='.length);
  if(!token || !/^[A-Za-z0-9_-]{32,128}$/.test(token))throw Error('OSRS MCP token is not configured');
  return token;
}

function parseResult(body,contentType,requestId) {
  if(body.length>MAX_RESPONSE)throw Error('MCP response exceeded permitted size');
  const values=[];
  if(contentType?.includes('text/event-stream')) {
    for(const block of body.split(/\r?\n\r?\n/)) {
      const data=block.split(/\r?\n/).filter(x=>x.startsWith('data:')).map(x=>x.slice(5).trimStart()).join('\n');
      if(data) {
        try {values.push(JSON.parse(data));} catch {throw Error('Invalid MCP event payload');}
      }
    }
  } else if(contentType?.includes('application/json')) {
    values.push(JSON.parse(body));
  } else throw Error('Unexpected MCP content type');
  const entry=values.find(v=>v && v.id===requestId);
  if(!entry || entry.jsonrpc!=='2.0')throw Error('MCP response missing matching request ID');
  if(entry.error)throw Error('MCP protocol error: '+String(entry.error.message||'unknown').slice(0,180));
  if(!entry.result || !Array.isArray(entry.result.content))throw Error('Malformed MCP tool result');
  return entry.result;
}

function validateTool(name,args) {
  if(!ALLOWED.has(name))throw Error('Unknown or unauthorized WaspMobile tool');
  if(!args || typeof args!=='object' || Array.isArray(args))throw Error('Tool arguments must be a JSON object');
  if(Object.keys(args).length>32 || Buffer.byteLength(JSON.stringify(args))>MAX_SOURCE_ARGS)throw Error('Too many tool arguments');
  if(args.deviceId && !/^[A-Za-z0-9_-]{1,64}$/.test(args.deviceId))throw Error('Invalid phone ID');
  if(name==='wasp_reply' && args.text && typeof args.text==='string' && !args.messageId){
    args={...args,messageId:crypto.randomUUID()};
  }
  return args;
}

async function mcpCall(name,args, {httpFetch=fetch,readConfig=()=>fs.readFile(ENV_FILE,'utf8')}={}) {
  args=validateTool(name,args);
  const token=safeServerToken(await readConfig());
  const requestId=crypto.randomUUID();
  const response=await httpFetch(PRIVATE_MCP_URL,{
    method:'POST',
    headers:{
      'Content-Type':'application/json',
      Accept:'application/json, text/event-stream',
      'MCP-Protocol-Version':'2025-11-25',
      Authorization:'Bearer '+token
    },
    body:JSON.stringify({jsonrpc:'2.0',id:requestId,method:'tools/call',
      params:{name,arguments:args}}),
    signal:AbortSignal.timeout(18000),
    redirect:'error'
  });
  if(!response.ok)throw Error('Private OSRS MCP endpoint rejected request: HTTP '+response.status);
  const contentLength=Number(response.headers.get('content-length'));
  if(contentLength>MAX_RESPONSE)throw Error('MCP response too large');
  const body=await response.text();
  return parseResult(body,response.headers.get('content-type'),requestId);
}

async function preserveImage(name,record,{saveDir=OBSERVATIONS,writeFs=fs}={}) {
  const c=record?.content;
  if(!Array.isArray(c))throw Error('Invalid MCP output');
  const result={name,ok:record.isError!==true};
  for(const entry of c){
    if(entry.type==='image') {
      if(!imageOps.has(name))throw Error('Unexpected image content');
      if(entry.mimeType!=='image/jpeg'||typeof entry.data!=='string'||
         entry.data.length>3*1024*1024||!/^[a-zA-Z0-9+/]+={0,2}$/.test(entry.data))
        throw Error('Invalid game screenshot');
      const image=Buffer.from(entry.data,'base64');
      if(image.length<4||image.length>1500000||image[0]!==0xff||image[1]!==0xd8)
        throw Error('Invalid JPEG frame');
      await writeFs.mkdir(saveDir,{recursive:true,mode:0o700});
      await writeFs.chmod(saveDir,0o700);
      // Old screenshots are sensitive. Remove them after 5 minutes.
      const now=Date.now();
      for(const file of await writeFs.readdir(saveDir)) {
        if(!/^osrs-[a-f0-9-]{36}\.jpg$/.test(file))continue;
        try {
          const full=path.join(saveDir,file);
          const stats=await writeFs.stat(full);
          if(now-stats.mtimeMs>5*60000)await writeFs.unlink(full);
        } catch {}
      }
      const file=path.join(saveDir,'osrs-'+crypto.randomUUID()+'.jpg');
      await writeFs.writeFile(file,image,{mode:0o600,flag:'wx'});
      result.image={path:file,mimeType:'image/jpeg',bytes:image.length,
        sha256:crypto.createHash('sha256').update(image).digest('hex')};
    } else if(entry.type==='text'&&typeof entry.text==='string') {
      try {result.data=JSON.parse(entry.text);}
      catch {result.text=entry.text.slice(0,16000);}
    }
  }
  return result;
}

async function runCLI(args=process.argv.slice(2)){
  const name=args[0] === 'devices' ? 'wasp_devices' :
    args[0]?.startsWith('wasp_')?args[0]:'';
  if(!ALLOWED.has(name) || args.length>2){
    throw Error('Usage: wasp-osrs-mcp devices | wasp-osrs-mcp wasp_inbox \'{"deviceId":"phone-ID","afterSeq":0}\' | wasp-osrs-mcp wasp_reply \'{"deviceId":"...","replyTo":"...", "text":"..."}\' | wasp-osrs-mcp wasp_observe \'{"deviceId":"..."}\'');
  }
  let parsed={};
  if(args[1]) {
    if(args[1].length>MAX_SOURCE_ARGS)throw Error('Arguments too large');
    parsed=JSON.parse(args[1]);
  }
  const result=await mcpCall(name,parsed);
  const data=await preserveImage(name,result);
  process.stdout.write(JSON.stringify(data)+'\n');
  if(!data.ok)process.exitCode=2;
}
if(require.main===module)runCLI().catch(err=>{
  process.stderr.write('WaspMobile MCP request failed: '+String(err.message||'unknown').slice(0,250)+'\n');
  process.exitCode=1;
});
module.exports={ALLOWED,safeServerToken,parseResult,validateTool,mcpCall,preserveImage,runCLI};

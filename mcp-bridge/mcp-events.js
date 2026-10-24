'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const https = require('node:https');
const dns = require('node:dns').promises;
const ipaddr = require('ipaddr.js');
const {Webhook} = require('standardwebhooks');
const {ProtocolError} = require('@modelcontextprotocol/server');

const EVENT_NAME = 'overlay.message.created';
const EXPIRY_MS = 7 * 86400000;
const EVENT_DEFINITION = Object.freeze({
  name: EVENT_NAME,
  description: 'A user posted a message from the one-phone WaspMobile OSRS overlay. The subscribed ChatGPT Work chat or OSRS dot may reply via wasp_reply.',
  delivery: ['webhook'],
  inputSchema: {
    type:'object',
    properties:{device_id:{type:'string',minLength:1,maxLength:64,pattern:'^[A-Za-z0-9_-]+$'}},
    required:['device_id'],additionalProperties:false
  },
  payloadSchema: {
    type:'object',
    properties:{
      device_id:{type:'string'},
      message_id:{type:'string'},
      text:{type:'string'},
      source:{type:'string',enum:['osrs-waspmobile']}
    },
    required:['device_id','message_id','text','source'],
    additionalProperties:false
  }
});

function callbackHostAllowed(host, suffixes) {
  const h=host.toLowerCase();
  return suffixes.some(raw=>{
    const domain=raw.toLowerCase().replace(/^\./,'');
    return /^[a-z0-9.-]+$/.test(domain) && (h===domain || h.endsWith('.'+domain));
  });
}
function parseCallbackUrl(input, suffixes) {
  if(typeof input!=='string'||input.length>2048)throw new Error('Invalid callback URL');
  let u;
  try{u=new URL(input);}catch{throw new Error('Invalid callback URL');}
  if(u.protocol!=='https:'||u.username||u.password||u.hash||u.port&&u.port!=='443'||
     !u.hostname||!callbackHostAllowed(u.hostname,suffixes)) {
    throw new Error('Callback must use HTTPS on port 443 with an approved host');
  }
  if(ipaddr.isValid(u.hostname))throw new Error('Callback IP literals are not allowed');
  return u;
}
function isPublic(address) {
  try {
    const ip=ipaddr.process(address);
    return ip.range()==='unicast';
  }catch{return false;}
}
async function securePost(url,body,headers,{allowedHosts,resolve=dns.lookup}={}) {
  const parsed=parseCallbackUrl(url,allowedHosts);
  const result=await resolve(parsed.hostname,{all:true,verbatim:true});
  if(!Array.isArray(result)||!result.length||result.some(x=>!isPublic(x.address)))
    throw new Error('Callback DNS contains non-public addresses');
  const chosen=result[0];
  return new Promise((done,reject)=>{
    let settled=false, bytes=0, text='';
    const request=https.request({
      hostname:parsed.hostname,port:443,protocol:'https:',
      method:'POST',path:parsed.pathname+parsed.search,servername:parsed.hostname,
      rejectUnauthorized:true,timeout:9000,
      lookup:(_hostname,_options,cb)=>cb(null,chosen.address,chosen.family),
      headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(body),...headers}
    },response=>{
      response.on('data',chunk=>{
        bytes+=chunk.length;
        if(bytes>4096){request.destroy(new Error('Callback response too large'));return;}
        text+=chunk.toString('utf8');
      });
      response.on('end',()=>{
        if(settled)return;settled=true;
        if(response.statusCode>=300&&response.statusCode<400)
          reject(new Error('Callback redirects not permitted'));
        else done({status:response.statusCode,body:text});
      });
    });
    request.on('timeout',()=>request.destroy(new Error('Callback timed out')));
    request.on('error',error=>{if(!settled){settled=true;reject(error);}});
    request.end(body);
  });
}

function parseSecret(secret) {
  if(typeof secret!=='string'||!secret.startsWith('whsec_'))throw new Error('Invalid webhook signing secret');
  const encoded=secret.slice(6);
  if(!/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)||encoded.length%4!==0)throw new Error('Invalid webhook signing secret');
  const bytes=Buffer.from(encoded,'base64');
  if(bytes.length<24||bytes.length>64||bytes.toString('base64')!==encoded)throw new Error('Webhook secret must decode to 24–64 bytes');
  return bytes;
}
function parseKey(key) {
  if(typeof key!=='string'||!/^[A-Za-z0-9+/]{43}=$/.test(key))throw new Error('WASP_EVENTS_STORAGE_KEY must be a 32-byte base64 secret');
  const bytes=Buffer.from(key,'base64');
  if(bytes.length!==32||bytes.toString('base64')!==key)throw new Error('Invalid event storage key encoding');
  return bytes;
}
function equalString(a,b) {
  if(typeof a!=='string'||typeof b!=='string')return false;
  const x=Buffer.from(a),y=Buffer.from(b);
  return x.length===y.length && crypto.timingSafeEqual(x,y);
}
function encodePayload(key,object) {
  const iv=crypto.randomBytes(12);
  const cipher=crypto.createCipheriv('aes-256-gcm',key,iv);
  const ct=Buffer.concat([cipher.update(JSON.stringify(object),'utf8'),cipher.final()]);
  return Buffer.concat([Buffer.from([1]),iv,cipher.getAuthTag(),ct]);
}
function decodePayload(key,data) {
  if(data.length<30||data[0]!==1)throw new Error('Stored event subscriptions are corrupt');
  const iv=data.subarray(1,13),tag=data.subarray(13,29),ct=data.subarray(29);
  const cipher=crypto.createDecipheriv('aes-256-gcm',key,iv);
  cipher.setAuthTag(tag);
  return JSON.parse(Buffer.concat([cipher.update(ct),cipher.final()]).toString('utf8'));
}

function createMcpEvents({
  relay,enabled=false,storageKey,storageFile=path.join(os.homedir(),'.config','waspmobile-osrs','event-subscriptions.enc'),
  allowedHosts=['openai.com','chatgpt.com'], post=securePost, now=()=>Date.now()
}={}) {
  if(!relay)throw new Error('Events require the one-phone MCP relay');
  if(enabled&&!Array.isArray(allowedHosts)||allowedHosts?.some(x=>typeof x!=='string'||!/^[a-z0-9.-]+$/i.test(x)))
    throw new Error('Invalid callback host allowlist');
  const key=enabled?parseKey(storageKey):null;
  let subscription=null,closed=false,ready=false;
  let deliveryState='no_recent_message';
  const inFlight=new Set();
  let saved=Promise.resolve();
  let subscriptionLock=Promise.resolve();
  function serializeSubscription(fn) {
    const result=subscriptionLock.then(fn,fn);
    subscriptionLock=result.catch(()=>{});
    return result;
  }
  function summary(deviceId) {
    const active=enabled && !!subscription && subscription.expires>now() && subscription.deviceId===deviceId;
    return {available:enabled,subscribed:!!active,
      delivery:active?'webhook':'none',lastDelivery:active?deliveryState:'not_subscribed',
      status:active?'ChatGPT Work/dot event subscription active; awaiting messages'
        :(enabled?'No Work/dot subscription yet. Overlay messages remain in the MCP inbox.'
          :'MCP events disabled. Overlay messages wait until ChatGPT calls wasp_inbox.')};
  }
  function refreshRelay() {
    relay.setEventSummary((deviceId)=>summary(deviceId));
  }
  refreshRelay();
  async function persist() {
    if(!enabled)return;
    const payload=encodePayload(key,subscription?{subscription}:{subscription:null});
    const directory=path.dirname(storageFile);
    await fs.mkdir(directory,{recursive:true,mode:0o700});
    const tmp=storageFile+'.'+crypto.randomUUID()+'.tmp';
    try {
      await fs.writeFile(tmp,payload,{flag:'wx',mode:0o600});
      await fs.rename(tmp,storageFile);
    } catch(e) {
      await fs.rm(tmp,{force:true}).catch(()=>{});
      throw e;
    }
  }
  function store() {
    const result=saved.then(persist);
    saved=result.catch(()=>{});
    return result;
  }
  async function load() {
    if(ready)return;
    if(!enabled){ready=true;return;}
    try {
      const raw=await fs.readFile(storageFile);
      const data=decodePayload(key,raw);
      const s=data?.subscription;
      if(s && typeof s.id==='string' && s.expires>now() &&
        typeof s.deviceId==='string' && typeof s.url==='string' && typeof s.secret==='string') {
        parseCallbackUrl(s.url,allowedHosts);
        parseSecret(s.secret);
        subscription=s;
      }
    }catch(e){if(e.code!=='ENOENT')throw new Error('Could not unlock event subscription store; do not overwrite it');}
    ready=true;
  }
  async function signedPost(s,body,id) {
    const signer=new Webhook(s.secret);
    const at=new Date(now());
    const header={
      'webhook-id':id,
      'webhook-timestamp':String(Math.floor(at.getTime()/1000)),
      'webhook-signature':signer.sign(id,at,body),
      'X-MCP-Subscription-Id':s.id
    };
    return post(s.url,body,header,{allowedHosts});
  }
  async function verify(s) {
    const challenge=crypto.randomBytes(24).toString('base64url');
    const id='msg_verification_'+crypto.randomUUID();
    const response=await signedPost(s,JSON.stringify({type:'verification',challenge}),id);
    if(response.status<200||response.status>=300)throw new Error('Webhook verification was not accepted');
    let value;
    try{value=JSON.parse(response.body);}catch{throw new Error('Webhook verification returned invalid JSON');}
    if(!equalString(value?.challenge,challenge))throw new Error('Webhook verification challenge did not match');
  }
  async function subscribeInternal(params) {
    if(!enabled||!ready||closed)throw new ProtocolError(-32603,'MCP events not enabled');
    const {name,arguments:args,delivery}=params;
    if(params.cursor != null)throw new ProtocolError(-32602,'This event does not support replay cursors');
    if(name!==EVENT_NAME||!args||Object.keys(args).length!==1||typeof args.device_id!=='string'||
      !/^[A-Za-z0-9_-]{1,64}$/.test(args.device_id)||delivery?.mode!=='webhook'||
      typeof delivery.url!=='string'||typeof delivery.secret!=='string') {
      throw new ProtocolError(-32602,'Invalid event subscription arguments');
    }
    parseSecret(delivery.secret);
    // ChatGPT MCP Events can request a bounded subscription lifetime. The
    // server never grants more than requested; null still receives a finite
    // seven-day lease rather than unlimited unattended access.
    if(params.ttlMs != null && (!Number.isSafeInteger(params.ttlMs) ||
      params.ttlMs<60000 || params.ttlMs>Number.MAX_SAFE_INTEGER-now())) {
      throw new ProtocolError(-32602,'Invalid subscription TTL (minimum one minute)');
    }
    const grantedMs=params.ttlMs == null ? EXPIRY_MS : Math.min(EXPIRY_MS,params.ttlMs);
    // The S23 can temporarily go offline when switching from OSRS to the
    // ChatGPT Work/dot setup screen. Authorize only the previously paired or
    // explicitly pinned phone ID; webhook delivery still requires a fresh
    // authenticated session and never executes while the phone is offline.
    if(!relay.isPairedDevice(args.device_id))
      throw new ProtocolError(-32602,'Pair or pin the selected phone before subscribing');
    parseCallbackUrl(delivery.url,allowedHosts);
    // The first one-phone version admits ONE ChatGPT Work/dot subscriber.
    // Never forward private overlay messages to a surprise second callback.
    if(subscription && subscription.expires>now() &&
      (subscription.deviceId!==args.device_id || subscription.url!==delivery.url))
      throw new ProtocolError(-32602,'Another Work/dot subscriber is active: unsubscribe it first');
    const id='sub_'+crypto.createHmac('sha256',key).update('private-owner\0'+EVENT_NAME+'\0'+args.device_id+'\0'+delivery.url).digest('hex').slice(0,32);
    const s={id,name:EVENT_NAME,deviceId:args.device_id,url:delivery.url,secret:delivery.secret,
      expires:now()+grantedMs};
    try { await verify(s); }
    catch(e){throw new ProtocolError(-32015,'Callback verification failed',{reason:'challenge_failed'});}
    const previous=subscription;
    subscription=s;
    deliveryState='no_recent_message';
    try{await store();}catch(e){subscription=previous;throw new ProtocolError(-32603,'Could not persist event subscription');}
    return {id,refreshBefore:new Date(s.expires).toISOString(),cursor:null,truncated:false};
  }
  async function unsubscribeInternal(params) {
    if(!enabled||!ready)throw new ProtocolError(-32603,'MCP events not enabled');
    if(params.name!==EVENT_NAME || params.delivery?.mode!=='webhook' ||
       !params.arguments || typeof params.arguments.device_id!=='string' ||
       typeof params.delivery.url!=='string')
      throw new ProtocolError(-32602,'Invalid unsubscribe request');
    if(subscription && subscription.deviceId===params.arguments.device_id &&
       subscription.url===params.delivery.url) {
      const old=subscription;subscription=null;
      deliveryState='not_subscribed';
      try{await store();}catch{subscription=old;throw new ProtocolError(-32603,'Could not persist event unsubscription');}
    }
    return {};
  }
  const subscribe=params=>serializeSubscription(()=>subscribeInternal(params));
  const unsubscribe=params=>serializeSubscription(()=>unsubscribeInternal(params));
  function emit(request) {
    if(!ready||!enabled||closed||!subscription||subscription.expires<=now()||
       subscription.deviceId!==request.deviceId||!relay.sessionActive(request.deviceId,request.sessionId))return;
    if(inFlight.size>=8)return;
    const s={...subscription};
    const event={
      eventId:'evt_'+crypto.randomUUID(),
      name:EVENT_NAME,timestamp:new Date(now()).toISOString(),
      data:{device_id:request.deviceId,message_id:request.id,text:request.text,source:'osrs-waspmobile'},
      cursor:null
    };
    const body=JSON.stringify(event);
    if(Buffer.byteLength(body)>262144)return;
    deliveryState='sending';
    const p=(async()=>{
      let attempt=0;
      while(attempt<3&&!closed&&subscription?.id===s.id && s.expires>now()){
        attempt++;
        try{
          const response=await signedPost(s,body,event.eventId);
          if(response.status>=200&&response.status<300){deliveryState='accepted_by_chatgpt_webhook';return;}
          if(response.status!==429&&response.status<500){deliveryState='rejected_by_callback';return;}
        }catch{}
        if(attempt<3)await new Promise(done=>setTimeout(done,attempt*1000));
      }
      if(!closed)deliveryState='delivery_failed';
    })();
    inFlight.add(p);void p.finally(()=>inFlight.delete(p));
  }
  const unsubscribeRelay=relay.subscribeMessages(emit);
  function register(mcp,z) {
    if(!enabled)return;
    mcp.server.registerCapabilities({events:{}});
    mcp.server.setRequestHandler('events/list',{
      params:z.strictObject({cursor:z.string().optional()}).passthrough()
    },async()=>({events:[EVENT_DEFINITION]}));
    mcp.server.setRequestHandler('events/subscribe',{
      params:z.strictObject({
        name:z.literal(EVENT_NAME),
        arguments:z.strictObject({device_id:z.string().regex(/^[A-Za-z0-9_-]{1,64}$/)}),
        delivery:z.strictObject({mode:z.literal('webhook'),url:z.string().url(),secret:z.string()}),
        cursor:z.string().nullable().optional(),
        ttlMs:z.number().int().positive().nullable().optional()
      })
    },subscribe);
    mcp.server.setRequestHandler('events/unsubscribe',{
      params:z.strictObject({
        name:z.literal(EVENT_NAME),
        arguments:z.strictObject({device_id:z.string().regex(/^[A-Za-z0-9_-]{1,64}$/)}),
        delivery:z.strictObject({mode:z.literal('webhook'),url:z.string().url()})
      })
    },unsubscribe);
  }
  async function close() {closed=true;unsubscribeRelay();await saved.catch(()=>{});}
  return {load,register,subscribe,unsubscribe,emit,summary,close,EVENT_NAME};
}
module.exports={createMcpEvents,parseCallbackUrl,isPublic,securePost,EVENT_NAME};

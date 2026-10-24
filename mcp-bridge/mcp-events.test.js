'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const crypto=require('node:crypto');
const {Webhook}=require('standardwebhooks');
const {createRelay,GAME_PACKAGE}=require('./relay');
const {createMcpEvents,parseCallbackUrl,isPublic,securePost,EVENT_NAME}=require('./mcp-events');
const token='event-test-token-0123456789012345678901234567';
const encryptionKey=Buffer.alloc(32,71).toString('base64');
const eventSecret='whsec_'+Buffer.alloc(32,34).toString('base64');
const url='https://hooks.work.openai.com/event-callback/private123';

async function fixture(t,{post,now,allowHosts=['openai.com'],key=encryptionKey}={}){
  let current=Date.now();
  const temp=await fs.mkdtemp(path.join(os.tmpdir(),'wasp-events-test-'));
  const relay=createRelay({token,now:now||(()=>current),onlineMs:10000});
  await new Promise(resolve=>relay.server.listen(0,'127.0.0.1',resolve));
  const port=relay.server.address().port;
  const headers={'content-type':'application/json',authorization:'Bearer '+token};
  async function poll(extras={}){
    const data={deviceId:'s23-one',sessionId:'phone-session-1',remoteEnabled:true,
      foreground:GAME_PACKAGE,windowKey:'osrs-window',width:1024,height:472,inputAllowed:true,...extras};
    const res=await fetch('http://127.0.0.1:'+port+'/device/poll',
      {method:'POST',headers,body:JSON.stringify(data)});
    assert.equal(res.status,200);
    return res.json();
  }
  const sender=post|| (async(_url,body,headers)=>{
    const decoded=JSON.parse(body);
    const signed=new Webhook(eventSecret);
    signed.verify(body,headers);
    return decoded.type==='verification'
      ? {status:200,body:JSON.stringify({challenge:decoded.challenge})}
      : {status:200,body:'{}'};
  });
  const storageFile=path.join(temp,'event-subscriptions.enc');
  const events=createMcpEvents({relay,enabled:true,storageKey:key,storageFile,
    allowedHosts:allowHosts,post:sender,now:now||(()=>current)});
  await events.load();
  t.after(async()=>{await events.close();await relay.close();await fs.rm(temp,{recursive:true,force:true});});
  async function subscribe(overrides={}){
    const value={name:EVENT_NAME,arguments:{device_id:'s23-one'},
      delivery:{mode:'webhook',url,secret:eventSecret},...overrides};
    return events.subscribe(value);
  }
  return {events,relay,poll,subscribe,storageFile,advance:ms=>current+=ms,
    token,eventSecret,port,temp};
}
async function waitFor(check,timeout=900){
 const limit=Date.now()+timeout;
 while(Date.now()<limit){if(check())return;await new Promise(r=>setTimeout(r,10));}
 assert.fail('Expected asynchronous event was not received');
}
test('the URL allowlist rejects SSRF, lookalikes, protocol downgrade, credentials and IP literals',()=>{
 const allow=['openai.com','chatgpt.com'];
 for(const unsafe of [
    'https://evilopenai.com/hook','https://openai.com.attacker.invalid/hook',
    'http://hooks.openai.com/hook','https://user:pass@hooks.openai.com/hook',
    'https://127.0.0.1/hook','https://[::1]/hook','https://hooks.openai.com:9443/hook',
    'https://hooks.openai.com/hook#fragment','https://169.254.169.254/latest/meta-data'
 ])assert.throws(()=>parseCallbackUrl(unsafe,allow));
 assert.equal(parseCallbackUrl(url,allow).hostname,'hooks.work.openai.com');
});
test('IP address classification permits only unicast public destinations',()=>{
 for(const x of ['127.0.0.1','10.10.0.2','192.168.1.4','172.16.1.1','169.254.169.254',
   '0.0.0.0','::1','fe80::1','fc00::1','::ffff:127.0.0.1','224.0.0.1'])assert.equal(isPublic(x),false,x);
 assert.equal(isPublic('1.1.1.1'),true);
 assert.equal(isPublic('2606:4700:4700::1111'),true);
});
test('event feature requires non-plaintext AES-256-GCM storage key',async t=>{
 const f=await fixture(t);
 assert.throws(()=>createMcpEvents({relay:f.relay,enabled:true,storageKey:'short'}),/STORAGE_KEY/);
});
test('subscription demands an online, enabled and matching phone',async t=>{
 const f=await fixture(t);
 await assert.rejects(f.subscribe(),/Pair and enable/);
 await f.poll({remoteEnabled:false});
 await assert.rejects(f.subscribe(),/Pair and enable/);
 await f.poll();
 assert.equal((await f.subscribe()).cursor,null);
});
test('webhook challenge is verified and signed before storing subscription',async t=>{
 const calls=[];
 const f=await fixture(t,{post:async(_url,body,headers)=>{
   const data=JSON.parse(body);
   new Webhook(eventSecret).verify(body,headers);
   calls.push({data,headers});
   return {status:200,body:JSON.stringify({challenge:data.challenge})};
 }});
 await f.poll();
 const result=await f.subscribe();
 assert.match(result.id,/^sub_[a-f0-9]{32}$/);
 assert.equal(calls.length,1);
 assert.equal(calls[0].data.type,'verification');
 assert.equal(calls[0].headers['X-MCP-Subscription-Id'],result.id);
 assert.equal(f.events.summary('s23-one').subscribed,true);
 const stored=await fs.readFile(f.storageFile);
 assert.equal(stored.includes(Buffer.from(eventSecret)),false,'No plaintext secret should be on disk');
 assert.equal(stored.includes(Buffer.from(url)),false,'No plaintext callback should be on disk');
});
test('invalid challenge never activates a callback or persists an active subscription',async t=>{
 const f=await fixture(t,{post:async()=>({status:200,body:'{"challenge":"wrong"}'})});
 await f.poll();
 await assert.rejects(f.subscribe(),/Callback verification failed/);
 assert.equal(f.events.summary('s23-one').subscribed,false);
 await assert.rejects(fs.stat(f.storageFile),{code:'ENOENT'});
});
test('signed overlay message webhook triggers on unique user message, not on retry',async t=>{
 const delivered=[];
 const f=await fixture(t,{post:async(_url,body,headers)=>{
   new Webhook(eventSecret).verify(body,headers);
   const data=JSON.parse(body);
   if(data.type==='verification')return {status:200,body:JSON.stringify({challenge:data.challenge})};
   delivered.push(data);
   return {status:202,body:'{}'};
 }});
 await f.poll();
 await f.subscribe();
 const a={id:'message-1',text:'What does my OSRS screen show?'};
 let result=await f.poll({outbox:[a]});
 assert.equal(result.desk.events.subscribed,true);
 await waitFor(()=>delivered.length===1);
 assert.equal(f.events.summary('s23-one').lastDelivery,'accepted_by_chatgpt_webhook');
 assert.equal(delivered[0].name,EVENT_NAME);
 assert.equal(delivered[0].data.text,a.text);
 assert.equal(delivered[0].data.source,'osrs-waspmobile');
 await f.poll({outbox:[a]});
 await new Promise(r=>setTimeout(r,50));
 assert.equal(delivered.length,1);
});
test('a callback for another phone or Work/dot cannot subscribe while current subscriber active',async t=>{
 const f=await fixture(t);
 await f.poll();
 await f.subscribe();
 await assert.rejects(f.subscribe({delivery:{mode:'webhook',url:'https://another.openai.com/hook',secret:eventSecret}}),/Another Work/);
 await assert.rejects(f.subscribe({arguments:{device_id:'second-phone'}}),/Pair and enable/);
});
test('unsubscribe revokes event delivery and can be repeated idempotently',async t=>{
 const delivered=[];
 const f=await fixture(t,{post:async(_url,body)=>{
   const data=JSON.parse(body);
   if(data.type==='verification')return {status:200,body:JSON.stringify({challenge:data.challenge})};
   delivered.push(data);return {status:200,body:'{}'};
 }});
 await f.poll();await f.subscribe();
 const args={name:EVENT_NAME,arguments:{device_id:'s23-one'},delivery:{mode:'webhook',url}};
 await f.events.unsubscribe(args);
 await f.events.unsubscribe(args);
 assert.equal(f.events.summary('s23-one').subscribed,false);
 await f.poll({outbox:[{id:'msg-2',text:'Is the event still connected?'}]});
 await new Promise(r=>setTimeout(r,50));
 assert.equal(delivered.length,0);
});
test('expired subscriptions are neither active nor delivered',async t=>{
 let now=Date.now();
 const f=await fixture(t,{now:()=>now});
 await f.poll();await f.subscribe();
 now+=8*86400000;
 assert.equal(f.events.summary('s23-one').subscribed,false);
});
test('subscribe idempotency uses a stable subscription ID and refreshes expiry',async t=>{
 const f=await fixture(t);
 await f.poll();const one=await f.subscribe(); f.advance(1000);
 const two=await f.subscribe();assert.equal(one.id,two.id);
 assert.notEqual(one.refreshBefore,two.refreshBefore);
});
test('the encrypted subscription survives process state reload',async t=>{
 const f=await fixture(t);
 await f.poll();await f.subscribe();
 const reloaded=createMcpEvents({relay:f.relay,enabled:true,storageKey:encryptionKey,
   storageFile:f.storageFile,allowedHosts:['openai.com'],
   post:async()=>({status:200,body:'{}'})});
 t.after(()=>reloaded.close());
 await reloaded.load();
 assert.equal(reloaded.summary('s23-one').subscribed,true);
});
test('MCP events are disabled without opt-in, never piggyback on the plain MCP inbox',async t=>{
 const f=await fixture(t);
 const disabled=createMcpEvents({relay:f.relay,enabled:false});
 t.after(()=>disabled.close());
 await disabled.load();
 assert.equal(disabled.summary('s23-one').subscribed,false);
});

test('DNS rebinding and private callback addresses are rejected before any HTTPS connection', async()=>{
 const bad=[{address:'127.0.0.1',family:4},{address:'169.254.169.254',family:4},
   {address:'fc00::1',family:6}];
 for(const record of bad){
   await assert.rejects(securePost(url,'{}',{}, {
     allowedHosts:['openai.com'], resolve:async()=>[record]
   }),/non-public addresses/);
 }
 await assert.rejects(securePost(url,'{}',{}, {
   allowedHosts:['openai.com'],resolve:async()=>[{address:'1.1.1.1',family:4},bad[0]]
 }),/non-public addresses/);
});
test('encrypted subscription store rejects tampered ciphertext',async t=>{
 const f=await fixture(t);
 await f.poll(); await f.subscribe();
 const before=await fs.readFile(f.storageFile);
 const modified=Buffer.from(before); modified[modified.length-1]^=0x08;
 await fs.writeFile(f.storageFile,modified);
 const restarted=createMcpEvents({relay:f.relay,enabled:true,storageKey:encryptionKey,
   storageFile:f.storageFile,allowedHosts:['openai.com']});
 t.after(()=>restarted.close());
 await assert.rejects(restarted.load(),/Could not unlock/);
 assert.equal(restarted.summary('s23-one').subscribed,false);
});

test('concurrent Work/dot subscribers cannot replace each other while validating callbacks',async t=>{
 const f=await fixture(t,{post:async(_url,body)=>{
   const data=JSON.parse(body);
   await new Promise(r=>setTimeout(r,20));
   return {status:200,body:JSON.stringify({challenge:data.challenge})};
 }});
 await f.poll();
 const a=f.subscribe();
 const b=f.subscribe({delivery:{mode:'webhook',url:'https://second.work.openai.com/hook',secret:eventSecret}});
 const results=await Promise.allSettled([a,b]);
 assert.equal(results.filter(x=>x.status==='fulfilled').length,1);
 assert.equal(results.filter(x=>x.status==='rejected').length,1);
 assert.equal(f.events.summary('s23-one').subscribed,true);
});
test('event type without replay explicitly rejects non-null cursors',async t=>{
 const f=await fixture(t);await f.poll();
 await assert.rejects(f.subscribe({cursor:'cursor-that-could-skip-messages'}),/replay cursors/);
});

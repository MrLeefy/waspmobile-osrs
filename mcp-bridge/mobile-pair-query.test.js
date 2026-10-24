'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const {createMobilePairPage}=require('./mobile-pair-page');
const origin='https://waspmobile.129.146.39.132.sslip.io';
const headers={Host:'waspmobile.129.146.39.132.sslip.io','X-Forwarded-Proto':'https'};
function call(uri,{method='GET',extraHeaders={},body}={}) {
  return new Promise((resolve,reject)=>{
    const req=http.request(uri,{method,headers:{...headers,...extraHeaders}},response=>{
      const parts=[];response.on('data',p=>parts.push(p));
      response.on('end',()=>resolve({code:response.statusCode,text:Buffer.concat(parts).toString(),
        cookie:response.headers['set-cookie']?.[0]||''}));
    });
    req.on('error',reject);req.end(body);
  });
}
async function fixture(t) {
  const token='synthetic-phone-token-012345678901234567890';
  const nonce='synthetic-long-random-nonce-01234567890123456789012';
  const portal=createMobilePairPage({phoneToken:token,nonce,expiresAt:Date.now()+600000,port:0});
  const p=await portal.start();t.after(()=>portal.close());
  const base='http://127.0.0.1:'+p.port+p.path;
  return {token,portal,base};
}
test('chat-app query suffix no longer returns a 404 on landing GET',async t=>{
  const f=await fixture(t);
  const r=await call(f.base+'?utm_source=chatgpt.com');
  assert.equal(r.code,200);
  assert.match(r.text,/Reveal my one-time pairing details/);
  assert.ok(!r.text.includes(f.token));
  assert.equal(f.portal.claimed,false);
  assert.equal((await call(f.base+'/wrong?utm_source=chatgpt.com')).code,404);
});
test('chat-app query suffix retains one-use claim and CSRF protections',async t=>{
  const f=await fixture(t);
  const landing=await call(f.base+'?utm_source=chatgpt.com');
  const csrf=landing.text.match(/name="csrf" value="([^"]+)"/)?.[1];
  assert.ok(csrf);
  const opts={method:'POST',extraHeaders:{Origin:origin,
    'Content-Type':'application/x-www-form-urlencoded',
    Cookie:landing.cookie.split(';')[0]},body:new URLSearchParams({csrf}).toString()};
  const accepted=await call(f.base+'/reveal?utm_source=chatgpt.com',opts);
  assert.equal(accepted.code,200);
  assert.ok(accepted.text.includes(f.token));
  assert.equal(f.portal.claimed,true);
  const again=await call(f.base+'/reveal?utm_source=chatgpt.com',opts);
  assert.equal(again.code,410);
  assert.ok(!again.text.includes(f.token));
});

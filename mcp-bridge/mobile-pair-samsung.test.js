'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {createMobilePairPage} = require('./mobile-pair-page');
const origin='https://waspmobile.129.146.39.132.sslip.io';
function request(url,{method='GET',headers={},body}={}) {
  return new Promise((resolve,reject)=>{
    const req=http.request(url,{method,headers:{Host:'waspmobile.129.146.39.132.sslip.io',
      'X-Forwarded-Proto':'https',...headers}},res=>{
      let data='';res.on('data',b=>data+=b.toString());
      res.on('end',()=>resolve({status:res.statusCode,html:data,cookie:res.headers['set-cookie']?.[0]}));
    });
    req.on('error',reject);
    req.end(body);
  });
}
const testToken='synthetic-for-test-only-012345678901234567890';
const testNonce=require('node:crypto').randomBytes(32).toString('base64url');
async function fixture(t) {
  const p=createMobilePairPage({phoneToken:testToken,nonce:testNonce,
    expiresAt:Date.now()+120000,port:0});
  const ready=await p.start();t.after(()=>p.close());
  const url='http://127.0.0.1:'+ready.port+ready.path;
  const landing=await request(url+'?utm_source=chatgpt.com');
  assert.equal(landing.status,200);
  const csrf=landing.html.match(/name="csrf" value="([^"]+)"/)?.[1];
  assert.ok(csrf);
  return {p,url,csrf,cookie:landing.cookie.split(';')[0]};
}
function post(f,headers={},csrf=f.csrf){
  return request(f.url+'/reveal',{method:'POST',headers:{
    'Content-Type':'application/x-www-form-urlencoded','Cookie':f.cookie,
    'Sec-Fetch-Site':'same-origin',...headers
  },body:new URLSearchParams({csrf}).toString()});
}
test('Samsung in-app browser without Origin may reveal once after CSRF validation',async t=>{
  const f=await fixture(t);
  const revealed=await post(f);
  assert.equal(revealed.status,200);
  assert.ok(revealed.html.includes(testToken));
  assert.equal(f.p.claimed,true);
  const again=await post(f);
  assert.equal(again.status,410);
  assert.ok(!again.html.includes(testToken));
});
test('Foreign origin with forged form challenge cannot redeem pairing URL',async t=>{
  const f=await fixture(t);
  const result=await post(f,{Origin:'https://attacker.invalid'},'invalid-csrf');
  assert.equal(result.status,403);
  assert.equal(f.p.claimed,false);
});
test('Invalid form value remains blocked regardless of browser cookie',async t=>{
  const f=await fixture(t);
  const forged=await post(f,{Cookie:'osrs_pair_csrf=wrong'},'invalid-csrf');
  assert.equal(forged.status,403);
  assert.ok(!forged.html.includes(testToken));
  assert.equal(f.p.claimed,false);
});
test('Mobile browser cross-site metadata is permitted only with the valid form challenge',async t=>{
  const f=await fixture(t);
  const result=await post(f,{'Sec-Fetch-Site':'cross-site', Origin:'null', Cookie:''});
  assert.equal(result.status,200);
  assert.equal(f.p.claimed,true);
});

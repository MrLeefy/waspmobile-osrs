'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createMobilePairPage}=require('./mobile-pair-page');
const http=require('node:http');
async function request(url,{method='GET',headers={},body=null}={}) {
  return new Promise((resolve,reject)=>{
    const req=http.request(url,{method,headers},res=>{
      const chunks=[];res.on('data',x=>chunks.push(x));res.on('end',()=>{
        const text=Buffer.concat(chunks).toString('utf8');
        resolve({status:res.statusCode,headers:{get:name=>{const v=res.headers[name.toLowerCase()];return Array.isArray(v)?v[0]:v;}},text:async()=>text});
      });
    });
    req.on('error',reject);req.end(body===null?undefined:body.toString());
  });
}
const token='private-pairing-token-012345678901234567890123';
const nonce='pairing-url-01234567890123456789012345678909876';
const origin='https://waspmobile.129.146.39.132.sslip.io';
async function pairFixture(t,{now=()=>Date.now()}={}){
  const page=createMobilePairPage({phoneToken:token,nonce,port:0,expiresAt:now()+30*60*1000,now});
  const info=await page.start();
  t.after(()=>page.close());
  const base='http://127.0.0.1:'+info.port;
  const headers={'Host':'waspmobile.129.146.39.132.sslip.io','X-Forwarded-Proto':'https'};
  return {page,base,headers,path:info.path};
}
function csrfFromHtml(str){
 const m=str.match(/name="csrf" value="([^"]+)"/);
 assert.ok(m,'expected csrf input');
 return m[1];
}
test('GET is safe for link previews: no persistent phone credential shown',async t=>{
 const f=await pairFixture(t);
 const r=await request(f.base+f.path,{headers:f.headers});
 assert.equal(r.status,200);
 assert.match(r.headers.get('cache-control'),/no-store/);
 assert.equal(r.headers.get('referrer-policy'),'no-referrer');
 assert.match(r.headers.get('content-security-policy'),/default-src 'none'/);
 assert.match(r.headers.get('set-cookie'),/HttpOnly; Secure; SameSite=Strict/);
 const body=await r.text();
 assert.equal(body.includes(token),false);
 assert.match(body,/Reveal my one-time pairing details/);
});
test('correct POST reveals token exactly once and includes copyable URL',async t=>{
 const f=await pairFixture(t);
 const landing=await request(f.base+f.path,{headers:f.headers});
 const body=await landing.text();
 const csrf=csrfFromHtml(body);
 const cookie=landing.headers.get('set-cookie').split(';')[0];
 const req=()=>request(f.base+f.path+'/reveal',{method:'POST',headers:{
  ...f.headers,'Origin':origin,'Content-Type':'application/x-www-form-urlencoded',
  'Cookie':cookie,'Sec-Fetch-Site':'same-origin'
 },body:new URLSearchParams({csrf})});
 const r=await req();assert.equal(r.status,200);
 const result=await r.text();
 assert.ok(result.includes(token),'revealed private phone pairing token');
 assert.ok(result.includes(origin),'shows phone HTTPS origin');
 assert.ok(result.includes('Copy pairing token'));
 assert.equal(f.page.claimed,true);
 const again=await req();assert.equal(again.status,410);
 assert.equal((await again.text()).includes(token),false);
});
test('invalid path, host and forged cross-origin form challenge are blocked',async t=>{
 const f=await pairFixture(t);
 assert.equal((await request(f.base+'/pair/not-valid',{headers:f.headers})).status,404);
 assert.equal((await request(f.base+f.path,{headers:{...f.headers,Host:'evil.example'}})).status,421);
 assert.equal((await request(f.base+f.path,{headers:{Host:f.headers.Host}})).status,421);
 const landing=await request(f.base+f.path,{headers:f.headers});
 const csrf=csrfFromHtml(await landing.text());
 const cookie=landing.headers.get('set-cookie').split(';')[0];
 const bad=await request(f.base+f.path+'/reveal',{method:'POST',headers:{
  ...f.headers,'Origin':'https://attacker.example',
  'Content-Type':'application/x-www-form-urlencoded','Cookie':cookie
 },body:new URLSearchParams({csrf:'forged-invalid-challenge'})});
 assert.equal(bad.status,403);
 assert.equal((await bad.text()).includes(token),false);
});
test('POST rejects an invalid form challenge without consuming the link',async t=>{
 const f=await pairFixture(t);
 const landing=await request(f.base+f.path,{headers:f.headers});
 const csrf=csrfFromHtml(await landing.text());
 const r=await request(f.base+f.path+'/reveal',{method:'POST',headers:{
  ...f.headers,'Origin':origin,'Content-Type':'application/x-www-form-urlencoded'
 },body:new URLSearchParams({csrf:'bad-value'})});
 assert.equal(r.status,403);
 assert.equal(f.page.claimed,false);
});
test('link refuses access after expiry, without ever revealing token',async t=>{
 let now=Date.now();
 const f=await pairFixture(t,{now:()=>now});
 now+=35*60*1000;
 const r=await request(f.base+f.path,{headers:f.headers});
 assert.equal(r.status,410);
 assert.equal((await r.text()).includes(token),false);
});
test('bad startup configuration refuses weak tokens or non-expiring claim URLs',()=>{
 assert.throws(()=>createMobilePairPage({phoneToken:'short',nonce,expiresAt:Date.now()+60000}),/Missing private/);
 assert.throws(()=>createMobilePairPage({phoneToken:token,nonce:'guessable',expiresAt:Date.now()+60000}),/one-time/);
 assert.throws(()=>createMobilePairPage({phoneToken:token,nonce,expiresAt:Date.now()+2*3600000}),/expire within/);
});

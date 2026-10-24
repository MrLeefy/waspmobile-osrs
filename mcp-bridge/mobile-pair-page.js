'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const {URLSearchParams} = require('node:url');

const HTTPS_ORIGIN = 'https://waspmobile.129.146.39.132.sslip.io';
const PHONE_ORIGIN = HTTPS_ORIGIN;
const MAX_AGE_MS = 60 * 60 * 1000;

function safeEqual(a,b) {
  if(typeof a !== 'string' || typeof b !== 'string') return false;
  const x=Buffer.from(a),y=Buffer.from(b);
  return x.length===y.length && crypto.timingSafeEqual(x,y);
}
function htmlEscape(s) {
  return String(s).replace(/[&<>"']/g, ch=>({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  })[ch]);
}
function createMobilePairPage({
  phoneToken,nonce,expiresAt,port=7789,now=()=>Date.now(),
  origin=HTTPS_ORIGIN,onClaim=()=>{}
}={}){
  if(typeof phoneToken!=='string'||!/^[A-Za-z0-9_-]{32,128}$/.test(phoneToken))
    throw new Error('Missing private WaspMobile token');
  if(typeof nonce!=='string'||!/^[A-Za-z0-9_-]{43,90}$/.test(nonce))
    throw new Error('Missing one-time pairing page credential');
  if(!Number.isSafeInteger(expiresAt)||expiresAt<=now()||expiresAt-now()>MAX_AGE_MS)
    throw new Error('Pairing page must expire within one hour');
  if(!Number.isInteger(port)||port<0||port>65535)throw new Error('Invalid port');
  const parsed=new URL(origin);
  if(parsed.protocol!=='https:'||parsed.pathname!=='/'||parsed.search||parsed.hash)
    throw new Error('The pairing origin must be HTTPS');
  const startPath='/pair/'+nonce;
  const buttonPath=startPath+'/reveal';
  let claimed=false, csrf=null, attempts=0, pageViews=0;
  const secretNonce=crypto.randomBytes(16).toString('base64url');
  function baseHeaders(res,type='text/html; charset=utf-8'){
    res.setHeader('Content-Type',type);
    res.setHeader('Cache-Control','no-store, max-age=0, private');
    res.setHeader('Pragma','no-cache');
    res.setHeader('X-Frame-Options','DENY');
    res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('X-Robots-Tag','noindex, nofollow, noarchive');
    res.setHeader('Permissions-Policy','camera=(), microphone=(), geolocation=()');
    res.setHeader('Content-Security-Policy',
      "default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; "+
      "style-src 'nonce-"+secretNonce+"'; script-src 'nonce-"+secretNonce+"'");
  }
  const style="<style nonce='"+secretNonce+"'>"+
    "body{background:#0b1220;color:#f8fafc;font:16px system-ui;margin:0;padding:24px;line-height:1.5}"+
    "main{max-width:560px;margin:35px auto;padding:24px;border:1px solid #334155;border-radius:16px;background:#111c2e}"+
    "h1{font-size:23px;margin:0 0 16px}"+
    "input{width:100%;box-sizing:border-box;margin:8px 0 18px;background:#1e293b;border:1px solid #64748b;"+
      "padding:13px;color:#e2e8f0;border-radius:8px;font-size:14px}"+
    "button{font-size:16px;border:0;background:#0284c7;color:white;padding:13px 20px;border-radius:9px;"+
      "font-weight:650;cursor:pointer;min-height:46px}"+
    "p{margin:12px 0}.note{color:#cbd5e1}.warn{color:#fbbf24}"+
    "label{font-weight:700;display:block}.tag{color:#38bdf8;font-weight:bold}"+
    "</style>";
  function layout(body) {
    return '<!doctype html><html lang="en"><head><meta charset="utf-8">'+
      '<meta name="viewport" content="width=device-width,initial-scale=1">'+
      '<meta name="referrer" content="no-referrer">'+
      '<title>WaspMobile OSRS — Secure Phone Pairing</title>'+style+
      '</head><body><main><p class="tag">OSRS / ONE PHONE / PRIVATE SETUP</p>'+body+
      '<p class="note">Keep this information private. Do not paste a pairing token into ChatGPT or Discord.</p>'+
      '</main></body></html>';
  }
  function reply(res,code,body){
    baseHeaders(res);
    res.writeHead(code);
    res.end(layout(body));
  }
  function closed(req,res){
    reply(res,410,'<h1>Pairing link is used or expired</h1>'+
      '<p class="note">For security this link only reveals the token once. A new link needs to be issued.</p>');
  }
  const server=http.createServer(async(req,res)=>{
    const ip=req.socket.remoteAddress;
    if(ip!=='127.0.0.1'&&ip!=='::1'&&ip!=='::ffff:127.0.0.1') {
      res.writeHead(403);return res.end();
    }
    if(req.headers.host!==parsed.host || req.headers['x-forwarded-proto']!=='https') {
      res.writeHead(421);return res.end();
    }
    const requestPath = typeof req.url === 'string' && req.url.length <= 512 ? req.url.split('?',1)[0] : '';
    if(requestPath!==startPath && requestPath!==buttonPath){
      res.writeHead(404);return res.end();
    }
    if(claimed||now()>=expiresAt)return closed(req,res);
    if(req.method==='GET' && requestPath===startPath){
      if(++pageViews>100)return reply(res,429,'<h1>Request limit reached</h1>');
      if(!csrf)csrf=crypto.randomBytes(24).toString('base64url');
      baseHeaders(res);
      res.setHeader('Set-Cookie','osrs_pair_csrf='+csrf+'; Path='+startPath+
        '; HttpOnly; Secure; SameSite=Strict; Max-Age=3600');
      const page='<h1>Connect your Samsung S23</h1>'+
        '<p class="note">The Oracle OSRS bridge is already running. This page shows the phone token '+
        'only after you tap Reveal; link previews cannot redeem it.</p>'+
        '<p class="warn">Open this page on the phone where WaspMobile is installed.</p>'+
        '<form method="POST" action="'+buttonPath+'">'+
        '<input type="hidden" name="csrf" value="'+csrf+'">'+
        '<button type="submit">Reveal my one-time pairing details</button></form>'+
        '<p class="note">This link will expire in less than one hour.</p>';
      res.writeHead(200);
      return res.end(layout(page));
    }
    if(req.method==='POST' && requestPath===buttonPath){
      if(++attempts>5)return reply(res,429,'<h1>Too many invalid attempts</h1>');
      const originHeader=req.headers.origin;
      if(originHeader!==origin || req.headers['content-type']?.split(';')[0]!=='application/x-www-form-urlencoded'
        || req.headers['sec-fetch-site']==='cross-site')return reply(res,403,'<h1>Unauthorized request</h1>');
      let raw='';
      for await(const chunk of req){
        raw+=chunk.toString('utf8');
        if(raw.length>512)return reply(res,413,'<h1>Invalid request size</h1>');
      }
      const params=new URLSearchParams(raw);
      const cookie=String(req.headers.cookie||'').split(';')
        .map(x=>x.trim()).find(x=>x.startsWith('osrs_pair_csrf='));
      const cookieValue=cookie?.slice('osrs_pair_csrf='.length);
      if(params.size!==1||!safeEqual(params.get('csrf'),csrf)||!safeEqual(cookieValue,csrf))
        return reply(res,403,'<h1>Invalid or expired pairing request</h1>');
      if(claimed||now()>=expiresAt)return closed(req,res);
      claimed=true;
      baseHeaders(res);
      res.setHeader('Set-Cookie','osrs_pair_csrf=; Path='+startPath+
        '; HttpOnly; Secure; SameSite=Strict; Max-Age=0');
      const page='<h1>Pair your WaspMobile OSRS app</h1>'+
        '<p class="note">Keep this page open while you paste both values into WaspMobile.</p>'+
        '<label for="origin">MCP Phone Bridge — Server URL</label>'+
        '<input readonly id="origin" value="'+htmlEscape(PHONE_ORIGIN)+'">'+
        '<button id="copyorigin" type="button">Copy server URL</button>'+
        '<label for="token">MCP Phone Bridge — Pairing token</label>'+
        '<input readonly id="token" type="text" value="'+htmlEscape(phoneToken)+'">'+
        '<button id="copytoken" type="button">Copy pairing token</button>'+
        '<p class="warn">Open WaspMobile → MCP Phone Bridge → paste the URL and token → Connect. '+
        'You must keep OSRS in a normal single window. This setup is OSRS-only, separate from 2009Scape.</p>'+
        '<p class="note">The token is displayed exactly once. This page expires if refreshed. '+
        'A copied token remains sensitive.</p>'+
        '<p id="copied" aria-live="polite"></p>'+
        '<script nonce="'+secretNonce+'">'+
        "async function copyFrom(id){const e=document.getElementById(id);"+
        "try{await navigator.clipboard.writeText(e.value);document.getElementById('copied').textContent='Copied';}"+
        "catch{e.focus();e.select();document.getElementById('copied').textContent='Select and copy the highlighted text';}}"+
        "document.getElementById('copyorigin').addEventListener('click',()=>copyFrom('origin'));"+
        "document.getElementById('copytoken').addEventListener('click',()=>copyFrom('token'));"+
        '</script>';
      res.writeHead(200);
      res.end(layout(page));
      try{onClaim()}catch{}
      return;
    }
    res.writeHead(405);res.end();
  });
  const timeoutMs=Math.max(1,expiresAt-now());
  const deadline=setTimeout(()=>{
    server.close(()=>{});
  },timeoutMs);
  deadline.unref();
  function start(){
    return new Promise((resolve,reject)=>{
      server.once('error',reject);
      server.listen(port,'127.0.0.1',()=>resolve({
        port:server.address().port,path:startPath,expiresAt
      }));
    });
  }
  function close(){clearTimeout(deadline);return new Promise(resolve=>{
    if(!server.listening)return resolve();
    server.close(resolve);
  })}
  return {server,start,close,path:startPath,get claimed(){return claimed}};
}
async function main(){
  const env=process.env;
  const portal=createMobilePairPage({
    phoneToken:env.WASP_DEVICE_TOKEN,nonce:env.WASP_PAIR_LINK_NONCE,
    expiresAt:Number(env.WASP_PAIR_EXPIRES_AT),port:Number(env.WASP_PAIR_HTTP_PORT||7789),
    onClaim:()=>setTimeout(()=>process.exit(0),120000).unref()
  });
  const ready=await portal.start();
  process.stderr.write('One-time WaspMobile pairing portal listening on 127.0.0.1:'+ready.port+
    '; expires at '+new Date(ready.expiresAt).toISOString()+'\n');
}
if(require.main===module)main().catch(e=>{
  process.stderr.write('WaspMobile pairing portal failed: '+e.message+'\n');
  process.exitCode=1;
});
module.exports={createMobilePairPage};

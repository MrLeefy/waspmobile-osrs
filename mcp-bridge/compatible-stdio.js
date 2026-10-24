'use strict';
const {createMcpHandler} = require('@modelcontextprotocol/server');
const {StringDecoder} = require('node:string_decoder');
const LEGACY = '2025-11-25';
const MODERN = '2026-07-28';
const MAX_BYTES = 4 * 1024 * 1024;
const PRIVILEGED = new Set(['execute','start_job','read_file','write_file','brain_read','brain_write','job_status']);

function requestVersion(message, preferred = LEGACY) {
  const meta = message?.params?._meta || message?._meta || {};
  const explicit = meta['io.modelcontextprotocol/protocolVersion'];
  if (explicit !== undefined) return explicit;
  if (message?.method === 'initialize') return message.params?.protocolVersion || LEGACY;
  if (message?.method === 'server/discover' || message?.method?.startsWith('events/')) return MODERN;
  return preferred;
}
function responseMessages(text, type) {
  if (!text) return [];
  if (Buffer.byteLength(text) > MAX_BYTES) throw new Error('MCP response exceeds limit');
  if (type.includes('text/event-stream')) return text.split(/\r?\n\r?\n/).flatMap(block => {
    const data = block.split(/\r?\n/).filter(x => x.startsWith('data:')).map(x => x.slice(5).trimStart()).join('\n');
    return data ? [JSON.parse(data)] : [];
  });
  return [JSON.parse(text)];
}

/** Per-request protocol compatibility. Never retry an input or downgrade event-session privileges. */
function serveCompatibleStdio(makeServer, {input = process.stdin, output = process.stdout, onerror = () => {}} = {}) {
  const options = {legacy:'stateless', responseMode:'auto', maxRequestBodySize:MAX_BYTES};
  const handlers = {
    [LEGACY]: createMcpHandler(ctx => makeServer({...ctx, era:'legacy'}), options),
    [MODERN]: createMcpHandler(ctx => makeServer({...ctx, era:'modern'}), options)
  };
  const active = new Map(), decoder = new StringDecoder('utf8');
  let preferred = LEGACY, eventSession = false, buffered = '', closed = false, outputQueue = Promise.resolve();
  const write = value => {
    outputQueue = outputQueue.then(() => new Promise((resolve, reject) => {
      output.write(JSON.stringify(value) + '\n', error => error ? reject(error) : resolve());
    })).catch(onerror);
    return outputQueue;
  };
  const errorResult = (id, code, message, data) => write({jsonrpc:'2.0', id:id ?? null,
    error:{code,message,...(data ? {data} : {})}});
  async function dispatch(message) {
    if (closed) return;
    const id = message?.id;
    if (!message || message.jsonrpc !== '2.0' || typeof message.method !== 'string') {
      await errorResult(id,-32600,'Invalid JSON-RPC request'); return;
    }
    if (message.method === 'notifications/cancelled') {
      active.get(message.params?.requestId)?.abort(); return;
    }
    const version = requestVersion(message,preferred);
    if (![LEGACY,MODERN].includes(version)) {
      if (id !== undefined) await errorResult(id,-32022,'Unsupported protocol version',{requested:version,supported:[LEGACY,MODERN]});
      return;
    }
    if (message.method === 'server/discover' || message.method.startsWith('events/')) eventSession = true;
    if (eventSession && message.method === 'tools/call' && PRIVILEGED.has(message.params?.name)) {
      await errorResult(id,-32602,'Privileged Oracle operations remain blocked in an event-enabled connection'); return;
    }
    if (message.method === 'initialize' || message.method === 'server/discover') preferred = version;
    if (id !== undefined && (active.has(id) || active.size >= 12)) {
      await errorResult(id,-32000,'Duplicate request ID or too many active requests'); return;
    }
    const controller = new AbortController();
    if (id !== undefined) active.set(id,controller);
    try {
      // MCP2's reserved routing metadata is not part of the old protocol body.
      // Translate only this routing field; all actual tool arguments stay intact.
      const wire = structuredClone(message);
      if (version === LEGACY) {
        if (wire.params?._meta) delete wire.params._meta['io.modelcontextprotocol/protocolVersion'];
        if (wire._meta) delete wire._meta['io.modelcontextprotocol/protocolVersion'];
      }
      const request = new Request('http://127.0.0.1/mcp',{method:'POST',headers:{
        'Content-Type':'application/json','Accept':'application/json, text/event-stream',
        'MCP-Protocol-Version':version,
        'Mcp-Method':message.method,
        ...(message.method === 'tools/call' ? {'Mcp-Name':message.params.name} : {})
      },body:JSON.stringify(wire),signal:controller.signal});
      const response = await handlers[version].fetch(request);
      const payload = await response.text();
      const messages = responseMessages(payload,response.headers.get('content-type') || 'application/json');
      for (const result of messages) {
        // The wrapper accepts BOTH versions on the same connection; advertise
        // that contract instead of inheriting the modern-only HTTP projection.
        if (message.method === 'server/discover' && result.result) result.result.supportedVersions = [MODERN,LEGACY];
        await write(result);
      }
      if (!messages.length && id !== undefined) await errorResult(id,-32603,'Empty MCP response; action not retried');
    } catch (error) {
      if (id !== undefined && !controller.signal.aborted) await errorResult(id,-32603,'MCP request failed without retry');
      onerror(error);
    } finally { if (id !== undefined) active.delete(id); }
  }
  const onData = chunk => {
    buffered += typeof chunk === 'string' ? chunk : decoder.write(chunk);
    if (Buffer.byteLength(buffered) > MAX_BYTES) {
      buffered = ''; void errorResult(null,-32600,'MCP input exceeds limit'); return;
    }
    let end;
    while ((end = buffered.indexOf('\n')) >= 0) {
      const line = buffered.slice(0,end).trim(); buffered = buffered.slice(end+1);
      if (!line) continue;
      try { void dispatch(JSON.parse(line)); }
      catch { void errorResult(null,-32700,'Invalid JSON'); }
    }
  };
  input.on('data',onData);
  async function close() {
    if (closed) return; closed = true;
    input.removeListener('data',onData);
    for (const controller of active.values()) controller.abort();
    await Promise.all(Object.values(handlers).map(handler => handler.close()));
    await outputQueue;
  }
  return {close,dispatch};
}
module.exports = {serveCompatibleStdio,requestVersion,responseMessages,LEGACY,MODERN};

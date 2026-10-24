'use strict';
const {createMcpHandler} = require('@modelcontextprotocol/server');
const LEGACY = '2025-11-25';
const MODERN = '2026-07-28';
const MAX_BYTES = 4 * 1024 * 1024;

/** Route each explicit protocol version independently; discovery must not pin later legacy tool calls. */
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

/** Use the official per-request handler in memory. No network port, key, or public HTTP endpoint is opened. */
function serveCompatibleStdio(makeServer, {input = process.stdin, output = process.stdout, onerror = () => {}} = {}) {
  const handler = createMcpHandler(makeServer, {legacy: 'stateless', responseMode: 'auto', maxRequestBodySize: MAX_BYTES});
  const active = new Map();
  let preferred = LEGACY, buffered = '', closed = false, outputQueue = Promise.resolve();
  const write = value => {
    outputQueue = outputQueue.then(() => new Promise((resolve, reject) => {
      output.write(JSON.stringify(value) + '\n', error => error ? reject(error) : resolve());
    })).catch(onerror);
    return outputQueue;
  };
  const errorResult = (id, code, message, data) => write({jsonrpc: '2.0', id: id ?? null,
    error: {code, message, ...(data ? {data} : {})}});
  async function dispatch(message) {
    const id = message?.id;
    if (!message || message.jsonrpc !== '2.0' || typeof message.method !== 'string') {
      if (id !== undefined) await errorResult(id, -32600, 'Invalid JSON-RPC request');
      return;
    }
    if (message.method === 'notifications/cancelled') {
      active.get(message.params?.requestId)?.abort(); return;
    }
    const version = requestVersion(message, preferred);
    if (![LEGACY, MODERN].includes(version)) {
      if (id !== undefined) await errorResult(id, -32022, 'Unsupported protocol version', {requested: version, supported: [LEGACY, MODERN]});
      return;
    }
    if (message.method === 'initialize' || message.method === 'server/discover') preferred = version;
    if (id !== undefined && (active.has(id) || active.size >= 12)) {
      await errorResult(id, -32000, 'Duplicate request ID or too many active requests'); return;
    }
    const controller = new AbortController();
    if (id !== undefined) active.set(id, controller);
    try {
      const request = new Request('http://127.0.0.1/mcp', {method: 'POST', headers: {
        'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
        'MCP-Protocol-Version': version
      }, body: JSON.stringify(message), signal: controller.signal});
      const response = await handler.fetch(request);
      const payload = await response.text();
      for (const result of responseMessages(payload, response.headers.get('content-type') || 'application/json')) await write(result);
    } catch (error) {
      if (id !== undefined && !controller.signal.aborted) await errorResult(id, -32603, 'MCP request failed without retry');
      onerror(error);
    } finally { if (id !== undefined) active.delete(id); }
  }
  const onData = chunk => {
    buffered += chunk.toString('utf8');
    if (Buffer.byteLength(buffered) > MAX_BYTES) {
      buffered = ''; void errorResult(null, -32600, 'MCP input exceeds limit'); return;
    }
    let end;
    while ((end = buffered.indexOf('\n')) >= 0) {
      const line = buffered.slice(0, end).trim(); buffered = buffered.slice(end + 1);
      if (!line) continue;
      try { void dispatch(JSON.parse(line)); }
      catch { void errorResult(null, -32700, 'Invalid JSON'); }
    }
  };
  input.on('data', onData);
  async function close() {
    if (closed) return; closed = true;
    input.removeListener('data', onData);
    for (const controller of active.values()) controller.abort();
    await handler.close(); await outputQueue;
  }
  return {close, dispatch};
}
module.exports = {serveCompatibleStdio, requestVersion, responseMessages, LEGACY, MODERN};

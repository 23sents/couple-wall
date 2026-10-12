'use strict';
/**
 * couple-wall relay: tiny publish/subscribe server (ntfy-style wire format, zero dependencies).
 *
 * The relay only ever sees ciphertext: the app seals every message with AES-256-GCM using a key derived from the
 * couple's secret code. On top of that the relay protects itself:
 *   - APP_KEY            shared by the apps; without it nobody can use (and bill) this server
 *   - per-pair token     first use of a topic binds it to a token derived from the secret code (hash stored only),
 *                        so knowing a topic name is not enough to read or write it
 *   - strict validation  topic format, base64url-only bodies, 4 KB limit, no other routes
 *   - rate limits        per IP, per topic, plus lock-out after repeated auth failures
 *   - resource caps      topics, subscribers, messages per topic, message lifetime
 */
const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const TOPIC_RE = /^cw[0-9a-f]{40}$/;
const TOKEN_RE = /^[0-9a-f]{64}$/;
const BODY_RE = /^[A-Za-z0-9_-]+$/;
const ID_RE = /^[0-9a-z]{1,12}\.\d{1,12}$/;

function loadConfig(env = process.env) {
  const num = (k, d) => (env[k] !== undefined && env[k] !== '' && Number.isFinite(Number(env[k])) ? Number(env[k]) : d);
  return {
    port: num('PORT', 3000),
    appKey: env.APP_KEY || '',
    allowOpen: env.ALLOW_OPEN === '1',
    dataDir: env.DATA_DIR || '',
    maxBody: num('MAX_BODY', 4096),
    msgTtlMs: num('MSG_TTL_HOURS', 72) * 3600_000,
    maxMsgsPerTopic: num('MAX_MSGS_PER_TOPIC', 500),
    maxTopics: num('MAX_TOPICS', 2000),
    maxSubsPerTopic: num('MAX_SUBS_PER_TOPIC', 4),
    maxSubsTotal: num('MAX_SUBS_TOTAL', 600),
    ipPerMin: num('IP_PER_MIN', 240),
    topicPublishPerMin: num('TOPIC_PUBLISH_PER_MIN', 90),
    subscribePerMin: num('SUBSCRIBE_PER_MIN', 30),
    authFailLimit: num('AUTH_FAIL_LIMIT', 8),
    authLockMs: num('AUTH_LOCK_MIN', 15) * 60_000,
    keepaliveMs: num('KEEPALIVE_SEC', 30) * 1000,
    topicIdleMs: num('TOPIC_IDLE_DAYS', 14) * 86400_000,
    trustProxy: env.TRUST_PROXY !== '0',
  };
}

const sha256hex = (s) => crypto.createHash('sha256').update(s).digest('hex');

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

/** Fixed-window counter map with periodic cleanup. */
class Counter {
  constructor(windowMs) {
    this.windowMs = windowMs;
    this.map = new Map();
  }
  hit(key, now = Date.now()) {
    const e = this.map.get(key);
    if (!e || now - e.start >= this.windowMs) {
      this.map.set(key, { start: now, n: 1 });
      return 1;
    }
    return ++e.n;
  }
  sweep(now = Date.now()) {
    for (const [k, e] of this.map) if (now - e.start >= this.windowMs) this.map.delete(k);
  }
}

function createRelay(config) {
  const cfg = config;
  if (!cfg.appKey && !cfg.allowOpen) {
    throw new Error('APP_KEY is not set. Set a long random APP_KEY (or ALLOW_OPEN=1 for local testing only).');
  }
  if (cfg.appKey && cfg.appKey.length < 16) throw new Error('APP_KEY must be at least 16 characters.');

  const epoch = Date.now().toString(36);
  /** topic -> { tokenHash, epoch, seq, msgs: [{seq,time,text}], touched } */
  const topics = new Map();
  /** topic -> Set<ServerResponse> */
  const subs = new Map();
  let subsTotal = 0;
  let dirty = false;

  const ipRate = new Counter(60_000);
  const subRate = new Counter(60_000);
  const topicRate = new Counter(60_000);
  const authFails = new Map(); // ip -> { n, first, lockedUntil }

  const statePath = cfg.dataDir ? path.join(cfg.dataDir, 'state.json') : '';
  let stateEpoch = epoch;

  function loadState() {
    if (!statePath) return;
    try {
      const raw = JSON.parse(fs.readFileSync(statePath, 'utf8'));
      if (typeof raw.epoch === 'string' && raw.topics && typeof raw.topics === 'object') {
        stateEpoch = raw.epoch;
        for (const [name, t] of Object.entries(raw.topics)) {
          if (!TOPIC_RE.test(name) || !TOKEN_RE.test(t.tokenHash || '')) continue;
          topics.set(name, {
            tokenHash: t.tokenHash,
            seq: Number(t.seq) || 0,
            msgs: Array.isArray(t.msgs) ? t.msgs.filter((m) => m && BODY_RE.test(m.text || '')) : [],
            touched: Number(t.touched) || Date.now(),
          });
        }
      }
    } catch {
      /* first start or unreadable file: start empty */
    }
  }

  function saveState() {
    if (!statePath || !dirty) return;
    dirty = false;
    try {
      fs.mkdirSync(cfg.dataDir, { recursive: true });
      const out = { epoch: stateEpoch, topics: Object.fromEntries(topics) };
      const tmp = statePath + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(out), { mode: 0o600 });
      fs.renameSync(tmp, statePath);
    } catch (e) {
      dirty = true;
      console.error('state save failed:', e.code || e.message);
    }
  }

  const makeId = (seq) => `${stateEpoch}.${seq}`;

  function clientIp(req) {
    if (cfg.trustProxy) {
      const xff = req.headers['x-forwarded-for'];
      if (typeof xff === 'string' && xff) {
        const parts = xff.split(',').map((s) => s.trim()).filter(Boolean);
        // rightmost entry = the address our trusted edge proxy saw; earlier entries are client-controlled
        if (parts.length) return parts[parts.length - 1].slice(0, 64);
      }
    }
    return req.socket.remoteAddress || 'unknown';
  }

  function send(res, status, body, extra = {}) {
    if (res.headersSent) return;
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    res.writeHead(status, {
      'Content-Type': typeof body === 'string' ? 'text/plain; charset=utf-8' : 'application/json',
      'Content-Length': Buffer.byteLength(text),
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
      'Content-Security-Policy': "default-src 'none'",
      ...(status >= 400 ? { Connection: 'close' } : {}),
      ...extra,
    });
    res.end(text);
  }

  function lockedOut(ip, now) {
    const f = authFails.get(ip);
    return !!(f && f.lockedUntil && f.lockedUntil > now);
  }

  function authFailed(ip, now) {
    let f = authFails.get(ip);
    if (!f || now - f.first > 10 * 60_000) f = { n: 0, first: now, lockedUntil: 0 };
    f.n++;
    if (f.n >= cfg.authFailLimit) f.lockedUntil = now + cfg.authLockMs;
    authFails.set(ip, f);
  }

  function getOrCreateTopic(name, token, now) {
    const tokenHash = sha256hex(token);
    let t = topics.get(name);
    if (!t) {
      if (topics.size >= cfg.maxTopics) return { error: 507 };
      t = { tokenHash, seq: 0, msgs: [], touched: now };
      topics.set(name, t);
      dirty = true;
    } else if (!safeEqual(t.tokenHash, tokenHash)) {
      return { error: 401 };
    }
    t.touched = now;
    return { topic: t };
  }

  function readBody(req, res, cb) {
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > cfg.maxBody) {
      send(res, 413, 'payload too large');
      req.destroy();
      return;
    }
    const chunks = [];
    let size = 0;
    let done = false;
    req.on('data', (c) => {
      if (done) return;
      size += c.length;
      if (size > cfg.maxBody) {
        done = true;
        send(res, 413, 'payload too large');
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!done) {
        done = true;
        cb(Buffer.concat(chunks).toString('utf8'));
      }
    });
    req.on('error', () => {
      done = true;
    });
  }

  function writeLine(res, obj) {
    try {
      res.write(JSON.stringify(obj) + '\n');
      return true;
    } catch {
      return false;
    }
  }

  function msgJson(name, m) {
    return { id: makeId(m.seq), time: Math.floor(m.time / 1000), event: 'message', topic: name, message: m.text };
  }

  function handlePublish(name, req, res, now) {
    readBody(req, res, (raw) => {
      const text = raw.trim();
      if (!text || !BODY_RE.test(text)) return send(res, 400, 'bad message');
      if (topicRate.hit(name, now) > cfg.topicPublishPerMin) return send(res, 429, 'slow down', { 'Retry-After': '30' });
      const r = getOrCreateTopic(name, req._token, now);
      if (r.error) return send(res, r.error === 401 ? 401 : 507, r.error === 401 ? 'unauthorized' : 'server full');
      const t = r.topic;
      const m = { seq: ++t.seq, time: now, text };
      t.msgs.push(m);
      if (t.msgs.length > cfg.maxMsgsPerTopic) t.msgs.splice(0, t.msgs.length - cfg.maxMsgsPerTopic);
      dirty = true;
      const set = subs.get(name);
      if (set) for (const s of set) writeLine(s, msgJson(name, m));
      send(res, 200, { id: makeId(m.seq), time: Math.floor(now / 1000), event: 'message', topic: name });
    });
  }

  function handleSubscribe(name, url, req, res, now) {
    if (subRate.hit(clientIp(req), now) > cfg.subscribePerMin) return send(res, 429, 'slow down', { 'Retry-After': '30' });
    const r = getOrCreateTopic(name, req._token, now);
    if (r.error) return send(res, r.error === 401 ? 401 : 507, r.error === 401 ? 'unauthorized' : 'server full');
    let set = subs.get(name);
    if (!set) subs.set(name, (set = new Set()));
    if (set.size >= cfg.maxSubsPerTopic || subsTotal >= cfg.maxSubsTotal) return send(res, 429, 'too many connections');

    const since = url.searchParams.get('since') || 'all';
    let after = 0;
    if (since !== 'all' && ID_RE.test(since)) {
      const [ep, n] = since.split('.');
      if (ep === stateEpoch) after = Number(n);
    }

    res.writeHead(200, {
      'Content-Type': 'application/x-ndjson',
      'Cache-Control': 'no-store, no-transform',
      'X-Accel-Buffering': 'no',
      'X-Content-Type-Options': 'nosniff',
      Connection: 'keep-alive',
    });
    req.socket.setNoDelay(true);
    req.socket.setTimeout(0);
    set.add(res);
    subsTotal++;
    writeLine(res, { id: makeId(0), time: Math.floor(now / 1000), event: 'open', topic: name });
    for (const m of r.topic.msgs) if (m.seq > after) writeLine(res, msgJson(name, m));

    const ka = setInterval(() => {
      if (!writeLine(res, { event: 'keepalive', time: Math.floor(Date.now() / 1000), topic: name })) cleanup();
    }, cfg.keepaliveMs);
    let cleaned = false;
    function cleanup() {
      if (cleaned) return;
      cleaned = true;
      clearInterval(ka);
      set.delete(res);
      subsTotal--;
      if (!set.size) subs.delete(name);
      try { res.end(); } catch { /* already closed */ }
    }
    req.on('close', cleanup);
    res.on('close', cleanup);
    res.on('error', cleanup);
  }

  const server = http.createServer((req, res) => {
    const now = Date.now();
    const ip = clientIp(req);
    let url;
    try {
      url = new URL(req.url, 'http://relay.local');
    } catch {
      return send(res, 400, 'bad request');
    }

    if (url.pathname === '/health' && req.method === 'GET') return send(res, 200, 'ok');

    const proto = req.headers['x-forwarded-proto'];
    if (cfg.trustProxy && typeof proto === 'string' && proto.split(',')[0].trim() !== 'https') {
      return send(res, 426, 'https required');
    }

    if (lockedOut(ip, now)) return send(res, 429, 'try later', { 'Retry-After': String(Math.ceil(cfg.authLockMs / 1000)) });
    if (ipRate.hit(ip, now) > cfg.ipPerMin) return send(res, 429, 'slow down', { 'Retry-After': '30' });

    const m = /^\/(cw[0-9a-f]{40})(\/json)?$/.exec(url.pathname);
    const wantPost = req.method === 'POST' && m && !m[2];
    const wantSub = req.method === 'GET' && m && m[2];
    if (!wantPost && !wantSub) return send(res, 404, 'not found');

    if (cfg.appKey) {
      const k = req.headers['x-app-key'];
      if (typeof k !== 'string' || !safeEqual(k, cfg.appKey)) {
        authFailed(ip, now);
        return send(res, 401, 'unauthorized');
      }
    }
    const token = req.headers['x-pair-token'];
    if (typeof token !== 'string' || !TOKEN_RE.test(token)) {
      authFailed(ip, now);
      return send(res, 401, 'unauthorized');
    }
    req._token = token;

    // a mismatching pair token counts as a failed attempt too
    const name = m[1];
    const t = topics.get(name);
    if (t && !safeEqual(t.tokenHash, sha256hex(token))) {
      authFailed(ip, now);
      return send(res, 401, 'unauthorized');
    }

    if (wantPost) return handlePublish(name, req, res, now);
    return handleSubscribe(name, url, req, res, now);
  });

  server.headersTimeout = 15_000;
  server.requestTimeout = 30_000;
  server.maxHeadersCount = 40;
  server.keepAliveTimeout = 65_000;

  function gc() {
    const now = Date.now();
    ipRate.sweep(now);
    subRate.sweep(now);
    topicRate.sweep(now);
    for (const [ip, f] of authFails) if (now - f.first > 10 * 60_000 && f.lockedUntil < now) authFails.delete(ip);
    for (const [name, t] of topics) {
      const before = t.msgs.length;
      t.msgs = t.msgs.filter((m) => now - m.time < cfg.msgTtlMs);
      if (t.msgs.length !== before) dirty = true;
      if (!subs.has(name) && now - t.touched > cfg.topicIdleMs) {
        topics.delete(name);
        dirty = true;
      }
    }
  }

  let timers = [];
  function start() {
    loadState();
    timers = [setInterval(gc, 10 * 60_000), setInterval(saveState, 10_000)];
    timers.forEach((t) => t.unref());
    return new Promise((resolve) => server.listen(cfg.port, '0.0.0.0', () => resolve(server.address().port)));
  }

  function stop() {
    timers.forEach(clearInterval);
    saveState();
    for (const set of subs.values()) for (const r of set) { try { r.end(); } catch { /* closed */ } }
    return new Promise((resolve) => {
      server.close(() => resolve());
      server.closeAllConnections?.();
    });
  }

  return { server, start, stop, _topics: topics, _gc: gc };
}

module.exports = { createRelay, loadConfig };

if (require.main === module) {
  let relay;
  try {
    relay = createRelay(loadConfig());
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
  relay.start().then((port) => console.log(`couple-wall relay listening on :${port}`));
  const bye = () => relay.stop().then(() => process.exit(0));
  process.on('SIGTERM', bye);
  process.on('SIGINT', bye);
}

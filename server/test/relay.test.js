'use strict';
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const crypto = require('node:crypto');
const { createRelay, loadConfig } = require('../server.js');

const APP_KEY = 'test-app-key-0123456789abcdef';
const topicOf = (s) => 'cw' + crypto.createHash('sha256').update('t' + s).digest('hex').slice(0, 40);
const tokenOf = (s) => crypto.createHash('sha256').update('k' + s).digest('hex');

let relay, port;
before(async () => {
  relay = createRelay({ ...loadConfig({}), appKey: APP_KEY, port: 0, keepaliveMs: 200, ipPerMin: 100000, authFailLimit: 1000, topicPublishPerMin: 5, trustProxy: false });
  port = await relay.start();
});
after(() => relay.stop());

function req(method, path, { headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const r = http.request({ host: '127.0.0.1', port, method, path, headers }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    r.on('error', reject);
    if (body !== undefined) r.write(body);
    r.end();
  });
}
const auth = (s, key = APP_KEY) => ({ 'x-app-key': key, 'x-pair-token': tokenOf(s) });

test('refuses to start without APP_KEY', () => {
  assert.throws(() => createRelay({ ...loadConfig({}), appKey: '' }));
});

test('health is public', async () => {
  assert.equal((await req('GET', '/health')).status, 200);
});

test('unknown routes 404, no auth leak', async () => {
  assert.equal((await req('GET', '/')).status, 404);
  assert.equal((await req('GET', '/admin')).status, 404);
  assert.equal((await req('POST', '/cwZZZ')).status, 404);
});

test('missing or wrong app key is rejected', async () => {
  const t = topicOf('a');
  assert.equal((await req('POST', '/' + t, { headers: { 'x-pair-token': tokenOf('a') }, body: 'abc' })).status, 401);
  assert.equal((await req('POST', '/' + t, { headers: auth('a', 'wrong-wrong-wrong-wrong'), body: 'abc' })).status, 401);
});

test('missing or malformed pair token is rejected', async () => {
  const t = topicOf('b');
  assert.equal((await req('POST', '/' + t, { headers: { 'x-app-key': APP_KEY }, body: 'abc' })).status, 401);
  assert.equal((await req('POST', '/' + t, { headers: { 'x-app-key': APP_KEY, 'x-pair-token': 'zz' }, body: 'abc' })).status, 401);
});

test('publish accepts base64url only and enforces size', async () => {
  const t = topicOf('c');
  assert.equal((await req('POST', '/' + t, { headers: auth('c'), body: 'AbC-_123' })).status, 200);
  assert.equal((await req('POST', '/' + t, { headers: auth('c'), body: '<script>' })).status, 400);
  assert.equal((await req('POST', '/' + t, { headers: auth('c'), body: '' })).status, 400);
  assert.equal((await req('POST', '/' + t, { headers: auth('c'), body: 'A'.repeat(5000) })).status, 413);
});

test('topic is bound to the first pair token', async () => {
  const t = topicOf('d');
  assert.equal((await req('POST', '/' + t, { headers: auth('d'), body: 'first' })).status, 200);
  assert.equal((await req('POST', '/' + t, { headers: auth('intruder'), body: 'evil' })).status, 401);
  assert.equal((await req('GET', '/' + t + '/json', { headers: auth('intruder') })).status, 401);
});

function subscribe(topic, since, headers, onLine) {
  return new Promise((resolve, reject) => {
    const r = http.request({ host: '127.0.0.1', port, method: 'GET', path: `/${topic}/json?since=${since}`, headers }, (res) => {
      if (res.statusCode !== 200) { res.resume(); return resolve({ status: res.statusCode, close() {} }); }
      let buf = '';
      res.on('data', (c) => {
        buf += c;
        let i;
        while ((i = buf.indexOf('\n')) >= 0) { onLine(JSON.parse(buf.slice(0, i))); buf = buf.slice(i + 1); }
      });
      resolve({ status: 200, close: () => r.destroy() });
    });
    r.on('error', () => {});
    r.end();
  });
}
const until = async (fn, ms = 3000) => { const t = Date.now(); while (!fn()) { if (Date.now() - t > ms) throw new Error('timeout'); await new Promise((r) => setTimeout(r, 20)); } };

test('subscriber gets open, backlog, live messages and keepalive; since works', async () => {
  const t = topicOf('e');
  await req('POST', '/' + t, { headers: auth('e'), body: 'one' });
  await req('POST', '/' + t, { headers: auth('e'), body: 'two' });
  const lines = [];
  const s = await subscribe(t, 'all', auth('e'), (l) => lines.push(l));
  assert.equal(s.status, 200);
  await until(() => lines.filter((l) => l.event === 'message').length === 2);
  assert.equal(lines[0].event, 'open');
  await req('POST', '/' + t, { headers: auth('e'), body: 'three' });
  await until(() => lines.some((l) => l.message === 'three'));
  await until(() => lines.some((l) => l.event === 'keepalive'));
  const lastId = lines.filter((l) => l.event === 'message')[1].id;
  s.close();

  const again = [];
  const s2 = await subscribe(t, lastId, auth('e'), (l) => again.push(l));
  await until(() => again.some((l) => l.message === 'three'));
  assert.deepEqual(again.filter((l) => l.event === 'message').map((l) => l.message), ['three']);
  s2.close();
});

test('foreign epoch / garbage since falls back to full history', async () => {
  const t = topicOf('f');
  await req('POST', '/' + t, { headers: auth('f'), body: 'm1' });
  const lines = [];
  const s = await subscribe(t, 'zzzz.99', auth('f'), (l) => lines.push(l));
  await until(() => lines.some((l) => l.message === 'm1'));
  s.close();
});

test('per-topic publish rate limit', async () => {
  const t = topicOf('g');
  const codes = [];
  for (let i = 0; i < 8; i++) codes.push((await req('POST', '/' + t, { headers: auth('g'), body: 'x' + i })).status);
  assert.ok(codes.includes(429), codes.join());
  assert.equal(codes[0], 200);
});

test('gc drops expired messages', async () => {
  const t = topicOf('h');
  await req('POST', '/' + t, { headers: auth('h'), body: 'old' });
  relay._topics.get(t).msgs[0].time = Date.now() - 10 * 24 * 3600_000;
  relay._gc();
  assert.equal(relay._topics.get(t).msgs.length, 0);
});

test('lock-out after repeated auth failures', async () => {
  const r2 = createRelay({ ...loadConfig({}), appKey: APP_KEY, port: 0, authFailLimit: 3, trustProxy: false });
  const p2 = await r2.start();
  const hit = (key) => new Promise((resolve) => {
    http.request({ host: '127.0.0.1', port: p2, method: 'POST', path: '/' + topicOf('z'), headers: { 'x-app-key': key, 'x-pair-token': tokenOf('z') } }, (res) => { res.resume(); resolve(res.statusCode); }).end('a');
  });
  const out = [];
  for (let i = 0; i < 4; i++) out.push(await hit('bad-bad-bad-bad-bad'));
  assert.deepEqual(out, [401, 401, 401, 429]);
  assert.equal(await hit(APP_KEY), 429); // even the right key is blocked while locked
  await r2.stop();
});

test('https enforced behind a proxy', async () => {
  const r3 = createRelay({ ...loadConfig({}), appKey: APP_KEY, port: 0, trustProxy: true });
  const p3 = await r3.start();
  const status = await new Promise((resolve) => {
    http.request({ host: '127.0.0.1', port: p3, method: 'POST', path: '/' + topicOf('p'), headers: { 'x-forwarded-proto': 'http', 'x-app-key': APP_KEY, 'x-pair-token': tokenOf('p') } }, (res) => { res.resume(); resolve(res.statusCode); }).end('a');
  });
  assert.equal(status, 426);
  await r3.stop();
});

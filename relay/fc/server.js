/**
 * Aerial 远程中继（零依赖 Node 实现）。
 *
 * 架构：电视和手机都不直接互通，两边各自出站连到本服务，中继只做请求/响应转发。
 * 传输用 HTTP 长轮询（手机 POST 请求排队 -> 电视 poll 取走 -> 电视回传响应 -> 手机取响应），
 * 不引入 WebSocket 依赖，部署到任意 Node 免费托管即可。
 *
 * 鉴权：每台电视首次启动生成 deviceId + secret，hello 时登记；手机必须带相同 secret
 * 才能配对和下发请求。中继内存保存，不落盘；服务重启后电视重新 hello 即可。
 *
 * 消息体（JSON），二进制字段统一 base64：
 *   bodyB64 / headers 均为对象；rid 由手机端生成。
 *
 * 路由见文末。MVP 只转发控制类小请求；整机备份、大批量照片走局域网，不走中继。
 */

'use strict';

const http = require('http');
const crypto = require('crypto');

// 阿里云 FC 自定义运行时固定监听 9000；本地调试可用 PORT 覆盖。
const PORT = parseInt(process.env.PORT || '9000', 10);
const MAX_BODY = 8 * 1024 * 1024; // 8MB，超出直接拒绝
const ONLINE_MS = 60_000;          // 电视超过这个时间没心跳视为离线
const STALE_DEVICE_MS = 10 * 60_000;
const RES_TTL_MS = 5 * 60_000;
const QUEUE_TTL_MS = 2 * 60_000;
const POLL_HOLD_MS = 20_000;       // 长轮询挂起时长

/** id -> { secret, name, lastSeen, queue: [], waiters: [], resps: Map } */
const devices = new Map();

function now() { return Date.now(); }

function prune() {
  const cutoff = now();
  for (const [id, d] of devices) {
    if (cutoff - d.lastSeen > STALE_DEVICE_MS) {
      for (const w of d.waiters) w({ reqs: [] });
      devices.delete(id);
      continue;
    }
    d.queue = d.queue.filter(q => cutoff - q.at < QUEUE_TTL_MS);
    for (const [rid, r] of d.resps) {
      if (cutoff - r.at > RES_TTL_MS) d.resps.delete(rid);
    }
  }
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (chunks.length === 0) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch (e) { reject(new Error('bad json')); }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(body);
}

function authorize(d, secret) {
  if (!d || typeof secret !== 'string') return false;
  const a = Buffer.from(d.secret);
  const b = Buffer.from(secret);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function isOnline(d) { return now() - d.lastSeen < ONLINE_MS; }

function ensureDevice(id, secret, name) {
  let d = devices.get(id);
  if (!d) {
    d = { secret, name: name || 'TV', lastSeen: now(), queue: [], waiters: [], resps: new Map() };
    devices.set(id, d);
  } else {
    d.lastSeen = now();
    if (name) d.name = name;
  }
  return d;
}

const routes = {
  // 电视：登记/心跳
  'POST /tv/hello': async (req, res, body) => {
    const { id, secret, name } = body;
    if (!id || !secret) return sendJson(res, 400, { ok: false, reason: 'missing id/secret' });
    ensureDevice(id, secret, name);
    sendJson(res, 200, { ok: true });
  },

  // 电视：长轮询取手机下发的请求；队列空就挂起，有请求或超时后返回。
  'POST /tv/poll': async (req, res, body) => {
    const d = devices.get(body.id);
    if (!authorize(d, body.secret)) return sendJson(res, 401, { ok: false, reason: 'unauthorized' });
    d.lastSeen = now();

    const drain = () => {
      const reqs = d.queue.splice(0, d.queue.length);
      return { reqs: reqs.map(q => ({
        rid: q.rid, method: q.method, path: q.path, headers: q.headers || {}, bodyB64: q.bodyB64 || null,
      })) };
    };

    if (d.queue.length > 0) return sendJson(res, 200, drain());

    const waiter = (payload) => sendJson(res, 200, payload);
    d.waiters.push(waiter);
    const timer = setTimeout(() => {
      const i = d.waiters.indexOf(waiter);
      if (i >= 0) d.waiters.splice(i, 1);
      waiter({ reqs: [] });
    }, POLL_HOLD_MS);
    res.on('close', () => {
      clearTimeout(timer);
      const i = d.waiters.indexOf(waiter);
      if (i >= 0) d.waiters.splice(i, 1);
    });
  },

  // 电视：回传某条请求的响应
  'POST /tv/res': async (req, res, body) => {
    const d = devices.get(body.id);
    if (!authorize(d, body.secret)) return sendJson(res, 401, { ok: false, reason: 'unauthorized' });
    d.lastSeen = now();
    const { rid, status, headers, bodyB64 } = body;
    if (!rid) return sendJson(res, 400, { ok: false, reason: 'missing rid' });
    d.resps.set(rid, { status: status || 200, headers: headers || {}, bodyB64: bodyB64 || null, at: now() });
    sendJson(res, 200, { ok: true });
  },

  // 手机：配对查询（校验 secret + 返回在线状态和设备名）
  'POST /phone/pair': async (req, res, body) => {
    const d = devices.get(body.id);
    if (!d) return sendJson(res, 404, { ok: false, reason: 'not-found' });
    if (!authorize(d, body.secret)) return sendJson(res, 401, { ok: false, reason: 'unauthorized' });
    sendJson(res, 200, { ok: true, name: d.name, online: isOnline(d) });
  },

  // 手机：下发一条请求到电视队列
  'POST /phone/req': async (req, res, body) => {
    const d = devices.get(body.id);
    if (!authorize(d, body.secret)) return sendJson(res, 401, { ok: false, reason: 'unauthorized' });
    if (!isOnline(d)) return sendJson(res, 503, { ok: false, reason: 'offline' });
    const { rid, method, path, headers, bodyB64 } = body;
    if (!rid || !path) return sendJson(res, 400, { ok: false, reason: 'missing rid/path' });
    d.queue.push({ rid, method: method || 'GET', path, headers: headers || {}, bodyB64: bodyB64 || null, at: now() });
    const w = d.waiters.shift();
    if (w) w({ reqs: d.queue.splice(0, d.queue.length).map(q => ({
      rid: q.rid, method: q.method, path: q.path, headers: q.headers || {}, bodyB64: q.bodyB64 || null,
    })) });
    sendJson(res, 200, { ok: true });
  },

  // 手机：取响应；还没回来就 pending
  'GET /phone/res': async (req, res, body, query) => {
    const d = devices.get(query.id);
    if (!authorize(d, query.secret)) return sendJson(res, 401, { ok: false, reason: 'unauthorized' });
    const r = d.resps.get(query.rid);
    if (!r) return sendJson(res, 200, { pending: true });
    d.resps.delete(query.rid);
    sendJson(res, 200, { pending: false, status: r.status, headers: r.headers, bodyB64: r.bodyB64 });
  },

  'GET /healthz': async (req, res) => sendJson(res, 200, { ok: true, devices: devices.size }),
};

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const key = `${req.method} ${url.pathname}`;
    const handler = routes[key];
    if (!handler) return sendJson(res, 404, { ok: false, reason: 'no route' });
    const body = ['GET', 'HEAD'].includes(req.method) ? {} : await readJson(req);
    await handler(req, res, body, Object.fromEntries(url.searchParams));
  } catch (e) {
    sendJson(res, 400, { ok: false, reason: e.message || 'error' });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`aerial relay listening on :${PORT}`);
});

// 自定义运行时要求长连接且服务端超时不打断长轮询。
server.keepAliveTimeout = 0;
server.timeout = 0;

setInterval(prune, 60_000);

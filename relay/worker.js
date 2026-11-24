/**
 * Aerial 远程中继（Cloudflare Worker 版，ES Module）。
 *
 * 架构和 relay/server.js 一致：电视和手机都不直连，两边各自出站连到本 Worker，
 * 中继只做请求/响应转发。协议字段不变：JSON + bodyB64，rid 由手机端生成。
 *
 * 与 Node 版的差异：
 * - 没有 Node http 模块，入口是 fetch(request)；长轮询用 Promise 挂起，不占 CPU。
 * - 状态是 isolate 内内存 Map，不落盘；Worker 冷启动后电视重新 hello 即可。
 * - MVP 单用户够用；多实例隔离状态不共享，后续要横向扩展再上 Durable Objects。
 *
 * MVP 限制：只转发控制类小请求；整机备份、大批量照片仍走局域网。
 */

const MAX_BODY = 8 * 1024 * 1024; // 8MB，超出直接拒绝
const ONLINE_MS = 60_000;         // 电视超过这个时间没心跳视为离线
const STALE_DEVICE_MS = 10 * 60_000;
const RES_TTL_MS = 5 * 60_000;
const QUEUE_TTL_MS = 2 * 60_000;
const POLL_HOLD_MS = 20_000;      // 长轮询挂起时长

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

function sendJson(status, obj) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Access-Control-Allow-Origin': '*',
    },
  });
}

function authorize(d, secret) {
  if (!d || typeof secret !== 'string') return false;
  if (d.secret.length !== secret.length) return false;
  return d.secret === secret;
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

function drain(d) {
  const reqs = d.queue.splice(0, d.queue.length);
  return { reqs: reqs.map(q => ({
    rid: q.rid, method: q.method, path: q.path, headers: q.headers || {}, bodyB64: q.bodyB64 || null,
  })) };
}

const routes = {
  // 电视：登记/心跳
  'POST /tv/hello': async (req, body) => {
    const { id, secret, name } = body;
    if (!id || !secret) return sendJson(400, { ok: false, reason: 'missing id/secret' });
    ensureDevice(id, secret, name);
    return sendJson(200, { ok: true });
  },

  // 电视：长轮询取手机下发的请求；队列空就挂起，有请求或超时后返回。
  'POST /tv/poll': async (req, body) => {
    const d = devices.get(body.id);
    if (!authorize(d, body.secret)) return sendJson(401, { ok: false, reason: 'unauthorized' });
    d.lastSeen = now();

    if (d.queue.length > 0) return sendJson(200, drain(d));

    return new Promise(resolve => {
      const timer = setTimeout(() => {
        const i = d.waiters.indexOf(waiter);
        if (i >= 0) d.waiters.splice(i, 1);
        resolve(sendJson(200, { reqs: [] }));
      }, POLL_HOLD_MS);
      const waiter = (payload) => {
        clearTimeout(timer);
        const i = d.waiters.indexOf(waiter);
        if (i >= 0) d.waiters.splice(i, 1);
        resolve(sendJson(200, payload));
      };
      d.waiters.push(waiter);
    });
  },

  // 电视：回传某条请求的响应
  'POST /tv/res': async (req, body) => {
    const d = devices.get(body.id);
    if (!authorize(d, body.secret)) return sendJson(401, { ok: false, reason: 'unauthorized' });
    d.lastSeen = now();
    const { rid, status, headers, bodyB64 } = body;
    if (!rid) return sendJson(400, { ok: false, reason: 'missing rid' });
    d.resps.set(rid, { status: status || 200, headers: headers || {}, bodyB64: bodyB64 || null, at: now() });
    return sendJson(200, { ok: true });
  },

  // 手机：配对查询（校验 secret + 返回在线状态和设备名）
  'POST /phone/pair': async (req, body) => {
    const d = devices.get(body.id);
    if (!d) return sendJson(404, { ok: false, reason: 'not-found' });
    if (!authorize(d, body.secret)) return sendJson(401, { ok: false, reason: 'unauthorized' });
    return sendJson(200, { ok: true, name: d.name, online: isOnline(d) });
  },

  // 手机：下发一条请求到电视队列
  'POST /phone/req': async (req, body) => {
    const d = devices.get(body.id);
    if (!authorize(d, body.secret)) return sendJson(401, { ok: false, reason: 'unauthorized' });
    if (!isOnline(d)) return sendJson(503, { ok: false, reason: 'offline' });
    const { rid, method, path, headers, bodyB64 } = body;
    if (!rid || !path) return sendJson(400, { ok: false, reason: 'missing rid/path' });
    d.queue.push({ rid, method: method || 'GET', path, headers: headers || {}, bodyB64: bodyB64 || null, at: now() });
    const w = d.waiters.shift();
    if (w) w(drain(d));
    return sendJson(200, { ok: true });
  },

  // 手机：取响应；还没回来就 pending
  'GET /phone/res': async (req, body, query) => {
    const d = devices.get(query.get('id'));
    if (!authorize(d, query.get('secret'))) return sendJson(401, { ok: false, reason: 'unauthorized' });
    const rid = query.get('rid');
    const r = d.resps.get(rid);
    if (!r) return sendJson(200, { pending: true });
    d.resps.delete(rid);
    return sendJson(200, { pending: false, status: r.status, headers: r.headers, bodyB64: r.bodyB64 });
  },

  'GET /healthz': async () => sendJson(200, { ok: true, devices: devices.size }),
};

async function readJson(request) {
  const buf = await request.arrayBuffer();
  if (buf.byteLength > MAX_BODY) throw new Error('body too large');
  if (buf.byteLength === 0) return {};
  return JSON.parse(new TextDecoder().decode(buf));
}

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const key = `${request.method} ${url.pathname}`;
    const handler = routes[key];
    if (!handler) return sendJson(404, { ok: false, reason: 'no route' });
    try {
      prune();
      const body = ['GET', 'HEAD'].includes(request.method) ? {} : await readJson(request);
      return await handler(request, body, url.searchParams);
    } catch (e) {
      return sendJson(400, { ok: false, reason: e.message || 'error' });
    }
  },
};

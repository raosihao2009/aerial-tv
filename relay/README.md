# Aerial 远程中继

电视和手机都不直连，两边各自出站连到本中继，中继按设备转发 HTTP 请求/响应。
零依赖 Node（≥18），本地和免费托管都能跑。

## 运行

```bash
PORT=8799 node server.js
# 健康检查
curl localhost:8799/healthz
```

## 协议

所有请求/响应都是 JSON，二进制字段 base64（`bodyB64`）。

| 路由 | 方向 | 说明 |
| --- | --- | --- |
| `POST /tv/hello` | 电视 | 登记/心跳：`{id, secret, name}`，返回 `{ok:true}` |
| `POST /tv/poll` | 电视 | 长轮询取请求，最多挂起 20s：`{id, secret}` → `{reqs:[{rid,method,path,headers,bodyB64}]}` |
| `POST /tv/res` | 电视 | 回传响应：`{id, secret, rid, status, headers, bodyB64}` |
| `POST /phone/pair` | 手机 | 校验配对：`{id, secret}` → `{ok, name, online}` |
| `POST /phone/req` | 手机 | 下发请求：`{id, secret, rid, method, path, headers, bodyB64}` |
| `GET /phone/res?id=&secret=&rid=` | 手机 | 取响应：`{pending}` 或 `{pending:false, status, headers, bodyB64}` |

- 鉴权：每个请求校验 secret；长度不符直接 401，长度一致后走常量时间比较。
- 在线判定：电视 60s 内有 hello/poll 才算在线；离线时手机下发返回 503 `offline`。
- 内存状态：重启后电视重新 hello 即可，不持久化。
- MVP 限制：请求体上限 8MB；整机备份、大批量照片仍走局域网，不走中继。

## 免费部署

任选一种 Node 能跑的免费托管（Render / Replit / Fly / 自有 VPS 均可）：

1. 上传本目录，启动命令 `node server.js`，端口读环境变量 `PORT`。
2. 平台会分配一个公开 HTTPS 地址，例如 `https://aerial-relay.onrender.com`。
3. 电视端和手机端都填这个地址（电视端中继地址可配置，默认值在客户端常量里）。

注意：国外免费节点从国内访问可能偏慢或不稳；若已有 Cloudflare 域名，后续可把同一协议改写成 Worker + Durable Objects，协议字段不变。

## 安全说明

- 中继只转发，不存内容；secret 是设备级共享密钥，不要在日志里打印。
- 公网部署必须走平台自带的 HTTPS（WSS 等价），不要把明文 HTTP 端口直接暴露到公网。
- 8 位路径 token 是局域网用的；远程通道以设备 secret 为准。

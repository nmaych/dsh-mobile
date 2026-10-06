# DSH 远程协议（逆向记录）

本文件记录 DSH Mobile 与桌面版 DeepSeek Harness 通信时依赖的线上协议。
所有结论都通过**对真实 `dsh web` 实例的实测**验证，不是推测。

---

## 1. 启动服务端

```sh
dsh web --port 19387
```

启动后会打印一行带进程 token 的 URL：

```
dsh web: http://127.0.0.1:19387/?token=z4cGAxIEpINqHfQt0JyzYGMdgD30baQk68JFtCFqDYQ
```

**重要限制**：`--host 0.0.0.0` 会被主动拒绝（安全考虑）。要让手机访问，必须：

```sh
dsh web --trusted-host 192.168.1.5
```

`--trusted-host` 只是把该 authority 加入信任栅栏，服务器仍然只监听 loopback。
因此手机直连桌面端需要额外的端口转发（例如 `netsh interface portproxy`
或 SSH 隧道），详见 README 的「连接桌面端」一节。

---

## 2. 认证：token → Cookie 握手

`/api` 下**所有**方法和 WebSocket 都要求一个浏览器会话 Cookie，
**没有** Bearer token 通道。

```
GET /?token=<进程token>
```

响应：

- `Set-Cookie: dsh-auth-<...>=<签名值>; Path=/; HttpOnly; SameSite=Strict`
- `3xx` 重定向到不含 token 的同一目录

该 Cookie 绑定 authority（主机名 + 端口），因此换主机名访问会失效。

后续请求携带：

```
Cookie: dsh-auth-<...>=<签名值>
```

未认证时：`401 unauthorized`。
Host/Origin 校验失败时：`403`（例如用了非信任的 Host 头）。

---

## 3. 一元 RPC

```
POST /api/<namespace>/<method>
Content-Type: application/json
Cookie: <会话Cookie>

{
  "type": "client-request",
  "rpcId": "<uuid v4>",
  "method": "<namespace>/<method>",
  "payload": { "args": { ... } }
}
```

### 三个必须精确的细节

1. **`method` 是完整 endpoint**，不是裸方法名。
   传 `"list"` 会得到
   `gateway/bad-request: method "list" does not match endpoint "session/list"`。

2. **`payload` 只能有一个键 `args`**，且 `args` 的键必须是描述符的
   *wire* 名。多一个或少一个键都会被拒绝。

3. **wire 名不统一**：`session/list` 用 `_request`（因为 TS 参数名就是
   `_request`），其余 session 方法用 `request`。

响应：

```json
{ "type": "server-response", "rpcId": "<与请求相同>", "result": { "ok": true, "value": { ... } } }
```

失败：

```json
{ "type": "server-response", "rpcId": "...",
  "result": { "ok": false, "error": { "code": "...", "message": "...", "details": {} } } }
```

### 常用方法的 args

| 方法 | `args` |
|---|---|
| `session/list` | `{"_request":{}}` |
| `session/create` | `{"request":{}}`（可带 `cwd`） |
| `session/prompt` | `{"request":{"requestId","sessionId","mode","content","clientTimeZone"}}` |
| `session/cancel` | `{"request":{"sessionId"}}` |
| `session/rename` | `{"request":{"sessionId","title"}}` |
| `session/modelCatalog` | `{}` |
| `session/selectModel` | `{"request":{"sessionId","provider","model","reasoningEffort"?}}` |
| `session/page` | `{"request":{"address","throughSeq","maxMessages"}}` |

`clientTimeZone` 必须是 `UTC` 或合法的 IANA `Area/Location`，
否则返回 `session/invalid-time-zone`。

---

## 4. 流式 RPC（WebSocket）

单一物理连接，多路复用所有逻辑流：

```
ws://<host>/api/remote.mux
Cookie: <会话Cookie>
```

打开一条逻辑流：

```json
{ "type": "open", "streamId": "<uuid>", "endpoint": "<namespace>/<method>", "payload": { "args": { ... } } }
```

服务端下行帧：

```json
{ "type": "item",  "streamId": "...", "value": { ... } }
{ "type": "end",   "streamId": "..." }
{ "type": "error", "streamId": "...", "error": { "code": "...", "message": "...", "details": {} } }
```

取消：`{"type":"cancel","streamId":"..."}`

### `session/follow` 的 args

```json
{ "request": {
    "address": { "kind": "session", "sessionId": "session-…" },
    "assistantStream": true,
    "maxMessages": 200,
    "turnWindow": { "minMessages": 200, "minTurns": 2 }
} }
```

### `session/follow` 的三种 item

| `type` | 含义 |
|---|---|
| `snapshot` | 回填历史：`header`、`cursor`、`records[]`、`hasMore` |
| `event` | 增量持久事件：`{ type:"event", event:{...} }` |
| `assistant-stream` | **实时 token 增量**：`{ type:"assistant-stream", frame:{...} }` |

`assistant-stream` 的 `frame`：

```json
{ "type": "start", "turn": 1, "step": 1 }
{ "type": "chunk", "index": 0, "time": 1791…, "chunk": { "type": "text-delta", "text": "…" } }
{ "type": "end",   "outcome": { "kind": "committed", "eventType": "assistant/message", "seq": 17 } }
```

`chunk.type` 取值：
`block-start` / `text-delta` / `reasoning-delta` / `tool-call-delta` /
`block-end` / `usage` / `finish`。

**累加规则**：按 `chunk.index` 建块；`text-delta`、`reasoning-delta` 追加；
`block-end` 整体替换；`tool-call-delta` 追加到 JSON 参数字符串。

---

## 5. 会话事件

事件信封：

```json
{ "type": "user/message", "seq": 9, "time": 1791212576907,
  "data": { ... }, "surfaceOp": "append" }
```

### 关键规则：只渲染 `surfaceOp == "append"`

五个 surface 事件（`system/message`、`developer/message`、`user/message`、
`assistant/message`、`tool/result`）可能携带
`{"op":"replace","startSeq":n,"endSeq":m}`，那是**只改模型可见历史**的重写，
不能用来抹掉用户已经看到的内容。

### 渲染相关事件

| 事件 | `data` 要点 |
|---|---|
| `user/message` | `content[]`（`text` / `image` / `file`）、`id`、`source.kind` |
| `assistant/message` | `message.content[]`：`reasoning` → 思考，`text` → 正文，`tool-call` → 工具卡 |
| `tool/call` | `callId`、`name`、`arguments`（JSON **字符串**） |
| `tool/result` | `message.toolCallId`、`message.content[]`、`message.isError` |
| `turn/start` / `turn/end` | `turn`；`turn/end.reason.kind` ∈ `completed`/`aborted`/`error`/`max-tokens`/… |
| `step/start` / `step/end` | `turn`、`step` |
| `session/title` | `title` |
| `llm/retry` | `failure.message` |
| `approval/asked` | `toolName` |

`assistant/attempt` 是**失败/取消的尝试**，不含 `message`，**不能**当成回复渲染。

思考内容没有独立事件类型，它是 `assistant/message` 里 `type:"reasoning"` 的块。

### 如何判断「还在生成」

以 `turn/start` / `turn/end` 配对为准：看到 `turn/start{turn:n}` 且没有对应的
`turn/end{turn:n}` 就是在跑。`turn/end` 在 `finally` 中写入，任何路径
（完成、中止、出错、超长）都会到达，因此它是最可靠的收尾信号。

---

## 6. 实测结果

对本地 `dsh web` 实例的完整验证（`test/e2e.mjs`、`test/modeltest.mjs`）：

```
PASS  token handshake sets a session cookie
PASS  session/list returns items            (2 sessions)
PASS  summary has no top-level `title`      (标题在 projections.values.title)
PASS  session/create returns sessionId
PASS  session/follow opens and yields a snapshot
PASS  session/prompt is accepted and the turn runs
PASS  turn/end closes the turn
PASS  live assistant-stream frames arrive   (12 frames)
PASS  durable assistant/message arrives
PASS  assistant text recovered              ("PROTOCOL OK")
PASS  session/cancel returns accepted
```

真实模型回合的 `turn/end` 为 `{"kind":"completed"}`，
`assistant/message` 与实时 `assistant-stream` 增量均正确还原。

### 复现方式

```sh
# 1. 启动服务端（需要一个可用的 provider，例如 DeepSeek 账号登录）
dsh web --port 19555 --no-open

# 2. 用打印出的 URL 跑测试
node test/e2e.mjs     "http://127.0.0.1:19555/?token=…"
node test/modeltest.mjs "http://127.0.0.1:19555/?token=…"
```

---

## 7. 已知边界

- **没有 Bearer token 通道**，必须走 `?token=` → Cookie 握手。
- **Cookie 绑定 authority**：换 IP / 主机名访问需要重新配对。
- **Cookie 不带 `Secure`**（服务端是 loopback HTTP），
  明文网络下不要暴露该 authority。
- **没有登出接口**：清 Cookie 结束单个浏览器会话；
  删除凭据记录并重启 `dsh` 才会吊销全部。
- **`--host 0.0.0.0` 被拒绝**，跨设备访问需要端口转发或隧道。
- **`session/list` 的 `cursor` 参数存在但被忽略**，`items` 总是完整列表。

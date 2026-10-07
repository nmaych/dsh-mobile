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
| `session/create` | `{"request":{}}`（可带 `workspaceId`、`cwd`、`sessionId`、`agentPreset`） |
| `session/prompt` | `{"request":{"requestId","sessionId","mode","content","clientTimeZone"}}` |
| `session/cancel` | `{"request":{"sessionId"}}` |
| `session/rename` | `{"request":{"sessionId","title"}}` |
| `session/modelCatalog` | `{}`（无参数） |
| `session/selectModel` | `{"request":{"sessionId","provider","model","reasoningEffort"?}}` |
| `session/page` | `{"request":{"address","throughSeq","maxMessages"}}` |
| `session/projections` | `{"request":{"sessionId"}}` |
| `workspace/create` | `{"request":{"path"}}` |
| `directoryPicker/list` | `{"path"?}`（可选） |

`clientTimeZone` 必须是 `UTC` 或合法的 IANA `Area/Location`，
否则返回 `session/invalid-time-zone`。

`session/selectModel` 的 `reasoningEffort` 虽然是可选的，但**不能传空串**：
描述符只认「键不存在」或「合法值」，空串会被严格编解码器拒绝。
`session/create` 的每个可选字段同理。

---

## 3.5 工作区、模型目录与 token 用量（1.1.2 起）

### 工作区列表：没有一元方法

工作区**没有** `workspace/list`。完整列表是 `workspace/follow` 这条流的
第一帧：

```json
{ "type": "baseline",
  "value": { "items": [ { "workspaceId": "…", "path": "…", "title": "…",
                          "sessionIds": [], "createdAt": "…", "updatedAt": "…" } ],
             "archivedSessionIds": [], "pinnedSessionIds": [] } }
```

取到 `baseline` 后即可取消该流。后续帧是 `upsert` / `remove` / `order` /
`archived` / `pinned` 增量。

注册一个目录用 `workspace/create`：

```json
{ "request": { "path": "D:\\projects\\my-app" } }
```

回答 `{"workspace": {…}, "created": true|false}` —— 已存在时 `created` 为
`false`，不会报错。

把会话建在工作区里必须传 **`workspaceId`**：只传 `cwd` 会让会话使用那个
目录，但不会归入工作区，桌面端侧边栏也就看不到它。

### 模型目录

`session/modelCatalog` 不带参数，回答：

```json
{ "default": { "provider": "…", "model": "…", "reasoningEffort"? },
  "routableProviders": ["…"],
  "groups": [ { "id": "provider-id", "name": "显示名",
                "models": [ { "id": "…", "name": "…", "description"?,
                              "reasoning"?: { "efforts": [{"id","name","description"?}],
                                              "defaultEffort"? } } ] } ],
  "failures": [ { "id": "…", "name": "…", "message": "…" } ] }
```

`routableProviders` 就是 `groups[].id`，没有额外信息；`failures` 是某个
provider 列举模型时抛错的原因。切换用 `session/selectModel`，回答
`{"selected": {"provider","model","reasoningEffort"?}}`。

### token 用量

`session/projections` 一次返回所有已注册投影：

```json
{ "asOfSeq": 123,
  "values": {
    "tokenUsage": { "uncachedInputTokens": 782, "outputTokens": 123,
                    "cacheReadTokens": 6784, "cacheWriteTokens": 0 },
    "contextPressure": { "contextWindow": 65536, "pressureTokens": 7566,
                         "projectedTokens": 7689 }
  } }
```

两个要点：

1. **字段名不同**。投影里叫 `uncachedInputTokens`，而事件上
   `assistant/message.data.usage` 里同一个数叫 `inputTokens`。读错就是静默的 0。
2. **总量要自己加**。`totalTokens` 字段存在，但它等于
   `inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens`
   —— 已在 3001 条真实结算上验证一致。不要重复计入 reasoning。

没有装 token-meter 时这两个投影都不存在，`values` 里就没有对应键，
此时应当隐藏用量显示而不是显示 0。

实时的 `assistant-stream` 里还有 `chunk.type == "usage"` 的帧，携带同一
`TokenUsage` 结构；它比持久结算更早到达，适合做流式期间的临时显示。

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

### 重连语义（1.1.2 起）

复用连接会因为手机锁屏、切网、桌面端重启而断开。客户端会在传输层失败时
按退避重开这条流，因此必须知道**重放是否安全**：

- `session/follow` 重开后重新发送完整 `snapshot`（含积压），随后是增量的
  `event`。客户端按 `seq` 去重，所以重放不会产生重复消息。
- `workspace/follow` 重开后重新发送 `baseline`。
- 其他流（如 `session/attachment`）不是幂等的，不应自动重放。

去重必须是**每会话**的：`seq` 每个会话各自从 0 开始。把「已见过的 seq」
跨会话共享，会让新会话的首批事件被当成重放丢掉——这正是 1.1.2 修掉的
「对话显示异常」。

同理，`tool/result` 往往在 `tool/call` 之后的另一批事件里到达，所以
`callId → 卡片位置` 的索引也必须跨批次保留，只在切换会话时清空。

### `session/follow` 的 args

```json
{ "request": {
    "address": { "kind": "session", "sessionId": "session-…" },
    "assistantStream": true,
    "maxMessages": 200,
    "turnWindow": { "minMessages": 200, "minTurns": 2 }
} }
```

`address` 是一个 **union**，`session/page` 与 `session/follow` 共用：

```json
{ "kind": "session",  "sessionId": "…" }
{ "kind": "subagent", "parentSessionId": "…", "childSessionId": "…",
  "mode": "one-shot" | "continuable" | "unknown" }
```

（union 的定义取自 Host 自己生成的描述符；`mode` 的三个取值就是它的全部取值。）

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

## 4.5 subagent 会话：必须用「父地址」

子代理（subagent）会话**不能**当成普通会话来寻址。它的日志归父会话的委派所有，
用 `{"kind":"session"}` 去读会被拒绝：

```
session/agent-busy: subagent Sessions require their durable parent address
{ "reason": "use subagent delivery for this child session" }
```

服务端的校验逻辑（`validateAddress`）按顺序做这几件事，**每一条都值得照抄**：

1. `kind == "session"` 且 `header.origin == "subagent"` → 抛上面这个错。
2. `header.parentSession !== address.parentSessionId` → `subagent/unauthorized`。
3. 投影里的 `subagent` 缺失（或 `seq < inheritedEventCount`）→
   `subagent/catalog-diagnostic`。
4. **`address.mode !== "unknown"` 时才比对 `mode`**：

   ```js
   if (address.mode !== "unknown" && identity.mode !== address.mode) throw …
   ```

   所以读操作传 `"unknown"` 是安全的，也是投影没读到时的诚实取值。

### 父地址从哪来

`session/list` 的每一行就是答案，不需要额外请求：

```json
{ "sessionId": "…",
  "origin": "subagent",              // 有它才是子会话
  "parentSessionId": "…",            // 这就是 durable parent
  "cwd": "…",
  "projections": { "values": { "subagent": { "mode": "continuable",
                                             "label": "…", "seq": 12 } } } }
```

`origin` 与 `parentSessionId` 都是可选字段；**两个都在**才构造 subagent 地址，
否则按普通会话处理。`mode` 取 `projections.values.subagent.mode`，
缺失或不认识就退化成 `"unknown"`。

### 写操作要走 `subagents/*`，而且两个方法形状不同

子会话的读写走**不同**的端点，因为父会话才持有委派：

| 操作 | 端点 | args 形状 |
|---|---|---|
| 读历史 / 订阅 | `session/page`、`session/follow` | `{"request":{"address":{…}}}` |
| 发消息 | `subagents/prompt` | `{"request":{…}}`（**一个** `request`） |
| 停止 | `subagents/interruptByParent` | 三个**顶层**参数，**没有** `request` |

最后一行是这里最容易写错的地方——两个同属 `subagents` 的方法形状并不一致：

```json
// subagents/prompt —— 一个 request
{ "request": { "requestId": "…", "parentSessionId": "…", "childSessionId": "…",
               "mode": "continuable", "delivery": "queue" | "steer",
               "content": [ { "type": "text", "text": "…" } ],
               "clientTimeZone": "…" } }

// subagents/interruptByParent —— 三个顶层参数
{ "childSessionId": "…", "parentSessionId": "…", "mode": "continuable" }
```

`subagents/prompt` 的 `mode` 只接受 `"continuable"`：一次性（`one-shot`）子会话
已经没有收件箱可以接收新的回合，客户端应当在本地就说清楚，而不是发出去换一个
错误回来。`session/prompt` 对子会话的拒绝措辞是
`session "…" is owned by subagent routing`（同样是 `session/agent-busy`），
`session/selectModel` 也一样不接受子会话 id。

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

### 对真实会话日志的离线验证（1.1.2）

`test/session-fold.test.mjs` 守的是折叠规则本身，而规则是从**真实日志**里
核出来的。做法是把 `~/.dsh/sessions/**/session.v4.jsonl.zstd` 解出来折叠一遍。
注意这种文件是**几百个独立的 zstd 帧拼接**而成，Node 的一次性解压只读第一帧，
会让人误以为日志里只有一条 `session` 记录——必须按帧魔数
`28 B5 2F FD` 切开逐帧解压。

对 31 个会话（3248 条 `assistant/message`、3929 张工具卡）的结果：

| 检查 | 结果 |
|---|---|
| 工具卡配对不上结果 | 1 / 3929（该 call 确实没有 result） |
| 重复的消息 id | 0 |
| `totalTokens` 与四个桶之和一致 | 3001 / 3001 |
| 逐条折叠 vs 一次性折叠 | 完全一致 |

`turn/end` 的 `reason.kind` 分布：`completed` 65、`aborted` 35、`error` 5、
`max-tokens` 2 —— 所以 `aborted` 不是罕见情况，值得给一条「已停止生成」的提示。

`assistant/message.data.usage` 在 3248 条里有 3227 条存在（21 条缺失），
字段组合有 6 种，其中一种用 `serverTotal` 而不是 `totalTokens`，
所以**不要**假设 `totalTokens` 一定在。

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
- **工作区没有一元列表方法**，只能从 `workspace/follow` 的 `baseline` 取。
- **`seq` 是每会话独立的**，任何跨会话共享的去重集合都是错的。
- **`totalTokens` 不保证存在**（真实日志里有 226 条用 `serverTotal`），
  四个桶之和才是可靠的口径。
- **`session/selectModel` 的 `reasoningEffort` 不能传空串**，要么不给键，
  要么给合法值。
- **子会话（`origin == "subagent"`）不能按普通会话寻址**，见 4.5 节；
  读写走不同端点，且 `subagents` 的两个方法形状并不一致。
- **`subagents/interruptByParent` 的三个参数是顶层的**，没有 `request` 包装
  ——它和 `subagents/prompt` 不一样。
- **`address.mode` 传 `"unknown"` 时服务端跳过 mode 比对**，所以投影没读到也能读
  （只限读操作）。
- **传输层失败的文字由客户端生成**，不能回显 `Throwable.message`：OkHttp 会给出
  `failed to connect to /… (port …) from /… (port …) after 20000ms` 这类只对
  开发者有意义的英文。文案集中在 `net/TransportErrors.kt`，三处调用点
  （远程 / API / 更新）共用，只是结尾的建议不同。
- **OkHttp 会把连接失败包成 `RouteException`**（`RuntimeException`，**不是**
  `IOException`），WebSocket 监听器收到的也是它；只按最外层类型判断会漏掉
  最常见的情况，必须顺着 `cause` 链看。

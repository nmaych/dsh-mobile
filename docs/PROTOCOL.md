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

#### `session/create`：`workspaceId` 与 `cwd` 互斥

这两个字段**不能同时出现**，否则服务端直接拒绝：

```
gateway/bad-request: session.create accepts workspaceId or cwd, not both
```

它们不是「碰巧冲突」的两个可选参数：**工作区本身就是一个目录**，服务端会自己做
`cwd = workspace.path`。所以给了 `workspaceId` 之后，`cwd` 不但是多余的，
而且是会被拒绝的。

```jsonc
// 正确：在选中的工作区里新建
{ "request": { "workspaceId": "ws-…" } }

// 正确：在一个裸目录里新建（没有对应工作区时）
{ "request": { "cwd": "E:\\project" } }

// 错误：两个都给 → gateway/bad-request
{ "request": { "workspaceId": "ws-…", "cwd": "E:\\project" } }
```

> 这是一个**只有真机会暴露**的契约：两个键都是合法 wire 名，Kotlin 编译器
> 和描述符校验都不会报错，只有服务端在运行时拒绝。1.1.4 就是把选中工作区的
> id 和它的 path 一起传了出去，于是「只要选过工作区，新建对话必定失败」。
> `test/feature-contract.test.mjs` 与 `test/regression-1.1.5.test.mjs` 里
> 各有一条静态检查守着它。

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

> **这是最慢的一个一元调用。** 它会向**每个**已配置的 provider 要模型列表，
> 其中可能有需要联网刷新的远程目录，所以慢到几十秒是正常的，不代表桌面端卡死。
> 客户端据此给了它 120 秒的读超时（只为兜住无限等待），并且**不**把这种超时
> 报成「连接超时」——socket 这时其实是连上的，地址和 Wi-Fi 都没问题。
> 见 `net/TransportErrors.kt` 里三种超时的区分。

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

### 超时与重试（1.1.4 起，1.1.6 修订）

两种传输各有各的超时，**不能共用一个客户端**：

| 路径 | 读超时 | 理由 |
| --- | --- | --- |
| mux WebSocket（`http`） | `0`（无限） | 流是长连接，读超时会在正常空闲时把它掐断 |
| 一元 RPC / 握手（`rpcHttp`） | 120 秒 | 只为兜住**无限等待**，不是用来催正常请求 |

一元调用此前用的是**同一个**无限读超时的客户端，后果是双重的：桌面端卡住时
选模型会一直转圈（既不出结果也不报错），而唯一还能报出来的超时只剩**连接**超时。
两个客户端由 `http.newBuilder()` 派生，因此**共享连接池与 CookieJar**。

同一个 `SocketTimeoutException` 其实对应三种不同的失败，必须分开报，否则会把
用户送去修一个没坏的东西：

| 异常里的文字 | 真实含义 | 该说的话 |
| --- | --- | --- |
| `…didn't receive pong within…` | mux 心跳超时（OkHttp 的 `pingInterval`），流会自行重连 | 连接中断，正在自动重连 |
| `failed to connect to /… (port …) … after 20000ms`（Android）<br>`connect timed out` / `Connect timed out`（OpenJDK） | TCP 没连上 | 连接超时，去查 Wi-Fi / 桌面端 |
| `timeout` / `Read timed out` | socket 已连上，桌面端没及时回 | 已连上，桌面端可能正忙，稍后重试 |

判定用异常自带的文字而不是类型，因为三者是同一个类型。

> **1.1.6 更正**：Android 上 `Socket.connect` 超时的原文**不是** `connect timed out`
> ——那是 OpenJDK 的措辞（JDK 17 首字母大写，JDK 8 小写）。Android 的
> `IoBridge.createMessageForException` 硬编码的是上面那条长文本，它会带上**本机**
> 临时端口，正是不能回显给用户的东西。两者都含 `connect`，所以旧代码能用，
> 但它引用的证据是错的。现在按**两个真实来源的措辞**匹配，而不是裸子串
> `connect`：这个判定同时决定**要不要重试**，判错的代价是 `session/prompt`
> 可能被跑两次。

**只有连接超时会被重试。** 请求根本没到桌面端，所以重复发送不会
重复执行（`session/prompt` 不会被跑两次）——这也是 OkHttp 自身 `isRecoverable`
的条件（`SocketTimeoutException && !requestSendStarted`）。读超时**不重试**：
那时桌面端可能已经在处理了。OkHttp 自己不会替我们重试，因为它只考虑**换一条路由**
（`RouteSelector`），而手机只有一个 Wi-Fi、地址是字面 IP，路由只有一条。

> **1.1.6 更正**：1.1.4 只重试**一次**、且只等 **400ms**，比它自己要扛的
> 「射频还没睡醒」（注释自己写着「1 秒内就会过去」）还短——重试必然落在同一次休眠
> 的尾巴里，以同样的方式再失败一次，最后把一个马上就能用的连接报成「连不上」。
> 现在是**两次、分别等 1 秒和 2 秒**，真正覆盖住那个唤醒窗口。
> 另外报错横幅此前**从来没有被撤回**：mux 掉线是自愈的（`stream` 会用退避重开），
> 但失败那次留下的横幅一直挂着，于是**重连成功之后**应用仍在坚称「连接超时」。
> 现在套接字一回来就撤掉**传输类**错误；服务端错误不是自愈的，必须留着。

### `session/control`：投影的实时推送（1.1.6 起）

`session/projections` 是**一次性**读取，只适合在一轮结束时对账；要在一轮**进行中**
就看到用量增长，必须订阅 `session/control`：

```json
{ "args": {} }
```

**它不带任何参数**——描述符里参数表是空的，网关会逐字校验 `args` 的键，
多一个键就会被拒。它是 `mode: "stream"`，但走的是和其他流一样的逻辑流
（`{"type":"open","streamId","endpoint":"session/control","payload":{"args":{}}}`）。

帧有两种，而且这条流是**全 Host** 的，不是每会话一条：

```json
{ "type": "baseline",
  "value": { "projections": { "<sessionId>": { "asOfSeq": 42, "values": { … } } } } }

{ "type": "projection", "sessionId": "…", "key": "tokenUsage", "value": { … }, "seq": 43 }
```

`baseline` 覆盖**每一个**会话，所以调用方要按 `sessionId` 过滤。

**必须按 `seq` 裁决**（大的赢），并且只采用**严格更新**的帧。推送和一次性读取
是两条通道，帧可能乱序到达；不比较 `seq` 的话，一个迟到的旧帧会把计数器**往回拉**,
而 `tokenUsage` 是累计值，往回跳就是错的。`session/projections` 的返回值里带
`asOfSeq`，用它给水位线打底。

`tokenUsage` 的 wire 视图是
`{uncachedInputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}`，
注意 prompt 侧叫 **`uncachedInputTokens`**（未缓存输入），
和结算事件 `assistant/message.data.usage` 里的 `inputTokens` **不是同一个键**。
读错键不会报错，只会静默得到 0。

### `$events`：转发的 Host 事件与提问回答（1.1.6 起）

`ask_user_question` 需要认领 Host 的 **waterfall**（`user-questions/request`），
这是**唯一**能在问题「正在被问」时回答它的通道。一元接口 `userQuestions/answer`
做不到：它要求问题已进入 `continued` 状态，而那个状态只有**工具调用返回之后**才出现,
那时模型已经往下走了。

流端点是网关内部的 `$events`（注意不是 `session/*` 那种注册服务方法）：

```json
{ "args": {} }
```

**`args` 必须存在且为空对象。** 网关的校验是逐字的
（`Reflect.ownKeys(payload.args).length !== 0` → `gateway/arguments-invalid`），
所以多一个「顺便带上」的字段会让整条流开不起来，而且报错信息不会提示是哪个字段。

帧（都是 `item` 的 `value`）：

```json
{ "type": "ready", "clientId": "…", "host": { "home": "…" } }
{ "type": "emit", "event": "…", "args": [ … ] }
{ "type": "waterfall", "event": "user-questions/request",
  "eventId": "…", "agentId": "…", "request": { "questions": [ … ] } }
{ "type": "cancel", "eventId": "…" }
```

- `ready` **一定是第一帧**，`clientId` 只在这里下发，且**随流一起消亡**。
- `waterfall` 的 `request` 里**没有** `clientId`——它只带 `event/eventId/agentId/request`,
  所以 `clientId` 必须由调用方从 `ready` 里带进来。
- `request.wait.callId` 是把它和工具调用绑起来的字段，没有 `wait` 就说明这个提问
  不挂在某个工具调用上。
- 流**晚开也能收到积压**：pending 的 waterfall 会补发给新开的流。

回答走一元 `$events/result`：

```json
{ "clientId": "…", "eventId": "…",
  "outcome": { "kind": "result", "value": { "answers": [ … ] } } }
```

`outcome` 三种取值：`{"kind":"result","value":…}` 认领并作答（**先到先得**，
其余投递会收到 `cancel`）；`{"kind":"next"}` 让给别人；`{"kind":"rejected","error":…}` 报错。

**关联靠 `(clientId, eventId)` 这一对，不是套接字身份**——网关在自己的注册表里查这一对，
所以一元回复可以走**另一条** HTTP 连接。这正是它在 OkHttp 上可行的原因
（一元调用和 mux socket 是两条连接）。

回答体是**整批原子提交**的：`answer.answers` 必须把该 call 的每个问题**不多不少各点一次**，
否则整批被 `BAD_ANSWER` 拒绝，没有「下一题」的往返。

```json
{ "answers": [ { "id": "…", "selected": ["选项 label"], "custom": "自由输入" } ] }
```

- `selected` 里放的是**选项 label**，不是下标。
- `custom` **只在非空时才给键**：它的「存在」本身在 schema 里有意义。

### `workspaceFiles`：查看工作区文件（1.1.7 起）

命名空间 `workspaceFiles`，五个方法：`list`、`read`、`readBytes`、`stat`、`changes`
（后两个本应用没用）。都是**普通一元调用**，走 `POST /api/workspaceFiles/<method>`。

这个命名空间有两处**看起来可以简化、实际会静默失效**的地方，是它全部的价值所在。

#### 第一个参数是**查找**，值是**会话 id**

```json
{ "workspaceFileScopeId": "session-…", "path": "" }
```

`workspaceFileScopeId` 是描述符里的 wire 名，而它的 `source` 是 **`lookup`**，
不是 `json`：宿主把它解析成一个**活着的会话**，然后取那个会话的 `cwd`
（`header.cwd ?? sandboxPolicy.workspaceRoot`）作为工作区根目录。

所以：

- 传的必须是**会话 id**。传**工作区 id** 解析不到任何东西，网关会回一个查找失败——
  它**不会**退回到沙箱根目录。两种 id 长得几乎一样，这是最容易搞错的一处。
- 「浏览桌面端的文件」因此**不是一个功能**，而是一个刻意的边界：能看到的就是
  那个对话里的 agent 能碰到的东西。

#### `read` 的第三个参数 `range` 是**必填**的

```json
{ "workspaceFileScopeId": "session-…", "path": "src/index.ts",
  "range": { "offset": 1, "limit": 200 } }
```

`range` 里**每个字段都是可选的**（`offset` 默认 1，`limit` 默认取上限），
但**参数本身不是**：描述符把它声明成必需的 `json` 参数，
网关的 `assertExactArguments` 会拒绝缺少这个键的 `args`，报
`gateway/arguments-invalid: … missing "range"`。

「它没有必填字段，所以可以省略」正是那种会变成一个静默失效功能的判断。
所以 `range` **永远都发**，哪怕里面什么都不放。

#### `list` 的返回

```json
{ "path": "src",
  "entries": [ { "name": "index.ts", "type": "file", "size": 1234 },
               { "name": "util", "type": "directory" } ],
  "truncated": false }
```

- `type` 只有 `file` / `directory` / `other` 三种。
- **`size` 只在 `file` 上有**，目录没有（不要当成 0）。
- 每个子项**只有名字和元数据，没有绝对路径**：`list` 故意把解析后的目标剥掉了，
  所以完整路径要在客户端拼（`父目录 + "/" + name`）。
- `truncated` 为真表示服务端**截断**了列表。**必须告诉用户**——
  一份看起来完整的残缺列表，正是用户据此断定「这个文件不存在」的原因。
- 目录**被限制在工作区内**：绝对路径指向工作区之外会得到
  `workspace-file/outside-workspace`。单个**文件**在工作区之外反而**允许**读。

#### `read` 的返回

```json
{ "offset": 1, "text": "…", "lines": 200, "eof": false,
  "absolutePath": "/home/u/proj/src/index.ts", "version": "…", "bytes": 12345 }
```

- `offset` 是 **1 基**的行号，和服务端自己的编号一致。
- `eof` 说明这一页是否到达最后一行。不能靠「行数够不够」推断：
  正好结束在最后一行、和被行数上限截断，这两种情况从行数上看不出区别。

常见错误码：`workspace-file/not-found`、`workspace-file/not-directory`、
`workspace-file/not-regular-file`、`workspace-file/not-text`（含 NUL 字节，不是文本）、
`workspace-file/too-large`、`workspace-file/outside-workspace`。

上限：单页 5000 行、单次列举 2000 项、文本读取 2 MiB、整文件 32 MiB。

### token 显示口径（与桌面端一致，1.1.6 起）

手机此前自创了一套显示（`↑209k (10.3M 缓存) ↓309k`，小写 `k`，箭头），
桌面端从来没有过，于是同一个会话在两个客户端上读起来是两个数。桌面端的口径是：

```
{总计} tok · 缓存命中 {百分比}%
```

其中「总计」是四个计费桶**全部相加**：未缓存输入 + 缓存读 + 缓存写 + **输出**。

`formatTokens` 的规则（取自桌面端 `token-format.js`）：

- 1000 以下：原样，`517`
- 1000 ~ 100 万：**大写** `K`，缩放值**小于 100 时保留一位小数**（`12.2K`），
  100 及以上取整（`517K`，不是 `517.0K`）
- 100 万以上：`M`，同样规则
- 边界：`999999` → **`1000K`**（不是 `1M`）。桌面端对**缩放后**的值取整，
  999.999 个千位进位成 1000，单位仍是千。

**部分命中绝不能显示成 `100%`**：常规答案是取整后的百分比；当取整会得到 100
而实际仍有未命中的 token 时，改用 `99.9…X` 形式，保留刚好够用的精度。
百分比用**整数二分**算，不经过浮点。### `session/follow` 的 args

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

### 一轮 = 多条 `assistant/message`（1.1.4 起用于合并显示）

**一次对话（turn）会产生很多条 `assistant/message`，不是一条。** 每条对应一个
*step*，而一步就是一次模型调用，所以「读三个文件再跑一条命令」这样一轮会写下五到六条
结算。实测 41 份会话日志：一轮平均 **29 条**，最多的一轮 **830 条**。

把 `data.turn` 用来分组即可还原成一条回答。这个字段**在实测数据里 100% 存在**：

| 事件 | `data.turn` 存在 / 总数 |
|---|---|
| `assistant/message` | 4126 / 4126 |
| `tool/call` | 5056 / 5056 |
| `tool/result` | 5093 / 5093 |

`data.step` 同样存在，但**分组要用 `turn` 而不是 `step`**：`step` 是每一轮内部
重新计数的，跨轮不可比。

三条规则是客户端自己定的，服务端没有对应约定：

1. **`turn` 缺失的行是分界**。`llm/retry`、`approval/asked`、`turn/end` 都不带
   `turn`，所以「跨过提示去合并」在结构上不可能发生，而不是靠一条要记得遵守的规则。
   实测 313 轮里，201 轮被 `approval/asked` 正确截断、7 轮被 `llm/retry` 截断。
2. **`tool/result` 找不到对应 assistant 消息时会自建一张卡**，这张卡也带 `turn`，
   因此会并入所属的那一轮，而不会把一轮从中间切成两半。
3. **只合并显示**。折叠器内部的列表必须保持日志原样，因为
   `callId → (messageIndex, blockIndex)` 索引寻址的是那份列表。

对 35 份真实日志的验证（`mergeTurns` 前后）：

| 检查 | 结果 |
|---|---|
| 行数 4439 → 549 | 折叠掉 87.6% |
| 重复的消息 id | 0 |
| 丢失的工具卡 | 0 |
| 丢失的工具输出 | 0 |
| 每组首个 id 发生变化 | 0 |

### 工具调用的参数形状（1.1.4 起用于生成标签）

`tool/call.data.name` 的取值来自 41 份日志里的 **5041 条**真实调用，分布是
`pwsh` 2228、`read` 917、`edit` 640、`write` 407、`grep` 306、`read_image` 126、
`web_fetch` 89、`job_output` 77、`todo_write` 43，其余 17 种都在 40 条以下
（含 `pash` 这个 `pwsh` 的拼写变体，也要处理）。

`data.arguments` 是 **JSON 字符串**，且**有两种形状**：

```json
{"command": "Get-ChildItem", "description": "List files"}          // 通常
{"arguments": {"url": "https://…"}, "name": "web_fetch"}            // 10 条这样
```

第二种是模型把参数多套了一层，**必须解包**，否则那次调用会显示成没有目标。
按工具取哪个参数是有讲究的：文件类取 `file_path`、命令类取 `command`、
搜索类取 `pattern`。若按「第一个字符串参数」去扫，`pwsh` 会显示成
`{"description": "List files"}` 而把真正的命令藏起来。

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
- **一轮对话有很多条 `assistant/message`**（平均 29 条，最多 830 条），
  靠 `data.turn` 分组才能还原成一条回答；`data.step` 每轮重新计数，不能用来分组。
- **`tool/call.data.arguments` 有两种嵌套形状**，多套一层的那种必须解包。
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

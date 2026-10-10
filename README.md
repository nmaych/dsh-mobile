# DSH Mobile

[![CI](https://github.com/nmaych/dsh-mobile/actions/workflows/ci.yml/badge.svg)](https://github.com/nmaych/dsh-mobile/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/nmaych/dsh-mobile)](https://github.com/nmaych/dsh-mobile/releases/latest)
[![License](https://img.shields.io/github/license/nmaych/dsh-mobile)](LICENSE)

**在手机上远程操控桌面版 DeepSeek Harness。** 也可以脱离桌面端，独立进行 API 对话。

扫码配对，同一个 Wi-Fi 下直接连上，**不需要端口转发**。支持应用内自动更新。

> 非官方客户端。DeepSeek Harness 是 DeepSeek 的产品，本项目与其无隶属关系。

---

## 下载安装

到 [Releases](https://github.com/nmaych/dsh-mobile/releases/latest) 下载最新的
`dsh-mobile-x.y.z.apk` 直接安装。

首次安装需要允许「安装未知来源应用」，系统会引导你到对应设置页。

装好之后不用再手动下载——应用内更新会自己检查新版本。

**系统要求**：Android 8.0（API 26）及以上。

---

## 它能做什么

### 远程控制桌面端

连上桌面端 `dsh web` 之后：

- 浏览桌面端的所有会话（标题、工作目录、最近活动）
- 打开任意会话，**实时**看到助手的流式输出
- 发送新消息、停止生成
- 新建会话、重命名会话
- **选择工作区**：新建的会话创建在你选中的工作区里，也可以直接填一个目录路径
  把它注册成工作区
- **更换模型**：按服务商挑选模型，支持推理强度的模型还能单独选强度
- **查看 token 消耗**：当前会话的累计用量，以及上下文窗口占用了多少
- **回答助手的提问**：助手用 `ask_user_question` 问你时，直接在手机上选或填写，
  它就会继续往下做（不需要回到电脑前）
- **复制对话**：把当前对话导出成 Markdown 放进剪贴板，带说话人标题、
  思考过程和工具调用，可以直接贴进笔记或 issue
- **查看工作区文件**：逐层浏览当前会话工作区里的目录并打开文件看内容
  （根目录就是该会话的 `cwd`，所以看到的正是那个对话里的 agent 能碰到的东西）

界面呈现与桌面端一致：用户气泡、助手回复、可折叠的「思考过程」、
可展开的工具调用卡片（含参数与输出）、以及 Markdown 正文
（代码块、标题、列表、引用、行内强调）。

> 模型与工作区都取自桌面端已有的接口（`session/modelCatalog`、
> `session/selectModel`、`workspace/*`），不需要额外装什么。
> token 用量来自桌面端的 `session/projections`；桌面端没有装
> token-meter 时这一项会自动隐藏，而不是显示成 0。

### 独立 API 对话

不依赖桌面端，直接调用任意 OpenAI 兼容接口（默认 `https://api.deepseek.com`）。
支持流式输出与 `reasoning_content` 思考内容展示，模型列表可从 `/v1/models` 拉取。

---

## 连接桌面端

### 第一步：在电脑上装 dsh-mobile-connect 插件

手机连桌面端走的是配套插件 **dsh-mobile-connect**（独立仓库：
[`nmaych/dsh-mobile-connect`](https://github.com/nmaych/dsh-mobile-connect)）。它在电脑上开一个
**有认证的**局域网入口，取代手工端口转发。

```sh
dsh plugin add dsh-mobile-connect
dsh web
```

启动后终端会多出一块面板：

```
┌─ DSH Mobile Connect ──────────────────────────────────────────────┐
│ 手机连接地址  http://192.168.1.5:19387                      │
│ 配对码        021088                                        │
│ 有效期        10 分钟                                        │
├────────────────────────────────────────────────────────────┤
│ 用 DSH Mobile 扫描下面的二维码，或手动输入上面的地址，          │
│ 然后填入配对码。配对一次即可，之后自动连接。                     │
└────────────────────────────────────────────────────────────┘
```

### 第二步：在手机上配对

1. 打开 DSH Mobile → 右上角设置 → 运行模式选「远程控制桌面端」
2. 点「**扫描二维码连接**」，把电脑上那个二维码框进去——地址和配对码会自动填好
   （扫不了就用下面的方式手输）
3. 手输的话：点「**搜索电脑**」——同一 Wi-Fi 下的电脑会直接出现在列表里
   （搜不到就手动填面板上显示的地址，例如 `192.168.1.5:19387`），
   再填面板上的 6 位**配对码** → 点「连接」

配对一次即可，之后每次打开应用自动连上。想断开就回设置点「断开连接」。

> 二维码里装的就是面板上那份地址和配对码（`dshmobile://pair?host=…&port=…&code=…`），
> 所以扫码和手输是同一件事，只是少打几个字。相机权限只在这个界面用得到，
> 没有相机的设备照样能装、能用手输配对码配对。

> 配对码 10 分钟内有效，且只能成功使用一次。连错 8 次会作废，需要在电脑上重新生成
> ——这是为了挡住暴力猜测。桌面端用 `/connect devices` 可以查看和移除已配对的手机。

### 不想装插件？

`dsh web` 只监听 loopback 且主动拒绝 `--host 0.0.0.0`，所以要自己把端口转到局域网：

<details>
<summary>手工打通端口的三种方式</summary>

**A. Windows 端口代理（管理员 PowerShell）**

```powershell
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=19387 connectaddress=127.0.0.1 connectport=19387
New-NetFirewallRule -DisplayName "DSH web" -Direction Inbound -LocalPort 19387 -Protocol TCP -Action Allow
```

**B. SSH 隧道**（电脑有 SSH 服务时）

```sh
ssh -L 0.0.0.0:19387:127.0.0.1:19387 user@电脑地址
```

**C. adb 反向代理**（手机用 USB 连电脑时最简单）

```sh
adb reverse tcp:19387 tcp:19387
```

然后在手机上：设置 → 「**高级：不装插件，直接粘贴链接**」→ 粘贴 `dsh web`
打印的整行 URL（含 `?token=…`）→ 点「用链接配对」。

> 这条路径下凭据绑定主机名与端口，换了 IP 或端口就要重新配对。
> 它也没有任何设备管理能力——端口一旦暴露，同网络内谁都能尝试访问。

</details>

---

## 应用内更新

设置页 → 「应用更新」→ 「检查更新」。有新版本时点「立即安装」：
先下载，再校验 SHA-256，最后交给系统安装器。

默认清单地址指向本仓库的最新 Release，通常不用改。想自己托管就在设置里改地址，
格式见 [docs/RELEASING.md](docs/RELEASING.md#更新清单格式)。

> 校验不通过的文件会被直接删除，**不会**交给安装器。

---

## 自行构建

克隆后有两种方式，`build.cmd` 会自动选择。

### 方式一：用系统已装的工具链（CI 走这条）

需要 JDK 17 和 Android SDK（platform 34 + build-tools 34.0.0）：

```sh
git clone https://github.com/nmaych/dsh-mobile.git
cd dsh-mobile
build.cmd
```

`build.cmd` 会调用仓库里的 Gradle wrapper，不需要你装 Gradle。

Linux / macOS：

```sh
cd app-project && ./gradlew assembleRelease
```

### 方式二：用仓库自带的工具链（结果可复现）

仓库可以自己下载一套 JDK 17 + Android SDK 34 + Gradle 8.7 到 `toolchain/`，
这样系统里什么都不用装：

```cmd
build.cmd bootstrap
build.cmd assembleRelease
```

约 2.2 GB，下载一次以后可离线构建。

> `toolchain/` 是 `.gitignore` 的——它是构建输入而不是源码，提交进去会让仓库
> 历史永久膨胀。CI 用方式一，所以它也不需要这份工具链。

### 其他任务

```cmd
build.cmd assembleDebug     :: 调试包
build.cmd clean             :: 清理
build.cmd bootstrap         :: 只下载工具链
```

产物在 `app-project\app\build\outputs\apk\`。

### 签名

**仓库不含任何签名密钥。** 想让本地构建产出已签名的发布包，把自己的密钥路径写进
`app-project/keystore.properties`：

```properties
storeFile=../keystore/dsh-release.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

这个文件已被 `.gitignore`。CI 则从 GitHub Secrets 读同一组值——四个 Secret 的名字
和配置方法见 [docs/RELEASING.md](docs/RELEASING.md)。

> 没有密钥时 `assembleRelease` 仍会构建，只是不签名。这样全新克隆和 PR 都能跑通编译检查。

指定版本号：

```powershell
$env:DSH_VERSION_CODE = "10200"
$env:DSH_VERSION_NAME = "1.2.0"
build.cmd assembleRelease
```

---

## 项目结构

```
dsh-mobile/
├── app-project/                     Android 工程
│   ├── gradlew / gradlew.bat        Gradle wrapper（CI 用）
│   └── app/src/main/java/ai/deepseek/dshmobile/
│       ├── MainActivity.kt          入口，组装界面与更新流程
│       ├── DshApp.kt                应用级单例
│       ├── data/
│       │   ├── Prefs.kt             设置持久化
│       │   ├── DshClient.kt         DSH 远程协议客户端
│       │   └── SessionParser.kt     会话事件 → 消息模型（含 token 用量）
│       ├── net/
│       │   ├── ChatApi.kt           OpenAI 兼容接口客户端
│       │   └── GatewayClient.kt     dsh-mobile-connect 网关客户端（配对、发现）
│       ├── ui/
│       │   ├── ChatViewModel.kt     状态与业务逻辑
│       │   ├── ChatScreen.kt        聊天界面
│       │   ├── SettingsScreen.kt    设置界面
│       │   ├── AppShell.kt          抽屉 + 页面切换 + 选择器对话框
│       │   ├── QrScan.kt            扫描配对二维码（Compose 入口）
│       │   ├── components/          Markdown、消息气泡、模型/工作区/用量组件
│       │   └── theme/               配色与排版
│       └── update/
│           └── UpdateManager.kt     检查 / 下载 / 校验 / 安装
├── .github/workflows/
│   ├── ci.yml                       编译检查（不需要密钥）
│   └── release.yml                  打 tag 自动发布
├── docs/
│   ├── PROTOCOL.md                  逆向出的 DSH 线上协议，含实测结果
│   ├── RELEASING.md                 发布流程与 Secrets 配置
│   └── UPDATE.md                    更新清单格式
├── test/                            协议端到端测试
├── tools/                           构建辅助脚本
└── build.cmd                        构建入口
```

---

## 兼容性

- 最低 Android 8.0（API 26），目标 API 34
- 已在 DSH `0.2.0-rc.2`（`hostProtocolVersion` 4）上完成协议实测

DSH 的远程协议没有版本协商。升级桌面端后如果接口有变动，客户端会在设置页显示
服务端返回的具体错误信息，而不是笼统的「失败」。

---

## 测试

`test/` 下有几个**离线**脚本，CI 每次提交都会跑，不需要 Android SDK：

```sh
node test/release-logic.test.mjs      # 版本号算术与 CHANGELOG 解析
node test/gateway-contract.test.mjs   # 与 dsh-mobile-connect 的端点契约
node test/feature-contract.test.mjs   # 工作区 / 模型 / token 的 wire 名
node test/session-fold.test.mjs       # 会话事件 → 对话记录的折叠规则
node test/regression-1.1.3.test.mjs   # 连接报错文案 / 子会话地址 / 芯片行布局
node test/regression-1.1.4.test.mjs   # edge-to-edge 系统栏 / 自动滚动让位 / 回合合并 / 工具标签
node test/regression-1.1.5.test.mjs   # 互斥 wire 字段 / 扫码竖屏 / 更新说明 Markdown / 应用内下载
node test/regression-1.1.6.test.mjs   # 横屏芯片对齐 / 超时判定与撤回 / token 口径 / 提问回答通道
node test/regression-1.1.7.test.mjs   # 提问通道的订阅与重连 / 下载状态 / 误报断连 / 复制 / 查看文件
node test/regression-1.1.8.test.mjs   # 长按复制单条 / AI 提供文件 / 对话时长 / 按工作区分组折叠
node test/repo-hygiene.test.mjs       # gradlew 可执行位、LF/shebang、workflow 调用位置
```

它们守的是「编译器看不见」的那类错误：改了一个端点名、写错一个参数键、
或者把某个状态的作用域放错，Kotlin 都能编过，但用户手机上就是功能不生效。
1.1.0 的配对失败和 1.1.2 的对话空白都属于这一类。1.1.3 的三个问题
（连接失败显示 OkHttp 英文原文、子会话打不开、顶部芯片行被挤成竖排）
同样是编译器拦不住的。1.1.4 的四个问题也是——一个 XML 里的十六进制颜色、
一个「每次增量都重新滚动」的修饰符行为、一个始终存在却没人用的 `turn` 字段、
以及把工具标识符当成描述来显示。

1.1.5 又是同一类，而且更隐蔽一点：`session/create` 的 `workspaceId` 和 `cwd`
**各自都是合法的 wire 名**，描述符校验和 Kotlin 编译器都不会报错，
只有服务端在运行时拒绝「两个都给」；扫描界面的方向写在**依赖模块自己的清单**里，
本仓库改不动；更新说明一直是 Markdown，只是被当成纯文本画了出来；
而应用内下载的问题是**少了一个按钮**——`UpdateManager.download` 根本没有调用方，
这种「代码写了但没人用」的缺口没有任何编译器会提。

1.1.7 的三个修复还是同一类，而且都是**「写对了，但没接上」**：提问通道
（`$events`）整条都实现对了，却只在五条连接路径（四条建连 + 一次模式切换）里的两条被订阅，
而且断了就再也不重连、重新订阅时还留着上一代流的 `clientId`；
下载流程的失败会从 flow 里抛出去，**直接终结那个正在更新界面的协程**，
于是一次失败的下载和一次没反应的点击在屏幕上完全一样；
`connected` 会被一次失败的**列表请求**清掉，而那只是一次可能和射频唤醒抢跑的请求。
两个新功能也各带一个同类的坑：`workspaceFiles` 的第一个参数是**会话查找**而不是普通值
（传工作区 id 会静默解析不到），`read` 的 `range` **每个字段都可选但参数本身必填**。

`repo-hygiene` 守的是另一类：**只在别人机器上坏掉的东西**。`gradlew` 缺可执行位
让 Linux CI 报 `./gradlew: Permission denied`（退出码 126），而 Windows 上
`core.filemode=false`，权限位在检出目录里根本不存在，本地怎么试都是好的。

另外有两个针对**真实服务端**的端到端脚本：

```sh
# 握手、会话列表、创建、订阅、发消息、停止
node test/e2e.mjs "http://127.0.0.1:19555/?token=…"

# 模型目录 + 一个真实模型回合
node test/modeltest.mjs "http://127.0.0.1:19555/?token=…"
```

它们用的请求信封与 Android 客户端逐字一致，可作为协议回归测试。
协议本身的细节记录在 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

---

## 许可

[MIT](LICENSE)

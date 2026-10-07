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
2. 点「**搜索电脑**」——同一 Wi-Fi 下的电脑会直接出现在列表里
   （搜不到就手动填面板上显示的地址，例如 `192.168.1.5:19387`）
3. 填面板上的 6 位**配对码** → 点「连接」

配对一次即可，之后每次打开应用自动连上。想断开就回设置点「断开连接」。

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
```

它们守的是「编译器看不见」的那类错误：改了一个端点名、写错一个参数键、
或者把某个状态的作用域放错，Kotlin 都能编过，但用户手机上就是功能不生效。
1.1.0 的配对失败和 1.1.2 的对话空白都属于这一类。1.1.3 的三个问题
（连接失败显示 OkHttp 英文原文、子会话打不开、顶部芯片行被挤成竖排）
同样是编译器拦不住的。

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

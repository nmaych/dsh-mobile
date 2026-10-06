# 贡献指南

欢迎 issue 和 PR。这个文件说清楚怎么跑起来、以及会被怎么审。

---

## 开发环境

需要 JDK 17 和 Android SDK（platform 34 + build-tools 34.0.0）。

```sh
git clone https://github.com/nmaych/dsh-mobile.git
cd dsh-mobile
build.cmd assembleDebug        # Windows
```

Linux / macOS：

```sh
cd app-project && ./gradlew assembleDebug
```

不想在系统里装东西的话，仓库可以自备一套工具链：

```cmd
build.cmd bootstrap            :: 下载 JDK + SDK + Gradle 到 toolchain\（约 2.2 GB）
```

用 Android Studio 直接打开 `app-project/` 也可以。

---

## 提交前

```sh
# 版本号与更新日志的解析逻辑
node test/release-logic.test.mjs

# 编译
build.cmd assembleDebug
```

CI 会跑同样的检查。`release-logic` 那套测试值得留意——它守的是版本号推导和
CHANGELOG 解析，写错的后果是**发布出一个让所有用户反复提示更新的包**。

---

## 代码风格

没有强制的格式化工具，但请跟周围的代码保持一致：

- **Kotlin**：4 空格缩进，Compose 函数用 `PascalCase`，其余 `camelCase`
- **注释写「为什么」**，不写「是什么」。代码本身已经说明它在做什么
- 面向用户的文案用中文，和现有界面一致

---

## 改协议相关代码时

`docs/PROTOCOL.md` 是从真实服务端逆向出来的记录，含实测结果。

如果 DSH 升级后接口有变动：

1. 先跑 `test/e2e.mjs` 确认哪里断了
2. 改代码
3. **把 `docs/PROTOCOL.md` 一起更新**——它存在的意义就是让下一个人不用再逆向一遍

`test/` 下的脚本用的是与 Android 客户端逐字一致的请求信封，所以它们同时也是
协议回归测试。

---

## PR 会被怎么审

1. CI 是否通过
2. 有没有把密钥、令牌或大文件带进来（见 [SECURITY.md](SECURITY.md)）
3. 面向用户的错误信息是否说清了**发生了什么、为什么、下一步做什么**
4. 有没有在正常状态下渲染成警告

第 3、4 条是这个项目比较在意的：默认值就是产品，不要让人先做一堆配置才能用。

---

## 发布

只有维护者能发版。流程见 [docs/RELEASING.md](docs/RELEASING.md)。

# 发布流程

本文写给仓库维护者。目标：**推一个 tag，其余全自动**——构建、签名、校验、
生成更新清单、发 Release，一步不落。

---

## 一次性设置

### 1. 准备签名密钥

发布包必须用一个固定密钥签名。**换密钥会导致已安装的用户无法覆盖升级**，
所以这一步只做一次，然后把这个密钥备份好。

如果还没有密钥：

```powershell
keytool -genkeypair -v `
  -keystore dsh-release.jks `
  -alias dsh `
  -keyalg RSA -keysize 2048 -validity 10950 `
  -dname "CN=DSH Mobile, O=Your Name, C=CN"
```

记下你输入的密码。

### 2. 把密钥转成 Base64

GitHub Secrets 只能存文本，所以要转一下：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("dsh-release.jks")) | Set-Clipboard
```

（在 Linux / macOS 上：`base64 -w0 dsh-release.jks`）

> **注意**：这里不要换行。`base64 -w0` 的 `-w0` 就是干这个的。

### 3. 配置四个 Secrets

仓库 → Settings → Secrets and variables → Actions → New repository secret：

| Secret | 值 |
|---|---|
| `DSH_KEYSTORE_BASE64` | 上一步得到的 Base64 字符串 |
| `DSH_KEYSTORE_PASSWORD` | keystore 密码 |
| `DSH_KEY_ALIAS` | 密钥别名，例如 `dsh` |
| `DSH_KEY_PASSWORD` | 密钥密码（多数情况下与 keystore 密码相同） |

配好之后，CI 里的 `release.yml` 就能签名了。

> **`ci.yml` 不需要任何 secret。** 这是有意的：fork 和 PR 必须能在没有密钥的情况下
> 跑通编译检查。只有打 tag 才会走签名流程。

---

## 发一个版本

### 1. 更新版本号和更新日志

**改 `CHANGELOG.md`**，加一段：

```markdown
## [1.2.0] - 2026-11-01

### 新增
- …
```

标题**必须**是 `## [x.y.z]` 的格式——发布流程靠正则从这段抓取更新说明，
写成别的样子用户就看不到更新内容了。

> 不需要改 `build.gradle.kts` 里的版本号。CI 会从 tag 推导，并覆盖进去。
> 本地构建仍然用文件里的默认值。

### 2. 提交并打 tag

```sh
git add CHANGELOG.md
git commit -m "chore: release 1.2.0"
git push

git tag v1.2.0
git push origin v1.2.0
```

### 3. 等 CI 跑完

`release.yml` 会依次：

1. 从 tag 推导 `versionName=1.2.0` 和 `versionCode=10200`
   （算法：`major*10000 + minor*100 + patch`，保证单调递增）
2. 从 Secrets 还原密钥
3. 构建签名发布包
4. **校验**安装包内的 `versionCode`/`versionName` 与 tag 一致，
   并且签名有效、已 zipalign——任何一项不过就**让发布失败**
5. 生成 `update.json`（含 SHA-256 和从 CHANGELOG 抓取的说明）
6. 发 Release，附上 APK 和 `update.json` 两个资产

### 4. 确认

打开 Releases 页面，应该能看到新版本和两个资产。

已安装的应用会在下次检查更新时读到
`https://github.com/nmaych/dsh-mobile/releases/latest/download/update.json`，
提示用户升级。

---

## 手动触发（不发 tag）

Actions → Release → Run workflow → 填版本号（例如 `1.2.0`）。

适合补发或测试流程。注意它仍然会创建 tag 和 Release。

---

## 为什么这样设计

**版本号从 tag 推导，而不是手写。** 因为 APK 里的 `versionCode`、
文件名里的版本号、`update.json` 里的 `versionCode` 三者必须一致——
只要有一个对不上，用户就会遇到「永远提示更新」或者「永远收不到更新」。
让它们来自同一个源头，就不存在写错的可能。

> 这不是假想的风险。本项目 `1.1.0` 的首次发布就踩过：APK 内部实际是
> `versionCode=1 / versionName=1.0.0`，而 `update.json` 写的是 `versionCode=2`。
> 结果是应用会反复提示同一个更新。现在构建流程里有一步专门校验这个。

**发布前校验，而不是发布后补救。** Release 是用户会真的装上去的东西，
一个签名不对或版本错乱的包比不发还糟。所以 `verify` 步骤在打包资产之前就把
版本号、签名、对齐全查一遍。

**APK 走 Release 资产，不进 git。** 每个版本 10 MB 的二进制如果提交进仓库，
历史会永久膨胀且无法真正删除。Release 资产本来就有稳定 URL，正好给更新器用。

**`ci.yml` 不碰密钥。** 这样任何人都能 fork 并跑通编译检查。

---

## 本地构建发布包

不依赖 CI：

```powershell
# 用仓库自带的工具链（推荐，结果可复现）
build.cmd bootstrap
build.cmd assembleRelease
```

或者用你自己的 JDK 17 + Android SDK 34，通过 Gradle wrapper：

```powershell
cd app-project
./gradlew assembleRelease
```

想让本地构建也签名，在 `app-project/keystore.properties` 里写：

```properties
storeFile=../keystore/dsh-release.jks
storePassword=你的密码
keyAlias=dsh
keyPassword=你的密码
```

> 这个文件已在 `.gitignore` 里，不会被提交。**不要把密钥和密码提交进仓库**——
> 密钥一旦泄露，任何人都能签出一个你的应用会自动安装的「更新」。

想指定版本号（默认取 `build.gradle.kts` 里的值）：

```powershell
$env:DSH_VERSION_CODE = "10200"
$env:DSH_VERSION_NAME = "1.2.0"
build.cmd assembleRelease
```

---

## 更新清单格式

`update.json` 由 CI 生成，格式如下。自己托管也可以用这个格式：

```json
{
  "versionCode": 10200,
  "versionName": "1.2.0",
  "apkUrl": "https://github.com/nmaych/dsh-mobile/releases/download/v1.2.0/dsh-mobile-1.2.0.apk",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "notes": "本次更新内容…",
  "mandatory": false
}
```

| 字段 | 说明 |
|---|---|
| `versionCode` | 整数，**大于**已安装版本才会提示更新 |
| `versionName` | 展示用 |
| `apkUrl` | APK 直链 |
| `sha256` | 下载后校验；不匹配就删除文件并报错，不会交给安装器 |
| `notes` | 更新说明，显示在设置页 |
| `mandatory` | 预留给强制更新，当前版本仅作记录 |

用户在设置里可以改成自己的地址。

---

## 排障

**Release 失败：`Secret DSH_KEYSTORE_BASE64 is not set`**

四个 Secrets 没配全。回到「一次性设置」。

**Release 失败：`versionCode 1 != 10200`**

tag 与构建产物不一致。最常见的原因是 tag 打错了（比如 `v1.2` 少了补丁号）。
删掉重打：

```sh
git push --delete origin v1.2
git tag -d v1.2
git tag v1.2.0 && git push origin v1.2.0
```

**用户反馈「一直提示更新同一个版本」**

`update.json` 里的 `versionCode` 必须严格大于 APK 内部的 `versionCode`。
本仓库的流程会自动保证这一点；如果你手工改了清单，检查这两个值。

**用户反馈「安装失败，签名不一致」**

换了签名密钥。Android 要求覆盖升级必须同一密钥。要么找回旧密钥，
要么让用户卸载重装（会丢本地设置）。

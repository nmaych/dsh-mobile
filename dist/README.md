# dist/

本目录是**本地构建的产物投放处**，内容不进版本库。

`.gitignore` 里排除了 `dist/*.apk` 和 `dist/update.json`，因为：

- 每个版本 10 MB 的二进制提交进 git，历史会永久膨胀且无法真正删除
- Release 资产本来就有稳定 URL，正好给应用内更新用

正式的发布包由 `.github/workflows/release.yml` 生成并挂到 GitHub Releases，
应用默认从那里读取清单：

```
https://github.com/nmaych/dsh-mobile/releases/latest/download/update.json
```

## 本地构建

```cmd
build.cmd assembleRelease
```

产物在 `app-project\app\build\outputs\apk\release\app-release.apk`。
想在这里留一份，自己复制过来即可：

```powershell
$v = "1.1.1"
Copy-Item app-project\app\build\outputs\apk\release\app-release.apk "dist\dsh-mobile-$v.apk"
node tools/write-dist-manifest.mjs $v "dist\dsh-mobile-$v.apk" `
  "https://github.com/nmaych/dsh-mobile/releases/download/v$v/dsh-mobile-$v.apk"
```

`write-dist-manifest.mjs` 自己算 SHA-256、自己从 `CHANGELOG.md` 抓更新说明，
调的是发布流程同一个 `buildManifest()`，所以本地清单和线上清单的算法一致。

> 不要用 `node tools/release-metadata.mjs manifest … | Out-File dist\update.json`
> 这种写法。PowerShell 会把命令输出按行拆成数组再拼回去，结果 JSON 被压成
> 一行；`Out-File -Encoding utf8` 还会额外写一个 BOM。上面这个脚本直接写文件，
> 两个问题都没有。

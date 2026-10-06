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
$v = "1.1.0"
Copy-Item app-project\app\build\outputs\apk\release\app-release.apk "dist\dsh-mobile-$v.apk"
$hash = (Get-FileHash "dist\dsh-mobile-$v.apk" -Algorithm SHA256).Hash.ToLower()
node tools/release-metadata.mjs manifest $v `
  "https://github.com/nmaych/dsh-mobile/releases/download/v$v/dsh-mobile-$v.apk" `
  $hash | Out-File -Encoding utf8 dist\update.json
```

第二条命令用的是发布流程同一套逻辑，所以本地清单和线上清单的算法一致。

> 用 `Out-File -Encoding utf8` 写 JSON 时，Windows PowerShell 会带一个 BOM。
> 本项目的更新解析器能容忍它，但如果你要拿这个文件喂给别的工具，
> 用 `[System.IO.File]::WriteAllText()` 或 `Set-Content -Encoding utf8NoBOM` 更稳妥。

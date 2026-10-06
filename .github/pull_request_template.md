<!--
  标题建议用 conventional commits 风格，例如：
    fix: 配对失败时不再显示笼统的「失败」
    feat: 设置页支持一键解除配对
-->

## 改了什么

<!-- 一两句话说明。如果是修 bug，说清原来错在哪。 -->

## 为什么

<!-- 关联 issue：Fixes #123 -->

## 怎么验证的

<!--
  说清你实际跑过什么。CI 过了不算验证——它只证明能编译。
  涉及界面改动的，贴个截图。
-->

- [ ] `node test/release-logic.test.mjs` 通过
- [ ] `build.cmd assembleDebug` 通过
- [ ] 在真机或模拟器上实际点过改动的界面

## 检查

- [ ] 没有把密钥、令牌或构建产物带进来（见 [SECURITY.md](../blob/main/SECURITY.md)）
- [ ] 改了协议相关代码的话，`docs/PROTOCOL.md` 一并更新了
- [ ] 面向用户的错误信息说清了**发生了什么、为什么、下一步做什么**

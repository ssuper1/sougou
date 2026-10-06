# 免 Root 构建

免 Root 方案将符号模块和 LSPatch 运行环境打入搜狗 APK，无需用户安装 LSPatch 管理器。默认源码构建仍生成原有的 Root / LSPosed 模块。

## 当前验证状态

| 方案 | 安装内容 | 状态 |
| --- | --- | --- |
| `companion` | 修改版搜狗 + 独立「搜狗符号」配置 App | 搜狗 20.17.0、ColorOS / Android 13 已短时实机验证，用户确认符号功能可用 |
| `embedded` | 一个搜狗 APK，内置配置页，增加「搜狗符号」桌面入口 | 开发原型；首次实机安装因启动库未按页对齐而闪退，已恢复双包版。对齐检查已修正，单包完整使用流程尚未验证 |

本次测试手机已有 LSPosed。日志确认修改版搜狗从自身 APK 加载模块且 `useManager=false`，但仍需在未安装 Root / LSPosed 的手机上做独立回归。搜狗 12.0 暂无免 Root 实机验证。以上均不代表长期稳定性验证。

## 双包版使用

1. 安装 `companion/sogou-20.17.0-nonroot-companion.apk` 和 `companion/sogousym-module.apk`。
2. 在系统中启用并选择搜狗输入法。
3. 打开独立「搜狗符号」，填写配置，点击「保存并应用」。
4. 在弹出的搜狗应用信息页中点「强行停止」，随后重新唤起搜狗键盘。

修改版搜狗与官方包签名不同，首次安装通常需要先卸载官方搜狗；卸载会删除其本地数据。保留另一款可用输入法以便切换。官方自动更新可能覆盖修改版或因签名不同而安装失败。

## 复现构建

准备原始搜狗 APK、Android SDK 和 JDK 21。脚本会校验固定版本的 LSPatch JAR；默认使用本机 Android 开发签名，更新时必须保留同一密钥。

```powershell
.\tools\nonroot\build.ps1 `
  -SogouApk .\build\nonroot\input\sogou-20.17.0.apk `
  -AndroidSdk D:\App\AndroidSdk `
  -Mode companion
```

`-Mode embedded` 构建单包原型，`-Mode both` 构建两种方案。支持输入版本代码 2180 和 2620；不能直接拿其他搜狗版本套用。

产物位于 `build/nonroot/<方案>/`。脚本执行 Release 构建、单元测试和 lint，并检查最终签名、原始 APK 与模块哈希、运行库页对齐和管理器依赖。单包重新打包后按 16 KB 对齐运行库。只有静态检查通过才替换最终 APK；这些检查不能替代实机启动及功能验证。生成的 `build-report.json` 默认 `deviceVerified=false`。

单包设计将配置页和私有配置 Provider 放在搜狗的 `:sogousym` 进程中，保存后只重启同 UID 的搜狗主进程。单包配置与双包配置分别存储，当前未实现自动迁移。

工具来源：[JingMatrix/LSPatch v1.2](https://github.com/JingMatrix/LSPatch/releases/tag/v1.2)。首次下载需要联网；脚本校验 JAR 的 SHA-256 为 `d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165`。

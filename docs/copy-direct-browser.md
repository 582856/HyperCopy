# 复制直达浏览器

设置入口位于“跳转通知”下方，标题为“复制直达浏览器”，说明为“修改复制直达使用的浏览器”，使用现有 MIUIX 下拉设置组件。默认“不修改”，可选择已安装且支持通用网页链接的浏览器。设置保存在本地，并同步至 libxposed 远程设置。

## 行为边界

- 仅替换 AI 引擎的网页复制类型（11）所使用的浏览器，不改变 HyperCopy 规则或系统应用识别优先级。
- 原生浏览器气泡同步更新名称、图标和点击目标；京东等应用直达不替换。HyperCopy 自行注入的 `hypercopy.copy_jump#` 气泡不修改。
- 选择“不修改”或所选浏览器不可用时沿用系统流程。切换浏览器设置无需重新扫描 DEX。
- 需要 LSPosed/Vector，并将 AI 引擎 `com.xiaomi.aicr` 纳入作用域；安装更新后重启 AI 引擎进程使新增 Hook 生效。

## 实现与验证

使用 DexKit 2.2.0 定位混淆入口，在 AI 引擎的 Application.attach 完成后安装 Hook。兼容旧版直接启动入口与新版返回 Intent 的原生气泡入口；新入口克隆原 Intent，仅替换浏览器组件和包名，保留 URL、标志及附加参数。通知图标替换限定在网页类型的通知生成调用内。

AI 引擎 4.12.20 已完成真机验证：普通网页显示并打开 Via，京东链接保留京东直达。Release 使用 R8 与资源压缩。新增设置的 AndroidTest 已构建，尚未执行；其他 AI 引擎版本仍需实际验证。

本版本未修改淘口令规则；含空格或额外前缀的口令能否命中，仍取决于用户现有规则。

## 参考来源

参考 HyperCeiler 的 [ChangeBrowserForAIEngine](https://github.com/ReChronoRain/HyperCeiler/blob/main/library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/aiengine/ChangeBrowserForAIEngine.kt)（AGPL-3.0）所描述的网页复制类型和 DEX 字符串定位点，使用本模块的现代 libxposed API 重新实现。未引入其 BaseHook、PrefsBridge 或其他模块功能。

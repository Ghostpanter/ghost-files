# 0.7.1-test4（测试版）

内置提权 + 侧栏提权入口整理。草稿测试包，未打正式 tag。

## 新功能：内置提权（不用另装 Shizuku）

- **Android 11+：** 设置 →「内置提权」→「配对并启动」，在通知栏用 RemoteInput 输入无线调试 6 位配对码；应用通过已有 libadb 连本机无线调试，用 `app_process` 拉起 shell-uid 服务，经 binder 回传。
- **Android 9/10：** 设置页给出需在电脑或另一台手机（可用本应用 USB/OTG ADB）执行的启动命令。
- **Root：** 可直接用 su 启动同一服务。
- 授权成功后会尝试给自己 `WRITE_SECURE_SETTINGS`；开机且有 Wi‑Fi 时自动打开无线调试并拉起服务。若 ROM 不允许改写安全设置，页面会提示需手动打开「无线调试」。
- **已安装并已授权外置 Shizuku 时优先外置**；否则用内置。文件浏览、安装来源 pm、空间分析 Shizuku 档、终端等调用统一走提权提供者选择。
- 服务校验 `callingUid`，只接受本应用 uid（或 root）调用。
- 设置页提供状态 / 配对 / 启动 / 停止 / 开机自动 / 说明（开发者选项可能被银行类应用提示风险）。

## 侧栏「提权访问」改进（真机反馈）

- **已授权后不再提示点击授权：** Root / Shizuku / 内置提权在已授权或可判定已授权时直接进入，不再弹「点击授权」类提示。
- **Shizuku 的 Android/data 与 Android/obb 合并成一条**（如「Shizuku · 已授权 · Android/data、obb」），点进去两者都能访问；内置提权生效时同一行名称改为「内置提权」。

## 验证说明（请先看）

本版在 CI/开发机上跑了全量单测与 Paparazzi 快照；**未做真机验证**的包括：

- 无线调试配对码通知栏输入与 SPAKE2 配对
- `app_process` PrivilegeServer 启动与 ContentProvider binder 回传
- `WRITE_SECURE_SETTINGS` 自授权与开机自动打开无线调试（各 ROM 差异）
- 内置提权下 Android/data、obb 实际列表 / 读写
- 外置 Shizuku 与内置并存时的自动切换
- ColorOS / HyperOS 上开发者选项与银行类应用提示表现

请在真机上按：设置 → 内置提权 → 配对并启动，再从侧栏进 Android/data 试一遍。

## 已知限制

- 内置提权依赖无线调试（或 Root / 外接 ADB）；关闭无线调试后服务会掉，需再启动或靠开机自动拉起。
- 部分 ROM 禁止第三方改 `adb_wifi_enabled`，自动开启会失败，需手动打开。
- PrivilegeServer 的 `exec` 回退路径不适合超长/二进制流；正常情况优先走本机 ADB shell 通道。

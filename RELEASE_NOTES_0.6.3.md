# 双栏文件管理器 v0.6.3

## 相对 v0.6.2

- **应用管理支持批量备份 APK**：右上角多选或长按进入多选，全选后可串行导出到 `Download/GhostFiles/apks`，带进度对话框。
- **批量备份应用数据（Root）**：侧栏已授权 Root 时，多选底部额外显示「备份数据 Root」；内部 `/data/data` 仍仅 Root 可读，**不是** MT 免授权方案。
- 单应用详情里增加「加入多选」。

## 说明（与本版相关）

- MT 所谓免授权进 data：外置 `Android/data` 在新系统仍要 SAF/Shizuku；内部 `/data/data` 靠改包注入，不能批量。本应用不做「免 Root 批量内部数据」。
- 本机服务里的 SMB 显示「暂未内置」是设计如此（服务端未实现）；连 NAS 请用侧栏「添加服务器」。

## 兼容

包名不变；targetSdk 34；四 ABI；Ghostpanter V1+V2 签名；16KB page zipalign；ColorOS 友好打包。

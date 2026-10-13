# 双栏文件管理器 v0.6.2

## 相对 v0.6.1

- **Root 菜单不再误导为「灰掉」**：侧栏「Root」始终可点；授权成功后显示「已授权」。`su` 探测兼容 Magisk/KernelSU 及 ColorOS 下常见绝对路径（`/system/bin/su` 等）与 `su -c` / `su 0` 两种调用。
- **本机 SMB 开关说明**：灰色是因为「本机开 SMB 服务」尚未实现，**不是**因为没给 Root。连 NAS/电脑请用侧栏「添加服务器」（SMB 客户端，无需 Root）。授权 Magisk 也不会点亮该开关。

## 兼容

包名不变；targetSdk 34；四 ABI；Ghostpanter V1+V2 签名；ColorOS 友好打包。

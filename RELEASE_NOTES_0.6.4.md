# 双栏文件管理器 v0.6.4

## 相对 v0.6.3

### Root 浏览 `/` / `/storage/emulated`
- **原因**：Magisk 授权后侧栏仍用 LocalFs（`java.io.File`）列目录；`File.listFiles()` 对 `/`、`/storage/emulated` 返回 null →「无权限读取」。侧栏「系统根目录 /」也错误地走了 LocalFs。
- **修复**：Root 已授权时，受限路径自动切到 Root `ShellFs`（su）；授权成功后刷新卡在 LocalFs 的窗格；「系统根目录 /」与「Root」均打开 Root 后端。

### 本机 SMB 服务（真正可用）
- 基于 JFileServer（**SMBv1**），默认端口 **4455**，**无需 Root**。
- 开关、设置、账号密码、共享目录、复制 URL 均可用。
- 电脑连接示例：`smbclient //手机IP/share -p 4455 -m NT1 -U ghost`
- Windows 需启用 SMB1 客户端，或使用支持自定义端口的文件管理器；连 NAS 仍用侧栏「添加服务器」（SMB2 客户端，与本机服务无关）。

## 兼容
包名不变；targetSdk 34；四 ABI；Ghostpanter V1+V2；extractNativeLibs；ColorOS 友好打包。

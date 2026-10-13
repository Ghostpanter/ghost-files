# 双栏文件管理器 v0.6.5

## 相对 v0.6.4

### 本机 SMB：SMBv1 → SMB2（2.1）
- 去掉 JFileServer SMBv1，改为进程内 **SMB2** 服务端（方言 **2.0.2 / 2.1**，优先 2.1）。
- 默认端口仍为 **4455**，**无需 Root**，也**不必再开 Windows SMB1 客户端**。
- 支持账号密码（默认 ghost）、匿名、共享目录、复制 URL；签名（HMAC-SHA256）。
- 电脑连接示例：
  - `smbclient //手机IP/share -p 4455 -U ghost`
  - Win11 24H2+：`New-SmbMapping -LocalPath Z: -RemotePath \\手机IP\share -TcpPort 4455 -UserName ghost`
- **限制**：Windows 资源管理器默认只连 445；SMB3 加密未启用；若客户端只提供 SMB3 方言会尝试 3.0.2（签名仍在完善，更稳妥用能协商 2.1 的客户端，或本机 FTP/SFTP/WebDAV）。

### 其它
- 包名不变；targetSdk 34；四 ABI；Ghostpanter V1+V2；extractNativeLibs；ColorOS 友好打包。

# 双栏文件管理器 v0.6.0

应用名改为「双栏文件管理器」（英文 TwinPane File Manager），启动图标更换为 TwinPane 双栏方案 A。

## 本版功能（诚实状态）

| 功能 | 状态 | 说明 |
|------|------|------|
| 品牌重命名 + TwinPane 图标 | 完成 | zh/en 应用名；自适应图标 |
| Markdown 预览 | 完成 | 内置简易渲染（非完整 CommonMark） |
| PDF 预览 | 完成 | 系统 PdfRenderer 翻页 |
| EPUB 预览 | 部分 | 抽取首个 HTML 章节 WebView 阅读 |
| 字体预览 | 完成 | TTF/OTF 样张 |
| 音乐标签 | 部分 | MP3 ID3 读 + ID3v1 写；FLAC/M4A 未做 |
| 加密容器 .gfenc | 部分 | AES-256-GCM 本应用格式；EncFS/gocryptfs/AES Crypt 桌面格式未兼容 |
| 分卷 ZIP | 完成 | 压缩对话框可设分卷 MB（zip4j） |
| 简易终端 | 部分 | 逐行 sh，非完整 PTY；有 Root 时走特权壳 |
| APKS/XAPK 解包 | 完成 | 解出内含 APK 到目录 |
| APK V1 签名 | 部分 | 仅 JAR/v1；无 zipalign / v2/v3 |
| SFTP ProxyJump | 完成 | 服务器编辑可填跳板机 |
| 蓝牙发送 | 部分 | 走系统蓝牙分享 Intent |
| Root 应用数据备份 | 部分 | tar /data/data；需 Root |
| 皮肤/图标包 | 取消 | 按确认不实现 |
| USB OTG 自定义驱动 (exFAT/NTFS) | 延期 | 需原生驱动 |
| Smali / 双开共存 / DEX 编辑 | 延期 | 工具链过重，下版再议 |

包名不变：`com.ghostpanter.files`。targetSdk 34，四 ABI，Ghostpanter V1+V2 签名。

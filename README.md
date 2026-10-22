**简体中文** | [English](README.en.md)

# 双栏文件管理器 (ghost-files)

[![最新版本](https://img.shields.io/github/v/release/Ghostpanter/ghost-files?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/Ghostpanter/ghost-files/releases/latest)
[![下载](https://img.shields.io/github/downloads/Ghostpanter/ghost-files/total?label=%E4%B8%8B%E8%BD%BD)](https://github.com/Ghostpanter/ghost-files/releases)
![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)
[![许可证 MIT](https://img.shields.io/github/license/Ghostpanter/ghost-files?label=%E8%AE%B8%E5%8F%AF%E8%AF%81)](LICENSE)

参照 **MT 管理器** 和 **MiXplorer** 做的安卓双栏文件管理器：MT 式左右双栏，Root / Shizuku 提权访问 `Android/data` 和系统目录，连接 **SMB / FTP / FTPS / SFTP / WebDAV**，本机也能当服务器。还内置了投屏（scrcpy）、ADB 工具、终端、空间分析和 VLC 播放器。传输队列针对局域网复制容易卡死的问题专门做过设计。

**下载：[最新正式版 APK](https://github.com/Ghostpanter/ghost-files/releases/latest)**（当前为 v0.7.0）

<p>
  <img src="docs/images/browse.png" width="200" alt="双栏浏览">
  <img src="docs/images/android-data.png" width="200" alt="通过 Shizuku 浏览 Android/data，包名文件夹显示应用图标">
  <img src="docs/images/space.png" width="200" alt="空间分析">
  <img src="docs/images/adb-tools.png" width="200" alt="设备工具">
</p>
<p>
  <img src="docs/images/tablet.png" width="560" alt="平板布局：侧边导航、双栏和详情面板">
  <img src="docs/images/terminal.png" width="200" alt="终端（深色）">
</p>

## 基本信息

- 包名 `com.ghostpanter.files`，minSdk 28（Android 9），targetSdk 34
- 一个通用安装包，含 `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`；V1 + V2 签名，`extractNativeLibs=true`，16KB 页对齐
- 签名证书 SHA-256（可用 `apksigner verify --print-certs` 校验）：
  `303564befb25c73f4673f9eafb7001d91fac741fed33e8c4dd91604b016fdc53`
- 界面语言：默认中文；设置 → 语言 可选「跟随系统 / 简体中文 / English」
- Kotlin + Jetpack Compose（Material 3）

## 功能（v0.7.0）

### 双栏浏览
- MT 式双栏：手机竖屏也始终左右并排，中间分隔条可拖动（双击恢复一半）；当前栏有蓝色描边，复制 / 移动默认目标是另一栏
- 每栏多标签页、面包屑路径、后退 / 前进、上级目录、两栏同步、交换窗口
- 多选、全选 / 反选 / 类选；选中后底栏显示数量和总大小，以及复制、移动、删除、更多
- 长按文件弹出操作菜单：复制 / 移动到对面、重命名、删除、打开方式、文本打开、压缩、解压、分享、书签、权限、复制路径、属性
- 排序、筛选、显示隐藏文件；回收站（本机存储）；书签；递归搜索（子串或 `*` `?` 通配）和内容搜索
- 属性：大小（文件夹递归统计）、权限、所有者、时间、SHA-256
- 图片、视频、APK 缩略图（Root / Shizuku / SAF / 网络位置也支持）

### 界面（HyperOS 清爽风格）
- 浅灰底加白色圆角卡片，单一蓝色强调色；浅色、深色、OLED 纯黑三种主题
- 平板和大屏：侧边导航栏、常驻侧边面板、每栏单独的路径栏、详情面板，可选布局模式和显示密度；支持键盘快捷键、右键菜单和拖放

### 提权访问
- **Root**（su，兼容 Magisk / KernelSU）：浏览整个 `/`，含 `/data`、隐藏和系统文件；chmod（含递归）、chown；软链接；重新挂载为读写
- **Shizuku**（免 Root，adb shell 权限）：访问 `Android/data`、`Android/obb`
- **SAF**：Android 11–12 授权 `Android/data`；U 盘 / SD 卡
- **包名文件夹显示应用图标**：`Android/data`、`obb`、`media`、`/data/data`、`/data/user`、`/data/app` 下以包名命名的文件夹显示对应应用的图标和名称

### 远程存储与本机服务
- 客户端：SMB2/3、FTP / FTPS、SFTP（支持跳板机 ProxyJump）、WebDAV / HTTPS
- 局域网扫描：mDNS + NetBIOS 名称查询 + 本网段端口探测；SMB 共享名自动识别（先试来宾 / 匿名）；扫描结果一键预填服务器表单
- 本机服务：FTP / FTPS、WebDAV / HTTPS、SFTP、SMB2（2.1，默认端口 4455，免 Root）；前台服务和通知，可选仅限局域网、Wi-Fi 断开自动停止，首次开启生成随机密码
- HTTP 远程管理（含二维码）
- 后台传输队列：暂停 / 继续 / 取消 / 重试（断点续传）、通知栏进度、速度和失败明细；同一服务器内的复制 / 移动在服务器端完成

### 投屏与 ADB 工具
- **内置投屏（scrcpy）**：连接其他安卓设备，投屏并控制；投屏时可录屏，直接存成 MP4（可带声音，不重新编码）
- **设备工具**：设备信息、截图、录屏、开启 tcpip 5555、高级重启、保持唤醒、关闭屏幕；远程应用管理（安装 APK / APKS / XAPK、把本机应用装过去、导出安装包）；进程列表、一键激活、logcat
- **ADB 文件访问**：把对方设备当成一个存储位置，在双栏里浏览、复制、移动；支持普通 ADB 和 ADB Root
- **USB / OTG 直连 ADB**：用数据线连接，对方打开「USB 调试」即可，不需要无线调试
- **终端**：macOS 终端风格，可在「本机」和「目标设备」之间切换；多标签、历史记录、Tab 补全、Ctrl-C/D/L、Ctrl/Alt 粘滞键、双指缩放字体、常用命令收藏（可导入导出 JSON）

> ADB 首次使用需要在目标设备「开发者选项 → 无线调试」里配对；用 USB 连接时，首次需要在目标设备上点「允许 USB 调试」。

### 安装包与应用
- 安装 `.apk`，以及 `.apks` / `.xapk` / `.apkm` 和拆分 APK 文件夹：按本机自动选择合适的拆分包，可用系统安装器，也可用 Root / Shizuku 静默安装，自动复制 OBB
- APK 信息（图标、版本、SDK、权限、已装版本）
- 应用管理：批量导出 APK；有 Root 时可批量备份应用数据

### 空间分析
- 方块图显示哪些文件夹最占空间，可按类型、大小、修改时间筛选；另有「大文件」「重复」「空文件夹」列表
- 分三档：非 Root 扫描内部存储（授予「使用情况访问」后显示应用和系统占用）；Shizuku 额外读取 `Android/data` 和 `obb`，标出已卸载应用的残留，可清理应用缓存；Root 可分析整台设备。ADB 连接的设备也能分析
- 重复文件「保留最优，删除其他」：自动标出建议保留的一份，删除前确认数量和可释放空间，文件移入回收站
- 结果会缓存，下次打开立即显示并在后台增量刷新

### 查看、编辑与工具
- 内置 VLC 播放器（libVLC）播放视频：倍速、音轨 / 字幕选择、硬件解码开关、播完自动下一个；Root / Shizuku / SAF / 网络位置上的视频边读边播，无需先复制。音频用内置播放器
- 文本编辑器（编码、查找替换、行号、常见语法高亮）、十六进制查看与编辑
- 图片查看；Markdown、PDF、字体预览；EPUB 简易阅读
- 压缩 ZIP / 7z（可设 AES 密码，ZIP 可分卷）；解压 zip / tar* / 7z / RAR4 / ISO / CAB / Zstd / LZ4（RAR5 暂不支持）；ZIP 包内增删
- 批量重命名、文件 / 目录对比、文件加密（本应用的 `.gfenc` 格式）、配置导入导出

### 暂未实现
- WS-Discovery 发现、WebDAV Digest 认证（目前只支持 Basic）、Kerberos
- RAR5 解压

## 局域网稳定性设计

很多管理器复制一点东西就卡住，常见原因有：传输跑在界面线程或单条连接上；连接断开后没有超时，一直卡在阻塞读取；中途取消后还在等服务器的完成回复；整个文件读进内存。这里分别这样处理：

1. **后台任务队列**（`core/transfer/TransferEngine.kt`）：所有复制和移动都在 IO 协程里排队执行，同时运行的任务数可设（默认 2 个）。界面只订阅进度，不会被传输阻塞。
2. **前台服务 + WakeLock + 高性能 Wi-Fi 锁**：熄屏或切到后台时传输不会中断。服务只在有任务时运行。
3. **内存占用固定**：每个传输只用一块 256KB 缓冲区流式读写，多 GB 的文件也不会把内存撑爆。
4. **卡死看门狗**：45 秒没有任何字节进出，就主动关闭两端的流（SMB 放宽到单个请求的超时 + 15 秒），阻塞的读取会抛出异常并进入重试，而不是永远卡住。暂停和取消也是直接关流，能马上响应。
5. **指数退避重试 + 断点续传**：每个文件最多重试 5 次（1s→2s→4s…，最长 20s），重试前重建连接。SMB 和 SFTP 按偏移读写，FTP 用 REST / APPE，WebDAV 下载用 HTTP Range。传完后核对文件大小。
6. **超时与保活**：连接超时 10s，读超时 30s；SMB 单个请求超时 90s 并开启 TCP keepalive；SFTP 每 15s 发一次 keepalive；FTP 传输期间对控制连接发 NOOP；WebDAV 使用 OkHttp 连接池。
7. **浏览与传输分开连接**：每个传输任务对每台服务器单独建连接，任务结束即关闭；浏览目录用另一条连接，传大文件时浏览不用排队，坏掉的连接直接丢弃。
8. **FTP 中途取消不卡住**：没传完就关闭时直接断开数据连接，不再阻塞等待 `completePendingCommand()`。
9. **服务器端操作**：同一服务器内移动用 rename / MOVE；复制时 SMB 用服务器端 COPYCHUNK，WebDAV 用 COPY，SFTP 在服务器上执行 `cp`，Root / Shizuku 用 `cp -a`，数据不经过手机。
10. **大目录分批显示**：本机和 SMB 列目录时分批推送结果，几万个文件的目录也能先显示一部分。
11. **复制与删除源分开处理**：移动时，数据写完并校验大小后才删除源文件；删除源文件失败只重试 2 次，然后记为「已复制，但删除源文件失败：原因」，不会把整个文件重新传一遍。
12. **SMB 断线自动续接**：连接真的断开（Wi-Fi 抖动、NAS 断开空闲连接）时，读写流用新连接重新打开文件，从当前偏移继续，不计入重试。错误卡片会显示根本原因，可展开完整堆栈、复制错误和日志。
13. **SMB 卡顿自愈**：异步读取，单次读取 20 秒无响应就换新连接从当前偏移继续；读取块大小在 64KB–1MB 之间自适应；空闲连接每 30 秒发 SMB2 ECHO 保活；协商失败时自动降到 SMB 2.1 / 2.0.2。
14. **SFTP 安全**：首次连接记录主机指纹（TOFU），之后指纹变化就拒绝连接；密码用 Android Keystore 中的 AES-GCM 密钥加密保存。

## 构建

```sh
# 单元测试（core、ADB、投屏模块）和界面截图校验（Paparazzi，不需要模拟器）
./gradlew :core:test :adb-core:testDebugUnitTest :scrcpy:testDebugUnitTest :app:verifyPaparazziDebug

# 用本机临时 Samba 跑 SMB 集成测试和连接稳定性测试（需要 smbd 和 sudo）
scripts/test-samba

# 重新生成界面截图，输出到 app/src/test/snapshots/images
./gradlew :app:recordPaparazziDebug

# 生成签名包（签名材料不在仓库里）
KEY_PROPS=/path/to/key.properties scripts/build-apk
# 输出 artifacts/ghost-files.apk 和 .sha256
```

`scripts/build-apk` 会用 V1 + V2 签名并做 16KB 对齐，然后检查：`apksigner verify --min-sdk-version 23` 的 v1 和 v2 都为 true、targetSdk 34、四个 ABI 都在。

## 测试覆盖

- **传输队列**：跨后端复制和移动目录树；模拟断网后重试并从偏移处续传；看门狗打断卡死的流；暂停 / 继续 / 取消；冲突策略（覆盖 / 跳过 / 保留两者 / 续传）；超过 2 GiB 的文件
- **协议**：内嵌 Apache FtpServer 测 FTP，内嵌 Apache SSHD 测 SFTP（含主机指纹变化、服务器重启后重连），MockWebServer 测 WebDAV；`scripts/test-samba` 用临时 Samba 测 SMB
- **Shell 后端**（Root / Shizuku 用的就是它）：用本机 `sh` 跑同一套读写测试；隐藏文件、符号链接、chmod；toybox 输出解析
- **其他**：压缩解压（GBK 文件名、防 zip-slip）、局域网扫描、搜索、回收站；ADB 和投屏模块的单元测试；界面截图测试

## 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。

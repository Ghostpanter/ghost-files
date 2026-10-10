# Ghost 文件 (ghost-files)

参照 **MT 管理器** 与 **MiXplorer** 做的安卓文件管理器：MT 式双栏、Root / Shizuku 提权看隐藏与系统文件，以及 **SMB / FTP / FTPS / SFTP / WebDAV** 局域网连接。设计时重点处理了一个问题：很多管理器在局域网复制、移动时容易卡死，这里的传输队列针对这个问题专门做了设计。

- 包名 `com.ghostpanter.files` · minSdk 28 · **targetSdk 34**（兼容 ColorOS）
- 通用安装包：`arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`，V1 + V2 签名，`extractNativeLibs=true`
- Kotlin + Jetpack Compose（Material 3），默认中文，系统语言为英文时显示英文

## 功能

| 功能 | 状态 |
| --- | --- |
| 双栏浏览：平板 / 横屏左右并排，手机竖屏用顶部「左/右」切换；选中后「复制到对面」「移动到对面」 | ✅ |
| 每栏多标签页、路径面包屑、返回历史、上级目录、两栏同步 | ✅ |
| 多选（长按）、全选 / 反选、复制 / 剪切 / 粘贴剪贴板 | ✅ |
| 重命名、删除（可选回收站，本机存储）、新建文件 / 文件夹 | ✅ |
| 排序（名称 / 大小 / 时间 / 类型，升降序，文件夹优先）、当前目录筛选、显示隐藏文件开关 | ✅ |
| 属性：大小（文件夹递归统计）、权限、所有者、时间、SHA-256 | ✅ |
| Root（su）浏览整个 `/`，含 `/data`、隐藏与系统文件 | ✅ |
| Shizuku（免 Root，adb shell 权限）访问 `Android/data`、`Android/obb` | ✅ |
| SAF：Android 11–12 授权 `Android/data`；U 盘 / SD 卡目录 | ✅（基础功能） |
| 提权后修改权限 chmod（含递归）、chown | ✅（Root / Shizuku；SFTP / FTP 只支持 chmod） |
| SMB2/3（smbj）、FTP / FTPS（Commons Net）、SFTP（sshj）、WebDAV / HTTPS | ✅ |
| 后台传输队列：暂停 / 继续 / 取消 / 重试（续传）、通知栏进度、速度、失败明细 | ✅ |
| 同一服务器内复制 / 移动在服务器端完成，不经过手机 | ✅ |
| 递归搜索（子串或 `*` `?` 通配），本机 / Root / 局域网通用 | ✅ |
| 压缩 ZIP；解压 zip / tar / tar.gz / tar.xz / tar.bz2 / gz / xz / bz2 / 7z（含密码）/ RAR4 | ✅（RAR5、加密 zip 暂不支持） |
| 文本编辑器（UTF-8 / GBK 自动识别，8MB 以内） | ✅（基础功能，没有语法高亮和查找替换） |
| 图片查看（缩放、左右点击切换） | ✅ |
| APK 信息（图标、版本、SDK、权限、已装版本）与安装 | ✅ |
| 用其他应用打开、分享（远程文件先缓存再打开） | ✅ |
| 书签、存储空间占用 | ✅ |
| 局域网自动发现、SMB 共享名自动列出 | ⏳ 暂未实现（SMB 需要手动填写共享名） |
| WebDAV Digest 认证、FTP 主动模式穿透、Kerberos | ⏳ 暂未实现（WebDAV 目前只有 Basic） |
| 应用内播放视频 / 音频、十六进制编辑、APK 签名 / 反编译、文件对比 | ⏳ 暂未实现 |

## 局域网稳定性设计

很多管理器复制一点东西就卡住，常见原因有这几种：传输跑在界面线程或单条连接上；连接断开后没有超时，一直卡在阻塞读取；中途取消后还在等服务器的完成回复；整个文件读进内存。这里分别这样处理：

1. **后台任务队列**（`core/transfer/TransferEngine.kt`）：所有复制和移动都在 IO 协程里排队执行，同时运行的任务数可设（默认 2 个）。界面只订阅进度，不会被传输阻塞。
2. **前台服务 + WakeLock + 高性能 Wi-Fi 锁**：熄屏或切到后台时传输不会中断，Wi-Fi 也不会休眠。服务只在有任务时运行。
3. **内存占用固定**：每个传输只有一块 256KB 缓冲区，流式读写，多 GB 的文件也不会把内存撑爆。
4. **卡死看门狗**：45 秒没有任何字节进出，就主动关闭两端的流。阻塞在 socket 上的读取会因此抛出异常、进入重试，而不是永远卡住。暂停和取消也是直接关流，能马上响应。
5. **指数退避重试 + 断点续传**：每个文件最多重试 5 次（间隔 1s→2s→4s…，最长 20s），重试前会重建连接。两端都支持时，从目标文件已经写入的位置继续传：SMB 按偏移读写；FTP 用 REST / APPE；SFTP 按偏移读写；WebDAV 下载用 HTTP Range。失败的任务点「重试」也会续传。传完后会核对文件大小。
6. **超时与保活**：连接超时 10s，读超时 30s。SFTP 每 15s 发一次 SSH keepalive；FTP 在传输期间对控制连接发 NOOP；WebDAV 使用 OkHttp 连接池。
7. **按服务器复用连接**：SMB 和 SFTP 每台服务器共用一条多路复用连接；FTP 每台服务器维护最多 4 条已登录的连接（FTP 一条连接同时只能做一件事）。所以传大文件时，浏览目录不用排队；坏掉的连接直接丢弃，不会再被拿来用。
8. **FTP 中途取消不再卡住**：没传完就关闭时直接断开这条数据连接，不再阻塞等待 `completePendingCommand()`（这是常见的「取消后卡死」原因）。
9. **服务器端操作**：同一服务器内移动用 rename / MOVE；复制时 SMB 用服务器端 COPYCHUNK，WebDAV 用 COPY，SFTP 在服务器上执行 `cp`，Root / Shizuku 用 `cp -a`，数据不经过手机。
10. **大目录分批显示**：本机和 SMB 列目录时分批推送结果，几万个文件的目录也能先显示一部分；列表使用 LazyColumn。
11. **SFTP 安全**：首次连接时记录主机指纹（TOFU），之后指纹变化就拒绝连接，可以在服务器设置里重置。密码用 Android Keystore 中的 AES-GCM 密钥加密保存。

## 构建

```sh
# 运行单元测试（传输队列、FTP / SFTP / WebDAV 适配器、Shell 解析、压缩）
./gradlew :core:test

# 生成签名包（签名材料不在仓库里）
KEY_PROPS=/path/to/key.properties scripts/build-apk
# 输出 artifacts/ghost-files.apk 和 .sha256
```

`scripts/build-apk` 会先去掉没有签名保护的 META-INF 条目，再用 V1 + V2 重新签名，然后校验以下几项：`apksigner verify --min-sdk-version 23` 的 v1 和 v2 都为 true、targetSdk 34、四个 ABI 都在。

## 测试覆盖

`core` 模块的 JVM 测试（`./gradlew :core:test`）：

- 传输队列：跨后端复制和移动目录树；同一后端移动走 rename；模拟断网后重试并从偏移处续传；超过重试次数后记为失败，其余文件继续；看门狗打断卡死的流；暂停后继续；取消能在 3 秒内响应；冲突策略（覆盖 / 跳过 / 保留两者 / 续传）；拒绝复制到自身的子目录。
- 协议：内嵌 Apache FtpServer 测 FTP，包括连接池在传输进行时还能列目录、中途取消不卡住；内嵌 Apache SSHD 测 SFTP，包括主机指纹变化时拒绝连接、服务器重启后自动重连；用 MockWebServer 模拟的 WebDAV 服务器测 WebDAV，包括服务器端 COPY / MOVE 和 Basic 认证。三者都跑一遍统一的读写、续传、重命名、复制、删除测试。
- Shell 后端（Root / Shizuku 用的就是它）：用本机 `sh` 跑同一套测试；隐藏文件、符号链接、chmod；toybox 输出解析。
- 压缩：zip 压缩再解压、GBK 文件名、防 zip-slip、tar.gz；搜索；回收站。

SMB 没有找到可以嵌入 JVM 测试的服务器，所以没有自动化测试。

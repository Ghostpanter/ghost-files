# 双栏文件管理器 v0.6.8

## 修复
- **缩略图（Shizuku / Root / SAF / 网络）仍不显示**：根因是 0.6.7 缩略图临时文件用了 `File.createTempFile("t", …)`，Java 要求前缀至少 3 个字符，每次都抛 `IllegalArgumentException`，被吞掉后记成“无缩略图”。已修正并在模拟器上用与 Root 相同形态的 Shell 后端端到端验证（列表 → 缩略图出现）。本机 `/storage/emulated/0` 的缩略图走另一条路径，本来就正常。
- Root 常驻 shell：Magisk 的 su 进程把 stderr 合并进 stdout，0.6.7 的常驻会话在这种情况下每次都要等 5 秒超时再回退；现已兼容合并输出，不再卡顿。

## 新增：内置 VLC 播放器（libVLC 3.7.2）
- 点视频默认用内置 VLC 打开；长按菜单仍有「其他应用打开」。音频仍用原来的播放器。
- 播放/暂停、拖动进度、前进/后退 10 秒、上一个/下一个、时间显示、全屏横屏、音轨选择、字幕选择、倍速（0.5–3x）、硬件解码开关（记住设置）、播完自动下一个。
- 本机文件直接播放；Shizuku / Root / SAF / SMB / SFTP / WebDAV / FTP 上的视频通过本机回环 HTTP 代理（支持 Range 拖动）边读边播，无需先复制。
- 新增识别 rmvb / rm / mpg / mpeg / vob / m2ts / mts / ogv / asf / divx / f4v。

## 体积
- libVLC 四个 ABI 的原生库较大，通用 APK 体积明显增加。

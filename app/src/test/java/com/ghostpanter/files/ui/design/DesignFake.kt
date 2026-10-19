package com.ghostpanter.files.ui.design

/** Shared Chinese sample rows for all concept mockups. */
object DesignFake {
    data class Row(val name: String, val meta: String, val folder: Boolean, val selected: Boolean = false)

    val leftPath = "/存储/模拟的/0"
    val rightPath = "NAS · 影视"
    val left = listOf(
        Row("DCIM", "目录 · 128 项", true),
        Row("Download", "目录 · 42 项", true),
        Row("Movies", "目录 · 18 项", true),
        Row("音乐", "目录 · 56 项", true),
        Row("旅行照片.zip", "700 MB · 昨天", false, selected = true),
        Row("会议纪要.txt", "12 KB · 今天", false),
        Row("演示视频 1080p.mp4", "3.0 GB · 10-12", false),
        Row("app-release.apk", "15 MB · 10-18", false),
    )
    val right = listOf(
        Row("纪录片", "目录", true),
        Row("动画", "目录", true),
        Row("蓝光原盘", "目录", true),
        Row("流浪地球2.mkv", "28 GB", false),
        Row("三体.S01E01.mkv", "4.2 GB", false),
    )
    val devices = listOf(
        "Xiaomi 23049RAD8C" to "192.168.1.23:41235 · 在线",
        "Pixel 8 Pro" to "192.168.1.40:5555 · 离线",
    )
    val apps = listOf("微信", "支付宝", "哔哩哔哩", "抖音", "网易云音乐", "Shizuku")
    val drawer = listOf("本机存储", "Root", "Shizuku", "ADB · Xiaomi", "书签", "局域网", "本机服务", "回收站", "应用管理", "设备/投屏", "终端", "设置")
}

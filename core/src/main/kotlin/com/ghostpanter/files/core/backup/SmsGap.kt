package com.ghostpanter.files.core.backup

/** Vendor ROM families that hide some SMS from non-default apps even with READ_SMS. */
enum class SmsRom { MIUI, COLOROS, HUAWEI, VIVO, OTHER }

/**
 * Where the SMS rows come from (per box / per thread / per SIM / conversation totals),
 * so a short backup can be explained instead of guessed.
 */
data class SmsDiag(
    val rom: SmsRom,
    val romLabel: String,
    /** content://sms (all boxes, paged + per thread + per sub_id, de-duplicated). */
    val smsTotal: Int,
    /** inbox, sent, draft, outbox, failed, queued, all. */
    val byBox: Map<String, Int>,
    val mmsTotal: Int,
    /** Conversations listed by content://mms-sms/conversations. */
    val threads: Int,
    /** Sum of message_count over conversations (-1 = provider has no such column). */
    val threadMessageSum: Int,
    /** Rows found via per-thread content://sms thread_id queries. */
    val perThreadSms: Int,
    /** MIUI AppOp 10018 (通知类短信): true = allowed, false = denied, null = unknown / not MIUI. */
    val notificationSmsAllowed: Boolean?,
    val defaultSmsApp: String?,
    /** Visible rows per telephony sub_id (including -1 = restored / no SIM). */
    val bySubId: Map<Int, Int> = emptyMap(),
) {
    val readable: Int get() = smsTotal + mmsTotal
    val googleMessages: Boolean get() = defaultSmsApp?.contains("apps.messaging") == true ||
        defaultSmsApp == "com.google.android.apps.messaging"

    /** One-line summary for the backup result / logs. */
    fun summary(): String = buildString {
        append("短信 ").append(smsTotal)
        append("（收件箱 ").append(byBox["inbox"] ?: 0)
        append(" · 已发送 ").append(byBox["sent"] ?: 0)
        append(" · 草稿 ").append(byBox["draft"] ?: 0)
        val other = (byBox["outbox"] ?: 0) + (byBox["failed"] ?: 0) + (byBox["queued"] ?: 0)
        if (other > 0) append(" · 其他 ").append(other)
        append("）· 彩信 ").append(mmsTotal)
        append(" · 会话 ").append(threads)
        if (threadMessageSum >= 0) append("（会话内共 ").append(threadMessageSum).append(" 条）")
        if (perThreadSms > 0) append(" · 按会话查询 ").append(perThreadSms)
        if (bySubId.isNotEmpty()) {
            append(" · 卡槽 ")
            append(bySubId.entries.sortedBy { it.key }.joinToString(" / ") { (id, n) -> if (id < 0) "无卡/已恢复 $n" else "sub $id：$n" })
        }
        append(" · ").append(romLabel)
        when (notificationSmsAllowed) {
            true -> append(" · 通知类短信：已允许")
            false -> append(" · 通知类短信：未允许")
            null -> {}
        }
        defaultSmsApp?.let { append(" · 默认短信：").append(it.substringAfterLast('.')) }
    }
}

object SmsGap {
    enum class Verdict {
        /** System store looks complete relative to itself, and no migrated-app caveat. */
        OK,
        /** MIUI / HyperOS: 「读取通知类短信」 is off (AppOp 10018 denied). */
        MIUI_NOTIFICATION_SMS_DENIED,
        /** Conversations / message_count say there are many more rows than we can read (filter / sub_id / ROM). */
        HIDDEN_BY_FILTER,
        /** Few messages on a vendor ROM known to hide service / OTP SMS — likely, not proven. */
        SUSPECT_ROM_FILTER,
        /**
         * What we can read matches the system SMS store (threads ≈ rows),
         * but Google 信息 (or similar) can still show more that never entered the provider
         * (device-to-device restore, RCS / chat).
         */
        NOT_IN_PROVIDER,
    }

    fun isGoogleMessages(pkg: String?) = pkg?.contains("apps.messaging") == true

    /**
     * Compare the conversation list with the SMS/MMS rows this app is allowed to read.
     * A gap of ≥ 20 % (and ≥ 10 rows) means the provider is hiding rows from us.
     * If the two sides agree, extra threads in Google 信息 are outside the system store.
     */
    fun assess(d: SmsDiag): Verdict {
        val readable = d.readable
        if (d.rom == SmsRom.MIUI && d.notificationSmsAllowed == false) return Verdict.MIUI_NOTIFICATION_SMS_DENIED
        if (d.threadMessageSum >= 0) {
            val gap = d.threadMessageSum - readable
            if (gap >= 10 && gap * 5 >= d.threadMessageSum) return Verdict.HIDDEN_BY_FILTER
        }
        if (d.threads >= 5 && readable < d.threads) return Verdict.HIDDEN_BY_FILTER
        if (d.rom != SmsRom.OTHER && readable in 0..200) return Verdict.SUSPECT_ROM_FILTER
        // System store is internally consistent. If the default app is Google 信息,
        // a short (or any) count can still omit RCS / migrated history that never hit Telephony.
        if (d.googleMessages && providerAgrees(d)) return Verdict.NOT_IN_PROVIDER
        return Verdict.OK
    }

    /** Threads / message_count are not larger than what we can already read. */
    fun providerAgrees(d: SmsDiag): Boolean {
        val readable = d.readable
        if (d.threadMessageSum >= 0) {
            val gap = d.threadMessageSum - readable
            if (gap >= 10 && gap * 5 >= d.threadMessageSum) return false
            return true
        }
        if (d.threads >= 5 && readable < d.threads) return false
        return true
    }

    fun advice(d: SmsDiag): String? = advice(assess(d), d.rom, d.googleMessages, d.readable)

    /** Chinese guidance. [googleMessages] / [readable] refine the near-stock (Infinity-X / AOSP) case. */
    fun advice(v: Verdict, rom: SmsRom, googleMessages: Boolean = false, readable: Int = 0): String? = when (v) {
        Verdict.OK -> null
        Verdict.MIUI_NOTIFICATION_SMS_DENIED, Verdict.SUSPECT_ROM_FILTER -> when (rom) {
            SmsRom.MIUI -> "小米 / HyperOS 把验证码、银行、快递、平台通知等归为「通知类短信」，需要单独授权，否则只能读到普通短信。请点下方按钮，在本应用权限里打开「读取通知类短信」（设置 → 应用设置 → 应用管理 → 双栏文件管理器 → 应用权限 → 短信与彩信），返回后重新读取。"
            SmsRom.COLOROS -> "OPPO / 一加 / realme（ColorOS）开启「验证码安全保护」时，验证码和服务类短信不会给第三方应用。请打开系统「信息」→ 右上角 ⋮ → 设置 → 隐私保护 / 验证码安全保护，关闭后返回重新读取（部分版本在 设置 → 应用 → 系统应用 → 信息）。"
            SmsRom.HUAWEI -> "华为 / 荣耀开启「验证码安全保护」时，验证码类短信不会给第三方应用。请在 信息 → 更多 → 设置 → 高级 中关闭（或在设置里搜索「验证码安全保护」），返回后重新读取。"
            SmsRom.VIVO -> "vivo / iQOO 开启「验证码安全保护」时，验证码类短信不会给第三方应用。请在 设置 → 信息 → 隐私保护 中关闭，返回后重新读取。"
            SmsRom.OTHER -> null
        }
        Verdict.HIDDEN_BY_FILTER ->
            "系统会话列表里的条数比本应用能读到的多，多半是双卡 sub_id 过滤或系统对非默认短信应用做了限制。" +
                if (rom == SmsRom.MIUI) "也请检查是否未打开「读取通知类短信」。"
                else "本应用无法绕过这层限制；临时改成默认短信应用也不能保证读到 Google 信息自己库里的记录。"
        Verdict.NOT_IN_PROVIDER -> {
            val n = if (readable > 0) "系统短信库里目前能读到 $readable 条（含彩信），和系统会话列表一致。" else "系统短信库里目前能读到的条数，和系统会话列表一致。"
            val who = if (googleMessages) "Google「信息」" else "系统「信息」"
            n + "若 $who 里还能看到从旧手机恢复 / 迁移过来的对话，或标着 RCS /「聊天功能」的记录，它们往往只存在于「信息」自己的数据库，没有写入系统短信库，所以本应用和其他第三方备份都读不到。" +
                "请先备份系统库里这些已有的记录；旧历史需要在 Google 信息里自行查看或导出，或改用会写入系统短信库的迁移方式再传一次。" +
                "不要为了读这些记录而把本应用设为默认短信应用，那样也读不到「信息」私有库里的内容。"
        }
    }
}

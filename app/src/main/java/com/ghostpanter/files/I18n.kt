package com.ghostpanter.files

import com.ghostpanter.files.core.Lang

/** UI language: follows Settings → 语言 (system / 简体中文 / English); see [Lang]. */
object I18n {
    val isEn: Boolean get() = Lang.en
}

fun t(zh: String, en: String): String = if (Lang.en) en else zh

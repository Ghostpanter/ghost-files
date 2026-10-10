package com.ghostpanter.files

import java.util.Locale

/** Chinese by default; English when the system language is English. */
object I18n {
    val isEn: Boolean = Locale.getDefault().language == "en"
}

fun t(zh: String, en: String): String = if (I18n.isEn) en else zh

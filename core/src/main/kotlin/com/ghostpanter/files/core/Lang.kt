package com.ghostpanter.files.core

import java.util.Locale

/** UI language shared by app + core. Set by the app from Settings ("system" / "zh" / "en"). */
object Lang {
    @Volatile var en: Boolean = false

    fun apply(choice: String) {
        en = when (choice) {
            "en" -> true
            "zh" -> false
            else -> Locale.getDefault().language != "zh"
        }
    }
}

fun tr(zh: String, en: String): String = if (Lang.en) en else zh

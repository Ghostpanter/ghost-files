package com.ghostpanter.files.core.preview

/**
 * Pure extension → preview-kind routing used by the app and unit-tested here.
 * Keep in sync with UI openEntry / Format sets.
 */
enum class PreviewKind {
    IMAGE,
    PDF,
    DOCX,
    XLSX,
    MARKDOWN,
    EPUB,
    FONT,
    TEXT,
    /** doc / xls / ppt / pptx / unknown — hand off via FileProvider + ACTION_VIEW. */
    EXTERNAL,
}

object PreviewDispatch {
    val IMAGE_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "apng",
    )
    val FONT_EXT = setOf("ttf", "otf", "ttc", "otc", "woff")
    val TEXT_EXT = setOf(
        "txt", "log", "json", "xml", "yml", "yaml", "ini", "conf", "cfg", "prop", "properties", "sh", "py",
        "js", "ts", "kt", "kts", "java", "c", "h", "cpp", "hpp", "html", "htm", "css", "csv", "gradle", "toml",
        "sql", "bat", "lua", "rc", "smali", "list", "srt", "ass", "nfo", "env", "gitignore",
    )
    val MARKDOWN_EXT = setOf("md", "markdown")
    /** Built-in light preview. */
    val OFFICE_PREVIEW_EXT = setOf("docx", "xlsx")
    /** Known Office-ish that we only open externally. */
    val OFFICE_EXTERNAL_EXT = setOf("doc", "xls", "ppt", "pptx", "odt", "ods", "odp", "rtf")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

    fun kindOf(nameOrExt: String): PreviewKind {
        val ext = if (nameOrExt.contains('.')) extensionOf(nameOrExt) else nameOrExt.lowercase()
        return when {
            ext in IMAGE_EXT -> PreviewKind.IMAGE
            ext == "pdf" -> PreviewKind.PDF
            ext == "docx" -> PreviewKind.DOCX
            ext == "xlsx" -> PreviewKind.XLSX
            ext in MARKDOWN_EXT -> PreviewKind.MARKDOWN
            ext == "epub" -> PreviewKind.EPUB
            ext in FONT_EXT -> PreviewKind.FONT
            ext in TEXT_EXT -> PreviewKind.TEXT
            ext in OFFICE_EXTERNAL_EXT -> PreviewKind.EXTERNAL
            else -> PreviewKind.EXTERNAL
        }
    }

    /** True when the app should try an in-app preview before falling back to ACTION_VIEW. */
    fun hasInAppPreview(nameOrExt: String): Boolean = when (kindOf(nameOrExt)) {
        PreviewKind.EXTERNAL -> false
        else -> true
    }

    /**
     * Preview kind string used by [com.ghostpanter.files.ui.Screen.Preview], or null if not a Preview screen.
     */
    fun previewScreenKind(nameOrExt: String): String? = when (kindOf(nameOrExt)) {
        PreviewKind.PDF -> "pdf"
        PreviewKind.DOCX -> "docx"
        PreviewKind.XLSX -> "xlsx"
        PreviewKind.MARKDOWN -> "md"
        PreviewKind.EPUB -> "epub"
        PreviewKind.FONT -> "font"
        else -> null
    }
}

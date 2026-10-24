package com.ghostpanter.files.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewDispatchTest {
    @Test fun imageExts() {
        for (e in listOf("jpg", "JPEG", "png", "gif", "webp", "avif", "svg", "apng", "heic")) {
            assertEquals(e, PreviewKind.IMAGE, PreviewDispatch.kindOf("a.$e"))
        }
    }

    @Test fun officeInAppVsExternal() {
        assertEquals(PreviewKind.DOCX, PreviewDispatch.kindOf("x.docx"))
        assertEquals(PreviewKind.XLSX, PreviewDispatch.kindOf("x.xlsx"))
        assertEquals(PreviewKind.EXTERNAL, PreviewDispatch.kindOf("x.doc"))
        assertEquals(PreviewKind.EXTERNAL, PreviewDispatch.kindOf("x.xls"))
        assertEquals(PreviewKind.EXTERNAL, PreviewDispatch.kindOf("x.ppt"))
        assertEquals(PreviewKind.EXTERNAL, PreviewDispatch.kindOf("x.pptx"))
        assertTrue(PreviewDispatch.hasInAppPreview("a.docx"))
        assertFalse(PreviewDispatch.hasInAppPreview("a.pptx"))
    }

    @Test fun previewScreenKind() {
        assertEquals("pdf", PreviewDispatch.previewScreenKind("a.pdf"))
        assertEquals("docx", PreviewDispatch.previewScreenKind("a.docx"))
        assertEquals("xlsx", PreviewDispatch.previewScreenKind("a.xlsx"))
        assertEquals("md", PreviewDispatch.previewScreenKind("a.md"))
        assertNull(PreviewDispatch.previewScreenKind("a.jpg"))
        assertNull(PreviewDispatch.previewScreenKind("a.doc"))
    }

    @Test fun pdfAndFallback() {
        assertEquals(PreviewKind.PDF, PreviewDispatch.kindOf("pdf"))
        assertEquals(PreviewKind.EXTERNAL, PreviewDispatch.kindOf("unknownbin"))
    }
}

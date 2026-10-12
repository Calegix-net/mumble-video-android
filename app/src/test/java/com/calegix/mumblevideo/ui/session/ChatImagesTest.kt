package com.calegix.mumblevideo.ui.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImagesTest {
    // Bytes that encode with both + and / in base64, so the decoding paths are all exercised.
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 0xFB.toByte(), 0xFF.toByte())
    private val b64 = java.util.Base64.getEncoder().encodeToString(png)

    private fun onlyImage(html: String) {
        val parts = ChatImages.split(html)
        assertEquals(parts.toString(), 1, parts.size)
        val image = parts.single() as ChatImages.Part.Image
        assertArrayEquals(png, image.bytes)
    }

    @Test fun quoted() = onlyImage("""<img src="data:image/png;base64,$b64" />""")

    @Test fun singleQuotedAndUnquoted() {
        onlyImage("<img src='data:image/png;base64,$b64'>")
        onlyImage("<img src=data:image/png;base64,$b64>")
    }

    @Test fun extraParametersAndCase() = onlyImage("""<IMG alt="x" SRC="data:image/JPEG;charset=utf-8;base64,$b64">""")

    @Test fun wrappedEntityAndPercentEncoded() {
        val wrapped = b64.chunked(4).joinToString("&#10;")
        onlyImage("""<img src="data:image/png;base64,$wrapped">""")
        onlyImage("""<img src="data:image/png;base64,${b64.replace("+", "%2B").replace("/", "%2F")}">""")
    }

    @Test fun textAroundTheImageIsKept() {
        val parts = ChatImages.split("""look <img src="data:image/png;base64,$b64"> at this""")
        assertEquals(3, parts.size)
        assertEquals("look ", (parts[0] as ChatImages.Part.Html).html)
        assertTrue(parts[1] is ChatImages.Part.Image)
    }

    @Test fun remotePictureBecomesALinkNotAnObjectBox() {
        val part = ChatImages.split("""<img src="https://example.org/cat.gif">""").single() as ChatImages.Part.Html
        assertTrue(part.html, part.html.contains("href=\"https://example.org/cat.gif\""))
        assertTrue(!part.html.contains("<img"))
    }

    @Test fun brokenImagesNeverReachTheRenderer() {
        for (html in listOf("<img>", "<img src=\"data:image/png;base64,\">", "<img src=\"cid:foo\">")) {
            ChatImages.split(html).forEach { part ->
                if (part is ChatImages.Part.Html) assertTrue(html, !part.html.contains("<img", ignoreCase = true))
            }
        }
    }
}

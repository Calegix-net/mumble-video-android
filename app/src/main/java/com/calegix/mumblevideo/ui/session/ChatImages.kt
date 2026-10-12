package com.calegix.mumblevideo.ui.session

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Images in chat, the way every Mumble client does them: an `<img>` with a base64 `data:` URI
 * inside an ordinary text message, limited by the server's image message length (the whole
 * message, markup included). There is no separate upload.
 */
object ChatImages {
    /** Mumble's default `imagemessagelength`, used when the server does not say. */
    const val DEFAULT_LIMIT = 131_072

    /** Any `<img>` tag. Whatever it holds, it must not reach the HTML renderer, which shows an object box. */
    private val IMG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)

    /** The src attribute: double-quoted, single-quoted or bare, as different clients and Qt versions write it. */
    private val SRC = Regex("""\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    /** `data:image/<type>[;params];base64,<payload>`, parameters such as charset or name allowed. */
    private val DATA_URI =
        Regex(
            """^data:image/[^;,]+(?:;[^,]*)?;base64,(.*)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    /** A piece of a received message: markup to render as text, or an embedded image. */
    sealed interface Part {
        data class Html(val html: String) : Part

        class Image(val bytes: ByteArray) : Part
    }

    fun split(html: String): List<Part> {
        if (!html.contains("<img", ignoreCase = true)) return listOf(Part.Html(html))
        val parts = ArrayList<Part>()
        var last = 0
        for (match in IMG.findAll(html)) {
            val before = html.substring(last, match.range.first)
            if (before.isNotBlank()) parts += Part.Html(before)
            parts += imagePart(match.value)
            last = match.range.last + 1
        }
        val rest = html.substring(last)
        if (rest.isNotBlank()) parts += Part.Html(rest)
        return parts
    }

    private fun imagePart(tag: String): Part {
        val src = SRC.find(tag)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(::unescape)
            ?: return Part.Html("[picture]")
        DATA_URI.find(src)?.let { data ->
            // Payloads arrive line-wrapped, with entity-encoded newlines, or percent-encoded.
            val payload = percentDecode(data.groupValues[1]).filterNot { it.isWhitespace() }
            val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(payload) }.getOrNull()
            return if (bytes != null && bytes.isNotEmpty()) Part.Image(bytes) else Part.Html("[unreadable picture]")
        }
        // A picture by address. Not fetched, as in the desktop client: that would tell its host who
        // is reading. Shown as a link instead.
        if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) {
            val safe = src.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            return Part.Html("<a href=\"$safe\">picture: $safe</a>")
        }
        return Part.Html("[picture]")
    }

    /** %2B and friends only; unlike URLDecoder, a literal + stays a + (base64 needs it). */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val hex = if (value[i] == '%' &&
                i + 2 < value.length
            ) {
                value.substring(i + 1, i + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                out.append(hex.toChar())
                i += 3
            } else {
                out.append(value[i++])
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String = value
        .replace("&#10;", "\n")
        .replace("&#13;", "\r")
        .replace("&#43;", "+")
        .replace("&#47;", "/")
        .replace("&#61;", "=")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")

    /**
     * Encodes [bitmap] as a JPEG `<img>` message no longer than [limit] characters, shrinking the
     * picture and the quality until it fits. Returns null if even a thumbnail would not fit.
     * CPU-heavy: call off the main thread.
     */
    fun toMessage(bitmap: Bitmap, limit: Int): String? {
        val budget = if (limit > 0) limit else DEFAULT_LIMIT
        var longest = minOf(1600, max(bitmap.width, bitmap.height))
        while (longest >= 160) {
            val scaled = scale(bitmap, longest)
            for (quality in intArrayOf(85, 70, 55, 40)) {
                val jpeg = ByteArrayOutputStream().also {
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, it)
                }.toByteArray()
                val html = "<img src=\"data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}\"/>"
                if (html.length <= budget) return html
            }
            longest = (longest * 0.75).roundToInt()
        }
        return null
    }

    private fun scale(bitmap: Bitmap, longest: Int): Bitmap {
        val current = max(bitmap.width, bitmap.height)
        if (current <= longest) return bitmap
        val factor = longest.toFloat() / current
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).roundToInt(),
            (bitmap.height * factor).roundToInt(),
            true,
        )
    }
}

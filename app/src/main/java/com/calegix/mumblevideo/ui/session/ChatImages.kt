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

    private val IMG =
        Regex(
            """<img\b[^>]*?\bsrc\s*=\s*["']data:image/[a-zA-Z+.-]+;base64,([A-Za-z0-9+/=\s]+)["'][^>]*>""",
            RegexOption.IGNORE_CASE,
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
            val bytes = runCatching { Base64.decode(match.groupValues[1], Base64.DEFAULT) }.getOrNull()
            if (bytes != null) parts += Part.Image(bytes)
            last = match.range.last + 1
        }
        val rest = html.substring(last)
        if (rest.isNotBlank()) parts += Part.Html(rest)
        return parts
    }

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

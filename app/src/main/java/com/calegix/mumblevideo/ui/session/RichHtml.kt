package com.calegix.mumblevideo.ui.session

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/** The colours received HTML is drawn over and with, so its own colours can be kept readable on them. */
data class RichPalette(val background: Color, val content: Color, val link: Color)

/** A received HTML document (a welcome message, a server message) as blocks to lay out natively. */
data class RichDocument(val blocks: List<RichBlock>)

sealed interface RichBlock {
    enum class Align { Start, Center, End }

    /** A paragraph, heading or preformatted block. [marker] is the bullet or number of a list item. */
    data class Text(
        val text: AnnotatedString,
        val heading: Int = 0,
        val preformatted: Boolean = false,
        val align: Align = Align.Start,
        val indent: Int = 0,
        val marker: String? = null,
        val quote: Boolean = false,
    ) : RichBlock

    /**
     * An embedded picture that passed [ChatImages] and [ImageBounds], still to be decoded. [width] and
     * [height] are the size to show it at in CSS pixels (dp), 0 when neither the markup nor the header said.
     */
    class Image(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val align: Align,
        val href: String?,
        val description: String,
    ) : RichBlock

    data class Table(val rows: List<Row>, val width: Length?, val background: Color?) : RichBlock

    data class Row(val cells: List<Cell>, val background: Color?)

    data class Cell(
        val blocks: List<RichBlock>,
        val width: Length?,
        val background: Color?,
        val padding: Padding,
        val align: Align,
    )

    data object Rule : RichBlock

    /** A CSS length: pixels, or a percentage of the containing block. */
    data class Length(val value: Float, val percent: Boolean)

    data class Padding(val top: Int = 0, val end: Int = 0, val bottom: Int = 0, val start: Int = 0)
}

/**
 * Turns the HTML a Mumble server sends (welcome messages, messages from server-side bots) into
 * [RichBlock]s, roughly what the desktop client's QTextDocument shows: inline formatting, font
 * colours and sizes within reason, paragraphs, headings, lists, tables with their background colours,
 * links and embedded `data:` pictures.
 *
 * It is a renderer, not a browser: nothing in the markup is executed or fetched. Scripts, styles and
 * other active content are dropped with everything inside them; links keep only the schemes the
 * desktop client allows; pictures are only base64 `data:` URIs, within the chat picture limits and a
 * budget for the whole document; and every colour the server chose is adjusted until it reads
 * against the background it ends up on, so dark text cannot vanish on a dark theme.
 */
object RichHtml {
    /** Longest run of visible text kept, as for chat messages; the rest is cut. */
    const val MAX_TEXT_CHARS = ChatImages.MAX_TEXT_CHARS

    /** At most this many pictures in one document... */
    const val MAX_IMAGES = 64

    /** ...holding no more decoded pixels than this between them (32 MB at 4 bytes a pixel). */
    const val MAX_DOCUMENT_PIXELS = ImageBounds.MAX_DECODED_PIXELS * 2

    /** Tables nested deeper than this are flattened into their text. */
    const val MAX_TABLE_DEPTH = 6

    /** Open elements tracked at once; deeper markup is still read, its tags ignored. */
    private const val MAX_OPEN_ELEMENTS = 256

    /** WCAG AA for body text. */
    const val MIN_CONTRAST = 4.5

    /** The CSS font size that maps to the body text size. Qt's default chat font is about this. */
    private const val BASE_PX = 13f

    /** Schemes a link may use, the desktop client's list less its in-app `clientid` and `channelid`. */
    val ALLOWED_SCHEMES = setOf(
        "mumble", "http", "https", "gemini", "ftp", "spotify", "steam", "irc", "gg", "mailto", "xmpp",
        "skype", "rtmp", "magnet",
    )

    private val VOID = setOf(
        "br", "img", "hr", "meta", "link", "input", "col", "area", "base", "wbr", "source", "embed",
        "param", "track", "keygen",
    )

    /** Elements whose content is raw text up to their end tag, and never shown. */
    private val RAW_HIDDEN =
        setOf("script", "style", "title", "textarea", "xmp", "iframe", "noembed", "noframes", "noscript")

    /** Elements whose content is markup but not shown: metadata, forms, embedded objects. */
    private val HIDDEN =
        setOf("head", "template", "svg", "math", "object", "select", "button", "frameset", "applet", "canvas")

    private val BLOCK = setOf(
        "p", "div", "center", "blockquote", "pre", "ul", "ol", "li", "dl", "dt", "dd", "address", "section",
        "article", "header", "footer", "main", "nav", "aside", "figure", "figcaption", "caption", "form",
        "fieldset", "legend", "details", "summary", "h1", "h2", "h3", "h4", "h5", "h6", "body", "html",
    )

    fun parse(html: String, palette: RichPalette): RichDocument = Parser(palette).run(html)

    /** WCAG contrast ratio between two opaque colours, 1 to 21. */
    fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return if (la > lb) (la / lb).toDouble() else (lb / la).toDouble()
    }

    /**
     * [color] if it reads on [background], otherwise the nearest step towards black or white (whichever
     * contrasts more with the background) that does, so the hue the server chose survives where it can.
     */
    fun readable(color: Color, background: Color): Color {
        val bg = if (background.alpha < 1f) background.compositeOver(Color.Black) else background
        val fg = if (color.alpha < 1f) color.copy(alpha = 1f).let { lerp(bg, it, color.alpha) } else color
        if (contrast(fg, bg) >= MIN_CONTRAST) return fg
        val toward = if (contrast(Color.White, bg) >= contrast(Color.Black, bg)) Color.White else Color.Black
        for (step in 1..10) {
            val candidate = lerp(fg, toward, step / 10f)
            if (contrast(candidate, bg) >= MIN_CONTRAST) return candidate
        }
        return toward
    }

    /** A CSS or HTML colour: #rgb, #rrggbb, #rrggbbaa, rgb(), rgba() or a common name. Null if not one. */
    fun parseColor(value: String?): Color? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.startsWith("#") || (v.length == 6 && v.all { it.isHex() })) {
            val hex = v.removePrefix("#")
            if (!hex.all { it.isHex() }) return null
            return when (hex.length) {
                3 -> Color(
                    red = hex[0].digitToInt(16) * 17,
                    green = hex[1].digitToInt(16) * 17,
                    blue = hex[2].digitToInt(16) * 17,
                )

                6 -> Color(0xFF000000 or hex.toLong(16))

                8 -> Color(hex.substring(0, 6).toLong(16) or (hex.substring(6).toLong(16) shl 24))

                else -> null
            }
        }
        if (v.startsWith("rgb")) {
            val args = v.substringAfter('(', "").substringBefore(')').split(',', ' ', '/').filter { it.isNotBlank() }
            if (args.size < 3) return null
            fun channel(s: String): Int? = if (s.endsWith("%")) {
                s.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).toInt() }
            } else {
                s.toFloatOrNull()?.toInt()
            }?.coerceIn(0, 255)
            val r = channel(args[0]) ?: return null
            val g = channel(args[1]) ?: return null
            val b = channel(args[2]) ?: return null
            val a = args.getOrNull(3)?.let {
                if (it.endsWith("%")) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull()
            }?.coerceIn(0f, 1f) ?: 1f
            return Color(r, g, b, (a * 255).toInt())
        }
        return NAMED_COLORS[v]
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f'

    private val NAMED_COLORS = mapOf(
        "black" to Color(0xFF000000), "white" to Color(0xFFFFFFFF), "red" to Color(0xFFFF0000),
        "green" to Color(0xFF008000), "blue" to Color(0xFF0000FF), "yellow" to Color(0xFFFFFF00),
        "cyan" to Color(0xFF00FFFF), "aqua" to Color(0xFF00FFFF), "magenta" to Color(0xFFFF00FF),
        "fuchsia" to Color(0xFFFF00FF), "gray" to Color(0xFF808080), "grey" to Color(0xFF808080),
        "silver" to Color(0xFFC0C0C0), "maroon" to Color(0xFF800000), "olive" to Color(0xFF808000),
        "lime" to Color(0xFF00FF00), "teal" to Color(0xFF008080), "navy" to Color(0xFF000080),
        "purple" to Color(0xFF800080), "orange" to Color(0xFFFFA500), "pink" to Color(0xFFFFC0CB),
        "brown" to Color(0xFFA52A2A), "gold" to Color(0xFFFFD700), "darkgray" to Color(0xFFA9A9A9),
        "darkgrey" to Color(0xFFA9A9A9), "lightgray" to Color(0xFFD3D3D3), "lightgrey" to Color(0xFFD3D3D3),
        "darkred" to Color(0xFF8B0000), "darkgreen" to Color(0xFF006400), "darkblue" to Color(0xFF00008B),
        "orangered" to Color(0xFFFF4500), "violet" to Color(0xFFEE82EE), "indigo" to Color(0xFF4B0082),
        "crimson" to Color(0xFFDC143C), "coral" to Color(0xFFFF7F50), "salmon" to Color(0xFFFA8072),
        "khaki" to Color(0xFFF0E68C), "skyblue" to Color(0xFF87CEEB), "steelblue" to Color(0xFF4682B4),
        "royalblue" to Color(0xFF4169E1), "dodgerblue" to Color(0xFF1E90FF), "turquoise" to Color(0xFF40E0D0),
    )

    /** Decodes character references; unknown named ones are left as they are. */
    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = s.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val name = s.substring(i + 1, end)
            val decoded: String? = if (name.startsWith("#")) {
                val code = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) {
                    name.substring(2).toIntOrNull(16)
                } else {
                    name.substring(1).toIntOrNull()
                }
                code?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) }
            } else {
                ENTITIES[name]
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "middot" to "·", "bull" to "•", "hellip" to "…",
        "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "deg" to "°", "times" to "×", "divide" to "÷", "larr" to "←",
        "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔", "euro" to "€", "pound" to "£",
        "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶", "hearts" to "♥", "spades" to "♠",
        "clubs" to "♣", "diams" to "♦", "check" to "✓", "star" to "☆", "iexcl" to "¡", "iquest" to "¿",
        "plusmn" to "±", "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "sup2" to "²", "sup3" to "³",
        "micro" to "µ", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "‌",
        "zwj" to "‍",
    )

    /** What one open element contributes to the text inside it. Null fields inherit. */
    private class Element(
        val tag: String,
        val color: Color? = null,
        /** A background the element draws behind its own text (inline elements only). */
        val spanBackground: Color? = null,
        /** A background drawn by a table, row or cell, which its text must read against. */
        val boxBackground: Color? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        /** Font size relative to the body text, replacing the inherited one... */
        val sizeAbsolute: Float? = null,
        /** ...or multiplying it. */
        val sizeRelative: Float? = null,
        val family: FontFamily? = null,
        val letterSpacingEm: Float? = null,
        val baseline: BaselineShift? = null,
        val align: RichBlock.Align? = null,
        val href: String? = null,
        val hidden: Boolean = false,
        val preformatted: Boolean = false,
        /** For ol: the next item's number. */
        var counter: Int = 1,
        /** Tables past [MAX_TABLE_DEPTH], read as plain text. */
        val flattenedTable: Boolean = false,
    )

    private class CellBuilder(
        val width: RichBlock.Length?,
        val background: Color?,
        val padding: RichBlock.Padding,
        val align: RichBlock.Align,
    ) {
        val blocks = ArrayList<RichBlock>()
    }

    private class RowBuilder(val background: Color?) {
        val cells = ArrayList<RichBlock.Cell>()
        var open: CellBuilder? = null
    }

    private class TableBuilder(val width: RichBlock.Length?, val background: Color?, val cellPadding: Int) {
        val rows = ArrayList<RichBlock.Row>()
        var open: RowBuilder? = null
    }

    private class Parser(private val palette: RichPalette) {
        private val root = ArrayList<RichBlock>()
        private val stack = ArrayList<Element>()
        private val tables = ArrayList<TableBuilder>()

        private var paragraph: AnnotatedString.Builder? = null
        private var paragraphHeading = 0
        private var paragraphPre = false
        private var paragraphAlign = RichBlock.Align.Start
        private var paragraphIndent = 0
        private var paragraphQuote = false
        private var paragraphMarker: String? = null
        private var pendingMarker: String? = null
        private var pendingSpace = false
        private var lastWasBreak = true

        private var textChars = 0
        private var stopped = false
        private var images = 0
        private var imagePixels = 0L

        fun run(input: String): RichDocument {
            // Plain text, with no markup at all: its line breaks are meant.
            val html = if ('<' !in input) input.replace("\r\n", "\n").replace("\n", "<br>") else input
            feed(html)
            while (stack.isNotEmpty()) pop()
            flush()
            return RichDocument(root)
        }

        // --- Tokenizer ------------------------------------------------------------------------

        private fun feed(html: String) {
            var i = 0
            val n = html.length
            while (i < n && !stopped) {
                val lt = html.indexOf('<', i)
                if (lt < 0) {
                    text(html.substring(i))
                    break
                }
                if (lt > i) text(html.substring(i, lt))
                i = lt
                when {
                    html.startsWith("<!--", i) -> {
                        val end = html.indexOf("-->", i + 4)
                        i = if (end < 0) n else end + 3
                    }

                    i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?') -> {
                        val end = html.indexOf('>', i)
                        i = if (end < 0) n else end + 1
                    }

                    i + 1 < n && html[i + 1] == '/' -> {
                        val end = html.indexOf('>', i)
                        if (end < 0) {
                            i = n
                        } else {
                            val name = html.substring(i + 2, end).trim().takeWhile { it.isLetterOrDigit() }.lowercase()
                            if (name.isNotEmpty()) endTag(name)
                            i = end + 1
                        }
                    }

                    i + 1 < n && html[i + 1].isLetter() -> {
                        val (tag, next) = readTag(html, i)
                        i = next
                        if (tag.name in RAW_HIDDEN) {
                            // Skip to the matching end tag, whatever is inside.
                            val close = html.indexOf("</${tag.name}", i, ignoreCase = true)
                            i = if (close < 0) n else (html.indexOf('>', close).takeIf { it >= 0 } ?: (n - 1)) + 1
                        } else {
                            startTag(tag.name, tag.attrs, tag.selfClosing)
                        }
                    }

                    else -> {
                        text("<")
                        i++
                    }
                }
            }
        }

        private class Tag(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean)

        /** Reads `<name attr=value ...>` from [start]; returns the tag and the index after it. */
        private fun readTag(html: String, start: Int): Pair<Tag, Int> {
            var i = start + 1
            val n = html.length
            val nameStart = i
            while (i < n && (html[i].isLetterOrDigit() || html[i] == '-' || html[i] == ':')) i++
            val name = html.substring(nameStart, i).lowercase()
            val attrs = HashMap<String, String>()
            var selfClosing = false
            while (i < n) {
                while (i < n && html[i].isWhitespace()) i++
                if (i >= n) break
                val c = html[i]
                if (c == '>') {
                    i++
                    break
                }
                if (c == '/') {
                    selfClosing = true
                    i++
                    continue
                }
                val attrStart = i
                while (i < n && !html[i].isWhitespace() && html[i] != '=' && html[i] != '>' && html[i] != '/') i++
                val attr = html.substring(attrStart, i).lowercase()
                if (attr.isEmpty()) {
                    i++
                    continue
                }
                while (i < n && html[i].isWhitespace()) i++
                var value = ""
                if (i < n && html[i] == '=') {
                    i++
                    while (i < n && html[i].isWhitespace()) i++
                    if (i < n && (html[i] == '"' || html[i] == '\'')) {
                        val quote = html[i]
                        val end = html.indexOf(quote, i + 1).let { if (it < 0) n else it }
                        value = html.substring(i + 1, end)
                        i = minOf(end + 1, n)
                    } else {
                        val valueStart = i
                        while (i < n && !html[i].isWhitespace() && html[i] != '>') i++
                        value = html.substring(valueStart, i)
                    }
                }
                if (attr !in attrs) attrs[attr] = decodeEntities(value)
                selfClosing = false
            }
            return Tag(name, attrs, selfClosing) to i
        }

        // --- Elements -------------------------------------------------------------------------

        private fun startTag(name: String, attrs: Map<String, String>, selfClosing: Boolean) {
            if (hidden()) {
                if (name !in VOID && !selfClosing) push(Element(name, hidden = true))
                return
            }
            when (name) {
                "br" -> lineBreak()

                "img" -> image(attrs)

                "hr" -> {
                    flush()
                    sink().add(RichBlock.Rule)
                }

                "table" -> startTable(attrs)

                "tr" -> startRow(attrs)

                "td", "th" -> startCell(name, attrs)

                "li" -> startItem()

                else -> {
                    if (name in VOID || selfClosing) return
                    if (name == "p") closeOpen("p", stopAt = setOf("td", "th", "li", "table", "div", "blockquote"))
                    if (name in BLOCK) flush()
                    push(element(name, attrs))
                }
            }
        }

        private fun endTag(name: String) {
            val boundary = if (name == "table") emptySet() else setOf("table")
            val index = find(name, boundary)
            if (index < 0) {
                // A stray </p> is a paragraph break, as in browsers; other strays are ignored.
                if (name == "p" || name == "br") lineBreak()
                return
            }
            while (stack.size > index) pop()
        }

        private fun find(name: String, stopAt: Set<String>): Int {
            for (i in stack.indices.reversed()) {
                val tag = stack[i].tag
                if (tag == name) return i
                if (tag in stopAt) return -1
            }
            return -1
        }

        private fun closeOpen(name: String, stopAt: Set<String>) {
            val index = find(name, stopAt)
            if (index >= 0) while (stack.size > index) pop()
        }

        private fun push(element: Element) {
            if (stack.size < MAX_OPEN_ELEMENTS) stack += element
        }

        private fun pop() {
            val element = stack.removeAt(stack.lastIndex)
            if (element.hidden) return
            when (element.tag) {
                "td", "th" -> if (!inFlattenedTable()) closeCell() else pendingSpace = true
                "tr" -> if (!inFlattenedTable()) closeRow() else flush()
                "table" -> if (element.flattenedTable) flush() else finishTable()
                else -> if (element.tag in BLOCK) flush()
            }
        }

        private fun hidden() = stack.any { it.hidden }

        private fun inFlattenedTable(): Boolean {
            for (i in stack.indices.reversed()) if (stack[i].tag == "table") return stack[i].flattenedTable
            return false
        }

        private fun element(name: String, attrs: Map<String, String>): Element {
            val css = parseStyle(attrs["style"])
            if (name in HIDDEN || css["display"]?.trim() == "none") return Element(name, hidden = true)
            val color = parseColor(css["color"]) ?: parseColor(attrs["color"])?.takeIf { name == "font" }
                ?: parseColor(attrs["text"])?.takeIf { name == "body" }
            val background = (parseColor(css["background-color"]) ?: parseColor(css["background"]))
                ?.takeIf { name != "body" && name != "html" && it.alpha > 0f }
            val decoration = (css["text-decoration"] ?: css["text-decoration-line"])?.lowercase()
            val weight = css["font-weight"]?.trim()?.lowercase()
            val fontStyle = css["font-style"]?.trim()?.lowercase()
            val family = css["font-family"]?.lowercase() ?: attrs["face"]?.lowercase()?.takeIf { name == "font" }
            val (sizeAbsolute, sizeRelative) = fontSize(
                css["font-size"],
                attrs["size"]?.takeIf {
                    name == "font"
                },
                name,
            )
            val href = if (name == "a") safeHref(attrs["href"]) else null
            val heading = name.length == 2 && name[0] == 'h' && name[1] in '1'..'6'
            return Element(
                tag = name,
                color = color ?: if (href != null) palette.link else null,
                spanBackground = background?.takeIf { name !in BLOCK },
                boxBackground = background?.takeIf { name in BLOCK && name != "body" },
                bold = when {
                    weight == "bold" || weight == "bolder" -> true

                    weight?.toIntOrNull()?.let { it >= 600 } == true -> true

                    weight == "normal" || weight == "lighter" || weight?.toIntOrNull()?.let {
                        it < 600
                    } == true -> false

                    name == "b" || name == "strong" || name == "th" || heading || name == "dt" -> true

                    else -> null
                },
                italic = when {
                    fontStyle == "italic" || fontStyle == "oblique" -> true
                    fontStyle == "normal" -> false
                    name in ITALIC_TAGS -> true
                    else -> null
                },
                underline = when {
                    decoration != null && "underline" in decoration -> true
                    decoration != null && "none" in decoration -> false
                    name == "u" || name == "ins" -> true
                    href != null -> true
                    else -> null
                },
                strike = when {
                    decoration != null && "line-through" in decoration -> true
                    decoration != null && "none" in decoration -> false
                    name == "s" || name == "strike" || name == "del" -> true
                    else -> null
                },
                sizeAbsolute = sizeAbsolute,
                sizeRelative = sizeRelative,
                family = when {
                    family != null && MONO_FAMILIES.any { it in family } -> FontFamily.Monospace
                    family != null && "sans" in family -> FontFamily.SansSerif
                    family != null && "serif" in family -> FontFamily.Serif
                    name in MONO_TAGS -> FontFamily.Monospace
                    else -> null
                },
                letterSpacingEm = css["letter-spacing"]?.let(::pixels)?.let { (it / BASE_PX).coerceIn(0f, 0.3f) },
                baseline = when {
                    name == "sup" || css["vertical-align"]?.trim() == "super" -> BaselineShift.Superscript
                    name == "sub" || css["vertical-align"]?.trim() == "sub" -> BaselineShift.Subscript
                    else -> null
                },
                align =
                    align(css["text-align"] ?: attrs["align"]) ?: if (name ==
                        "center"
                    ) {
                        RichBlock.Align.Center
                    } else {
                        null
                    },
                href = href,
                preformatted = name == "pre",
                counter = attrs["start"]?.toIntOrNull() ?: 1,
            )
        }

        private fun fontSize(css: String?, fontAttr: String?, tag: String): Pair<Float?, Float?> {
            val v = css?.trim()?.lowercase()
            if (v != null) {
                pixels(v)?.let { return (it / BASE_PX).coerceIn(0.75f, 2f) to null }
                when {
                    v.endsWith("%") -> v.dropLast(1).toFloatOrNull()?.let {
                        return null to
                            (it / 100f).coerceIn(0.5f, 2f)
                    }

                    v.endsWith("em") -> v.dropLast(2).toFloatOrNull()?.let { return null to it.coerceIn(0.5f, 2f) }
                }
                CSS_SIZES[v]?.let { return it to null }
            }
            if (fontAttr != null) {
                val raw = fontAttr.trim()
                val size = when {
                    raw.startsWith("+") -> raw.drop(1).toIntOrNull()?.let { 3 + it }
                    raw.startsWith("-") -> raw.drop(1).toIntOrNull()?.let { 3 - it }
                    else -> raw.toIntOrNull()
                }?.coerceIn(1, 7)
                if (size != null) return FONT_SIZES[size - 1].coerceIn(0.75f, 2f) to null
            }
            return when (tag) {
                "small" -> null to 0.85f
                "big" -> null to 1.2f
                "sup", "sub" -> null to 0.8f
                else -> null to null
            }
        }

        /** px and pt lengths in CSS pixels; null for other units. */
        private fun pixels(value: String): Float? {
            val v = value.trim().lowercase()
            return when {
                v.endsWith("px") -> v.dropLast(2).trim().toFloatOrNull()
                v.endsWith("pt") -> v.dropLast(2).trim().toFloatOrNull()?.times(4f / 3f)
                v.isNotEmpty() && v.all { it.isDigit() || it == '.' } -> v.toFloatOrNull()
                else -> null
            }
        }

        private fun align(value: String?): RichBlock.Align? = when (value?.trim()?.lowercase()) {
            "center", "middle" -> RichBlock.Align.Center
            "right", "end" -> RichBlock.Align.End
            "left", "start", "justify" -> RichBlock.Align.Start
            else -> null
        }

        private fun safeHref(href: String?): String? {
            val url = href?.trim()?.filterNot { it.isISOControl() } ?: return null
            val scheme = url.substringBefore(':', "").lowercase()
            return url.takeIf { scheme in ALLOWED_SCHEMES && url.length > scheme.length + 1 }
        }

        // --- Text -----------------------------------------------------------------------------

        private fun text(raw: String) {
            if (raw.isEmpty() || stopped || hidden()) return
            val decoded = decodeEntities(raw)
            val pre = stack.any { it.preformatted }
            if (!pre && decoded.all { it.isHtmlSpace() }) {
                if (paragraph != null) pendingSpace = true
                return
            }
            ensureCellForStrayText()
            // Started first, so that the space this run may end in survives to the next one.
            if (paragraph == null) startParagraph()
            val out = StringBuilder()
            // A space left over from before this run is written unstyled, as the whitespace between
            // two elements belongs to neither: no underline or link reaching back over it.
            var leadingSpace = false
            if (pre) {
                out.append(decoded.replace("\r\n", "\n").replace('\r', '\n'))
            } else {
                for (c in decoded) {
                    if (c.isHtmlSpace()) {
                        pendingSpace = true
                    } else {
                        if (pendingSpace && !(lastWasBreak && out.isEmpty())) {
                            if (out.isEmpty()) leadingSpace = true else out.append(' ')
                        }
                        pendingSpace = false
                        out.append(c)
                    }
                }
            }
            if (out.isEmpty()) return
            if (leadingSpace) {
                paragraph?.let {
                    it.append(' ')
                    textChars++
                }
            }
            append(out.toString())
        }

        private fun Char.isHtmlSpace() = this == ' ' || this == '\n' || this == '\t' || this == '\r' || this == '\u000C'

        private fun append(s: String) {
            var chunk = s
            if (textChars + chunk.length > MAX_TEXT_CHARS) {
                chunk = chunk.take(MAX_TEXT_CHARS - textChars) + " … [message cut]"
                stopped = true
            }
            textChars += chunk.length
            val builder = paragraph ?: startParagraph()
            val (style, href) = currentStyle()
            if (href != null) {
                // The link's look is given explicitly, or Text adds its own underline even where the
                // server asked for none.
                val look = (style ?: SpanStyle()).let {
                    it.copy(
                        textDecoration =
                            it.textDecoration ?: TextDecoration.None,
                    )
                }
                builder.withLink(LinkAnnotation.Url(href, TextLinkStyles(style = look))) {
                    appendStyled(builder, style, chunk)
                }
            } else {
                appendStyled(builder, style, chunk)
            }
            lastWasBreak = false
        }

        private fun appendStyled(builder: AnnotatedString.Builder, style: SpanStyle?, s: String) {
            if (style == null) {
                builder.append(s)
            } else {
                val index = builder.pushStyle(style)
                builder.append(s)
                builder.pop(index)
            }
        }

        private fun lineBreak() {
            if (hidden()) return
            val builder = paragraph ?: startParagraph()
            builder.append('\n')
            pendingSpace = false
            lastWasBreak = true
        }

        /** The merged style of the open elements, with its colours made readable, and the link if any. */
        private fun currentStyle(): Pair<SpanStyle?, String?> {
            var color: Color? = null
            var spanBackground: Color? = null
            var background: Color = palette.background
            var bold: Boolean? = null
            var italic: Boolean? = null
            var underline: Boolean? = null
            var strike: Boolean? = null
            var size = 1f
            var family: FontFamily? = null
            var spacing: Float? = null
            var baseline: BaselineShift? = null
            var href: String? = null
            for (e in stack) {
                e.color?.let { color = it }
                e.boxBackground?.let {
                    background = it.compositeOver(background)
                    spanBackground = null
                }
                e.spanBackground?.let {
                    background = it.compositeOver(background)
                    spanBackground = background
                }
                e.bold?.let { bold = it }
                e.italic?.let { italic = it }
                e.underline?.let { underline = it }
                e.strike?.let { strike = it }
                e.sizeAbsolute?.let { size = it }
                e.sizeRelative?.let { size = (size * it).coerceIn(0.75f, 2f) }
                e.family?.let { family = it }
                e.letterSpacingEm?.let { spacing = it }
                e.baseline?.let { baseline = it }
                e.href?.let { href = it }
            }
            val wanted = color ?: palette.content
            val fixed = readable(wanted, background)
            val decorations = listOfNotNull(
                TextDecoration.Underline.takeIf { underline == true },
                TextDecoration.LineThrough.takeIf { strike == true },
            )
            val style = SpanStyle(
                color = if (color == null && fixed == palette.content) Color.Unspecified else fixed,
                background = spanBackground ?: Color.Unspecified,
                fontWeight = bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
                fontStyle = italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                fontSize = if (size != 1f) size.em else TextUnit.Unspecified,
                fontFamily = family,
                letterSpacing = spacing?.em ?: TextUnit.Unspecified,
                baselineShift = baseline,
            )
            return (if (style == SpanStyle()) null else style) to href
        }

        // --- Blocks ---------------------------------------------------------------------------

        private fun startParagraph(): AnnotatedString.Builder {
            ensureCellForStrayText()
            val builder = AnnotatedString.Builder()
            paragraph = builder
            var heading = 0
            var align: RichBlock.Align? = null
            var indent = 0
            var quote = false
            for (e in stack) {
                if (e.tag.length == 2 && e.tag[0] == 'h' && e.tag[1] in '1'..'6') heading = e.tag[1] - '0'
                e.align?.let { align = it }
                if (e.tag == "td" || e.tag == "th") {
                    // A cell starts afresh: indents outside the table do not reach into it.
                    indent = 0
                    quote = false
                }
                if (e.tag == "ul" || e.tag == "ol" || e.tag == "dd") indent++
                if (e.tag == "blockquote") {
                    indent++
                    quote = true
                }
            }
            paragraphHeading = heading
            paragraphPre = stack.any { it.preformatted }
            paragraphAlign = align ?: RichBlock.Align.Start
            paragraphIndent = indent.coerceAtMost(6)
            paragraphQuote = quote
            paragraphMarker = pendingMarker
            pendingMarker = null
            pendingSpace = false
            lastWasBreak = true
            return builder
        }

        private fun flush() {
            val builder = paragraph ?: return
            paragraph = null
            pendingSpace = false
            lastWasBreak = true
            val text = builder.toAnnotatedString()
            var end = text.length
            while (end > 0 && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
            if (end == 0) {
                if (paragraphMarker != null) pendingMarker = paragraphMarker
                return
            }
            sink().add(
                RichBlock.Text(
                    text = if (end == text.length) text else text.subSequence(0, end),
                    heading = paragraphHeading,
                    preformatted = paragraphPre,
                    align = paragraphAlign,
                    indent = paragraphIndent,
                    marker = paragraphMarker,
                    quote = paragraphQuote,
                ),
            )
        }

        private fun sink(): MutableList<RichBlock> = tables.lastOrNull()?.open?.open?.blocks ?: root

        private fun startItem() {
            flush()
            val list = stack.lastOrNull { it.tag == "ul" || it.tag == "ol" }
            closeOpen("li", stopAt = setOf("ul", "ol", "table"))
            pendingMarker = when {
                list == null -> "•"

                list.tag == "ol" -> "${list.counter++}."

                else -> when (stack.count { it.tag == "ul" } % 3) {
                    1 -> "•"
                    2 -> "◦"
                    else -> "▪"
                }
            }
            push(Element("li"))
        }

        private fun currentTableElement(): Element? = stack.lastOrNull { it.tag == "table" }

        private fun startTable(attrs: Map<String, String>) {
            flush()
            val css = parseStyle(attrs["style"])
            if (tables.size >= MAX_TABLE_DEPTH) {
                push(Element("table", flattenedTable = true))
                return
            }
            val element = element("table", attrs)
            if (element.hidden) {
                push(element)
                return
            }
            val background =
                parseColor(attrs["bgcolor"]) ?: parseColor(css["background-color"]) ?: parseColor(css["background"])
            tables += TableBuilder(
                width = length(css["width"] ?: attrs["width"]),
                background = background?.takeIf { it.alpha > 0f },
                cellPadding = attrs["cellpadding"]?.let(::pixels)?.toInt()?.coerceIn(0, 16) ?: 1,
            )
            push(
                Element(
                    tag = "table",
                    color = element.color,
                    boxBackground = background?.takeIf { it.alpha > 0f },
                    bold = element.bold,
                    italic = element.italic,
                    sizeAbsolute = element.sizeAbsolute,
                    sizeRelative = element.sizeRelative,
                    family = element.family,
                ),
            )
        }

        private fun startRow(attrs: Map<String, String>) {
            if (currentTableElement()?.flattenedTable == true) {
                closeOpen("tr", stopAt = setOf("table"))
                flush()
                push(Element("tr"))
                return
            }
            val table = tables.lastOrNull()
            if (table == null || currentTableElement() == null) {
                flush()
                return
            }
            closeOpen("tr", stopAt = setOf("table"))
            closeRow()
            val css = parseStyle(attrs["style"])
            val background = (parseColor(attrs["bgcolor"]) ?: parseColor(css["background-color"]))?.takeIf {
                it.alpha >
                    0f
            }
            table.open = RowBuilder(background)
            push(Element("tr", boxBackground = background, align = align(css["text-align"] ?: attrs["align"])))
        }

        private fun startCell(name: String, attrs: Map<String, String>) {
            if (currentTableElement()?.flattenedTable == true) {
                closeOpen("td", stopAt = setOf("table"))
                closeOpen("th", stopAt = setOf("table"))
                pendingSpace = true
                push(Element(name))
                return
            }
            val table = tables.lastOrNull()
            if (table == null || currentTableElement() == null) {
                flush()
                return
            }
            closeOpen("td", stopAt = setOf("table", "tr"))
            closeOpen("th", stopAt = setOf("table", "tr"))
            if (table.open == null) {
                table.open = RowBuilder(null)
                push(Element("tr"))
            }
            flush()
            val element = element(name, attrs)
            val css = parseStyle(attrs["style"])
            val background = (parseColor(attrs["bgcolor"]) ?: element.boxBackground ?: element.spanBackground)?.takeIf {
                it.alpha >
                    0f
            }
            val padding = padding(css, table.cellPadding)
            val cellAlign = align(css["text-align"] ?: attrs["align"])
            table.open!!.open = CellBuilder(
                width = length(css["width"] ?: attrs["width"]),
                background = background,
                padding = padding,
                align = cellAlign ?: stack.lastOrNull { it.tag == "tr" }?.align ?: RichBlock.Align.Start,
            )
            push(
                Element(
                    tag = name,
                    color = element.color,
                    boxBackground = background,
                    bold = element.bold,
                    italic = element.italic,
                    underline = element.underline,
                    strike = element.strike,
                    sizeAbsolute = element.sizeAbsolute,
                    sizeRelative = element.sizeRelative,
                    family = element.family,
                    letterSpacingEm = element.letterSpacingEm,
                    align = cellAlign,
                    hidden = element.hidden,
                ),
            )
        }

        /** Text inside a table but outside any cell goes into a cell of its own, as browsers show it. */
        private fun ensureCellForStrayText() {
            val table = tables.lastOrNull() ?: return
            val element = currentTableElement() ?: return
            if (element.flattenedTable) return
            if (table.open?.open != null) return
            startCell("td", emptyMap())
        }

        private fun closeCell() {
            val table = tables.lastOrNull() ?: return
            val row = table.open ?: return
            val cell = row.open ?: return
            flush()
            row.open = null
            row.cells += RichBlock.Cell(cell.blocks.toList(), cell.width, cell.background, cell.padding, cell.align)
        }

        private fun closeRow() {
            val table = tables.lastOrNull() ?: return
            closeCell()
            val row = table.open ?: return
            table.open = null
            if (row.cells.isNotEmpty()) table.rows += RichBlock.Row(row.cells.toList(), row.background)
        }

        private fun finishTable() {
            closeRow()
            val table = tables.removeAt(tables.lastIndex)
            if (table.rows.isEmpty()) return
            sink().add(RichBlock.Table(table.rows.toList(), table.width, table.background))
        }

        private fun length(value: String?): RichBlock.Length? {
            val v = value?.trim()?.lowercase() ?: return null
            if (v.endsWith("%")) {
                return v.dropLast(1).toFloatOrNull()?.takeIf {
                    it > 0f
                }?.let { RichBlock.Length(it.coerceAtMost(100f), true) }
            }
            return pixels(v)?.takeIf { it > 0f }?.let { RichBlock.Length(it.coerceAtMost(2048f), false) }
        }

        private fun padding(css: Map<String, String>, cellPadding: Int): RichBlock.Padding {
            val values = css["padding"]?.trim()?.split(Regex("\\s+"))?.map { pixels(it)?.toInt()?.coerceIn(0, 24) ?: 0 }
            val base = when (values?.size) {
                1 -> RichBlock.Padding(values[0], values[0], values[0], values[0])
                2 -> RichBlock.Padding(values[0], values[1], values[0], values[1])
                3 -> RichBlock.Padding(values[0], values[1], values[2], values[1])
                4 -> RichBlock.Padding(values[0], values[1], values[2], values[3])
                else -> RichBlock.Padding(cellPadding, cellPadding, cellPadding, cellPadding)
            }
            fun side(name: String, fallback: Int) = css[name]?.let(::pixels)?.toInt()?.coerceIn(0, 24) ?: fallback
            return RichBlock.Padding(
                top = side("padding-top", base.top),
                end = side("padding-right", base.end),
                bottom = side("padding-bottom", base.bottom),
                start = side("padding-left", base.start),
            )
        }

        // --- Pictures -------------------------------------------------------------------------

        private fun image(attrs: Map<String, String>) {
            when (val part = ChatImages.fromSource(attrs["src"])) {
                is ChatImages.Part.Html -> feed(part.html)

                is ChatImages.Part.Image -> {
                    val declared = ImageBounds.declaredSize(part.bytes)
                    val pixels = if (declared == null) {
                        ImageBounds.MAX_DECODED_PIXELS
                    } else {
                        val sample = ImageBounds.sampleSize(declared.width, declared.height)
                        if (sample == null) {
                            feed(TOO_LARGE)
                            return
                        }
                        (declared.width.toLong() / sample) * (declared.height.toLong() / sample)
                    }
                    if (images >= MAX_IMAGES || imagePixels + pixels > MAX_DOCUMENT_PIXELS) {
                        feed("[too many pictures]")
                        return
                    }
                    images++
                    imagePixels += pixels
                    ensureCellForStrayText()
                    flush()
                    val (width, height) = displaySize(attrs, declared)
                    var align: RichBlock.Align? = null
                    var href: String? = null
                    for (e in stack) {
                        e.align?.let { align = it }
                        e.href?.let { href = it }
                    }
                    val cellAlign = tables.lastOrNull()?.open?.open?.align
                    sink().add(
                        RichBlock.Image(
                            bytes = part.bytes,
                            width = width,
                            height = height,
                            align = align ?: cellAlign ?: RichBlock.Align.Start,
                            href = href,
                            description =
                                attrs["alt"]?.takeIf { it.isNotBlank() } ?: attrs["title"]?.takeIf { it.isNotBlank() }
                                    ?: "Picture",
                        ),
                    )
                }
            }
        }

        /** The size the markup asks for, scaled to keep the picture's shape when only one side is given. */
        private fun displaySize(attrs: Map<String, String>, declared: ImageBounds.Size?): Pair<Int, Int> {
            val css = parseStyle(attrs["style"])
            var w = (css["width"]?.let(::pixels) ?: attrs["width"]?.let(::pixels))?.toInt()?.takeIf { it > 0 }
            var h = (css["height"]?.let(::pixels) ?: attrs["height"]?.let(::pixels))?.toInt()?.takeIf { it > 0 }
            if (declared != null && declared.width > 0 && declared.height > 0) {
                if (w == null && h == null) {
                    w = declared.width
                    h = declared.height
                } else if (w == null) {
                    w = (h!!.toLong() * declared.width / declared.height).toInt()
                } else if (h == null) {
                    h = (w.toLong() * declared.height / declared.width).toInt()
                }
            }
            val width = (w ?: 0).coerceIn(0, ImageBounds.MAX_DECODED_SIDE)
            val height = (h ?: 0).coerceIn(0, ImageBounds.MAX_DECODED_SIDE)
            return if (width == 0 || height == 0) 0 to 0 else width to height
        }

        private companion object {
            const val TOO_LARGE = "[picture too large to show]"
        }
    }

    private val ITALIC_TAGS = setOf("i", "em", "cite", "var", "dfn", "address")
    private val MONO_TAGS = setOf("code", "tt", "kbd", "samp", "pre")
    private val MONO_FAMILIES = listOf("mono", "courier", "consol")

    private val CSS_SIZES = mapOf(
        "xx-small" to 0.75f,
        "x-small" to 0.75f,
        "small" to 0.85f,
        "medium" to 1f,
        "large" to 1.2f,
        "x-large" to 1.5f,
        "xx-large" to 2f,
    )

    /** `<font size>` 1 to 7, relative to the default 3. */
    private val FONT_SIZES = floatArrayOf(0.63f, 0.82f, 1f, 1.13f, 1.5f, 2f, 3f)

    /** `a: b; c: d` into a map of lower-case property names to values. */
    private fun parseStyle(style: String?): Map<String, String> {
        if (style.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (declaration in style.split(';')) {
            val colon = declaration.indexOf(':')
            if (colon <= 0) continue
            val name = declaration.substring(0, colon).trim().lowercase()
            val value = declaration.substring(colon + 1).replace("!important", "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
        }
        return out
    }
}

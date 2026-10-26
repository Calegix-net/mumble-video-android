package com.calegix.mumblevideo.ui.session

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHtmlTest {
    private val light =
        RichPalette(background = Color(0xFFF7F9F9), content = Color(0xFF191C1C), link = Color(0xFF00696E))
    private val trueBlack =
        RichPalette(background = Color(0xFF050505), content = Color(0xFFE0E3E3), link = Color(0xFF80D4D9))

    private fun parse(html: String, palette: RichPalette = light) = RichHtml.parse(html, palette).blocks

    private fun texts(blocks: List<RichBlock>): List<RichBlock.Text> = blocks.flatMap { block ->
        when (block) {
            is RichBlock.Text -> listOf(block)
            is RichBlock.Table -> block.rows.flatMap { row -> row.cells.flatMap { texts(it.blocks) } }
            else -> emptyList()
        }
    }

    private fun images(blocks: List<RichBlock>): List<RichBlock.Image> = blocks.flatMap { block ->
        when (block) {
            is RichBlock.Image -> listOf(block)
            is RichBlock.Table -> block.rows.flatMap { row -> row.cells.flatMap { images(it.blocks) } }
            else -> emptyList()
        }
    }

    private fun plain(html: String, palette: RichPalette = light) = texts(parse(html, palette)).joinToString("|") {
        it.text.text
    }

    private fun AnnotatedString.urls() = getLinkAnnotations(0, length).map {
        (it.item as LinkAnnotation.Url).url to
            text.substring(it.start, it.end)
    }

    /** The colour the run containing [needle] is drawn in, or null if it uses the default. */
    private fun AnnotatedString.colorOf(needle: String): Color? {
        val at = text.indexOf(needle)
        assertTrue("'$needle' in '$text'", at >= 0)
        return spanStyles.lastOrNull {
            it.start <= at && at < it.end && it.item.color != Color.Unspecified
        }?.item?.color
    }

    /** A PNG header declaring [width] x [height]: all the parser reads before handing it to the decoder. */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val b = ByteArray(33)
        val signatureAndIhdr =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
        signatureAndIhdr.copyInto(b)
        for (i in 0..3) {
            b[16 + i] = (width ushr (24 - 8 * i)).toByte()
            b[20 + i] = (height ushr (24 - 8 * i)).toByte()
        }
        return b
    }

    private fun dataUri(bytes: ByteArray) =
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes)

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("motd/$name")!!.readText()

    // --- Links ----------------------------------------------------------------------------------

    @Test
    fun linksKeepTheirTargetAndText() {
        val text = texts(
            parse(
                """See <a href="https://example.org/rules">the rules</a> or <a href='mumble://voice.example.org:64738/Lobby'>Lobby</a>.""",
            ),
        ).single().text
        assertEquals("See the rules or Lobby.", text.text)
        assertEquals(
            listOf("https://example.org/rules" to "the rules", "mumble://voice.example.org:64738/Lobby" to "Lobby"),
            text.urls(),
        )
    }

    @Test
    fun unstyledLinksLookLikeLinks() {
        val text = texts(parse("""<a href="https://example.org">here</a>""")).single().text
        val style = text.spanStyles.single().item
        assertEquals(light.link, style.color)
        assertEquals(TextDecoration.Underline, style.textDecoration)
    }

    @Test
    fun dangerousSchemesAreNotLinks() {
        val hrefs = listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", "java\tscript:alert(1)", "file:///sdcard/x",
            "intent://x#Intent;end", "content://x", "data:text/html,hi", "/relative", "clientid://5",
        )
        for (href in hrefs) {
            val text = texts(parse("""<a href="$href">click</a>""")).single().text
            assertEquals(href, "click", text.text)
            assertTrue(href, text.urls().isEmpty())
        }
    }

    // --- Line breaks, paragraphs, whitespace ------------------------------------------------------

    @Test
    fun lineBreaksAndParagraphs() {
        assertEquals("one\ntwo", plain("one<br>two"))
        assertEquals("one\ntwo", plain("one<br/>two<br />"))
        assertEquals("first|second", plain("<p>first</p><p>second</p>"))
        assertEquals("first|second", plain("first<p>second"))
        assertEquals("a|b", plain("<div>a</div><div>b</div>"))
    }

    @Test
    fun whitespaceCollapsesAsInHtml() {
        assertEquals("Welcome to the server", plain("<span>  Welcome \n\t to   the\nserver  </span>"))
        assertEquals("bold and italic", plain("<b>bold</b> <i>and</i>\n<i>italic</i>"))
        assertEquals("a  b", plain("a&nbsp;&nbsp;b"))
    }

    @Test
    fun plainTextKeepsItsLineBreaks() {
        assertEquals("Rules:\n1. Be nice\n2. No spam", plain("Rules:\n1. Be nice\r\n2. No spam"))
    }

    @Test
    fun preformattedTextIsKeptAsIs() {
        val block = texts(parse("<pre>  a\n    b</pre>")).single()
        assertTrue(block.preformatted)
        assertEquals("  a\n    b", block.text.text)
    }

    @Test
    fun entities() {
        assertEquals("Tom & Jerry <3 A © é 😀", plain("Tom &amp; Jerry &lt;3 &#x41; &copy; &#233; &#128512;"))
        assertEquals("&bogus; & 5", plain("&bogus; & 5"))
    }

    // --- Formatting -------------------------------------------------------------------------------

    @Test
    fun inlineFormatting() {
        val text = texts(
            parse(
                "<b>bold</b> <i>it</i> <u>under</u> <span style=\"font-weight:700;text-decoration:line-through\">x</span>",
            ),
        ).single().text
        fun style(needle: String) = text.spanStyles.first { text.text.substring(it.start, it.end) == needle }.item
        assertEquals(FontWeight.Bold, style("bold").fontWeight)
        assertEquals(androidx.compose.ui.text.font.FontStyle.Italic, style("it").fontStyle)
        assertEquals(TextDecoration.Underline, style("under").textDecoration)
        assertEquals(FontWeight.Bold, style("x").fontWeight)
        assertEquals(TextDecoration.LineThrough, style("x").textDecoration)
    }

    @Test
    fun headingsListsAndRules() {
        val blocks =
            parse("<h2>Rules</h2><ol><li>Be nice</li><li>No spam</ol><ul><li>one<ul><li>nested</li></ul></li></ul><hr>")
        val t = texts(blocks)
        assertEquals(2, t[0].heading)
        assertEquals(listOf("1.", "2.", "•", "◦"), t.drop(1).map { it.marker })
        assertEquals(listOf(1, 1, 1, 2), t.drop(1).map { it.indent })
        assertTrue(blocks.last() is RichBlock.Rule)
    }

    @Test
    fun fontSizesStayWithinReason() {
        val text = texts(parse("""<span style="font-size:200px">huge</span><font size="1">tiny</font>""")).single().text
        val sizes = text.spanStyles.map { it.item.fontSize.value }
        assertTrue(sizes.toString(), sizes.all { it in 0.75f..2f })
    }

    // --- Pictures ---------------------------------------------------------------------------------

    @Test
    fun dataImageBecomesAPictureBlock() {
        val bytes = pngHeader(400, 62)
        val image = images(parse("""<p>Hi</p><img src="${dataUri(bytes)}" width="200" alt="Banner">""")).single()
        assertArrayEquals(bytes, image.bytes)
        assertEquals(200, image.width)
        assertEquals(31, image.height) // the header's shape, for the width the markup asked for
        assertEquals("Banner", image.description)
    }

    @Test
    fun linkedPictureKeepsItsLink() {
        val image = images(
            parse("""<a href="https://skyn3t.lol"><img src="${dataUri(pngHeader(10, 10))}"></a>"""),
        ).single()
        assertEquals("https://skyn3t.lol", image.href)
    }

    @Test
    fun oversizedDataImageIsRefusedBeforeDecoding() {
        val big = "A".repeat(ImageBounds.MAX_ENCODED_CHARS + 4)
        val blocks = parse("""<img src="data:image/png;base64,$big">""")
        assertTrue(images(blocks).isEmpty())
        assertEquals("[picture too large to show]", plain("""<img src="data:image/png;base64,$big">"""))
    }

    @Test
    fun pictureDeclaringAnEnormousSizeIsRefused() {
        // A few bytes that would decode to 14 GB.
        val html = """<img src="${dataUri(pngHeader(60_000, 60_000))}">"""
        assertTrue(images(parse(html)).isEmpty())
        assertEquals("[picture too large to show]", plain(html))
    }

    @Test
    fun theWholeDocumentHasAPictureBudget() {
        // Each of these decodes to the full 4 MP per-picture limit, so only two fit the document.
        val one = """<img src="${dataUri(pngHeader(2048, 2048))}">"""
        val blocks = parse(one.repeat(5))
        assertEquals(2, images(blocks).size)
        assertTrue(plain(one.repeat(5)).contains("[too many pictures]"))
        val many = """<img src="${dataUri(pngHeader(4, 4))}">""".repeat(RichHtml.MAX_IMAGES + 10)
        assertEquals(RichHtml.MAX_IMAGES, images(parse(many)).size)
    }

    @Test
    fun remotePicturesAreNotFetchedButLinked() {
        val text = texts(parse("""<img src="https://tracker.example/pixel.gif">""")).single().text
        assertEquals(
            listOf("https://tracker.example/pixel.gif" to "picture: https://tracker.example/pixel.gif"),
            text.urls(),
        )
        assertTrue(images(parse("""<img src="https://tracker.example/pixel.gif">""")).isEmpty())
    }

    // --- Sanitising -------------------------------------------------------------------------------

    @Test
    fun scriptsStylesAndActiveContentAreDropped() {
        val html = """
            <head><title>T</title><meta charset="utf-8"></head>
            <script>alert('x')</script><SCRIPT type="text/javascript">document.write("<b>x</b>")</SCRIPT>
            <style>p { color: red }</style>
            <iframe src="https://example.org">frame</iframe><object data="x">obj</object>
            <svg><text>svg</text></svg><noscript>ns</noscript>
            <div style="display:none">hidden</div>
            <p onclick="alert(1)" onmouseover="alert(2)">Hello</p><!-- a comment <b>x</b> -->
            <button>Press</button><form><input value="secret"><textarea>typed</textarea></form>
        """
        assertEquals("Hello", plain(html))
    }

    @Test
    fun brokenMarkupDoesNotThrow() {
        val broken = listOf(
            "<", "<b", "<a href=", "</", "<table><td>x", "<tr><td>stray</td></tr>", "<<<>>>", "<p></b></table></td>x",
            "&#99999999;", "<img src=", "<ul><li><table><tr><td><li>z",
        )
        for (html in broken) {
            RichHtml.parse(html, light)
        }
        assertEquals("<<<>>>", plain("<<<>>>"))
    }

    @Test
    fun deepNestingIsBounded() {
        val html = "<table><tr><td>".repeat(200) + "deep" + "</td></tr></table>".repeat(200)
        val blocks = parse(html)
        assertEquals("deep", plain(html).trim())
        var depth = 0
        var level: List<RichBlock> = blocks
        while (true) {
            val table = level.filterIsInstance<RichBlock.Table>().firstOrNull() ?: break
            depth++
            level = table.rows.first().cells.first().blocks
        }
        assertEquals(RichHtml.MAX_TABLE_DEPTH, depth)
        RichHtml.parse("<span>".repeat(10_000) + "x", light) // no stack overflow
    }

    @Test
    fun longTextIsCut() {
        val text = plain("<p>" + "word ".repeat(30_000) + "</p>")
        assertTrue(text.length <= RichHtml.MAX_TEXT_CHARS + 20)
        assertTrue(text.endsWith("[message cut]"))
    }

    // --- Colours ------------------------------------------------------------------------------------

    @Test
    fun darkTextIsLiftedOnADarkTheme() {
        val html = """<font color="#000000">black</font> <span style="color:#202020">grey</span> """ +
            """<span style="color:navy">navy</span>"""
        val text = texts(parse(html, trueBlack)).single().text
        for (word in listOf("black", "grey", "navy")) {
            val color = assertNotNullColor(text.colorOf(word))
            assertTrue(
                "$word: ${RichHtml.contrast(color, trueBlack.background)}",
                RichHtml.contrast(color, trueBlack.background) >= RichHtml.MIN_CONTRAST,
            )
        }
        // On the light theme the same colours already read, and are kept exactly.
        val onLight = texts(parse(html, light)).single().text
        assertEquals(Color(0xFF000000), onLight.colorOf("black"))
        assertEquals(Color(0xFF000080), onLight.colorOf("navy"))
    }

    @Test
    fun lightTextIsDarkenedOnALightTheme() {
        val text = texts(
            parse("""<span style="color:#ffffff">white</span> <span style="color:yellow">yellow</span>"""),
        ).single().text
        for (word in listOf("white", "yellow")) {
            val color = assertNotNullColor(text.colorOf(word))
            assertTrue(word, RichHtml.contrast(color, light.background) >= RichHtml.MIN_CONTRAST)
        }
    }

    @Test
    fun textIsCheckedAgainstTheBackgroundItIsDrawnOn() {
        // skyn3t's card: light text on its own dark table, which is kept whatever the app theme.
        val html = """<table bgcolor="#070b0f"><tr><td><span style="color:#e3eef2">Welcome</span> """ +
            """<span style="color:#000000">dark</span></td></tr></table>"""
        val table = parse(html, light).single() as RichBlock.Table
        assertEquals(Color(0xFF070B0F), table.background)
        val text = texts(listOf(table)).single().text
        assertEquals(Color(0xFFE3EEF2), text.colorOf("Welcome"))
        val dark = assertNotNullColor(text.colorOf("dark"))
        assertTrue(RichHtml.contrast(dark, Color(0xFF070B0F)) >= RichHtml.MIN_CONTRAST)
        // A pill with its own background: the text must read on the pill, not on the page.
        val pill = texts(
            parse("""<span style="background-color:#0a3b2a;color:#3df5a3">ONLINE</span>""", light),
        ).single().text
        assertEquals(Color(0xFF3DF5A3), pill.colorOf("ONLINE"))
        assertEquals(Color(0xFF0A3B2A), pill.spanStyles.single().item.background)
    }

    @Test
    fun readableKeepsGoodColoursAndFixesBadOnes() {
        assertEquals(Color.White, RichHtml.readable(Color.White, Color.Black))
        val fixed = RichHtml.readable(Color(0xFF111111), Color.Black)
        assertTrue(RichHtml.contrast(fixed, Color.Black) >= RichHtml.MIN_CONTRAST)
        assertTrue(
            RichHtml.contrast(RichHtml.readable(Color.Black.copy(alpha = 0.1f), Color.White), Color.White) >=
                RichHtml.MIN_CONTRAST,
        )
    }

    @Test
    fun colourSyntaxes() {
        assertEquals(Color(0xFFFF0000), RichHtml.parseColor("#f00"))
        assertEquals(Color(0xFF00E5FF), RichHtml.parseColor("#00E5FF"))
        assertEquals(Color(0xFF00E5FF), RichHtml.parseColor("00e5ff"))
        assertEquals(Color(10, 20, 30), RichHtml.parseColor("rgb(10, 20, 30)"))
        assertEquals(Color(0xFF008080), RichHtml.parseColor(" Teal "))
        assertEquals(null, RichHtml.parseColor("expression(alert(1))"))
        assertEquals(null, RichHtml.parseColor("#12"))
    }

    private fun assertNotNullColor(color: Color?): Color {
        assertNotNull(color)
        return color!!
    }

    // --- Real welcome messages ----------------------------------------------------------------------

    @Test
    fun skyn3tWelcomeCard() {
        val blocks = parse(fixture("skyn3t.html"), trueBlack)
        val card = blocks.single() as RichBlock.Table
        assertEquals(Color(0xFF070B0F), card.background)
        assertEquals(RichBlock.Length(400f, false), card.width)
        // Banner, four service icons and two separator lines.
        val pictures = images(blocks)
        assertEquals(7, pictures.size)
        assertEquals(400 to 62, pictures.first().width to pictures.first().height)
        val all = texts(blocks)
        val welcome = all.first { it.text.text.startsWith("Welcome,") }
        assertEquals("Welcome, nate\nEnjoy your stay!", welcome.text.text)
        assertEquals(FontWeight.Bold, welcome.text.spanStyles.first().item.fontWeight)
        val urls = all.flatMap { it.text.urls() }.map { it.first }
        assertTrue(urls.toString(), "mumble://mumble.skyn3t.lol:64738" in urls)
        assertTrue(urls.toString(), "https://search.skyn3t.lol" in urls)
        assertTrue(all.any { it.text.text.contains("ONLINE") })
        assertTrue(all.any { it.text.text == "real afk" })
        // Centred footer.
        assertEquals(RichBlock.Align.Center, all.first { it.text.text.startsWith("skyn3t.lol console") }.align)
        assertFalse(all.any { "<" in it.text.text })
    }

    @Test
    fun calegixTestServerWelcome() {
        val text = texts(parse(fixture("calegix-test.html"))).single().text
        assertTrue(text.text.startsWith("Mumble fork TEST server"))
        assertEquals(FontWeight.Bold, text.spanStyles.first().item.fontWeight)
    }
}

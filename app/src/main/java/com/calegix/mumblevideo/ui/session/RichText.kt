package com.calegix.mumblevideo.ui.session

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Received HTML (a welcome message, a message from the server) laid out natively from [RichHtml]'s
 * blocks: no WebView, so nothing in it can run or load anything. [background] and [contentColor]
 * are what it is drawn on and with; the server's colours are kept readable against them.
 */
@Composable
fun RichHtmlText(
    html: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    loadImages: Boolean = true,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val palette = RichPalette(background, contentColor, MaterialTheme.colorScheme.primary)
    // Parsed off the main thread: a message can hold a megabyte of embedded pictures.
    val document by produceState<RichDocument?>(null, html, palette) {
        value = withContext(Dispatchers.Default) { RichHtml.parse(html, palette) }
    }
    val blocks = document?.blocks ?: return
    val context = LocalContext.current
    val links = remember(context) { RichLinkHandler(context) }
    CompositionLocalProvider(LocalUriHandler provides links) {
        Blocks(blocks, RichStyle(style, contentColor, loadImages), modifier)
    }
}

/**
 * Opens links from received HTML: `mumble://` ones in this app, through the same path as a link
 * tapped anywhere else, and the other schemes the desktop client allows in whatever app handles
 * them. Anything else is ignored, though the parser never produces it.
 */
private class RichLinkHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val parsed = uri.toUri()
        val scheme = parsed.scheme?.lowercase() ?: return
        if (scheme !in RichHtml.ALLOWED_SCHEMES) return
        val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
            if (scheme == "mumble") setPackage(context.packageName) else addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }
}

private class RichStyle(val text: TextStyle, val color: Color, val loadImages: Boolean)

@Composable
private fun Blocks(blocks: List<RichBlock>, style: RichStyle, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is RichBlock.Text -> TextBlock(block, style)
                is RichBlock.Image -> ImageBlock(block, style)
                is RichBlock.Table -> TableBlock(block, style)
                RichBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun TextBlock(block: RichBlock.Text, style: RichStyle) {
    val typography = MaterialTheme.typography
    val base = when (block.heading) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleMedium
        4, 5, 6 -> typography.titleSmall
        else -> style.text
    }
    val textStyle = if (block.preformatted) base.copy(fontFamily = FontFamily.Monospace) else base
    val align = when (block.align) {
        RichBlock.Align.Start -> TextAlign.Start
        RichBlock.Align.Center -> TextAlign.Center
        RichBlock.Align.End -> TextAlign.End
    }
    val fill = if (block.align != RichBlock.Align.Start) Modifier.fillMaxWidth() else Modifier
    Row(
        Modifier
            .padding(start = (block.indent * 16).dp)
            .then(if (block.quote) Modifier.height(IntrinsicSize.Min) else Modifier),
    ) {
        if (block.quote) {
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        block.marker?.let {
            Text(it, style = textStyle, color = style.color, modifier = Modifier.padding(end = 6.dp))
        }
        Text(block.text, style = textStyle, color = style.color, textAlign = align, modifier = fill)
    }
}

@Composable
private fun ImageBlock(block: RichBlock.Image, style: RichStyle) {
    if (style.loadImages) {
        LoadedImage(block, style)
    } else {
        Text(
            "Picture hidden (Settings › General › Load images)",
            style = MaterialTheme.typography.bodySmall,
            color = style.color.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun LoadedImage(block: RichBlock.Image, style: RichStyle) {
    // Decoded off the main thread and within ImageBounds, as chat pictures are.
    val decoded by produceState<List<ImageBitmap>?>(null, block) {
        value = withContext(Dispatchers.Default) { listOfNotNull(ChatImages.decode(block.bytes)?.asImageBitmap()) }
    }
    val bitmap = decoded?.firstOrNull()
    val width = block.width.takeIf { it > 0 } ?: bitmap?.width
    val height = block.height.takeIf { it > 0 } ?: bitmap?.height
    val alignment = when (block.align) {
        RichBlock.Align.Start -> Alignment.CenterStart
        RichBlock.Align.Center -> Alignment.Center
        RichBlock.Align.End -> Alignment.CenterEnd
    }
    when {
        decoded != null && bitmap == null ->
            Text("[picture could not be shown]", style = MaterialTheme.typography.bodySmall, color = style.color)

        // Size unknown until decoded: nothing to reserve yet.
        width == null || height == null -> Unit

        else -> Box(
            if (block.align != RichBlock.Align.Start) Modifier.fillMaxWidth() else Modifier,
            contentAlignment = alignment,
        ) {
            val size = Modifier
                .widthIn(max = width.dp)
                .fillMaxWidth()
                .aspectRatio(width.toFloat() / height.coerceAtLeast(1))
            if (bitmap == null) {
                // The space it will take, so the layout does not jump when it arrives.
                Spacer(size)
            } else {
                ShownImage(bitmap, block, size)
            }
        }
    }
}

@Composable
private fun ShownImage(bitmap: ImageBitmap, block: RichBlock.Image, modifier: Modifier = Modifier) {
    var viewing by rememberSaveable { mutableStateOf(false) }
    val links = LocalUriHandler.current
    Image(
        bitmap = bitmap,
        contentDescription = block.description,
        contentScale = ContentScale.FillBounds,
        modifier = modifier.clickable(onClickLabel = if (block.href != null) "Open link" else "View full screen") {
            val href = block.href
            if (href != null) links.openUri(href) else viewing = true
        },
    )
    if (viewing) ImageViewer(bitmap, block.description, onDismiss = { viewing = false })
}

@Composable
private fun TableBlock(table: RichBlock.Table, style: RichStyle) {
    val width = table.width
    val sized = when {
        width == null -> Modifier.fillMaxWidth()
        width.percent -> Modifier.fillMaxWidth(width.value / 100f)
        else -> Modifier.widthIn(max = width.value.dp).fillMaxWidth()
    }
    Column(sized.background(table.background ?: Color.Transparent)) {
        table.rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(row.background ?: Color.Transparent),
            ) {
                val percents = row.cells.mapNotNull { cell -> cell.width?.takeIf { it.percent }?.value }
                val unsized = row.cells.count { it.width == null }
                val restWeight = if (percents.isEmpty()) {
                    1f
                } else {
                    (
                        (100f - percents.sum()) / unsized.coerceAtLeast(
                            1,
                        )
                        ).coerceAtLeast(1f)
                }
                row.cells.forEach { cell -> TableCell(cell, row.cells.size, restWeight, style) }
            }
        }
    }
}

/** Narrow fixed widths (icon and label columns) are kept as they are; wider ones share the row. */
private const val MAX_FIXED_CELL_DP = 160f

@Composable
private fun RowScope.TableCell(cell: RichBlock.Cell, cellsInRow: Int, restWeight: Float, style: RichStyle) {
    val width = cell.width
    val sized = when {
        width == null -> Modifier.weight(restWeight)

        width.percent -> Modifier.weight(width.value)

        // At least the width asked for, and a little more if the text needs it: fonts on a phone are
        // not the sizes the server's designer measured against.
        cellsInRow > 1 && width.value <= MAX_FIXED_CELL_DP ->
            // Sized from the content alone (not from what is left of the row), so the row's height,
            // measured beforehand, matches the width the cell really gets.
            Modifier.width(IntrinsicSize.Max).widthIn(min = width.value.dp, max = (width.value * 1.6f).dp)

        else -> Modifier.weight(width.value)
    }
    val alignment = when (cell.align) {
        RichBlock.Align.Start -> Alignment.CenterStart
        RichBlock.Align.Center -> Alignment.Center
        RichBlock.Align.End -> Alignment.CenterEnd
    }
    val p = cell.padding
    Box(
        sized
            .fillMaxHeight()
            .background(cell.background ?: Color.Transparent)
            .padding(start = p.start.dp, top = p.top.dp, end = p.end.dp, bottom = p.bottom.dp),
        contentAlignment = alignment,
    ) {
        Blocks(cell.blocks, style)
    }
}

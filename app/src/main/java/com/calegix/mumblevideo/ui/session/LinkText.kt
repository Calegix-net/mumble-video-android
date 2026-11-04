package com.calegix.mumblevideo.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * Text whose links open on a tap, as before, and copy on a long press. A long press anywhere else
 * still starts a selection in the surrounding SelectionContainer.
 */
@Composable
fun LinkText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    onCopyLink: (String) -> Unit = rememberLinkCopier(),
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val copy by rememberUpdatedState(onCopyLink)
    val hasLinks = remember(text) { text.getLinkAnnotations(0, text.length).isNotEmpty() }
    Text(
        text,
        style = style,
        color = color,
        textAlign = textAlign,
        onTextLayout = { layout = it },
        modifier = if (hasLinks) {
            modifier.pointerInput(text) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val url = layout?.let { linkUrlAt(text, it, down.position) } ?: return@awaitEachGesture
                    // Children (the link's own tap target) have seen the down; consuming it here,
                    // before the SelectionContainer above, keeps a press on a link from turning into
                    // a selection. A tap still reaches the link and opens it.
                    down.consume()
                    awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    copy(url)
                    // Swallow the rest of the press in the Initial pass, ahead of the link, so lifting
                    // the finger doesn't also open what was just copied.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        } else {
            modifier
        },
    )
}

/** The href of the link drawn at [position], or null when it's not over a link's glyphs. */
internal fun linkUrlAt(text: AnnotatedString, layout: TextLayoutResult, position: Offset): String? {
    if (text.isEmpty()) return null
    val offset = layout.getOffsetForPosition(position)
    // The nearest character can be the one before the caret position, or far from the finger
    // (past the end of a short line): only count a hit inside the character's own box.
    val index = listOf(offset, offset - 1).firstOrNull { i ->
        i in text.indices && layout.getBoundingBox(i).inflate(TOUCH_SLOP_PX).contains(position)
    } ?: return null
    val link = text.getLinkAnnotations(index, index + 1).firstOrNull()?.item
    return (link as? LinkAnnotation.Url)?.url
}

private const val TOUCH_SLOP_PX = 8f

/** Puts a link on the clipboard with a buzz, and says so where Android doesn't already. */
@Composable
fun rememberLinkCopier(): (String) -> Unit {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    return remember(context, haptics) {
        { url ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Link", url))
            // Android 13 and later show their own "Copied" confirmation; a toast on top doubles it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

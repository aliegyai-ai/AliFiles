package com.ali.files.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val SeekGold = Color(0xFFD4A94F)

/**
 * A thin, smooth seek bar: a fine line that thickens and shows a handle only while you touch it.
 * Callers keep it short (side padding) so it doesn't fight the screen-edge back gesture.
 */
@Composable
fun SleekSeekBar(
    value: Float,
    max: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    inactive: Color = Color(0xFF3A3328),
) {
    var touching by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val thick by animateDpAsState(if (touching) 10.dp else 4.dp, tween(160), label = "thick")
    val handle by animateDpAsState(if (touching) 9.dp else 0.dp, tween(160), label = "handle")
    val frac by animateFloatAsState((value / max).coerceIn(0f, 1f), tween(if (touching) 0 else 350, easing = LinearEasing), label = "frac")
    val dens = LocalDensity.current

    Box(
        modifier.fillMaxWidth().height(40.dp)
            .pointerInput(max, rtl) {
                detectTapGestures { o ->
                    val f = (if (rtl) 1f - o.x / size.width else o.x / size.width).coerceIn(0f, 1f)
                    onChange(f * max); onDone()
                }
            }
            .pointerInput(max, rtl) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        touching = true
                        onChange((if (rtl) 1f - o.x / size.width else o.x / size.width).coerceIn(0f, 1f) * max)
                    },
                    onDragEnd = { touching = false; onDone() },
                    onDragCancel = { touching = false; onDone() },
                ) { change, _ ->
                    change.consume()
                    onChange((if (rtl) 1f - change.position.x / size.width else change.position.x / size.width).coerceIn(0f, 1f) * max)
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val h = with(dens) { thick.toPx() }
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(inactive, Offset(0f, y), Size(size.width, h), r)
            val w = size.width * frac
            val x0 = if (rtl) size.width - w else 0f
            if (w > 0f) drawRoundRect(
                Brush.horizontalGradient(if (rtl) listOf(SeekGold, Color(0xFFA67F2E)) else listOf(Color(0xFFA67F2E), SeekGold), startX = x0, endX = x0 + w),
                Offset(x0, y), Size(w, h), r,
            )
            val hr = with(dens) { handle.toPx() }
            if (hr > 0.5f) drawCircle(Color.White, hr, Offset(if (rtl) size.width - w else w, size.height / 2))
        }
    }
}

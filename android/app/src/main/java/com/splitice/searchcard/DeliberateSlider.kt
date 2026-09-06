package com.splitice.searchcard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** No tap-to-seek: scrolling must not edit a number. Only a deliberate horizontal drag owns input. */
@Composable
internal fun DeliberateSlider(
    value: Float, enabled: Boolean, steps: Int, modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit, onFinished: () -> Unit, onCancelled: () -> Unit,
) {
    val latestValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val finish by rememberUpdatedState(onFinished)
    val cancel by rememberUpdatedState(onCancelled)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val primary = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .24f else .12f)
    fun adjust(target: Float): Boolean {
        if (!enabled || target.coerceIn(0f, 1f) == latestValue) return false
        change(target.coerceIn(0f, 1f)); finish(); return true
    }
    Canvas(modifier.height(48.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f, steps)
        if (!enabled) disabled()
        setProgress { adjust(it) }
    }.onKeyEvent {
        if (!enabled || it.type != KeyEventType.KeyDown) false else {
            val increment = if (steps > 0) 1f / (steps + 1) else .01f
            when (it.key) {
                Key.DirectionRight -> adjust(latestValue + if (rtl) -increment else increment)
                Key.DirectionLeft -> adjust(latestValue + if (rtl) increment else -increment)
                Key.MoveHome -> adjust(0f)
                Key.MoveEnd -> adjust(1f)
                else -> false
            }
        }
    }.focusable(enabled).pointerInput(enabled, rtl) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = latestValue
            val threshold = maxOf(24.dp.toPx(), viewConfiguration.touchSlop * 2)
            val travel = (size.width - 24.dp.toPx()).coerceAtLeast(1f)
            var dragging = false
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (pointer.isConsumed || event.changes.count { it.pressed } > 1) break
                    if (!pointer.pressed) {
                        if (dragging) { pointer.consume(); released = true; finish() }
                        break
                    }
                    val delta = pointer.position - down.position
                    if (!dragging) {
                        // Leave vertical/diagonal gestures unconsumed for the enclosing lazy list.
                        if (abs(delta.y) >= viewConfiguration.touchSlop && abs(delta.x) < abs(delta.y) * 1.5f) break
                        if (abs(delta.x) < threshold || abs(delta.x) < abs(delta.y) * 1.5f) continue
                        dragging = true
                    }
                    pointer.consume()
                    // Subtract the activation distance so entering a drag does not jump the thumb.
                    val distance = (abs(delta.x) - threshold).coerceAtLeast(0f) * if (delta.x < 0) -1 else 1
                    change((start + distance / travel * if (rtl) -1 else 1).coerceIn(0f, 1f))
                }
            } finally {
                if (dragging && !released) cancel()
            }
        }
    }) {
        val left = 12.dp.toPx()
        val right = (size.width - left).coerceAtLeast(left)
        val fraction = if (rtl) 1f - value else value
        val thumb = Offset(left + (right - left) * fraction, size.height / 2)
        val start = Offset(if (rtl) right else left, thumb.y)
        drawLine(track, Offset(left, thumb.y), Offset(right, thumb.y), 4.dp.toPx())
        drawLine(primary, start, thumb, 4.dp.toPx())
        drawCircle(primary, 10.dp.toPx(), thumb)
    }
}

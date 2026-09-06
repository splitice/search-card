package com.splitice.searchcard

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel coordinates, kept independent of Android for geometry regression tests. */
internal data class PanelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right.toLong() - left).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val height: Int get() = (bottom.toLong() - top).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

internal data class WidgetAnchor(val bounds: PanelBounds, val window: PanelBounds)

internal fun localWidgetBounds(anchor: WidgetAnchor?, window: PanelBounds, rootX: Int, rootY: Int): PanelBounds? {
    if (anchor == null || anchor.window != window) return null
    val b = anchor.bounds
    // Reject stale, empty, oversized, and off-screen launcher hints before arithmetic/layout.
    if (b.right.toLong() - b.left !in 1..window.width.toLong() ||
        b.bottom.toLong() - b.top !in 1..window.height.toLong() ||
        b.left < window.left || b.top < window.top || b.right > window.right || b.bottom > window.bottom) return null
    return PanelBounds(b.left - rootX, b.top - rootY, b.right - rootX, b.bottom - rootY)
}

internal data class PanelPlacement(val left: Int, val top: Int, val width: Int, val fieldHeight: Int, val bodyHeight: Int)

internal fun placeWidgetPanel(
    widget: PanelBounds,
    safe: PanelBounds,
    fieldHeight: Int,
    statusHeight: Int,
    resultHeight: Int,
    keyboardGap: Int,
    imeBottom: Int,
    imeSourceBottom: Int = imeBottom,
    imeTargetBottom: Int = imeBottom,
): PanelPlacement {
    val width = min(widget.width, safe.width)
    val left = widget.left.coerceIn(safe.left, safe.right - width)
    fun bottom(ime: Int) = min(safe.bottom, ime - keyboardGap).coerceAtLeast(safe.top)
    fun top(ime: Int): Int {
        val latest = (bottom(ime) - fieldHeight - statusHeight - resultHeight).coerceAtLeast(safe.top)
        return widget.top.coerceIn(safe.top, latest)
    }
    // The IME provides the animation clock; do not start a second position animation.
    val fraction = if (imeSourceBottom == imeTargetBottom) 1f else
        ((imeBottom - imeSourceBottom).toFloat() / (imeTargetBottom - imeSourceBottom)).coerceIn(0f, 1f)
    val top = (top(imeSourceBottom) + (top(imeTargetBottom) - top(imeSourceBottom)) * fraction).roundToInt()
    val height = min(fieldHeight, (bottom(imeBottom) - top).coerceAtLeast(0))
    return PanelPlacement(left, top, width, height, max(0, bottom(imeBottom) - top - height))
}

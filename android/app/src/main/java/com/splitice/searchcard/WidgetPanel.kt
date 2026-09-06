package com.splitice.searchcard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/** Root coordinates are measured in screen space, just like RemoteViews' sourceBounds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WidgetPanelHost(
    anchor: WidgetAnchor?,
    currentWindow: () -> PanelBounds,
    launchSequence: Int,
    visible: Boolean,
    status: String,
    dismiss: () -> Unit,
    field: @Composable (Modifier) -> Unit,
    body: @Composable (Modifier, Boolean) -> Unit,
    fallback: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    val measurer = rememberTextMeasurer()
    val statusStyle = MaterialTheme.typography.labelMedium
    var origin by remember { mutableStateOf<IntOffset?>(null) }
    // Save only whether this opening was shown, never geometry derived from an old window.
    var revealed by rememberSaveable(launchSequence) { mutableStateOf(false) }
    var staleAnchor by rememberSaveable(launchSequence) { mutableStateOf(false) }
    val reveal = remember(launchSequence) { Animatable(if (revealed) 1f else 0f) }

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionOnScreen().round() }) {
        val window = currentWindow()
        LaunchedEffect(window, launchSequence) {
            if (anchor != null && anchor.window != window) staleAnchor = true
        }
        val widget = origin?.let { localWidgetBounds(if (staleAnchor) null else anchor, window, it.x, it.y) }
        val anchored = widget != null && widget.width >= with(density) { 48.dp.roundToPx() }
        LaunchedEffect(launchSequence, visible, anchored) {
            if (visible && anchored) {
                revealed = true
                reveal.animateTo(1f, tween(200))
            }
        }
        // Do not flash the fallback layout while waiting for the root's first screen position.
        val awaitingOrigin = origin == null && anchor != null && anchor.window == window
        val progress = if (anchored || awaitingOrigin) reveal.value else 1f
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f * progress))
            .semantics { contentDescription = "Dismiss search" }.clickable(onClick = dismiss))
        if (anchored) {
            val root = origin!!
            val left = (window.left + bars.getLeft(density, direction) - root.x).coerceIn(0, constraints.maxWidth)
            val top = (window.top + bars.getTop(density) - root.y).coerceIn(0, constraints.maxHeight)
            val safe = PanelBounds(left, top,
                (window.right - bars.getRight(density, direction) - root.x).coerceIn(left, constraints.maxWidth),
                (window.bottom - bars.getBottom(density) - root.y).coerceIn(top, constraints.maxHeight))
            val width = widget!!.width.coerceAtMost(safe.width)
            // Measure the same text/style/width used by WidgetStatus; its menu has a 48 dp target.
            val statusWidth = (width - with(density) { 72.dp.roundToPx() }).coerceAtLeast(1)
            val statusTextHeight = measurer.measure(status, statusStyle, constraints = Constraints(maxWidth = statusWidth)).size.height
            val statusHeight = max(with(density) { 48.dp.roundToPx() }, statusTextHeight) + with(density) { 16.dp.roundToPx() }
            val textHeight = measurer.measure("Ag", widgetTextStyle()).size.height
            val fieldHeight = max(widget.height, max(with(density) { 48.dp.roundToPx() }, textHeight + with(density) { 16.dp.roundToPx() }))
            val placement = placeWidgetPanel(widget, safe, fieldHeight, statusHeight,
                with(density) { 72.dp.roundToPx() }, with(density) { 8.dp.roundToPx() },
                window.bottom - root.y - ime.getBottom(density),
                window.bottom - root.y - imeSource.getBottom(density),
                window.bottom - root.y - imeTarget.getBottom(density))
            Column(Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(placement.left, placement.top) }
                .width(with(density) { placement.width.toDp() })) {
                field(Modifier.fillMaxWidth().height(with(density) { placement.fieldHeight.toDp() }).testTag("widget-search-field"))
                // Measure at full height, reveal from the top without scaling text or moving the field.
                Box(Modifier.fillMaxWidth().clipToBounds().layout { measurable, c ->
                    val child = measurable.measure(c.copy(minHeight = placement.bodyHeight, maxHeight = placement.bodyHeight))
                    layout(child.width, (child.height * reveal.value).roundToInt()) { child.place(0, 0) }
                }) {
                    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), tonalElevation = 4.dp) {
                        body(Modifier.fillMaxSize(), placement.bodyHeight < statusHeight + with(density) { 72.dp.roundToPx() })
                    }
                }
            }
        } else if (!awaitingOrigin) {
            fallback()
        }
    }
}

@Composable
private fun widgetTextStyle() = TextStyle(color = colorResource(R.color.widget_text), fontSize = 16.sp, fontFamily = FontFamily.SansSerif)

@Composable
internal fun WidgetSearchField(query: String, onQuery: (String) -> Unit, hint: String, modifier: Modifier) {
    val style = widgetTextStyle()
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = colorResource(R.color.widget_background),
        border = BorderStroke(1.dp, colorResource(R.color.widget_border))) {
        BasicTextField(query, onQuery, Modifier.fillMaxSize().semantics { contentDescription = "Search Home Assistant" },
            singleLine = true, textStyle = style, cursorBrush = SolidColor(style.color),
            decorationBox = { input ->
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_search), null, Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(hint.ifEmpty { stringResource(R.string.widget_hint) }, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Clear, "Clear search", tint = style.color)
                    }
                }
            })
    }
}

@Composable
internal fun WidgetStatus(status: String, refresh: () -> Unit, settings: () -> Unit, dismiss: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(status, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Search options") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; refresh() })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; settings() })
                DropdownMenuItem(text = { Text("Close") }, onClick = { menu = false; dismiss() })
            }
        }
    }
}

package app.unpaused.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.unpaused.ui.theme.LocalExtra
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt

/** Upper-case monospace label, the app's "technical" voice. */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalExtra.current.dim) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun DotHeading(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(LocalExtra.current.accent))
        Spacer(Modifier.width(10.dp))
        MonoLabel(title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
        if (action != null && onAction != null) TextButton(onClick = onAction) { MonoLabel(action) }
    }
}

/** Album art from the local cache (a file path) or a URL; a dot grid when there's none. */
@Composable
fun Artwork(model: Any?, modifier: Modifier = Modifier, corner: Dp = 12.dp, contentDescription: String? = null) {
    val shape = RoundedCornerShape(corner)
    val resolved: Any? = when (model) {
        null -> null
        is String -> when {
            model.isEmpty() -> null
            model.startsWith("/") -> File(model)
            else -> model
        }
        else -> model
    }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        DotPattern(Modifier.fillMaxSize())
        if (resolved != null) {
            AsyncImage(model = resolved, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun DotPattern(modifier: Modifier = Modifier) {
    val dot = LocalExtra.current.dotOff
    Canvas(modifier) {
        val step = 9.dp.toPx()
        val r = 1.2.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(dot, r, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/**
 * Seek bar drawn as a row of dots. Tap or drag to seek; the red dot is the play head.
 * [enabled] = false for live streams.
 */
@Composable
fun DotSeekBar(positionMs: Long, bufferedMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val on = MaterialTheme.colorScheme.onBackground
    val buffered = LocalExtra.current.dim
    val off = LocalExtra.current.dotOff
    val accent = LocalExtra.current.accent
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val input = if (!enabled || durationMs <= 0) Modifier else Modifier
        .pointerInput(durationMs) {
            detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong()) }
        }
        .pointerInput(durationMs) {
            detectHorizontalDragGestures(
                onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { dragFraction?.let { onSeek((it * durationMs).toLong()) }; dragFraction = null },
                onDragCancel = { dragFraction = null },
                onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
            )
        }
    Canvas(
        modifier.fillMaxWidth().height(28.dp).then(input)
            .semantics { contentDescription = "Seek bar, ${(fraction * 100).roundToInt()} percent" },
    ) {
        widthPx = size.width
        val step = 7.dp.toPx()
        val count = (size.width / step).toInt().coerceAtLeast(2)
        val gap = size.width / (count - 1)
        val cy = size.height / 2
        val head = (fraction * (count - 1)).roundToInt()
        val bufferedIndex = (bufferedFraction * (count - 1)).roundToInt()
        for (i in 0 until count) {
            val color = when {
                i < head -> on
                i <= bufferedIndex -> buffered.copy(alpha = 0.55f)
                else -> off
            }
            drawCircle(color, 1.8.dp.toPx(), Offset(i * gap, cy))
        }
        if (enabled && durationMs > 0) drawCircle(accent, 4.5.dp.toPx(), Offset(head * gap, cy))
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

data class MenuAction(val label: String, val onClick: () -> Unit)

@Composable
fun OverflowMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a -> DropdownMenuItem(text = { Text(a.label) }, onClick = { open = false; a.onClick() }) }
        }
    }
}

@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    artwork: Any?,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    badge: String? = null,
    actions: List<MenuAction> = emptyList(),
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artwork, Modifier.size(48.dp), corner = 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                color = if (playing) LocalExtra.current.accent else MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) {
                    Text(
                        badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
            }
        }
        if (actions.isNotEmpty()) OverflowMenu(actions) else Spacer(Modifier.width(12.dp))
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp)) { DotPattern(Modifier.fillMaxSize()) }
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.dim)
        if (action != null && onAction != null) PillButton(action, onAction)
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true, enabled: Boolean = true) {
    val bg = if (filled) MaterialTheme.colorScheme.onBackground else Color.Transparent
    val fg = if (filled) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .border(1.dp, if (filled) Color.Transparent else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Small selectable chip used for modes, tabs and filters. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalExtra.current.accent
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(6.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.5f))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = LocalExtra.current.accent, checkedThumbColor = Color.White),
        )
    }
}

@Composable
fun SliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, subtitle: String? = null, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onBackground)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.dim)
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}

@Composable
fun ClickRow(title: String, subtitle: String? = null, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
        }
        if (trailing != null) MonoLabel(trailing)
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(padding),
    ) { content() }
}

@Composable
fun KeyValue(key: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        MonoLabel(key, Modifier.width(120.dp))
        Text(
            value, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal),
            color = if (highlight) LocalExtra.current.accent else MaterialTheme.colorScheme.onBackground,
        )
    }
}

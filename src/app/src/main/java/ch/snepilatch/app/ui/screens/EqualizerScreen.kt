package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.playback.EqualizerHeadroom
import ch.snepilatch.app.playback.MusicPlaybackService
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.AppSettings
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import ch.snepilatch.app.viewmodel.ThemeController
import kotlin.math.abs
import kotlin.math.roundToInt

private val MAX_DB = EqualizerHeadroom.MAX_GAIN_DB
private const val GRID_STEP_DB = 6f

@Composable
fun EqualizerScreen(vm: PlaybackViewModel) {
    val context = LocalContext.current
    val enabled by AppSettings.eqEnabled.collectAsState()
    val bands by AppSettings.eqBands.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    // Reported by the service; on API 26–27 DynamicsProcessing doesn't exist and the EQ stays inert.
    val supported = MusicPlaybackService.instance?.equalizerSupported ?: true
    val preamp = EqualizerHeadroom.inputGainDb(bands)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SpfyWhite)
            }
            Text(stringResource(R.string.equalizer), color = SpfyWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        if (!supported) {
            Text(
                stringResource(R.string.eq_requires_p),
                color = SpfyLightGray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        EqualizerHeader(
            enabled = enabled,
            supported = supported,
            preamp = preamp,
            accent = theme.primary,
            onEnabledChange = { AppSettings.setEqEnabled(it, context) },
            onFlat = { AppSettings.setEqBands(FloatArray(EqualizerHeadroom.BANDS), context) }
        )

        EqualizerCurve(
            bands = bands,
            enabled = supported && enabled,
            accent = theme.primary,
            onCommit = { AppSettings.setEqBands(it, context) }
        )
    }
}

@Composable
private fun EqualizerHeader(
    enabled: Boolean,
    supported: Boolean,
    preamp: Float,
    accent: Color,
    onEnabledChange: (Boolean) -> Unit,
    onFlat: () -> Unit
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.eq_in_app), color = SpfyWhite) },
        supportingContent = { Text(stringResource(R.string.eq_in_app_desc), color = SpfyLightGray) },
        trailingContent = {
            // Tint from the album palette like every other toggle; the Material default is the
            // template's purple, which is what made this one stand out.
            Switch(
                checked = enabled,
                enabled = supported,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.5f),
                    uncheckedThumbColor = SpfyLightGray,
                    uncheckedTrackColor = SpfyLightGray.copy(alpha = 0.3f)
                )
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.eq_preamp, "%.1f".format(preamp)), color = SpfyWhite, fontSize = 15.sp)
            Text(stringResource(R.string.eq_preamp_auto), color = SpfyLightGray, fontSize = 12.sp)
        }
        TextButton(onClick = onFlat) { Text(stringResource(R.string.eq_flat), color = accent) }
    }
}

/**
 * Wavelet-style curve editor: one draggable point per band, joined by a Catmull-Rom spline with the
 * area under it filled. Dragging redraws live but only commits on release — committing rebuilds the
 * audio effect, which is not a per-frame operation.
 */
@Composable
private fun EqualizerCurve(
    bands: FloatArray,
    enabled: Boolean,
    accent: Color,
    onCommit: (FloatArray) -> Unit
) {
    var live by remember(bands) { mutableStateOf(bands.copyOf()) }
    var active by remember { mutableIntStateOf(-1) }
    val alpha = if (enabled) 1f else 0.4f

    BandValueRow(live, accent.copy(alpha = alpha))

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(268.dp)
            // Vertical inset so a ±12 dB point sits inside the plot instead of on its edge. The
            // pointer handlers below see this padded box, so touch and drawing stay in one space.
            .padding(horizontal = 8.dp, vertical = 14.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    // Grab the nearest point and move it BY the drag, never TO the finger — jumping
                    // the value to wherever the touch landed is what made this feel like it snapped.
                    onDragStart = { pos -> active = bandAt(pos.x, size.width) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val index = active
                        if (index >= 0) {
                            val delta = -dragAmount.y / size.height * 2 * MAX_DB
                            live = live.copyOf().also {
                                it[index] = (it[index] + delta).coerceIn(-MAX_DB, MAX_DB)
                            }
                        }
                    },
                    onDragEnd = {
                        active = -1
                        onCommit(live.snapped())
                    }
                )
            }
    ) {
        drawGrid(accent.copy(alpha = alpha))
        drawCurve(live, accent.copy(alpha = alpha), active)
    }

    BandFrequencyRow(SpfyLightGray.copy(alpha = alpha))
}

@Composable
private fun BandValueRow(bands: FloatArray, color: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        bands.forEach { gain ->
            Text(
                "%.1f".format(gain),
                color = color,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BandFrequencyRow(color: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        EqualizerHeadroom.FREQUENCIES.forEach { frequency ->
            Text(
                if (frequency >= 1000f) "${(frequency / 1000).toInt()}k" else "${frequency.toInt()}",
                color = color,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** Band whose column contains [x]; columns are equal cells so the label rows line up by weight. */
private fun bandAt(x: Float, width: Int): Int {
    val cell = width.toFloat() / EqualizerHeadroom.BANDS
    return (x / cell).toInt().coerceIn(0, EqualizerHeadroom.BANDS - 1)
}

/** Round to 0.1 dB on the way to the effect, so stored curves stay tidy. */
private fun FloatArray.snapped() = FloatArray(size) { (this[it] * 10).roundToInt() / 10f }

private fun DrawScope.pointFor(index: Int, gain: Float): Offset {
    val cell = size.width / EqualizerHeadroom.BANDS
    return Offset(cell * (index + 0.5f), (MAX_DB - gain) / (2 * MAX_DB) * size.height)
}

private fun DrawScope.drawGrid(accent: Color) {
    var db = -MAX_DB
    while (db <= MAX_DB) {
        val y = (MAX_DB - db) / (2 * MAX_DB) * size.height
        val isZero = abs(db) < 0.01f
        drawLine(
            color = if (isZero) accent.copy(alpha = 0.45f) else SpfyLightGray.copy(alpha = 0.12f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = if (isZero) 2f else 1f
        )
        db += GRID_STEP_DB
    }
}

private fun DrawScope.drawCurve(bands: FloatArray, accent: Color, active: Int) {
    val points = bands.mapIndexed { index, gain -> pointFor(index, gain) }
    // Stems, so every band stays visibly grabbable even where the curve is flat.
    points.forEach { drawLine(accent.copy(alpha = 0.3f), Offset(it.x, 0f), Offset(it.x, size.height), 2f) }

    val path = Path().apply {
        moveTo(0f, points.first().y)
        lineTo(points.first().x, points.first().y)
        for (i in 0 until points.size - 1) {
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(points.size - 1)]
            cubicTo(
                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                p2.x, p2.y
            )
        }
        lineTo(size.width, points.last().y)
    }
    val fill = Path().apply {
        addPath(path)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(fill, accent.copy(alpha = 0.18f))
    drawPath(path, accent, style = Stroke(width = 4f))
    points.forEachIndexed { index, point ->
        drawCircle(accent, radius = if (index == active) 18f else 12f, center = point)
    }
}

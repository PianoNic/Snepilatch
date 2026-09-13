@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.logic.lyrics.DotsAnimator
import ch.snepilatch.app.logic.lyrics.DotsItem
import ch.snepilatch.app.logic.lyrics.LineAnimator
import ch.snepilatch.app.logic.lyrics.LineItem
import ch.snepilatch.app.logic.lyrics.LyricsStyle
import ch.snepilatch.app.logic.lyrics.Motion
import ch.snepilatch.app.logic.lyrics.SungState
import ch.snepilatch.app.logic.lyrics.Words
import ch.snepilatch.app.logic.lyrics.WordsAnimator
import ch.snepilatch.app.logic.lyrics.lyricItems
import ch.snepilatch.app.logic.lyrics.sungStateAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotify.api.lyrics.LyricsData
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Synced lyrics in the Beautiful Lyrics style (#810): the sung line is bright and its words fill
 * left to right with a soft edge, each word springs up and glows as it is sung, long words light
 * letter by letter, the other lines dim and blur with their distance, the sung line is kept just
 * above the middle, and long gaps show three breathing dots. The numbers come from
 * [LyricsStyle]; the springs run in [LineAnimator]. Only the sung line's leaves redraw per frame:
 * the frame loop bumps a tick that the draw and layer lambdas read, nothing recomposes.
 */
@Composable
internal fun SyncedLyricsView(lyrics: LyricsData, smoothPosition: State<Long>, isLandscape: Boolean, lineFillDirection: String) {
    val items = remember(lyrics) { lyricItems(lyrics.lines) }
    val syllableSynced = lyrics.syncType == "SYLLABLE_SYNCED"
    val fontSize = if (isLandscape) 24.sp else 30.sp
    val listState = rememberLazyListState()
    val activeIndex by remember(items) {
        derivedStateOf {
            val t = smoothPosition.value + LyricsStyle.LEAD_MS
            items.indexOfLast { t >= it.startMs }.coerceAtLeast(0)
        }
    }
    val userScrolling = rememberUserScrollLockout(listState)
    val density = LocalDensity.current
    val anchor = remember(items, fontSize, density) {
        with(density) {
            ScrollAnchor(
                abovePx = LyricsStyle.SCROLL_ABOVE_CENTER_PX.dp.toPx(),
                estimatedItemPx = (fontSize * LyricsStyle.LINE_HEIGHT_EM).toPx() + 8.dp.toPx(),
            )
        }
    }
    LaunchedEffect(activeIndex, userScrolling.value) {
        if (!userScrolling.value) anchor.scrollTo(listState, activeIndex)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().fadedEdges(),
            contentPadding = PaddingValues(start = 24.dp, end = 36.dp, top = viewport * 0.35f, bottom = viewport * 0.5f),
        ) {
            itemsIndexed(items, key = { i, _ -> i }) { index, item ->
                when (item) {
                    is DotsItem -> DotsRow(item, smoothPosition, fontSize)
                    is LineItem -> {
                        val state by remember(item) {
                            derivedStateOf { sungStateAt(smoothPosition.value + LyricsStyle.LEAD_MS, item.startMs, item.endMs) }
                        }
                        LyricLine(
                            item = item,
                            state = state,
                            blurPx = if (userScrolling.value) 0f else LyricsStyle.blurPx(abs(index - activeIndex)),
                            wholeLine = !syllableSynced || item.lead.syllables.isEmpty(),
                            fillDirection = lineFillDirection,
                            smoothPosition = smoothPosition,
                            fontSize = fontSize,
                        )
                    }
                }
            }
        }
    }
}

/** Lyrics without timing: every line the same, nothing moves. */
@Composable
internal fun UnsyncedLyricsView(lyrics: LyricsData, isLandscape: Boolean) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().fadedEdges(),
        contentPadding = PaddingValues(start = 24.dp, end = 36.dp, top = if (isLandscape) 40.dp else 80.dp, bottom = 160.dp),
    ) {
        itemsIndexed(lyrics.lines, key = { i, _ -> i }) { _, line ->
            Text(
                text = line.text,
                color = Color.White.copy(alpha = LyricsStyle.FILL_ALPHA),
                style = lyricStyle(if (isLandscape) 20.sp else 24.sp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
        }
    }
}

/** True from the first touch drag until the cooldown after the last one has passed and the fling has ended. */
@Composable
private fun rememberUserScrollLockout(listState: LazyListState): State<Boolean> {
    val locked = remember { mutableStateOf(false) }
    var lastRelease by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> locked.value = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> lastRelease = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(lastRelease) {
        if (lastRelease == 0L) return@LaunchedEffect
        delay(LyricsStyle.USER_SCROLL_COOLDOWN_MS)
        snapshotFlow { listState.isScrollInProgress }.first { !it }
        locked.value = false
    }
    return locked
}

/**
 * Puts the active row a little above the middle of the viewport, then corrects once its real
 * height is known. The first scroll (the lyrics just opened, or a new track's arrived) and any
 * jump of more than [JUMP_LINES] rows (a seek) are instant, the way the web player does it; only
 * ordinary progression glides (#812).
 */
private class ScrollAnchor(private val abovePx: Float, private val estimatedItemPx: Float) {

    private var lastIndex: Int? = null

    suspend fun scrollTo(listState: LazyListState, index: Int) {
        snapshotFlow { listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset }.first { it > 0 }
        val jump = lastIndex?.let { abs(index - it) > JUMP_LINES } ?: true
        lastIndex = index
        val offset = -targetTop(listState, index).roundToInt()
        if (jump) listState.scrollToItem(index, offset) else listState.animateScrollToItem(index, offset)
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val delta = item.offset - targetTop(listState, index)
        if (abs(delta) <= 2f) return
        if (jump) listState.scrollBy(delta) else listState.animateScrollBy(delta)
    }

    private fun targetTop(listState: LazyListState, index: Int): Float {
        val info = listState.layoutInfo
        val itemPx = info.visibleItemsInfo.firstOrNull { it.index == index }?.size?.toFloat() ?: estimatedItemPx
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        return info.viewportStartOffset + (viewport - itemPx) / 2f - abovePx
    }

    private companion object {
        const val JUMP_LINES = 4
    }
}

/** The list fades out over its top and bottom edges, like the web player's mask. */
private fun Modifier.fadedEdges(): Modifier = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
    drawContent()
    val solid = 64.dp.toPx() / size.height
    val clear = 16.dp.toPx() / size.height
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent, clear to Color.Transparent, solid to Color.Black,
            1f - solid to Color.Black, 1f - clear to Color.Transparent, 1f to Color.Transparent,
        ),
        blendMode = BlendMode.DstIn,
    )
}

/**
 * One lyric row: dims, blurs ([blurPx], by distance from the sung row, 0 while the user scrolls)
 * and (line type) scales with its state; the words inside animate on their own.
 */
@Composable
private fun LyricLine(
    item: LineItem,
    state: SungState,
    blurPx: Float,
    wholeLine: Boolean,
    fillDirection: String,
    smoothPosition: State<Long>,
    fontSize: TextUnit,
) {
    val alpha by animateFloatAsState(
        targetValue = when (state) {
            SungState.ACTIVE -> LyricsStyle.ACTIVE_OPACITY
            SungState.NOT_SUNG -> LyricsStyle.NOT_SUNG_OPACITY
            SungState.SUNG -> LyricsStyle.SUNG_OPACITY
        },
        animationSpec = tween(LyricsStyle.LINE_TRANSITION_MS, easing = CubicBezierEasing(0.61f, 1f, 0.88f, 1f)),
        label = "lineAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (wholeLine && state == SungState.ACTIVE) LyricsStyle.LINE_ACTIVE_SCALE else 1f,
        animationSpec = tween(LyricsStyle.LINE_TRANSITION_MS, easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)),
        label = "lineScale",
    )
    val animator = remember(item) { LineAnimator(item) }
    val tick = remember { mutableLongStateOf(0L) }
    DriveAnimator(state, smoothPosition, tick, asleep = { animator.asleep }, step = animator::step, snap = animator::snapTo)
    val look = LineLook(
        style = lyricStyle(fontSize),
        fontPx = with(LocalDensity.current) { fontSize.toPx() },
        state = state,
        blurPx = if (state == SungState.ACTIVE) 0f else blurPx,
        fillAlpha = LyricsStyle.FILL_ALPHA,
        fillAlphaEnd = if (wholeLine) LyricsStyle.LINE_FILL_ALPHA_END else LyricsStyle.FILL_ALPHA_END,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = if (wholeLine) 8.dp else 4.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
    ) {
        if (wholeLine) {
            WholeLine(item.line.text, look, animator, tick, vertical = fillDirection != "horizontal")
        } else {
            WordsLine(item, look, animator, tick)
        }
    }
}

/**
 * Steps the animator once per frame while the row is sung, and on until its springs have come to
 * rest afterwards. A row composed at rest (scrolled into view) is just snapped to its state.
 */
@Composable
private fun DriveAnimator(
    state: SungState,
    smoothPosition: State<Long>,
    tick: MutableLongState,
    asleep: () -> Boolean,
    step: (Long, Float) -> Unit,
    snap: (Long) -> Unit,
) {
    LaunchedEffect(state) {
        val now = { smoothPosition.value + LyricsStyle.LEAD_MS }
        if (state != SungState.ACTIVE && asleep()) {
            snap(now())
            tick.longValue++
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val dt = ((frame - last) / 1e9f).coerceIn(0f, 0.1f)
            last = frame
            step(now(), dt)
            tick.longValue++
            if (state != SungState.ACTIVE && asleep()) break
        }
    }
}

/** How a voice's pieces are painted: the text style, the glyph alpha behind and ahead of the fill, the state and blur. */
private class LineLook(
    val style: TextStyle,
    val fontPx: Float,
    val state: SungState,
    val blurPx: Float,
    val fillAlpha: Float,
    val fillAlphaEnd: Float,
) {
    /** The backing vocals' look: three quarters of the size, a lighter weight, a dimmer fill. */
    fun background(): LineLook = LineLook(
        style = style.copy(
            fontSize = style.fontSize * LyricsStyle.BACKGROUND_SCALE,
            lineHeight = style.lineHeight * LyricsStyle.BACKGROUND_SCALE,
            fontWeight = FontWeight.SemiBold,
        ),
        fontPx = fontPx * LyricsStyle.BACKGROUND_SCALE,
        state = state,
        blurPx = blurPx,
        fillAlpha = LyricsStyle.BACKGROUND_FILL_ALPHA,
        fillAlphaEnd = LyricsStyle.BACKGROUND_FILL_ALPHA_END,
    )
}

/** How a piece glows: shadow blur growth in px, shadow opacity per unit of glow, and how far it lifts. */
private class GlowSpec(val blurPx: Float, val opacity: Float, val liftFactor: Float) {
    companion object {
        val WORD = GlowSpec(LyricsStyle.WORD_GLOW_BLUR_PX, LyricsStyle.WORD_GLOW_OPACITY, 1f)
        val LETTER =
            GlowSpec(LyricsStyle.LETTER_GLOW_BLUR_PX, LyricsStyle.LETTER_GLOW_OPACITY, LyricsStyle.LETTER_LIFT_FACTOR)
        val LINE = GlowSpec(LyricsStyle.LINE_GLOW_BLUR_PX, LyricsStyle.LINE_GLOW_OPACITY, 1f)
        val DOT = GlowSpec(LyricsStyle.DOT_GLOW_BLUR_PX, LyricsStyle.DOT_GLOW_OPACITY, 1f)
    }
}

private fun lyricStyle(fontSize: TextUnit) = TextStyle(
    fontSize = fontSize,
    fontWeight = FontWeight.Bold,
    lineHeight = fontSize * LyricsStyle.LINE_HEIGHT_EM,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** The lead voice of a syllable-synced line, and under it the backing vocals when the line has them. */
@Composable
private fun WordsLine(item: LineItem, look: LineLook, animator: LineAnimator, tick: State<Long>) {
    val background = item.background
    if (background == null) {
        WordsFlow(item.lead, look, animator.lead, tick)
        return
    }
    Column {
        WordsFlow(item.lead, look, animator.lead, tick)
        Spacer(Modifier.height(1.dp))
        WordsFlow(background, look.background(), checkNotNull(animator.background), tick)
    }
}

/** One voice's words, wrapping like text; the pieces of one word stay together. */
@Composable
private fun WordsFlow(spec: Words, look: LineLook, animator: WordsAnimator, tick: State<Long>) {
    val measurer = rememberTextMeasurer()
    val gap = with(LocalDensity.current) {
        remember(look.style) { (measurer.measure("0", look.style).size.width * LyricsStyle.WORD_GAP_CH).toDp() }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        for (word in spec.runs) {
            Row {
                for (i in word) {
                    val origin = pieceOrigin(i, word)
                    val text = spec.syllables[i].text
                    val letters = animator.letters[i]
                    if (letters == null) {
                        WordPiece(text, look, animator.words[i], tick, origin, GlowSpec.WORD)
                    } else {
                        EmphasisedWord(text, look, animator.words[i], letters, tick, origin)
                    }
                }
            }
        }
    }
}

/** A piece that continues into the next one scales from its right edge, the piece after it from its left. */
private fun pieceOrigin(index: Int, word: IntRange): TransformOrigin = when {
    word.first == word.last -> TransformOrigin.Center
    index == word.first -> TransformOrigin(1f, 0.5f)
    index == word.last -> TransformOrigin(0f, 0.5f)
    else -> TransformOrigin.Center
}

/** A long syllable: the word springs as one, and each letter springs and glows on its own on top. */
@Composable
private fun EmphasisedWord(
    text: String,
    look: LineLook,
    word: Motion,
    letters: List<Motion>,
    tick: State<Long>,
    origin: TransformOrigin,
) {
    Row(
        Modifier.graphicsLayer {
            tick.value
            scaleX = word.scale.position
            scaleY = word.scale.position
            translationY = word.lift.position * look.fontPx
            transformOrigin = origin
        }
    ) {
        for ((k, ch) in text.withIndex()) {
            WordPiece(ch.toString(), look, letters[k], tick, TransformOrigin.Center, GlowSpec.LETTER)
        }
    }
}

/** One word, syllable or letter: measured once, transformed by its springs, painted by its state. */
@Composable
private fun WordPiece(text: String, look: LineLook, motion: Motion, tick: State<Long>, origin: TransformOrigin, glow: GlowSpec) {
    val measurer = rememberTextMeasurer()
    val layout = remember(text, look.style) { measurer.measure(text, look.style) }
    val size = with(LocalDensity.current) { DpSize(layout.size.width.toDp(), layout.size.height.toDp()) }
    Spacer(
        Modifier
            .size(size)
            .graphicsLayer {
                tick.value
                scaleX = motion.scale.position
                scaleY = motion.scale.position
                translationY = motion.lift.position * glow.liftFactor * look.fontPx
                transformOrigin = origin
            }
            .drawBehind {
                tick.value
                drawPiece(layout, look, motion.fill, motion.glow.position, glow, vertical = false)
            }
    )
}

/** A line synced as one piece: wraps to the width, fills top to bottom (or left to right), glows as a whole. */
@Composable
private fun WholeLine(text: String, look: LineLook, animator: LineAnimator, tick: State<Long>, vertical: Boolean) {
    val measurer = rememberTextMeasurer()
    val holder = remember { LayoutHolder() }
    Spacer(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val result = measurer.measure(text, look.style, constraints = Constraints(maxWidth = constraints.maxWidth))
                holder.layout = result
                val placeable = measurable.measure(Constraints.fixed(result.size.width, result.size.height))
                layout(result.size.width, result.size.height) { placeable.place(0, 0) }
            }
            .drawBehind {
                tick.value
                val layout = holder.layout ?: return@drawBehind
                drawPiece(layout, look, animator.lineFill, animator.lineGlow.position, GlowSpec.LINE, vertical)
            }
    )
}

private class LayoutHolder {
    var layout: TextLayoutResult? = null
}

/**
 * The sung piece is a gradient across the glyphs, bright behind the fill edge and dim ahead of it,
 * with a glow underneath; any other piece is solid dim glyphs, or their blurred shadow when the
 * line is blurred (the glyphs themselves transparent, exactly how the web player does it).
 */
private fun DrawScope.drawPiece(
    layout: TextLayoutResult,
    look: LineLook,
    fill: Float,
    glow: Float,
    spec: GlowSpec,
    vertical: Boolean,
) {
    if (look.state != SungState.ACTIVE) {
        val alpha = if (look.state == SungState.SUNG) look.fillAlpha else look.fillAlphaEnd
        if (look.blurPx > 0f) {
            drawText(layout, color = Color.Transparent, shadow = Shadow(Color.White.copy(alpha = alpha), Offset.Zero, look.blurPx.dp.toPx()))
        } else {
            drawText(layout, color = Color.White.copy(alpha = alpha))
        }
        return
    }
    if (glow > 0.005f) {
        val blur = (LyricsStyle.GLOW_BLUR_BASE_PX + spec.blurPx * glow).dp.toPx()
        val alpha = (glow * spec.opacity).coerceIn(0f, MAX_SHADOW_ALPHA)
        drawText(layout, color = Color.Transparent, shadow = Shadow(Color.White.copy(alpha = alpha), Offset.Zero, blur))
    }
    val sung = Color.White.copy(alpha = look.fillAlpha)
    val unsung = Color.White.copy(alpha = look.fillAlphaEnd)
    val extent = if (vertical) size.height else size.width
    val start = extent * fill / 100f
    val end = extent * (fill + LyricsStyle.FILL_BAND) / 100f
    when {
        end - start < 1f -> drawText(layout, color = if (fill >= LyricsStyle.FILL_END) sung else unsung)
        // The explicit alpha matters: the glow pass above leaves the shared text paint transparent.
        vertical -> drawText(layout, brush = Brush.verticalGradient(listOf(sung, unsung), startY = start, endY = end), alpha = 1f)
        else -> drawText(layout, brush = Brush.horizontalGradient(listOf(sung, unsung), startX = start, endX = end), alpha = 1f)
    }
}

/** An opaque shadow colour makes Android take the (transparent) text paint's alpha instead. */
private const val MAX_SHADOW_ALPHA = 0.99f

/** The three dots of a long gap: the row unfolds while the gap is on, the group pops in and out, each dot breathes in turn. */
@Composable
private fun DotsRow(item: DotsItem, smoothPosition: State<Long>, fontSize: TextUnit) {
    val animator = remember(item) { DotsAnimator(item) }
    val tick = remember { mutableLongStateOf(0L) }
    val state by remember(item) {
        derivedStateOf { sungStateAt(smoothPosition.value + LyricsStyle.LEAD_MS, item.startMs, item.endMs) }
    }
    val shown by remember(item) {
        derivedStateOf {
            val t = smoothPosition.value + LyricsStyle.LEAD_MS
            t >= item.startMs && t < item.endMs - LyricsStyle.DOTS_PRE_HIDDEN_MS
        }
    }
    DriveAnimator(state, smoothPosition, tick, asleep = { animator.asleep }, step = animator::step, snap = animator::snapTo)
    val groupScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (shown) tween(LyricsStyle.DOTS_IN_MS) else tween(LyricsStyle.DOTS_OUT_MS, easing = DotsOutEasing),
        label = "dotsGroup",
    )
    val unfolded by animateFloatAsState(
        targetValue = if (state == SungState.ACTIVE) 1f else 0f,
        animationSpec = tween(LyricsStyle.DOTS_LINE_MS),
        label = "dotsRow",
    )
    val density = LocalDensity.current
    val fontPx = with(density) { fontSize.toPx() }
    val style = lyricStyle(fontSize * LyricsStyle.DOT_SIZE_EM).copy(lineHeight = fontSize * LyricsStyle.DOT_SIZE_EM * 0.65f)
    val measurer = rememberTextMeasurer()
    val layout = remember(style) { measurer.measure("•", style) }
    val size = with(density) { DpSize(layout.size.width.toDp(), layout.size.height.toDp()) }
    Row(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height * unfolded).roundToInt()) { placeable.place(0, 0) }
            }
            .padding(vertical = 4.dp)
            .graphicsLayer {
                scaleX = groupScale
                scaleY = groupScale
                alpha = unfolded
                transformOrigin = TransformOrigin.Center
            },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (dot in animator.dots) {
            Spacer(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        tick.value
                        scaleX = dot.scale.position
                        scaleY = dot.scale.position
                        translationY = dot.lift.position * fontPx
                        alpha = dot.opacity.position.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin.Center
                    }
                    .drawBehind {
                        tick.value
                        val glow = dot.glow.position
                        if (glow > 0.005f) {
                            val blur = (LyricsStyle.GLOW_BLUR_BASE_PX + GlowSpec.DOT.blurPx * glow).dp.toPx()
                            val alpha = (glow * GlowSpec.DOT.opacity).coerceIn(0f, MAX_SHADOW_ALPHA)
                            drawText(layout, color = Color.Transparent, shadow = Shadow(Color.White.copy(alpha = alpha), Offset.Zero, blur))
                        }
                        drawText(layout, color = Color.White.copy(alpha = LyricsStyle.FILL_ALPHA))
                    }
            )
        }
    }
}

/** The web player's exit curve for the dots: a small anticipation swell, then the collapse. */
private val DotsOutEasing = Easing { t ->
    val points = DOTS_OUT_CURVE
    var i = 2
    while (i < points.size - 2 && t > points[i]) i += 2
    val x0 = points[i - 2]
    val y0 = points[i - 1]
    val x1 = points[i]
    val y1 = points[i + 1]
    if (x1 == x0) y1 else y0 + (y1 - y0) * ((t - x0) / (x1 - x0)).coerceIn(0f, 1f)
}

private val DOTS_OUT_CURVE = floatArrayOf(
    0f, 0f, 0.094f, -0.006f, 0.18f, -0.029f, 0.433f, -0.157f, 0.514f, -0.185f, 0.559f, -0.189f, 0.6f, -0.182f,
    0.639f, -0.163f, 0.676f, -0.133f, 0.723f, -0.074f, 0.767f, 0.006f, 0.85f, 0.238f, 0.927f, 0.566f, 1f, 1f,
)

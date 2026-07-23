@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.LyricsViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotify.api.lyrics.LyricsData
import kotify.api.lyrics.SyncedLine

@Composable
fun LyricsScreen(vm: PlaybackViewModel) {
    val lyricsVm: LyricsViewModel = viewModel()
    // Narrow projections — the lyrics scaffold must not recompose on the 2Hz position tick; position
    // is consumed only through the smoothPosition state below (mutated off the flow, not read here).
    val track by vm.currentTrack.collectAsState()
    val isPlayingRaw by vm.isPlayingFlow.collectAsState()
    val isPaused by vm.isPausedFlow.collectAsState()
    val repeatMode by vm.repeatModeFlow.collectAsState()
    val theme by vm.themeColors.collectAsState()
    val lyrics by lyricsVm.lyrics.collectAsState()
    val isLoading by lyricsVm.isLoading.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "lyricsPrimary")
    val lyricsAnimDirection by vm.lyricsAnimDirection.collectAsState()

    LaunchedEffect(track?.uri) {
        track?.uri?.let { lyricsVm.fetch(it) }
    }

    // Smooth position interpolation:
    // We remember the wall-clock time when the ViewModel last gave us a position,
    // then each frame we compute: lastKnownPos + (now - lastKnownWallClock)
    val smoothPosition = remember { mutableLongStateOf(vm.positionFlow.value) }
    val lastKnownPos = remember { mutableLongStateOf(vm.positionFlow.value) }
    val lastKnownWallClock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isPlaying = isPlayingRaw && !isPaused

    // Re-anchor whenever an authoritative position arrives. Collecting the flow inside the coroutine
    // (instead of keying a LaunchedEffect on a composition read) keeps the 2Hz ticks from recomposing
    // the whole lyrics scaffold — only smoothPosition mutates, which only the lyric leaves read.
    LaunchedEffect(Unit) {
        vm.positionFlow.collect { pos ->
            lastKnownPos.longValue = pos
            lastKnownWallClock.longValue = System.currentTimeMillis()
            smoothPosition.longValue = pos
        }
    }

    // Only LINE/SYLLABLE-synced lyrics drive a per-frame karaoke reveal; unsynced/loading/no-lyrics
    // tracks don't need the frame loop at all (the coarse anchor effect above already tracks position).
    val syncedReveal = lyrics?.syncType == "LINE_SYNCED" || lyrics?.syncType == "SYLLABLE_SYNCED"

    // Each frame, compute interpolated position from anchor — but only when a synced reveal needs it.
    LaunchedEffect(isPlaying, syncedReveal) {
        if (!isPlaying || !syncedReveal) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val elapsed = System.currentTimeMillis() - lastKnownWallClock.longValue
            val interpolated = lastKnownPos.longValue + elapsed
            smoothPosition.longValue = interpolated.coerceAtMost(vm.durationFlow.value.coerceAtLeast(1))
        }
    }

    Box(Modifier.fillMaxSize()) {
        track?.albumArt?.let { artUrl ->
            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(artUrl).crossfade(800).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(100.dp)
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isLandscape = maxWidth > maxHeight

            if (isLandscape) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .weight(0.4f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        SpotifyImage(
                            url = track?.albumArt,
                            modifier = Modifier
                                .fillMaxHeight(0.55f)
                                .aspectRatio(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(10.dp))

                        Text(
                            track?.name ?: "",
                            color = SpotifyWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            track?.artist ?: "",
                            color = SpotifyLightGray,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(10.dp))

                        val streamLoading by vm.isStreamLoading.collectAsState()
                        val isLiked by vm.currentTrackLiked.collectAsState()
                        val buttonBg = Color.White.copy(alpha = 0.12f)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.cycleRepeat() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    when (repeatMode) { "track" -> Icons.Rounded.RepeatOne; else -> Icons.Rounded.Repeat },
                                    stringResource(R.string.repeat),
                                    tint = if (repeatMode != "off") animatedPrimary else SpotifyWhite.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.skipPrevious() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.previous), tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                            }
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(if (streamLoading) animatedPrimary.copy(alpha = 0.5f) else animatedPrimary, CircleShape)
                                    .clip(CircleShape)
                                    .clickable { if (!streamLoading) vm.togglePlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (streamLoading) {
                                    LoadingIndicator(color = SpotifyWhite, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(
                                        if (isPaused || !isPlayingRaw) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        stringResource(R.string.play_pause), tint = SpotifyWhite, modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.skipNext() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.SkipNext, stringResource(R.string.next), tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                            }
                            FilledIconToggleButton(
                                checked = isLiked,
                                onCheckedChange = { _ ->
                                    val id = track?.uri?.removePrefix("spotify:track:") ?: return@FilledIconToggleButton
                                    if (isLiked) vm.unlikeSong(id) else vm.likeSong(id)
                                },
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledIconToggleButtonColors(
                                    containerColor = buttonBg,
                                    contentColor = SpotifyWhite.copy(alpha = 0.7f),
                                    checkedContainerColor = buttonBg,
                                    checkedContentColor = animatedPrimary,
                                ),
                            ) {
                                Icon(
                                    if (isLiked) Icons.Rounded.Favorite else Icons.Filled.FavoriteBorder,
                                    stringResource(R.string.like),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(
                        Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                    ) {
                        when {
                            isLoading -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    LoadingIndicator(color = animatedPrimary)
                                }
                            }
                            lyrics == null || lyrics?.lines.isNullOrEmpty() -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.MusicNote, null, tint = SpotifyLightGray.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text(stringResource(R.string.lyrics_not_available), color = SpotifyLightGray, fontSize = 16.sp)
                                    }
                                }
                            }
                            else -> {
                                SyncedLyricsView(
                                    lyrics = lyrics!!,
                                    smoothPosition = smoothPosition,
                                    accentColor = animatedPrimary,
                                    isLandscape = true,
                                    lyricsAnimDirection = lyricsAnimDirection
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount > 5) vm.goBack()
                            }
                        }
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(40.dp)
                                .background(Color.White.copy(alpha = 0.12f), CircleShape)
                                .clip(CircleShape)
                                .clickable { vm.goBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.close), tint = SpotifyWhite, modifier = Modifier.size(24.dp))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(track?.name ?: "", color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(track?.artist ?: "", color = SpotifyLightGray, fontSize = 12.sp, maxLines = 1)
                        }
                        Spacer(Modifier.size(40.dp))
                    }

                    when {
                        isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                LoadingIndicator(color = animatedPrimary)
                            }
                        }
                        lyrics == null || lyrics?.lines.isNullOrEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Rounded.MusicNote, null, tint = SpotifyLightGray.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text(stringResource(R.string.lyrics_not_available), color = SpotifyLightGray, fontSize = 16.sp)
                                }
                            }
                        }
                        else -> {
                            SyncedLyricsView(
                                lyrics = lyrics!!,
                                smoothPosition = smoothPosition,
                                accentColor = animatedPrimary,
                                isLandscape = false,
                                lyricsAnimDirection = lyricsAnimDirection
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsView(
    lyrics: LyricsData,
    smoothPosition: MutableState<Long>,
    accentColor: Color,
    isLandscape: Boolean = false,
    lyricsAnimDirection: String = "vertical"
) {
    val lines = lyrics.lines
    val syncType = lyrics.syncType
    val isUnsynced = syncType == "UNSYNCED"
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // derivedStateOf so this view recomposes only when the active LINE changes, not every frame:
    // smoothPosition updates per display frame, but the derived index changes only a few times a
    // minute. Reading smoothPosition at body level would instead recompose the whole view every frame.
    val activeIndex by remember(lines, isUnsynced) {
        derivedStateOf {
            if (isUnsynced) -1 else lines.indexOfLast { smoothPosition.value >= it.startTimeMs }.coerceAtLeast(0)
        }
    }

    var userScrolling by remember { mutableStateOf(false) }
    var lastUserScroll by remember { mutableLongStateOf(0L) }

    // Auto-scroll only for synced lyrics
    if (!isUnsynced) {
        LaunchedEffect(activeIndex) {
            if (!userScrolling && System.currentTimeMillis() - lastUserScroll > 750) {
                scope.launch {
                    listState.animateScrollToItem(
                        index = (activeIndex - 1).coerceAtLeast(0)
                    )
                }
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrolling = true
            lastUserScroll = System.currentTimeMillis()
        } else if (userScrolling) {
            delay(750)
            userScrolling = false
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = if (isLandscape) 12.dp else 24.dp),
        contentPadding = if (isLandscape) PaddingValues(top = 40.dp, bottom = 80.dp)
                         else PaddingValues(top = 80.dp, bottom = 300.dp)
    ) {
        itemsIndexed(lines, key = { i, _ -> i }) { index, line ->
            if (isUnsynced) {
                // Unsynced: all lines same brightness, no highlight
                Text(
                    text = line.text,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 32.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            } else {
                LyricsLine(
                    line = line,
                    syncType = syncType,
                    isActive = index == activeIndex,
                    isSung = index < activeIndex,
                    smoothPosition = smoothPosition,
                    accentColor = accentColor,
                    lyricsAnimDirection = lyricsAnimDirection
                )
            }
        }
    }
}

@Composable
private fun LyricsLine(
    line: SyncedLine,
    syncType: String,
    isActive: Boolean,
    isSung: Boolean,
    smoothPosition: MutableState<Long>,
    accentColor: Color,
    lyricsAnimDirection: String = "vertical"
) {
    if (line.text.isBlank() || line.text == "♪") {
        if (isActive) InterludeDots(accentColor) else Spacer(Modifier.height(48.dp))
        return
    }

    val dimColor = Color.White.copy(alpha = if (isSung) 0.35f else 0.45f)
    val featherPx = with(LocalDensity.current) { 12.dp.toPx() }

    if (isActive) {
        when {
            syncType == "SYLLABLE_SYNCED" && line.syllables.isNotEmpty() -> {
                SyllableSyncedLine(line, smoothPosition, dimColor, featherPx)
            }
            syncType == "LINE_SYNCED" -> {
                LineSyncedLine(line, smoothPosition, dimColor, featherPx, lyricsAnimDirection)
            }
            else -> {
                // UNSYNCED or fallback — just show bright
                Text(
                    text = line.text,
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 36.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                )
            }
        }
    } else {
        Text(
            text = line.text,
            color = dimColor,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 34.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
        )
    }
}

// --- SYLLABLE SYNCED: word-by-word highlight ---

@Composable
private fun SyllableSyncedLine(
    line: SyncedLine,
    smoothPosition: MutableState<Long>,
    dimColor: Color,
    featherPx: Float
) {
    val syllables = line.syllables

    // Build the full text by joining syllables
    val fullText = buildString {
        for (syl in syllables) {
            if (isNotEmpty() && !syl.isPartOfWord) append(" ")
            append(syl.text)
        }
    }

    // Build character offset ranges for each syllable
    val sylRanges = mutableListOf<Triple<Int, Int, Int>>() // startChar, endChar, sylIndex
    var charPos = 0
    for ((idx, syl) in syllables.withIndex()) {
        if (charPos > 0 && !syl.isPartOfWord) charPos++ // space
        val start = charPos
        charPos += syl.text.length
        sylRanges.add(Triple(start, charPos, idx))
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        // Base — dimmed
        Text(
            text = fullText,
            color = dimColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            onTextLayout = { layoutResult = it }
        )
        // Overlay — bright, masked per-syllable
        Text(
            text = fullText,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            modifier = Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val layout = layoutResult ?: return@drawWithContent
                    val pos = smoothPosition.value

                    // Find how far through the syllables we are
                    // Compute a character-level reveal position
                    var revealChars = 0f
                    for ((startChar, endChar, sylIdx) in sylRanges) {
                        val syl = syllables[sylIdx]
                        val sylDur = (syl.endTimeMs - syl.startTimeMs).coerceAtLeast(1L)
                        val sylProgress = ((pos - syl.startTimeMs).toFloat() / sylDur).coerceIn(0f, 1f)
                        if (pos >= syl.endTimeMs) {
                            revealChars = endChar.toFloat()
                        } else if (pos >= syl.startTimeMs) {
                            revealChars = startChar + (endChar - startChar) * sylProgress
                            break
                        } else {
                            break
                        }
                    }

                    // Clip per visual line based on revealed characters
                    val lineCount = layout.lineCount
                    for (i in 0 until lineCount) {
                        val lineStart = layout.getLineStart(i)
                        val lineEnd = layout.getLineEnd(i)
                        val lineLeft = layout.getLineLeft(i)
                        val lineRight = layout.getLineRight(i)
                        val lineTop = layout.getLineTop(i)
                        val lineBottom = layout.getLineBottom(i)
                        val lineWidth = lineRight - lineLeft

                        val lineCharsRevealed = (revealChars - lineStart).coerceIn(0f, (lineEnd - lineStart).toFloat())
                        val lineProgress = if (lineEnd > lineStart) lineCharsRevealed / (lineEnd - lineStart) else 0f

                        clipRect(left = lineLeft, top = lineTop, right = lineRight, bottom = lineBottom) {
                            val revealX = lineLeft + (lineWidth + featherPx) * lineProgress
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startX = (revealX - featherPx).coerceAtLeast(lineLeft),
                                    endX = revealX
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                    }
                }
        )
    }
}

// --- LINE SYNCED: top-to-bottom reveal ---

@Composable
private fun LineSyncedLine(
    line: SyncedLine,
    smoothPosition: MutableState<Long>,
    dimColor: Color,
    featherPx: Float,
    animDirection: String = "vertical"
) {
    val startMs = line.startTimeMs
    val durationMs = (line.endTimeMs - line.startTimeMs).coerceAtLeast(1L)
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        // Base — dimmed
        Text(
            text = line.text,
            color = dimColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            onTextLayout = { layoutResult = it }
        )
        // Overlay — bright, reveal based on direction setting
        Text(
            text = line.text,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 36.sp,
            modifier = Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val pos = smoothPosition.value
                    val progress = ((pos - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
                    if (animDirection == "horizontal") {
                        val layout = layoutResult ?: return@drawWithContent
                        val lineCount = layout.lineCount
                        // Total characters to distribute progress across visual lines
                        val totalChars = line.text.length.toFloat()
                        val revealChars = totalChars * progress

                        for (i in 0 until lineCount) {
                            val lineStart = layout.getLineStart(i)
                            val lineEnd = layout.getLineEnd(i)
                            val lineLeft = layout.getLineLeft(i)
                            val lineRight = layout.getLineRight(i)
                            val lineTop = layout.getLineTop(i)
                            val lineBottom = layout.getLineBottom(i)
                            val lineWidth = lineRight - lineLeft
                            val lineChars = (lineEnd - lineStart).toFloat()

                            val lineRevealed = (revealChars - lineStart).coerceIn(0f, lineChars)
                            val lineProgress = if (lineChars > 0) lineRevealed / lineChars else 0f

                            clipRect(left = lineLeft, top = lineTop, right = lineRight, bottom = lineBottom) {
                                val revealX = lineLeft + (lineWidth + featherPx) * lineProgress
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(Color.Black, Color.Transparent),
                                        startX = (revealX - featherPx).coerceAtLeast(lineLeft),
                                        endX = revealX
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                    } else {
                        val revealY = (size.height + featherPx) * progress
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Black, Color.Transparent),
                                startY = (revealY - featherPx).coerceAtLeast(0f),
                                endY = revealY
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
        )
    }
}

@Composable
private fun InterludeDots(accentColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "interlude")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val dotScale by infiniteTransition.animateFloat(
                initialValue = 0.6f, targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 200)
                ), label = "dot$i"
            )
            val dotAlpha by infiniteTransition.animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 200)
                ), label = "dotAlpha$i"
            )
            Box(
                Modifier.padding(horizontal = 6.dp).size(10.dp)
                    .graphicsLayer {
                        scaleX = dotScale
                        scaleY = dotScale
                        alpha = dotAlpha
                    }
                    .background(accentColor, CircleShape)
            )
        }
    }
}

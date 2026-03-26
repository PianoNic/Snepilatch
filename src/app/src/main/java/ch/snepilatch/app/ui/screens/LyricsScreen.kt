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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.SpotifyViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotify.api.lyrics.LyricsData
import kotify.api.lyrics.SyncedLine

@Composable
fun LyricsScreen(vm: SpotifyViewModel) {
    val playback by vm.playback.collectAsState()
    val track = playback.track
    val theme by vm.themeColors.collectAsState()
    val lyrics by vm.lyrics.collectAsState()
    val isLoading by vm.isLyricsLoading.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "lyricsPrimary")
    val lyricsAnimDirection by vm.lyricsAnimDirection.collectAsState()

    LaunchedEffect(track?.uri) {
        if (track != null) vm.fetchLyrics()
    }

    // Smooth position interpolation:
    // We remember the wall-clock time when the ViewModel last gave us a position,
    // then each frame we compute: lastKnownPos + (now - lastKnownWallClock)
    val smoothPosition = remember { mutableLongStateOf(playback.positionMs) }
    val lastKnownPos = remember { mutableLongStateOf(playback.positionMs) }
    val lastKnownWallClock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isPlaying = playback.isPlaying && !playback.isPaused

    // When ViewModel position changes, record the new anchor point
    LaunchedEffect(playback.positionMs) {
        lastKnownPos.longValue = playback.positionMs
        lastKnownWallClock.longValue = System.currentTimeMillis()
        smoothPosition.longValue = playback.positionMs
    }

    // Each frame, compute interpolated position from anchor
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val elapsed = System.currentTimeMillis() - lastKnownWallClock.longValue
            val interpolated = lastKnownPos.longValue + elapsed
            smoothPosition.longValue = interpolated.coerceAtMost(playback.durationMs.coerceAtLeast(1))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Blurred background
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
                // === LANDSCAPE: album art left, lyrics right ===
                Row(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: album art + track info
                    Column(
                        Modifier
                            .weight(0.4f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Album art
                        SpotifyImage(
                            url = track?.albumArt,
                            modifier = Modifier
                                .fillMaxHeight(0.55f)
                                .aspectRatio(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(10.dp))

                        // Track info
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

                        // Mini playback controls
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
                                    when (playback.repeatMode) { "track" -> Icons.Default.RepeatOne; else -> Icons.Default.Repeat },
                                    "Repeat",
                                    tint = if (playback.repeatMode != "off") animatedPrimary else SpotifyWhite.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.skipPrevious() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.SkipPrevious, "Previous", tint = SpotifyWhite, modifier = Modifier.size(20.dp))
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
                                    CircularProgressIndicator(color = SpotifyWhite, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(
                                        if (playback.isPaused || !playback.isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        "Play/Pause", tint = SpotifyWhite, modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable { vm.skipNext() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.SkipNext, "Next", tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                            }
                            Box(
                                Modifier.size(36.dp).background(buttonBg, CircleShape).clip(CircleShape).clickable {
                                    val id = track?.uri?.removePrefix("spotify:track:") ?: return@clickable
                                    if (isLiked) vm.unlikeSong(id) else vm.likeSong(id)
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    "Like",
                                    tint = if (isLiked) animatedPrimary else SpotifyWhite.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    // Right: lyrics
                    Column(
                        Modifier
                            .weight(0.6f)
                            .fillMaxHeight()
                    ) {
                        when {
                            isLoading -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = animatedPrimary, strokeWidth = 3.dp)
                                }
                            }
                            lyrics == null || lyrics?.lines.isNullOrEmpty() -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.MusicNote, null, tint = SpotifyLightGray.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                                        Spacer(Modifier.height(12.dp))
                                        Text("No lyrics available", color = SpotifyLightGray, fontSize = 16.sp)
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
                // === PORTRAIT (original) ===
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
                            Icon(Icons.Default.KeyboardArrowDown, "Close", tint = SpotifyWhite, modifier = Modifier.size(24.dp))
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
                                CircularProgressIndicator(color = animatedPrimary, strokeWidth = 3.dp)
                            }
                        }
                        lyrics == null || lyrics?.lines.isNullOrEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.MusicNote, null, tint = SpotifyLightGray.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text("No lyrics available", color = SpotifyLightGray, fontSize = 16.sp)
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
    val posMs by smoothPosition

    val activeIndex = if (isUnsynced) -1 else remember(posMs / 100) {
        lines.indexOfLast { posMs >= it.startTimeMs }.coerceAtLeast(0)
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
                    .graphicsLayer(scaleX = dotScale, scaleY = dotScale, alpha = dotAlpha)
                    .background(accentColor, CircleShape)
            )
        }
    }
}

package ch.snepilatch.app.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.shared.SpfyImage
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.FriendActivityViewModel
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotify.api.user.FriendActivity

/**
 * The web player's buddy feed (#843): one row per friend with their avatar, the track they are on
 * and how long ago; playing friends say "Now". Tapping a row plays that track in the friend's
 * context, so the queue continues the way theirs does.
 */
@Composable
fun FriendActivityScreen(vm: PlaybackViewModel, friendsVm: FriendActivityViewModel = viewModel()) {
    val feed by friendsVm.feed.collectAsState()
    val loading by friendsVm.loading.collectAsState()
    LaunchedEffect(Unit) { friendsVm.load() }

    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
            }
            Text(
                stringResource(R.string.friend_activity),
                color = SnepilatchWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { friendsVm.load() }, enabled = !loading) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), tint = SnepilatchWhite)
            }
        }

        when {
            loading && feed.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SnepilatchWhite)
            }
            feed.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.friend_activity_empty), color = SnepilatchLightGray, fontSize = 14.sp)
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)) {
                items(feed, key = { it.userUri }) { friend ->
                    FriendRow(friend) { vm.playTrack(friend.trackUri, friend.contextUri) }
                }
            }
        }
    }
}

/**
 * Laid out like the web's row: a playing friend has no time after the name and the track line in
 * the accent colour behind a spinning disc; a stopped one gets "• 56 min ago" after the name and a
 * grey track line.
 */
@Composable
private fun FriendRow(friend: FriendActivity, onClick: () -> Unit) {
    val theme by ThemeController.themeColors.collectAsState()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpfyImage(friend.userImageUrl, Modifier.size(44.dp), shape = CircleShape, icon = Icons.Rounded.Person)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    friend.userName,
                    color = SnepilatchWhite,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!friend.isPlaying) {
                    Text(" • " + relativeTime(friend.timestampMs), color = SnepilatchLightGray, fontSize = 12.sp, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (friend.isPlaying) {
                    SpinningDisc(theme.primary, Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "${friend.trackName} • ${friend.artistName}",
                    color = if (friend.isPlaying) theme.primary else SnepilatchLightGray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SpfyImage(friend.imageUrl, Modifier.size(44.dp))
    }
}

/** A vinyl record turning at a steady pace, the now-playing glyph. */
@Composable
private fun SpinningDisc(color: Color, modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "disc").animateFloat(
        initialValue = 0f, targetValue = 360f, label = "angle",
        animationSpec = infiniteRepeatable(tween(DISC_TURN_MS, easing = LinearEasing)),
    )
    Icon(painterResource(R.drawable.ic_vinyl_record), null, tint = color, modifier = modifier.rotate(angle))
}

private const val DISC_TURN_MS = 1800

/** "now" inside the first minute, then the platform's abbreviated relative time ("56 min ago"). */
@Composable
private fun relativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    return when {
        now - timestampMs < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.friend_activity_now)
        else -> DateUtils.getRelativeTimeSpanString(timestampMs, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
}

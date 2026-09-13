package ch.snepilatch.app.ui.shared

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import kotify.api.jam.JamMember
import kotify.api.jam.JamSession

/** The strip above the mini player while the account is in a jam: whose jam, and who is in it. */
@Composable
fun JamBanner(jam: JamSession, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme by ThemeController.themeColors.collectAsState()
    val accent by animateColorAsState(theme.primary, tween(800), label = "jamBanner")
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .shadow(8.dp, RoundedCornerShape(12.dp), ambientColor = accent, spotColor = accent)
            .clip(RoundedCornerShape(12.dp))
            .background(accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            jamTitle(jam),
            color = SnepilatchWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        JamAvatars(jam.members, size = 26.dp, ring = accent)
    }
}

/** "reazn's Jam", or the plain word when the host is not among the members yet. */
@Composable
fun jamTitle(jam: JamSession): String {
    val host = jam.host?.displayName?.ifBlank { null } ?: jam.host?.username?.ifBlank { null }
    return if (host != null) stringResource(R.string.jam_of, host) else stringResource(R.string.jam)
}

/**
 * Overlapping member avatars, at most three of them and a count for the rest. The ring is drawn in
 * [ring], the colour behind the avatars, so each one looks cut out over the one below it.
 */
@Composable
fun JamAvatars(members: List<JamMember>, size: Dp, ring: Color, modifier: Modifier = Modifier) {
    val shown = members.take(3)
    val more = members.size - shown.size
    val step = size * 0.7f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(size + step * (shown.size - 1).coerceAtLeast(0))) {
            shown.forEachIndexed { index, member ->
                SpfyImage(
                    url = member.imageUrl,
                    modifier = Modifier
                        .offset(x = step * index)
                        .zIndex(index.toFloat())
                        .size(size)
                        .border(2.dp, ring, CircleShape),
                    shape = CircleShape
                )
            }
        }
        if (more > 0) {
            Text("+$more", color = SnepilatchWhite, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

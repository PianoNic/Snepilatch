package ch.snepilatch.app.ui.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import kotify.api.jam.JamSession

/**
 * The top of the queue sheet while the account is in a jam, laid out like the official app: whose
 * jam it is, the members with an invite button in front, and the way out (leave, or end for the
 * host). Tapping the avatar stack folds the member list open.
 */
@Composable
fun JamHeader(jam: JamSession, busy: Boolean, onInvite: () -> Unit, onLeave: () -> Unit, onEnd: () -> Unit) {
    var showMembers by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                jamTitle(jam),
                color = SnepilatchWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = if (jam.isSessionOwner) onEnd else onLeave, enabled = !busy) {
                Text(stringResource(if (jam.isSessionOwner) R.string.jam_end else R.string.jam_leave))
            }
        }
        Spacer(Modifier.height(4.dp))
        // The stack says who is here on its own; tapping it folds the names open.
        JamAvatars(
            jam.members,
            size = 40.dp,
            ring = SnepilatchElevated,
            modifier = Modifier.clickable { showMembers = !showMembers },
            onInvite = onInvite,
        )
        AnimatedVisibility(showMembers) {
            Column(Modifier.padding(top = 8.dp)) {
                jam.members.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SpfyImage(url = m.imageUrl, modifier = Modifier.size(32.dp), shape = CircleShape)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            m.displayName.ifBlank { m.username },
                            color = SnepilatchWhite,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        val role = when {
                            m.id == jam.sessionOwnerId -> stringResource(R.string.jam_host)
                            m.isCurrentUser -> stringResource(R.string.jam_you)
                            m.isListening -> stringResource(R.string.jam_listening)
                            else -> null
                        }
                        if (role != null) Text(role, color = SnepilatchLightGray, fontSize = 12.sp)
                    }
                }
            }
        }
        if (!jam.isControlling) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.jam_controls_locked), color = SnepilatchLightGray, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = SnepilatchLightGray.copy(alpha = 0.15f))
        Spacer(Modifier.height(8.dp))
    }
}

package ch.snepilatch.app.ui.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.data.UiMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * The app's short messages, drawn just above the mini player and the nav bar ([bottom]) instead of
 * over them, and never in the way of a tap: the snackbar that once sat on the mini player blocked
 * it (#465, #572). A system toast cannot be moved on Android 11 and later, hence this.
 */
@Composable
fun AppToastHost(messages: Flow<UiMessage>, bottom: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(messages) {
        messages.collectLatest { message ->
            text = message.resolve(context)
            visible = true
            delay(SHOWN_MS)
            visible = false
        }
    }
    AnimatedVisibility(visible, modifier.padding(bottom = bottom + 8.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            text,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .background(Color(0xE6333333), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** As long as a short system toast. */
private const val SHOWN_MS = 2_000L

package ch.snepilatch.app.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ch.snepilatch.app.logic.shared.JamHolder

/** False while a jam's host has turned play, pause and skip off for guests; true outside a jam. */
@Composable
fun jamAllowsControls(): Boolean {
    val jam by JamHolder.session.collectAsState()
    return jam?.isControlling != false
}

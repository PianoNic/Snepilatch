package ch.snepilatch.app.ui.shared

import androidx.compose.ui.graphics.vector.ImageVector

/** One action row in an [OverflowMenu]. */
data class OverflowAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

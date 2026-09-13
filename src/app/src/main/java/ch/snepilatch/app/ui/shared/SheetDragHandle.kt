package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.ui.theme.SnepilatchLightGray

/** The 40 by 4 bar at the top of every bottom sheet in the app. */
@Composable
fun SheetDragHandle() {
    Box(
        Modifier
            .padding(vertical = 12.dp)
            .width(40.dp)
            .height(4.dp)
            .background(SnepilatchLightGray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
    )
}

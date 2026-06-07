package ch.snepilatch.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.ui.theme.SpotifyElevated

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TightAlertDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    containerColor: Color = SpotifyElevated,
    shape: Shape = AlertDialogDefaults.shape,
) {
    val maxBodyHeight = LocalConfiguration.current.screenHeightDp.dp * 0.6f
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = shape,
            color = containerColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                if (title != null) {
                    Box(Modifier.padding(bottom = 12.dp)) { title() }
                }
                if (text != null) {
                    Box(
                        Modifier
                            .heightIn(max = maxBodyHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp)
                    ) { text() }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(Modifier.width(4.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
}

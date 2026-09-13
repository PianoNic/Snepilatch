package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** [text] as a QR code drawn to fill [modifier]'s box, module by module, so it stays crisp at any size. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, foreground: Color = Color.Black, background: Color = Color.White) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        drawRect(background)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix.get(x, y)) drawRect(foreground, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}

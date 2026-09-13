package ch.snepilatch.app.ui.shared

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.os.Build
import androidx.compose.ui.graphics.Color

/**
 * Match the app's transparent, edge-to-edge nav bar inside a ModalBottomSheet. The sheet
 * has its own window that re-enables the nav-bar contrast scrim the Activity turned off,
 * which shows as a static white backdrop behind the system buttons. Call at the top of the
 * sheet's content.
 */
@Composable
fun SheetNavBarFix() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        // The scrim is all that's left from API 35 — the bar can't be tinted there — and this is the
        // only setter that still removes it. NOT deprecated, and NOT skippable on 35+: doing so is
        // what put the white backdrop back behind the buttons.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // Deprecated and ignored from API 35; below that it's the only way to clear the bar.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }
}

// --- Shimmer effect ---

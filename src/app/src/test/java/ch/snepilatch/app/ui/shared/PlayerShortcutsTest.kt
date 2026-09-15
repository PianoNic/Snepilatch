package ch.snepilatch.app.ui.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.OfflinePin
import ch.snepilatch.app.data.PlayerShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The per-shortcut title and glyph the sheet, the button and the picker all read from. */
class PlayerShortcutsTest {

    @Test
    fun everyShortcutHasItsOwnTitle() {
        val titles = PlayerShortcut.entries.map(::playerShortcutTitle)
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun likeAndDownloadGlyphsFollowTheTrackState() {
        assertNotEquals(playerShortcutIcon(PlayerShortcut.LIKE), playerShortcutIcon(PlayerShortcut.LIKE, isLiked = true))
        assertEquals(Icons.Rounded.Favorite, playerShortcutIcon(PlayerShortcut.LIKE, isLiked = true))
        assertEquals(Icons.Rounded.OfflinePin, playerShortcutIcon(PlayerShortcut.DOWNLOAD, isDownloaded = true))
    }

    @Test
    fun unknownIdFallsBackToLike() {
        assertEquals(PlayerShortcut.LIKE, PlayerShortcut.fromId("nope"))
        assertEquals(PlayerShortcut.SHARE, PlayerShortcut.fromId("share"))
    }
}

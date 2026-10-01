package ch.snepilatch.app.logic.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/** A deleted file is a definite "gone" (#954); any other failure to look stays inconclusive. */
class MissingFileTest {

    @Test
    fun theProvidersAnswerForADeletedFileIsMissing() {
        // As the phone logged it for one of the files deleted from Music/Snepilatch.
        val e = IllegalArgumentException(
            "Failed to determine if primary:Music/Snepilatch/Panda Eyes - Radiate.m4a is child of primary:Music/Snepilatch: " +
                "java.io.FileNotFoundException: Missing file for primary:Music/Snepilatch/Panda Eyes - Radiate.m4a at " +
                "/storage/emulated/0/Music/Snepilatch/Panda Eyes - Radiate.m4a"
        )

        assertTrue(DownloadFolder.isMissingFile(e))
        assertTrue(DownloadFolder.isMissingFile(FileNotFoundException("Missing file")))
        assertTrue(DownloadFolder.isMissingFile(IllegalStateException("query failed", FileNotFoundException("gone"))))
    }

    @Test
    fun aRefusalIsNotMissing() {
        assertFalse(DownloadFolder.isMissingFile(SecurityException("Permission Denial: reading com.android.externalstorage")))
        assertFalse(DownloadFolder.isMissingFile(IllegalStateException("the provider died")))
    }
}

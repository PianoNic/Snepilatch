package ch.snepilatch.app.download

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the WebM to Ogg remux over a real file so the output can be checked with an actual decoder,
 * which no unit test here can do. Skipped unless both variables are set:
 *
 *   REMUX_IN=/path/track.webm REMUX_OUT=/path/track.opus ./gradlew :app:testProdDebugUnitTest \
 *     --tests "ch.snepilatch.app.download.RemuxLab"
 *
 * Verify the result with `ffprobe` (codec, duration and channel count must match the input) and
 * mutagen (tags). Same idea as InfiniPlayLab: env-gated so CI never runs it.
 */
class RemuxLab {

    @Test
    fun remuxesARealWebmFile() {
        val input = System.getenv("REMUX_IN")
        val output = System.getenv("REMUX_OUT")
        assumeTrue("set REMUX_IN and REMUX_OUT to run", input != null && output != null)

        val source = File(input!!)
        val target = File(output!!)
        assumeTrue("missing $input", source.exists())

        // A cover on purpose: without one the comment packet is tiny and fits a single page, which
        // is exactly the case that passed while every real download was corrupt.
        val tags = TrackTags(
            title = "Remux Lab",
            artist = "Snepilatch",
            album = "Test",
            cover = TrackTags.Cover(ByteArray(80_000) { (it % 251).toByte() }, "image/jpeg"),
        )
        val ok = source.inputStream().use { from ->
            target.outputStream().use { to ->
                OpusRemuxer.remux(from, to, tags, serial = 1234)
            }
        }
        println("[RemuxLab] remuxed=$ok  in=${source.length()}B  out=${target.length()}B")
        check(ok) { "remux reported failure" }
        check(target.length() > 0) { "no output written" }
    }
}

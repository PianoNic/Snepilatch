package ch.snepilatch.app.ui.shared

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #806: inline markdown as styled text with tappable links, instead of stripped markers and
 * bare urls. These pin the shapes a GitHub release body actually contains.
 */
class InlineMarkdownTest {

    private fun links(text: String): List<Pair<String, String>> {
        val s = inlineMarkdown(text, Color.Unspecified)
        return s.getLinkAnnotations(0, s.length).map { r -> (r.item as LinkAnnotation.Url).url to s.text.substring(r.start, r.end) }
    }

    @Test
    fun aGeneratedChangelogLine_showsThePullNumberAsTheLink() {
        val line = "Offline player: route the transport by @PianoNic in https://github.com/PianoNic/Snepilatch/pull/795"
        val s = inlineMarkdown(line, Color.Unspecified)
        assertEquals("Offline player: route the transport by @PianoNic in #795", s.text)
        assertEquals(listOf("https://github.com/PianoNic/Snepilatch/pull/795" to "#795"), links(line))
    }

    @Test
    fun aWrittenLink_keepsItsLabelAndItsUrl() {
        val line = "See [the compare](https://github.com/a/b/compare/v1...v2) for details"
        assertEquals("See the compare for details", inlineMarkdown(line, Color.Unspecified).text)
        assertEquals(listOf("https://github.com/a/b/compare/v1...v2" to "the compare"), links(line))
    }

    @Test
    fun aBareUrlThatIsNotAReference_losesOnlyItsScheme() {
        val line = "Full changelog: https://github.com/a/b/compare/v1...v2"
        assertEquals("Full changelog: github.com/a/b/compare/v1...v2", inlineMarkdown(line, Color.Unspecified).text)
    }

    @Test
    fun theBullet_isUnstyledInFront() {
        val s = inlineMarkdown("**Bold** start", Color.Unspecified, prefix = "  •  ")
        assertEquals("  •  Bold start", s.text)
        assertEquals(5, s.spanStyles.single().start)
    }

    @Test
    fun issueReferences_readLikePullReferences() {
        assertEquals("#12", linkLabel("https://github.com/x/y/issues/12"))
        assertEquals("#12", linkLabel("https://github.com/x/y/pull/12/"))
        assertEquals("lrclib.net/api", linkLabel("https://lrclib.net/api"))
    }
}

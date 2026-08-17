package ch.snepilatch.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the inline stripping behind [MarkdownText] (issue #598).
 *
 * The update dialog printed release bodies into a plain `Text`, so a changelog showed
 * its own syntax. These pin the cases a GitHub release body actually contains.
 */
class CleanMarkdownTest {

    @Test
    fun stripsBold() {
        assertEquals("Full Changelog", "**Full Changelog**".cleanMarkdown())
    }

    /**
     * The ordering trap: `*(.+?)*` matches the first two asterisks of `**bold**`, so
     * running italic first would leave `*bold*` behind rather than `bold`.
     */
    @Test
    fun boldIsStrippedBeforeItalic() {
        assertEquals("bold", "**bold**".cleanMarkdown())
        assertEquals("italic", "*italic*".cleanMarkdown())
        assertEquals("both bold and italic", "**both bold** and *italic*".cleanMarkdown())
    }

    @Test
    fun linksKeepTheirLabel() {
        assertEquals(
            "See v3.0.0...v3.1.0 for details",
            "See [v3.0.0...v3.1.0](https://github.com/a/b/compare/x) for details".cleanMarkdown()
        )
    }

    @Test
    fun stripsInlineCode() {
        assertEquals("set AUDIT_STAFF to true", "set `AUDIT_STAFF` to true".cleanMarkdown())
    }

    /** A real release-note line, which is the shape that matters. */
    @Test
    fun handlesARealChangelogLine() {
        assertEquals(
            "Join a jam from a shared link @PianoNic (#549)",
            "* Join a jam from a shared link @PianoNic ([#549](https://github.com/x/pull/549))"
                .removePrefix("* ")
                .cleanMarkdown()
        )
    }

    @Test
    fun leavesPlainTextAlone() {
        assertEquals("nothing to strip here", "nothing to strip here".cleanMarkdown())
    }
}

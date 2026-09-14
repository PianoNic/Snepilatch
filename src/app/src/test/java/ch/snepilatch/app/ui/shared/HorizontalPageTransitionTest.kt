package ch.snepilatch.app.ui.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalPageTransitionTest {

    private val home = Page("home")
    private val account = Page("account")
    private val interfacePage = Page("interface", account)
    private val nestedPage = Page("nested", interfacePage)
    private val siblingPage = Page("sibling", account)
    private val standalone = Page("standalone")
    private val rules = HorizontalPageTransitionRules(
        pages = mapOf(standalone to HorizontalPageTransitionDirection.Left),
        subtrees = mapOf(account to HorizontalPageTransitionDirection.Right),
        parentOf = Page::parent,
    )

    @Test fun subtreeDirectionFollowsAncestryAndReverses() {
        assertNull(resolveHorizontalPageDirection(home, account, rules))
        assertEquals(
            HorizontalPageTransitionDirection.Right,
            resolveHorizontalPageDirection(account, nestedPage, rules),
        )
        assertEquals(
            HorizontalPageTransitionDirection.Left,
            resolveHorizontalPageDirection(nestedPage, account, rules),
        )
        assertEquals(
            HorizontalPageTransitionDirection.Right,
            resolveHorizontalPageDirection(interfacePage, nestedPage, rules),
        )
        assertEquals(
            HorizontalPageTransitionDirection.Left,
            resolveHorizontalPageDirection(nestedPage, interfacePage, rules),
        )
        assertNull(resolveHorizontalPageDirection(interfacePage, siblingPage, rules))
    }

    @Test fun exactPageRulesStillWork() {
        assertEquals(
            HorizontalPageTransitionDirection.Left,
            resolveHorizontalPageDirection(home, standalone, rules),
        )
        assertEquals(
            HorizontalPageTransitionDirection.Right,
            resolveHorizontalPageDirection(standalone, home, rules),
        )
    }

    @Test fun twoExactPageRulesReverseTheInitialDirection() {
        val exactRules = rules.copy(
            pages = mapOf(
                account to HorizontalPageTransitionDirection.Right,
                interfacePage to HorizontalPageTransitionDirection.Right,
            ),
        )

        assertEquals(
            HorizontalPageTransitionDirection.Left,
            resolveHorizontalPageDirection(account, interfacePage, exactRules),
        )
    }

    @Test fun cyclicParentMetadataStopsWalking() {
        val first = Page("first")
        val second = Page("second")
        val parents = mapOf(first to second, second to first)
        val cyclicRules = HorizontalPageTransitionRules<Page>(parentOf = parents::get)

        assertNull(resolveHorizontalPageDirection(first, second, cyclicRules))
        assertFalse(shouldRetainHorizontalPageState(first, cyclicRules))
    }

    @Test fun retentionIncludesOnlyConfiguredPagesAndSubtrees() {
        assertTrue(shouldRetainHorizontalPageState(account, rules))
        assertTrue(shouldRetainHorizontalPageState(interfacePage, rules))
        assertTrue(shouldRetainHorizontalPageState(nestedPage, rules))
        assertTrue(shouldRetainHorizontalPageState(standalone, rules))
        assertFalse(shouldRetainHorizontalPageState(home, rules))
    }

    private data class Page(val name: String, val parent: Page? = null)
}

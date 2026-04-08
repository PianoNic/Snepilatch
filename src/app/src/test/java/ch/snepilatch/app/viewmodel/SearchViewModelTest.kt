package ch.snepilatch.app.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SearchViewModel]. Doesn't need a mocked session because the
 * "no session" branch (SessionHolder.session == null) is the same path
 * tests exercise — they verify the local state transitions, not the
 * network response.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: SearchViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = SearchViewModel()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun emptyQueryClearsEverything() {
        vm.updateQuery("")
        assertEquals("", vm.query.value)
        assertTrue(vm.suggestions.value.isEmpty())
        assertEquals("", vm.submittedQuery.value)
        assertEquals(null, vm.results.value)
    }

    @Test fun shortQueryDoesNotFireSuggestions() {
        vm.updateQuery("a") // below MIN_SUGGEST_LENGTH
        assertEquals("a", vm.query.value)
        assertTrue(vm.suggestions.value.isEmpty())
        assertEquals(false, vm.isSuggesting.value)
    }

    @Test fun longerQueryStoresQuery() {
        vm.updateQuery("daft punk")
        assertEquals("daft punk", vm.query.value)
        // We're not in submitted state yet
        assertEquals("", vm.submittedQuery.value)
    }

    @Test fun submitQueryEntersSubmittedState() {
        vm.submitQuery("daft punk")
        assertEquals("daft punk", vm.query.value)
        assertEquals("daft punk", vm.submittedQuery.value)
        // Suggestions cleared on submit
        assertTrue(vm.suggestions.value.isEmpty())
    }

    @Test fun blankSubmitIsNoOp() {
        vm.submitQuery("   ")
        assertEquals("", vm.submittedQuery.value)
    }

    @Test fun clearSubmittedReturnsToSuggesting() {
        vm.submitQuery("daft punk")
        vm.clearSubmitted()
        assertEquals("", vm.submittedQuery.value)
        assertEquals(null, vm.results.value)
        // Query is still set so the user can keep editing
        assertEquals("daft punk", vm.query.value)
    }

    @Test fun clearingAfterTypingResetsAllState() {
        vm.updateQuery("daft")
        vm.updateQuery("")
        assertEquals("", vm.query.value)
        assertTrue(vm.suggestions.value.isEmpty())
        assertEquals("", vm.submittedQuery.value)
        assertEquals(null, vm.results.value)
    }
}

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

    @Test fun emptyQueryClearsResults() {
        vm.updateQuery("")
        assertEquals("", vm.query.value)
        assertTrue(vm.results.value.isEmpty())
    }

    @Test fun shortQueryDoesNotTriggerSearch() {
        vm.updateQuery("a") // below MIN_QUERY_LENGTH
        assertEquals("a", vm.query.value)
        assertTrue(vm.results.value.isEmpty())
        // isSearching should never have flipped on for a query this short
        assertEquals(false, vm.isSearching.value)
    }

    @Test fun longQueryStoresQuery() {
        vm.updateQuery("daft punk")
        assertEquals("daft punk", vm.query.value)
        // No real session is bound — the launch returns immediately, but the
        // query state must still be updated synchronously so the TextField
        // reflects the user's input.
    }

    @Test fun clearingAfterTypingResetsResults() {
        vm.updateQuery("daft")
        vm.updateQuery("")
        assertEquals("", vm.query.value)
        assertTrue(vm.results.value.isEmpty())
    }
}

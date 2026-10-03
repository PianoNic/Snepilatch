package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.data.TrackInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotify.api.playerstatus.PlayerStateData
import kotify.api.playerstatus.QueueEntry
import kotify.api.playerstatus.QueueTrack
import kotify.api.song.DecoratedTrack
import kotify.api.song.Song
import kotify.api.playerstatus.QueueView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueEditConcurrencyTest {
    private val rig = PlaybackTestRig()

    @Before
    fun setup() {
        rig.install()
        rig.seedStreaming()
        coEvery { rig.player.skipToTrack(any()) } returns true
    }

    @After
    fun teardown() {
        rig.vm.viewModelScope.cancel()
        rig.uninstall()
    }

    private fun queueEntry(uid: String, queued: Boolean = false) = QueueEntry(
        QueueTrack(
            uri = "spotify:track:$uid", uid = uid, name = uid, artistName = "artist",
            artistUri = null, albumName = null, albumUri = null, durationMs = 100,
            imageUrl = null, metadata = mapOf("iteration" to "0", "is_queued" to "$queued"),
        ),
        serverIndex = 0,
    )

    private fun queueState(vararg entries: QueueEntry) = PlayerStateData(
        is_playing = true, is_paused = false, track = null, position_as_of_timestamp = 0,
        play_origin = null, next_tracks = entries.map { it.track },
    )

    @Test
    fun confirmationReadStartedBeforeSkipMustNotRestoreConsumedHead() = runBlocking {
        val a = queueEntry("a")
        val b = queueEntry("b")
        val c = queueEntry("c")
        val confirmation = CompletableDeferred<PlayerStateData>()
        coEvery { rig.player.removeFromQueue(any()) } returns true
        coEvery { rig.player.getState() } coAnswers { confirmation.await() }
        rig.vm.applyQueue(QueueView("old", emptyList(), listOf(a, b, c)), null)
        rig.vm.removeFromQueue(rig.vm.queue.value.first())
        rig.testScope.advanceTimeBy(1_000)
        rig.testScope.runCurrent()
        coVerify(exactly = 1) { rig.player.getState() }
        rig.vm.skipNext()
        rig.vm.awaitCommandForTest()
        assertEquals(listOf("c:::0"), rig.vm.queue.value.map { it.qid })
        confirmation.complete(queueState(b, c))
        rig.testScope.runCurrent()
        assertEquals(listOf("c:::0"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun failedConfirmationMustNotDiscardRemovalsAddedWhileReading() = runBlocking {
        val a = queueEntry("a")
        val b = queueEntry("b")
        val confirmation = CompletableDeferred<PlayerStateData>()
        coEvery { rig.player.removeFromQueue(any()) } returns true
        coEvery { rig.player.getState() } coAnswers { confirmation.await() }
        rig.vm.applyQueue(QueueView("old", emptyList(), listOf(a, b)), null)
        rig.vm.removeFromQueue(rig.vm.queue.value.first())
        rig.testScope.advanceTimeBy(1_000)
        rig.testScope.runCurrent()
        coVerify(exactly = 1) { rig.player.getState() }
        rig.vm.removeFromQueue(rig.vm.queue.value.first())
        confirmation.completeExceptionally(IllegalStateException("network unavailable"))
        rig.testScope.advanceTimeBy(5_000)
        rig.testScope.runCurrent()
        coVerify(exactly = 1) { rig.player.removeFromQueue("b:::0") }
    }

    @Test
    fun supersededSkipFinishesRollbackBeforeTheNextCommandPublishes() = runBlocking {
        val b = queueEntry("b")
        val c = queueEntry("c")
        val d = queueEntry("d")
        val old = QueueView("old", emptyList(), listOf(b, c, d))
        val firstStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        // b is the machine's own next, so the first press advances locally; the second must jump.
        coEvery { rig.player.localNext(any()) } coAnswers {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    finishCleanup.await()
                    // This is the rollback callback that the real Kotify client emits on cancel.
                    rig.vm.applyQueue(old, null)
                }
            }
        }
        coEvery { rig.player.skipToTrack("c:::0") } coAnswers {
            rig.vm.applyQueue(QueueView("after-c", emptyList(), listOf(d)), null)
            true
        }
        rig.vm.applyQueue(old, null)
        rig.vm.skipNext()
        withTimeout(2_000) { firstStarted.await() }
        rig.vm.skipNext()
        withTimeout(2_000) { cleanupStarted.await() }
        coVerify(exactly = 0) { rig.player.skipToTrack("c:::0") }
        assertEquals(listOf("d:::0"), rig.vm.queue.value.map { it.qid })
        finishCleanup.complete(Unit)
        withTimeout(2_000) { rig.vm.awaitCommandForTest() }
        coVerify(exactly = 1) { rig.player.skipToTrack("c:::0") }
        coVerify(exactly = 0) { rig.player.skipToTrack("b:::0") }
        assertEquals(listOf("d:::0"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun nextAdvancesLocallyWhenTheVisibleHeadIsTheMachinesNext() = runBlocking {
        coEvery { rig.player.localNext(any()) } returns true
        rig.vm.applyQueue(QueueView("now", emptyList(), listOf(queueEntry("a"), queueEntry("b"))), null)

        rig.vm.skipNext()
        rig.vm.awaitCommandForTest()

        coVerify(exactly = 1) { rig.player.localNext(kotify.api.playerstatus.AdvanceReason.USER_SKIP) }
        coVerify(exactly = 0) { rig.player.skipToTrack(any()) }
        coVerify(exactly = 0) { rig.player.skipNext() }
        assertEquals(listOf("b:::0"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun nextOnTheRemoteDeviceUsesPlainSkipWhenTheHeadIsUnchanged() = runBlocking {
        coEvery { rig.player.skipNext() } returns true
        rig.vm.setForeignDeviceActiveForTest(true)
        rig.vm.applyQueue(QueueView("now", emptyList(), listOf(queueEntry("a"), queueEntry("b"))), null)

        rig.vm.skipNext()
        rig.vm.awaitCommandForTest()

        coVerify(exactly = 1) { rig.player.skipNext() }
        coVerify(exactly = 0) { rig.player.skipToTrack(any()) }
    }

    @Test
    fun nextJumpsToARowMovedAheadOfTheMachinesNext() = runBlocking {
        val a = queueEntry("a")
        val b = queueEntry("b")
        val c = queueEntry("c")
        rig.vm.applyQueue(QueueView("now", emptyList(), listOf(a, b, c)), null)
        // A drag the server has not echoed yet.
        rig.vm._queue.value = rig.vm.queue.value.let { listOf(it[2], it[0], it[1]) }

        rig.vm.skipNext()
        rig.vm.awaitCommandForTest()

        coVerify(exactly = 1) { rig.player.skipToTrack("c:::0") }
        coVerify(exactly = 0) { rig.player.localNext(any()) }
    }

    @Test
    fun moveResolvesItsAnchorAfterThePrecedingRemoval() = runBlocking {
        val entries = listOf("a", "b", "c", "d").mapIndexed { index, uid ->
            queueEntry(uid).copy(serverIndex = index)
        }
        val removal = CompletableDeferred<Boolean>()
        val serverRows = entries.drop(1).toMutableList()
        val moveFinished = CompletableDeferred<Unit>()
        coEvery { rig.player.removeFromQueue(any()) } coAnswers { removal.await() }
        coEvery { rig.player.getState() } coAnswers { queueState(*serverRows.toTypedArray()) }
        coEvery { rig.player.moveInQueue(any(), any()) } coAnswers {
            val from = serverRows.indexOfFirst { it.track.qid == firstArg<String>() }
            serverRows.add(secondArg(), serverRows.removeAt(from))
            moveFinished.complete(Unit)
            true
        }
        rig.vm.applyQueue(QueueView("old", emptyList(), entries), null)
        rig.vm.removeFromQueue(rig.vm.queue.value.first())
        rig.vm.moveQueueEntry(rig.vm.queue.value.last(), 0)
        assertEquals(listOf("d:::0", "b:::0", "c:::0"), rig.vm.queue.value.map { it.qid })
        removal.complete(true)
        rig.testScope.advanceTimeBy(1_000)
        rig.testScope.runCurrent()
        withTimeout(2_000) { moveFinished.await() }
        assertEquals(listOf("d:::0", "b:::0", "c:::0"), serverRows.map { it.track.qid })
        coVerify(exactly = 1) { rig.player.moveInQueue("d:::0", 0) }
    }
    private fun track(uid: String, iteration: Int = 0) = TrackInfo(
        uri = "spotify:track:$uid", name = uid, artist = "artist", albumArt = null,
        uid = uid, qid = "$uid:::$iteration",
    )

    @Test
    fun nextUsesVisibleHeadWhileRemovalIsStillWaiting() = runBlocking {
        val removed = track("a")
        val next = track("b")
        val removal = CompletableDeferred<Boolean>()
        coEvery { rig.player.removeFromQueue(any()) } coAnswers { removal.await() }
        rig.vm._queue.value = listOf(removed, next)
        rig.vm._queuedCount.value = 2
        rig.vm.nextTrackPreview.value = removed

        rig.vm.removeFromQueue(removed)
        rig.vm.skipNext()
        rig.vm.awaitCommandForTest()

        coVerify(exactly = 1) { rig.player.skipToTrack("b:::0") }
        coVerify(exactly = 0) { rig.player.localNext(any()) }
        coVerify(exactly = 0) { rig.player.skipNext() }
        assertEquals(next, rig.vm.playback.value.track)
        assertEquals(0, rig.vm.queuedCount.value)
        assertEquals(emptyList<TrackInfo>(), rig.vm.queue.value)
    }

    @Test
    fun nextTargetsTheExactRepeatIterationOnTheRemoteDevice() = runBlocking {
        rig.vm.setForeignDeviceActiveForTest(true)
        rig.vm._queue.value = listOf(track("duplicate", 2), track("duplicate", 3))

        rig.vm.skipNext()
        rig.vm.awaitCommandForTest()

        coVerify(exactly = 1) { rig.player.skipToTrack("duplicate:::2") }
        coVerify(exactly = 0) { rig.player.skipNext() }
        assertEquals(listOf("duplicate:::3"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun anEmptyVisibleQueueDoesNotSkipIntoPendingRemovals() = runBlocking {
        val removed = track("a")
        val removal = CompletableDeferred<Boolean>()
        coEvery { rig.player.removeFromQueue(any()) } coAnswers { removal.await() }
        rig.vm._queue.value = listOf(removed)
        rig.vm.nextTrackPreview.value = removed
        val playing = rig.vm.playback.value.track

        rig.vm.removeFromQueue(removed)
        rig.vm.skipNext()
        rig.vm.awaitCommandForTest()

        coVerify(exactly = 0) { rig.player.skipToTrack(any()) }
        coVerify(exactly = 0) { rig.player.skipNext() }
        coVerify(exactly = 0) { rig.player.localNext(any()) }
        assertEquals(playing, rig.vm.playback.value.track)
    }

    @Test
    fun rejectedTargetDoesNotFallBackToAnUnspecifiedNextTrack() = runBlocking {
        rig.vm._queue.value = listOf(track("a"))
        coEvery { rig.player.skipToTrack(any()) } returns false

        rig.vm.skipNext()
        rig.vm.awaitCommandForTest()

        coVerify(exactly = 1) { rig.player.skipToTrack("a:::0") }
        coVerify(exactly = 0) { rig.player.skipNext() }
        coVerify(exactly = 0) { rig.player.localNext(any()) }
    }

    @Test
    fun rapidRemovalsStayHiddenDuringPushesAndWaitOneSecondBetweenWrites() = runBlocking {
        val a = queueEntry("a", queued = true)
        val b = queueEntry("b", queued = true)
        val c = queueEntry("c")
        val stale = QueueView("old", listOf(a, b), listOf(c))
        val firstWrite = CompletableDeferred<Boolean>()
        coEvery { rig.player.removeFromQueue("a:::0") } coAnswers { firstWrite.await() }
        coEvery { rig.player.removeFromQueue("b:::0") } returns true
        coEvery { rig.player.getState() } returns queueState(c)
        rig.vm.applyQueue(stale, null)

        rig.vm.removeFromQueue(rig.vm.queue.value[0])
        rig.vm.removeFromQueue(rig.vm.queue.value[0])
        rig.vm.applyQueue(stale, null)
        assertEquals(listOf("c:::0"), rig.vm.queue.value.map { it.qid })
        assertEquals(0, rig.vm.queuedCount.value)
        coVerify(exactly = 0) { rig.player.removeFromQueue("b:::0") }

        firstWrite.complete(true)
        rig.testScope.advanceTimeBy(999)
        coVerify(exactly = 0) { rig.player.removeFromQueue("b:::0") }
        rig.testScope.advanceTimeBy(1)
        rig.testScope.runCurrent()
        coVerify(exactly = 1) { rig.player.removeFromQueue("b:::0") }
        rig.vm.applyQueue(stale, null)
        assertEquals(listOf("c:::0"), rig.vm.queue.value.map { it.qid })

        rig.testScope.advanceTimeBy(1_000)
        rig.testScope.runCurrent()
        coVerify(exactly = 1) { rig.player.getState() }
        assertEquals(listOf("c:::0"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun restoresAnUnconfirmedRemovalAfterBatchReconciliation() = runBlocking {
        val a = queueEntry("a", queued = true)
        coEvery { rig.player.removeFromQueue(any()) } returns true
        coEvery { rig.player.getState() } returns queueState(a)
        rig.vm.applyQueue(QueueView("old", listOf(a), emptyList()), null)
        rig.vm.removeFromQueue(rig.vm.queue.value.single())
        assertEquals(emptyList<TrackInfo>(), rig.vm.queue.value)

        rig.testScope.advanceTimeBy(1_000)
        rig.testScope.runCurrent()
        assertEquals(listOf("a:::0"), rig.vm.queue.value.map { it.qid })
        assertEquals(1, rig.vm.queuedCount.value)
    }

    @Test
    fun lateMetadataCannotRestoreRemovedRowsOrReplaceANewerQueue() = runBlocking {
        val a = queueEntry("a")
        val b = queueEntry("b")
        val songApi = mockk<Song>()
        val metadata = CompletableDeferred<List<DecoratedTrack>>()
        coEvery { songApi.decorateTracks(any()) } coAnswers { metadata.await() }
        val decoration = mockk<DecoratedTrack>(relaxed = true)
        every { decoration.uri } returns a.track.uri
        launch(rig.testDispatcher) { rig.vm.applyQueue(QueueView("old", emptyList(), listOf(a)), songApi) }
        rig.testScope.runCurrent()
        rig.vm.removeFromQueue(rig.vm.queue.value.single())
        rig.vm.applyQueue(QueueView("new", emptyList(), listOf(a, b)), null)

        metadata.complete(listOf(decoration))
        rig.testScope.runCurrent()
        assertEquals(listOf("b:::0"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun failedWriteDoesNotAbortLaterRemovals() = runBlocking {
        val a = queueEntry("a")
        val b = queueEntry("b")
        coEvery { rig.player.removeFromQueue("a:::0") } throws IllegalStateException("offline")
        coEvery { rig.player.removeFromQueue("b:::0") } returns true
        coEvery { rig.player.getState() } returns queueState(a)
        rig.vm.applyQueue(QueueView("old", emptyList(), listOf(a, b)), null)
        rig.vm.removeFromQueue(rig.vm.queue.value.first())
        rig.vm.removeFromQueue(rig.vm.queue.value.first())
        rig.testScope.advanceTimeBy(2_000)
        rig.testScope.runCurrent()
        coVerify(exactly = 1) { rig.player.removeFromQueue("b:::0") }
        assertEquals(listOf("a:::0"), rig.vm.queue.value.map { it.qid })
    }

    @Test
    fun rateLimitCooldownKeepsRowsHiddenAndBlocksFollowingRemovals() = runBlocking {
        val a = queueEntry("a")
        val b = queueEntry("b")
        val stale = QueueView("old", emptyList(), listOf(a, b))
        val gate = kotify.http.RateLimitGate
        mockkObject(gate)
        try {
            every { gate.waitMs } returns 30_000L
            coEvery { gate.awaitClearance() } coAnswers { delay(30_000) }
            var attempts = 0
            coEvery { rig.player.removeFromQueue("a:::0") } coAnswers {
                if (attempts++ == 0) throw kotify.http.HttpException(429, "rate limited")
                true
            }
            coEvery { rig.player.removeFromQueue("b:::0") } returns true
            coEvery { rig.player.getState() } returns queueState()
            rig.vm.applyQueue(stale, null)
            rig.vm.removeFromQueue(rig.vm.queue.value.first())
            rig.vm.removeFromQueue(rig.vm.queue.value.first())
            rig.testScope.advanceTimeBy(29_999)
            rig.vm.applyQueue(stale, null)
            assertEquals(emptyList<TrackInfo>(), rig.vm.queue.value)
            coVerify(exactly = 1) { rig.player.removeFromQueue("a:::0") }
            coVerify(exactly = 0) { rig.player.removeFromQueue("b:::0") }
            rig.testScope.advanceTimeBy(1)
            rig.testScope.runCurrent()
            coVerify(exactly = 2) { rig.player.removeFromQueue("a:::0") }
            rig.testScope.advanceTimeBy(2_000)
            rig.testScope.runCurrent()
            coVerify(exactly = 1) { rig.player.removeFromQueue("b:::0") }
            assertEquals(emptyList<TrackInfo>(), rig.vm.queue.value)
        } finally {
            unmockkObject(gate)
        }
    }
}

package ch.snepilatch.app.logic.relay

import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** [RelayHostPlayer] over the app's playback, so hosting reads and drives the same player the screens do. */
class ViewModelRelayPlayer(private val vm: PlaybackViewModel) : RelayHostPlayer {

    override val state: Flow<RelayHostPlayer.State> = combine(
        vm.currentTrack,
        vm.isPausedFlow,
        vm.isShufflingFlow,
        vm.repeatModeFlow,
        vm.queue,
    ) { _, _, _, _, _ -> current() }

    override val positionMs: StateFlow<Long> = vm.positionFlow

    override fun current(): RelayHostPlayer.State {
        val track = vm.currentTrack.value
        return RelayHostPlayer.State(
            trackUri = track?.uri,
            contextUri = vm.playingContext.value?.uri,
            durationMs = vm.durationFlow.value,
            paused = vm.isPausedFlow.value || !vm.isPlayingFlow.value,
            shuffle = vm.isShufflingFlow.value,
            repeat = vm.repeatModeFlow.value,
            queue = vm.queue.value.map { RelayQueueEntry(it.uri, it.uid) },
        )
    }

    override suspend fun pause() = onMain { vm.togglePlayPause() }

    override suspend fun resume() = onMain { vm.togglePlayPause() }

    override suspend fun next() = onMain { vm.skipNext() }

    override suspend fun previous() = onMain { vm.skipPrevious() }

    override suspend fun seek(positionMs: Long) = onMain { vm.seekTo(positionMs) }

    override suspend fun play(uri: String, contextUri: String?) = onMain { vm.playTrack(uri, contextUri) }

    override suspend fun enqueue(uri: String) = onMain { vm.addToQueue(uri) }

    private suspend fun onMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}

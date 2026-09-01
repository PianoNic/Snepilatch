package ch.snepilatch.app.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the app has been asked to download, as opposed to [Downloads], which indexes what is already
 * on disk. Each entry carries its own progress and outcome: a single slot meant a batch, a tapped
 * row and an auto-save fought over one card, and whichever finished first blanked it while the
 * others were still running.
 */
object DownloadQueue {

    /** One thing the user asked for: an album, a playlist, a tapped track, or an auto-save. */
    data class QueueEntry(
        val id: Int,
        val name: String,
        val type: String,
        val imageUrl: String?,
        val total: Int,
        val done: Int = 1,
        val trackPercent: Int = 0,
        val state: State = State.Running,
        val paused: Boolean = false,
    ) {
        enum class State { Running, Done, Failed, Cancelled }

        val running: Boolean get() = state == State.Running
    }

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())

    /** Everything asked for, oldest first. */
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * How far along each track being fetched is, by uri. A queue entry knows the album or playlist
     * the user asked for, so a track row cannot tell whether an entry's percentage is its own.
     */
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    fun reportTrack(uri: String, percent: Int) = _progress.update { it + (uri to percent) }

    fun clearTrack(uri: String) = _progress.update { it - uri }

    private val nextJobId = java.util.concurrent.atomic.AtomicInteger(0)

    private val gates = java.util.concurrent.ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    /** [QueueEntry.type] for a track kept from what was played rather than fetched. */
    const val TYPE_REENCODE = "reencode"

    /** How many finished entries to keep, so an auto-save on every track cannot grow without bound. */
    private const val KEEP_FINISHED = 10

    /** @return the id to pass back to [updateJob] and [finishJob]. */
    fun enqueue(name: String, type: String, imageUrl: String?, total: Int): Int {
        val id = nextJobId.incrementAndGet()
        _queue.update { list ->
            val finished = list.filter { !it.running }
            val trimmed = if (finished.size >= KEEP_FINISHED) {
                list - finished.take(finished.size - KEEP_FINISHED + 1).toSet()
            } else {
                list
            }
            trimmed + QueueEntry(id, name, type, imageUrl, total)
        }
        return id
    }

    fun updateJob(id: Int, done: Int, trackPercent: Int) = _queue.update { list ->
        list.map { if (it.id == id) it.copy(done = done, trackPercent = trackPercent) else it }
    }

    fun finishJob(id: Int, state: QueueEntry.State) {
        // Release the gate first: an entry cancelled while paused is otherwise left awaiting a resume
        // that will never come, and its coroutine never unwinds.
        gates.remove(id)?.complete(Unit)
        _queue.update { list ->
            list.map { if (it.id == id) it.copy(state = state, paused = false) else it }
        }
    }

    /**
     * Holds an entry between tracks while it is paused. Suspends on a gate rather than polling a
     * flag, so a paused download costs nothing and resumes the moment it is let go.
     */
    suspend fun awaitResume(id: Int) {
        gates[id]?.await()
    }

    fun pause(id: Int) {
        gates.putIfAbsent(id, CompletableDeferred())
        _queue.update { list -> list.map { if (it.id == id) it.copy(paused = true) else it } }
    }

    fun resume(id: Int) {
        gates.remove(id)?.complete(Unit)
        _queue.update { list -> list.map { if (it.id == id) it.copy(paused = false) else it } }
    }

    /** Drop everything that is no longer running; anything in flight stays. */
    fun clearFinished() = _queue.update { list -> list.filter { it.running } }
}

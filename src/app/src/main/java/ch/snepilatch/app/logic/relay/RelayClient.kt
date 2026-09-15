package ch.snepilatch.app.logic.relay

import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One live connection to Snepirelay. [start] opens it and keeps it open: the hello goes out as soon
 * as the socket is up, a drop is retried with a growing pause, and every retry says hello again with
 * the same identity so the relay puts the device back where it was. [events] carries everything the
 * relay sends; [request] sends a message and waits for its `ok` or `error`. A ping every
 * [PING_INTERVAL_MS] keeps [serverNow] on the relay's clock.
 */
class RelayClient(
    private val url: String,
    private val hello: () -> JsonObject,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = defaultHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Connected(val memberId: String) : State
        data class Refused(val code: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<RelayEvent> = _events

    @Volatile private var socket: WebSocket? = null

    @Volatile private var wanted = false

    @Volatile var clockOffsetMs: Long = 0
        private set

    private val pending = ConcurrentHashMap<String, CompletableDeferred<RelayEvent>>()
    private val nextRid = AtomicLong()
    private var attempts = 0
    private var retry: Job? = null
    private var pinger: Job? = null

    fun serverNow(): Long = now() + clockOffsetMs

    fun start() {
        if (wanted) return
        wanted = true
        attempts = 0
        open()
    }

    fun stop() {
        wanted = false
        retry?.cancel()
        pinger?.cancel()
        socket?.close(NORMAL_CLOSURE, "bye")
        socket = null
        failPending()
        _state.value = State.Idle
    }

    /** False when the socket is not open; nothing is queued for later. */
    fun send(message: JsonObject): Boolean = socket?.send(message.toString()) == true

    /** Sends [message] with a request id and waits for its answer, null when it never comes. */
    suspend fun request(message: JsonObject, timeoutMs: Long = REQUEST_TIMEOUT_MS): RelayEvent? {
        val rid = "r${nextRid.incrementAndGet()}"
        val answer = CompletableDeferred<RelayEvent>()
        pending[rid] = answer
        return try {
            if (!send(RelayCodec.withRid(message, rid))) null else withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            pending.remove(rid)
        }
    }

    private fun open() {
        _state.value = State.Connecting
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            webSocket.send(hello().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            val event = RelayCodec.parse(text) ?: return
            handle(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, t.message.orEmpty())
    }

    private fun handle(event: RelayEvent) {
        when (event) {
            is RelayEvent.Welcome -> welcomed(event)
            is RelayEvent.Pong -> clockOffsetMs = event.serverTime - (event.clientTime + now()) / 2
            is RelayEvent.Error -> if (_state.value is State.Connecting && event.code in REFUSALS) refused(event.code)
            else -> Unit
        }
        event.rid?.let { pending.remove(it)?.complete(event) }
        _events.tryEmit(event)
    }

    private fun welcomed(welcome: RelayEvent.Welcome) {
        attempts = 0
        clockOffsetMs = welcome.serverTime - now()
        _state.value = State.Connected(welcome.memberId)
        pinger?.cancel()
        pinger = scope.launch {
            while (isActive) {
                send(RelayCodec.ping(now()))
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun refused(code: String) {
        LokiLogger.w(TAG, "Relay refused the hello: $code")
        wanted = false
        _state.value = State.Refused(code)
    }

    private fun dropped(webSocket: WebSocket, reason: String) {
        if (webSocket !== socket) return
        socket = null
        pinger?.cancel()
        failPending()
        if (!wanted) {
            if (_state.value !is State.Refused) _state.value = State.Idle
            return
        }
        _state.value = State.Connecting
        val pause = (BASE_RETRY_MS shl attempts.coerceAtMost(MAX_BACKOFF_STEPS)).coerceAtMost(MAX_RETRY_MS)
        attempts++
        LokiLogger.i(TAG, "Relay connection dropped ($reason), retrying in ${pause}ms")
        retry?.cancel()
        retry = scope.launch {
            delay(pause)
            if (wanted && socket == null) open()
        }
    }

    private fun failPending() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    companion object {
        const val DEFAULT_URL = "wss://snepirelay.pianonic.ch/api/relay"
        private const val TAG = "RelayClient"
        private const val NORMAL_CLOSURE = 1000
        private const val EVENT_BUFFER = 64
        private const val PING_INTERVAL_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val BASE_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val MAX_BACKOFF_STEPS = 5
        private val REFUSALS = setOf("UNAUTHORIZED", "UNSUPPORTED_PROTOCOL", "INVALID_MESSAGE", "HELLO_REQUIRED")

        private fun defaultHttp() = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

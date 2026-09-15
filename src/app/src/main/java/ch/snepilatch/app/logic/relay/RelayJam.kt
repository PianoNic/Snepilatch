package ch.snepilatch.app.logic.relay

import android.content.Context
import android.os.Build
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The jam this device holds through Snepirelay, process-scoped like [JamHolder]. It opens the relay
 * connection when a jam starts, closes it when the jam is gone, keeps [JamHolder] showing the jam so
 * the header, banner and invite work unchanged, and while this device hosts runs a [RelayHost] that
 * publishes playback and carries out guest commands.
 */
object RelayJam {

    private const val TAG = "RelayJam"
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val CLOSE_DELAY_MS = 2_000L
    const val FAILED = "failed"

    val session = MutableStateFlow<RelaySession?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: RelayClient? = null
    private var host: RelayHost? = null
    private var listener: Job? = null

    @Volatile private var playback: PlaybackViewModel? = null

    @Volatile private var appContext: Context? = null

    val active: Boolean get() = session.value != null

    fun bind(vm: PlaybackViewModel, context: Context) {
        playback = vm
        appContext = context.applicationContext
    }

    /** Starts a jam this device hosts. Null when it worked, otherwise the relay's error code or [FAILED]. */
    suspend fun create(): String? = send(RelayCodec.create())

    suspend fun leave(): String? = send(RelayCodec.leave())

    suspend fun end(): String? = send(RelayCodec.end())

    suspend fun kick(memberId: String): String? = send(RelayCodec.kick(memberId))

    suspend fun setGuestControl(guestControl: String): String? = send(RelayCodec.settings(guestControl))

    private suspend fun send(message: kotlinx.serialization.json.JsonObject): String? {
        val connected = connect() ?: return FAILED
        return when (val answer = connected.request(message)) {
            is RelayEvent.Ok -> null
            is RelayEvent.Error -> answer.code
            else -> FAILED
        }
    }

    private suspend fun connect(): RelayClient? {
        client?.let { existing -> if (existing.state.value is RelayClient.State.Connected) return existing }
        val context = appContext ?: return null
        val created = client ?: newClient(context).also { client = it }
        created.start()
        val state = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            created.state.first { it is RelayClient.State.Connected || it is RelayClient.State.Refused }
        }
        if (state !is RelayClient.State.Connected) {
            LokiLogger.w(TAG, "Relay not reachable: $state")
            return null
        }
        return created
    }

    private fun newClient(context: Context): RelayClient {
        val identity = RelayIdentity.load(context)
        val relay = RelayClient(RelayClient.socketUrl(RelaySettings.url.value), { hello(identity) }, scope)
        listener?.cancel()
        listener = scope.launch { relay.events.collect { onEvent(it) } }
        return relay
    }

    private fun hello(identity: RelayIdentity): kotlinx.serialization.json.JsonObject {
        val account = playback?.account?.value
        val name = listOfNotNull(account?.displayName, account?.username).firstOrNull(String::isNotBlank) ?: Build.MODEL
        val profile = RelayCodec.Profile(
            userId = account?.userId?.ifBlank { null },
            displayName = name,
            imageUrl = account?.profileImageUrl,
        )
        val device = RelayCodec.Device(AppSettings.persistedDeviceId(), Build.MODEL, "Smartphone")
        return RelayCodec.hello(identity.installId, identity.secret, profile, device)
    }

    private suspend fun onEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.Welcome -> if (event.session != null || active) show(event.session)
            is RelayEvent.SessionUpdate -> show(event.session)
            is RelayEvent.Command -> host?.apply(event.command)
            else -> Unit
        }
    }

    private fun show(relaySession: RelaySession?) {
        session.value = relaySession
        if (relaySession == null) {
            ended()
            return
        }
        LokiLogger.i(TAG, "Relay jam ${relaySession.joinToken}: ${relaySession.members.size} members, host=${relaySession.isOwner}")
        JamHolder.session.value = RelayJamMapper.toJamSession(relaySession)
        if (relaySession.isOwner) startHosting() else stopHosting()
    }

    private fun startHosting() {
        if (host != null) return
        val vm = playback ?: return
        val relay = client ?: return
        host = RelayHost(scope, ViewModelRelayPlayer(vm), { relay.send(RelayCodec.playback(it)) }, relay::serverNow).also {
            it.start()
            it.publishNow()
        }
    }

    private fun stopHosting() {
        host?.stop()
        host = null
    }

    /** The jam is gone; the connection closes a moment later, once the answer to a leave or an end is in. */
    private fun ended() {
        stopHosting()
        session.value = null
        if (RelayJamMapper.isRelay(JamHolder.session.value)) JamHolder.clear()
        val closing = client
        scope.launch {
            delay(CLOSE_DELAY_MS)
            if (session.value == null && client === closing) {
                closing?.stop()
                client = null
                listener?.cancel()
                listener = null
            }
        }
    }
}

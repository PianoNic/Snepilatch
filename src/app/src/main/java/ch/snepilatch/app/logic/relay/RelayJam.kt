package ch.snepilatch.app.logic.relay

import android.content.Context
import android.os.Build
import ch.snepilatch.app.R
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
    private const val NOT_ALLOWED = "NOT_ALLOWED"
    private const val SESSION_DELETED = "SESSION_DELETED"
    private const val KEY_IN_JAM = "relay_in_jam"

    val session = MutableStateFlow<RelaySession?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: RelayClient? = null
    private var host: RelayHost? = null
    private var guest: RelayGuest? = null

    /** True while [RelayGuest] drives the player, so its own play and seek are not sent back as commands. */
    @Volatile private var following = false
    private var listener: Job? = null

    @Volatile private var playback: PlaybackViewModel? = null

    @Volatile private var appContext: Context? = null

    val active: Boolean get() = session.value != null

    fun bind(vm: PlaybackViewModel, context: Context) {
        playback = vm
        appContext = context.applicationContext
        if (client == null && wasInJam(context)) scope.launch { resume() }
    }

    /** After a restart: back into the jam the relay still holds for this device, or let the connection go. */
    private suspend fun resume() {
        val connected = connect()
        val restored = connected?.welcome?.session
        if (restored != null) show(restored) else if (!active) ended()
    }

    private fun wasInJam(context: Context): Boolean =
        context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_IN_JAM, false)

    private fun rememberInJam(inJam: Boolean) {
        appContext?.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_IN_JAM, inJam)?.apply()
    }

    /** Starts a jam this device hosts. Null when it worked, otherwise the relay's error code or [FAILED]. */
    suspend fun create(): String? {
        if (active) return null
        val connected = connect() ?: return FAILED
        // The welcome can put this device back into the jam it held before the app restarted.
        connected.welcome?.session?.let { restored ->
            show(restored)
            return null
        }
        return send(RelayCodec.create())
    }

    /** Joins the relay jam behind [joinToken]; the answer is shaped like [create]'s. */
    suspend fun join(joinToken: String): String? = send(RelayCodec.join(joinToken))

    /** An invite link opened the app: join, and say in the log when that did not work. */
    suspend fun joinFromLink(joinToken: String) {
        join(joinToken)?.let { LokiLogger.w(TAG, "Relay jam $joinToken not joined: $it") }
    }

    /**
     * A guest's transport goes to the host instead of the player: true when [command] was sent that
     * way and the caller must not act on it. A refusal shows why in the app's snackbar.
     */
    fun redirect(command: RelayCommand): Boolean {
        val current = session.value ?: return false
        if (current.isOwner || following) return false
        val relay = client ?: return false
        scope.launch {
            guest?.applyLocally(command)
            val answer = relay.request(RelayCodec.command(command))
            if (answer is RelayEvent.Error && answer.code == NOT_ALLOWED) playback?.emitMessage(R.string.jam_host_only)
        }
        return true
    }

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
            is RelayEvent.Welcome -> if (event.session != null || active) {
                show(event.session)
                event.playback?.let { guest?.follow(it) }
            }
            is RelayEvent.SessionUpdate -> {
                if (event.reason == SESSION_DELETED && session.value?.isOwner == false) playback?.emitMessage(R.string.jam_ended_by_host)
                show(event.session)
            }
            is RelayEvent.Playback -> guest?.follow(event.state)
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
        rememberInJam(true)
        JamHolder.session.value = RelayJamMapper.toJamSession(relaySession)
        if (relaySession.isOwner) {
            stopFollowing()
            startHosting()
        } else {
            stopHosting()
            startFollowing()
        }
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

    private fun startFollowing() {
        if (guest != null) return
        val vm = playback ?: return
        val relay = client ?: return
        guest = RelayGuest(scope, ViewModelRelayPlayer(vm), relay::serverNow) { following = it }.also { it.start() }
    }

    private fun stopFollowing() {
        guest?.stop()
        guest = null
        following = false
    }

    /** The jam is gone; the connection closes a moment later, once the answer to a leave or an end is in. */
    private fun ended() {
        stopHosting()
        stopFollowing()
        rememberInJam(false)
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

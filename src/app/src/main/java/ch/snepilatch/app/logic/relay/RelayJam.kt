package ch.snepilatch.app.logic.relay

import android.content.Context
import android.os.Build
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private const val HTTP_NOT_FOUND = 404
    private const val SESSION_DELETED = "SESSION_DELETED"
    private const val KEY_IN_JAM = "relay_in_jam"
    private const val KEY_LISTENING = "relay_listening"
    const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"

    val session = MutableStateFlow<RelaySession?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: RelayClient? = null
    private var host: RelayHost? = null
    private var guest: RelayGuest? = null
    private var remote: RelayRemoteView? = null
    private val http = OkHttpClient()

    /** A guest that plays the jam on this phone; false for one that only controls the host. */
    @Volatile private var listening = true

    /**
     * Back in a jam this guest listened to before a cold boot. Nothing plays by itself, as for a Spfy
     * jam (JamListenAlong.resumable): the jam banner offers [resumeListening] instead.
     */
    val resumable = MutableStateFlow(false)

    @Volatile private var quietAfterBoot = false

    // The host's latest playback, so a guest started later begins where the host is, not at its next change.
    @Volatile private var lastHostPlayback: RelayPlayback? = null

    /** A guest that only controls the host: its player shows the host's playback and plays nothing. */
    val remoteOnly: Boolean get() = session.value?.isOwner == false && !listening

    /** True while [RelayGuest] drives the player, so its own play and seek are not sent back as commands. */
    @Volatile private var following = false
    private var listener: Job? = null

    @Volatile private var playback: PlaybackViewModel? = null

    @Volatile private var appContext: Context? = null

    val active: Boolean get() = session.value != null

    fun bind(vm: PlaybackViewModel, context: Context) {
        playback = vm
        appContext = context.applicationContext
        listening = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LISTENING, true)
        if (client == null && wasInJam(context)) {
            quietAfterBoot = true
            scope.launch { resume() }
        }
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

    /**
     * Joins the relay jam behind [joinToken]; the answer is shaped like [create]'s. [listen] plays it on
     * this phone; without it this device only controls the host, and its own playback stops first so
     * the pause is not sent to the host.
     */
    suspend fun join(joinToken: String, listen: Boolean): String? {
        quietAfterBoot = false
        if (!listen) playback?.pauseAndWait()
        listening = listen
        appContext?.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_LISTENING, listen)?.apply()
        return send(RelayCodec.join(joinToken))
    }

    /**
     * Looks the relay jam behind [joinToken] up for the join sheet, which asks how to take part before
     * anything is joined, as for a Spfy jam. Null when the sheet is up, otherwise [SESSION_NOT_FOUND]
     * for a jam that is gone, or [FAILED].
     */
    suspend fun preview(joinToken: String): String? = withContext(Dispatchers.IO) {
        val url = "${RelaySettings.url.value.trim().trimEnd('/')}/api/jams/$joinToken"
        val body = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use {
                if (it.code == HTTP_NOT_FOUND) SESSION_NOT_FOUND else it.body?.string()
            }
        }.getOrNull() ?: return@withContext FAILED
        if (body == SESSION_NOT_FOUND) return@withContext SESSION_NOT_FOUND
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext FAILED
        val hostName = json["hostDisplayName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val hostImage = json["hostImageUrl"]?.jsonPrimitive?.contentOrNull
        JamHolder.pending.value = JamHolder.PendingJam(joinToken, RelayJamMapper.preview(joinToken, hostName, hostImage))
        null
    }

    /** This device's track ran out while listening as a guest: true when the guest plays the host's next. */
    fun trackEnded(): Boolean {
        val following = guest ?: return false
        // Without the hold Spfy advanced onto the host's next itself; the normal end path covers it.
        if (SessionHolder.player?.holdAtTrackEnd != true) return false
        scope.launch { following.trackEnded() }
        return true
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

    /** [leave] from a caller that cannot wait for the answer. */
    fun leaveInBackground() {
        scope.launch { leave()?.let { LokiLogger.w(TAG, "Relay jam not left: $it") } }
    }

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
                event.playback?.let { onHostPlayback(it) }
            }
            is RelayEvent.SessionUpdate -> {
                if (event.reason == SESSION_DELETED && session.value?.isOwner == false) playback?.emitMessage(R.string.jam_ended_by_host)
                show(event.session)
            }
            is RelayEvent.Playback -> onHostPlayback(event.state)
            is RelayEvent.Command -> host?.apply(event.command)
            else -> Unit
        }
    }

    /** The banner's "Mithören" after a cold boot: start following the host from where it is now. */
    fun resumeListening() {
        quietAfterBoot = false
        resumable.value = false
        if (session.value?.isOwner == false) startFollowing()
    }

    private suspend fun onHostPlayback(state: RelayPlayback) {
        lastHostPlayback = state
        guest?.follow(state)
        remote?.show(state)
    }

    private fun show(relaySession: RelaySession?) {
        session.value = relaySession
        if (relaySession == null) {
            ended()
            return
        }
        LokiLogger.i(TAG, "Relay jam ${relaySession.joinToken}: ${relaySession.members.size} members, host=${relaySession.isOwner}")
        rememberInJam(true)
        // isListening tells the jam pill whether this phone plays the jam or only controls it.
        JamHolder.session.value = RelayJamMapper.toJamSession(relaySession).copy(isListening = relaySession.isOwner || listening)
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
        if (guest != null || remote != null) return
        val vm = playback ?: return
        val relay = client ?: return
        if (!listening) {
            remote = RelayRemoteView(vm, relay::serverNow).also { view -> lastHostPlayback?.let { scope.launch { view.show(it) } } }
            return
        }
        if (quietAfterBoot) {
            LokiLogger.i(TAG, "Back in the jam after a restart; listening waits for the banner's Mithören")
            resumable.value = true
            return
        }
        // Hold until the guest has made this account's next the host's next (RelayGuest.prepareNext).
        SessionHolder.player?.holdAtTrackEnd = true
        guest = RelayGuest(scope, ViewModelRelayPlayer(vm), relay::serverNow) { following = it }.also {
            it.start()
            lastHostPlayback?.let(it::follow)
        }
    }

    private fun stopFollowing() {
        if (guest != null) SessionHolder.player?.holdAtTrackEnd = false
        guest?.stop()
        guest = null
        remote = null
        following = false
    }

    /** The jam is gone; the connection closes a moment later, once the answer to a leave or an end is in. */
    private fun ended() {
        resumable.value = false
        quietAfterBoot = false
        lastHostPlayback = null
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

package ch.snepilatch.app.logic.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Snepirelay's messages to and from JSON, one text frame each. Writing builds the objects the relay
 * reads; reading turns a frame into a [RelayEvent], or null for anything this app does not know,
 * so a newer relay can add messages without breaking an older app. Pure, for the tests.
 */
object RelayCodec {

    const val PROTOCOL = 1

    data class Profile(val userId: String?, val displayName: String, val imageUrl: String?)

    data class Device(val deviceId: String, val name: String, val type: String)

    fun hello(installId: String, secret: String, profile: Profile, device: Device): JsonObject {
        val profileJson = buildJsonObject {
            put("userId", profile.userId)
            put("displayName", profile.displayName)
            put("imageUrl", profile.imageUrl)
        }
        val deviceJson = buildJsonObject {
            put("deviceId", device.deviceId)
            put("name", device.name)
            put("type", device.type)
        }
        return message("hello") {
            put("protocol", PROTOCOL)
            put("installId", installId)
            put("secret", secret)
            put("profile", profileJson)
            put("device", deviceJson)
        }
    }

    fun ping(clientTime: Long): JsonObject = message("ping") { put("clientTime", clientTime) }

    fun create(): JsonObject = message("create")

    fun join(joinToken: String): JsonObject = message("join") { put("joinToken", joinToken) }

    fun leave(): JsonObject = message("leave")

    fun end(): JsonObject = message("end")

    fun kick(memberId: String): JsonObject = message("kick") { put("memberId", memberId) }

    fun settings(guestControl: String): JsonObject = message("settings") { put("guestControl", guestControl) }

    fun playback(state: RelayPlayback): JsonObject {
        val queue = buildJsonArray { state.queue.forEach { add(queueEntryJson(it)) } }
        val stateJson = buildJsonObject {
            put("trackUri", state.trackUri)
            put("contextUri", state.contextUri)
            put("positionMs", state.positionMs)
            put("durationMs", state.durationMs)
            put("paused", state.paused)
            put("shuffle", state.shuffle)
            put("repeat", state.repeat)
            put("queue", queue)
            state.sampledAt?.let { put("sampledAt", it) }
        }
        return message("playback") { put("state", stateJson) }
    }

    fun command(command: RelayCommand): JsonObject {
        val commandJson = buildJsonObject {
            put("kind", command.kind)
            command.positionMs?.let { put("positionMs", it) }
            command.uri?.let { put("uri", it) }
            command.contextUri?.let { put("contextUri", it) }
            command.uid?.let { put("uid", it) }
            command.index?.let { put("index", it) }
            command.toIndex?.let { put("toIndex", it) }
        }
        return message("command") { put("command", commandJson) }
    }

    private fun queueEntryJson(entry: RelayQueueEntry) = buildJsonObject {
        put("uri", entry.uri)
        put("uid", entry.uid)
        put("addedBy", entry.addedBy)
    }

    /** The same message with a request id, so the relay answers it with `ok` or `error`. */
    fun withRid(message: JsonObject, rid: String): JsonObject = JsonObject(message + ("rid" to JsonPrimitive(rid)))

    fun parse(text: String): RelayEvent? {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val rid = root.string("rid")
        return runCatching { event(root, root.string("type"), rid) }.getOrNull()
    }

    private fun event(root: JsonObject, type: String?, rid: String?): RelayEvent? = when (type) {
        "welcome" -> welcome(root, rid)
        "pong" -> RelayEvent.Pong(root.long("clientTime"), root.long("serverTime"), rid)
        "session_update" -> sessionUpdate(root, rid)
        "playback" -> RelayEvent.Playback(playback(root.obj("state")!!), root.long("serverTime"), rid)
        "command" -> RelayEvent.Command(root.string("from")!!, command(root.obj("command")!!), rid)
        "ok" -> RelayEvent.Ok(rid)
        "error" -> error(root, rid)
        else -> null
    }

    private fun welcome(root: JsonObject, rid: String?) = RelayEvent.Welcome(
        memberId = root.string("memberId")!!,
        serverTime = root.long("serverTime"),
        session = root.obj("session")?.let(::session),
        playback = root.obj("playback")?.let(::playback),
        rid = rid,
    )

    private fun sessionUpdate(root: JsonObject, rid: String?) = RelayEvent.SessionUpdate(
        reason = root.string("reason")!!,
        session = root.obj("session")?.let(::session),
        memberIds = root.strings("memberIds"),
        rid = rid,
    )

    private fun error(root: JsonObject, rid: String?): RelayEvent.Error {
        val values = root.obj("values")?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        return RelayEvent.Error(root.string("error")!!, values, rid)
    }

    private fun session(o: JsonObject) = RelaySession(
        sessionId = o.string("sessionId")!!,
        joinToken = o.string("joinToken")!!,
        ownerId = o.string("ownerId")!!,
        isOwner = o.bool("isOwner"),
        members = o.array("members").map { member(it.jsonObject) },
        guestControl = o.string("guestControl") ?: GUEST_CONTROL_FULL,
        maxMemberCount = o.int("maxMemberCount"),
        timestamp = o.long("timestamp"),
    )

    private fun member(o: JsonObject) = RelayMember(
        id = o.string("id")!!,
        userId = o.string("userId"),
        displayName = o.string("displayName").orEmpty(),
        imageUrl = o.string("imageUrl"),
        isHost = o.bool("isHost"),
        isCurrentUser = o.bool("isCurrentUser"),
        listening = o.bool("listening"),
        joinedAt = o.long("joinedAt"),
    )

    private fun playback(o: JsonObject) = RelayPlayback(
        trackUri = o.string("trackUri"),
        contextUri = o.string("contextUri"),
        positionMs = o.long("positionMs"),
        durationMs = o.long("durationMs"),
        paused = o.bool("paused"),
        shuffle = o.bool("shuffle"),
        repeat = o.string("repeat") ?: "off",
        queue = o.array("queue").map { e ->
            val entry = e.jsonObject
            RelayQueueEntry(entry.string("uri")!!, entry.string("uid"), entry.string("addedBy"))
        },
        sampledAt = o["sampledAt"]?.jsonPrimitive?.longOrNull,
    )

    private fun command(o: JsonObject) = RelayCommand(
        kind = o.string("kind")!!,
        positionMs = o["positionMs"]?.jsonPrimitive?.longOrNull,
        uri = o.string("uri"),
        contextUri = o.string("contextUri"),
        uid = o.string("uid"),
        index = o["index"]?.jsonPrimitive?.intOrNull,
        toIndex = o["toIndex"]?.jsonPrimitive?.intOrNull,
    )

    const val GUEST_CONTROL_FULL = "full"
    const val GUEST_CONTROL_QUEUE_ONLY = "queueOnly"
    const val GUEST_CONTROL_NONE = "none"

    private fun message(type: String, fields: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("type", type)
        fields()
    }
}

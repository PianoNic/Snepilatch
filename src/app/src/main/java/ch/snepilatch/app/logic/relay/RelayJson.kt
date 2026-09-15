package ch.snepilatch.app.logic.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient reads for relay frames: a missing or null field reads as null, 0 or false. */
internal fun JsonObject.value(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

internal fun JsonObject.string(key: String): String? = (value(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String): Long = (value(key) as? JsonPrimitive)?.longOrNull ?: 0L

internal fun JsonObject.int(key: String): Int = (value(key) as? JsonPrimitive)?.intOrNull ?: 0

internal fun JsonObject.bool(key: String): Boolean = (value(key) as? JsonPrimitive)?.booleanOrNull ?: false

internal fun JsonObject.obj(key: String): JsonObject? = value(key) as? JsonObject

internal fun JsonObject.array(key: String): JsonArray = value(key) as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.strings(key: String): List<String> = array(key).mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }

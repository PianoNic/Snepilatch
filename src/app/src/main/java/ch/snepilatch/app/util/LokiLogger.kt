package ch.snepilatch.app.util

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

object LokiLogger {

    private lateinit var endpoint: String
    private lateinit var appName: String
    private lateinit var deviceId: String
    private lateinit var sessionId: String
    var appVersion: String = "unknown"

    private val buffer = ConcurrentLinkedQueue<LogEntry>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    // Only buffer once init() has wired up the flush loop. Production ships with LOKI_ENDPOINT empty,
    // so init() is never called, no flush job ever drains the queue — without this gate the buffer
    // would grow unbounded for the whole app lifetime.
    @Volatile
    private var started = false

    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_BATCH_SIZE = 100
    private const val MAX_BUFFER = 1000

    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    fun init(endpoint: String, appName: String, deviceId: String, appVersion: String = "unknown") {
        this.endpoint = endpoint.trimEnd('/')
        this.appName = appName
        this.deviceId = deviceId
        this.appVersion = appVersion
        this.sessionId = UUID.randomUUID().toString().take(8)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("CRASH", "Uncaught exception in ${thread.name}", throwable)
            flushSync()
            defaultHandler?.uncaughtException(thread, throwable)
        }

        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
        started = true

        i("LokiLogger", "Session started | version=$appVersion | device=${Build.MODEL} | Android ${Build.VERSION.RELEASE}")
    }

    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)
    fun e(tag: String, message: String) = log("ERROR", tag, message)

    fun e(tag: String, message: String, throwable: Throwable) {
        val full = "$message\n${throwable.stackTraceToString()}"
        log("ERROR", tag, full)
    }

    private fun log(level: String, tag: String, message: String) {
        when (level) {
            "DEBUG" -> Log.d(tag, message)
            "INFO" -> Log.i(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
        }

        // Console logging above always runs; only queue for the Loki push when a flush loop exists
        // to drain it (dev builds with loki.endpoint set). Otherwise the queue would never empty.
        if (!started) return

        buffer.add(
            LogEntry(
                timestamp = System.currentTimeMillis() * 1_000_000,
                level = level,
                tag = tag,
                message = message
            )
        )
        // Bound the queue in case the flush loop falls behind (e.g. the endpoint is unreachable).
        while (buffer.size > MAX_BUFFER) buffer.poll()

        if (level == "ERROR") {
            scope.launch { flush() }
        }
    }

    private suspend fun flush() {
        if (buffer.isEmpty()) return

        val batch = mutableListOf<LogEntry>()
        while (batch.size < MAX_BATCH_SIZE) {
            val entry = buffer.poll() ?: break
            batch.add(entry)
        }

        if (batch.isEmpty()) return
        sendToLoki(batch)
    }

    private fun flushSync() {
        val batch = mutableListOf<LogEntry>()
        while (batch.size < MAX_BATCH_SIZE) {
            val entry = buffer.poll() ?: break
            batch.add(entry)
        }
        if (batch.isEmpty()) return

        runBlocking(Dispatchers.IO) {
            sendToLoki(batch)
        }
    }

    private suspend fun sendToLoki(batch: List<LogEntry>) {
        withContext(Dispatchers.IO) {
            try {
                val grouped = batch.groupBy { "${it.level}|${it.tag}" }

                val streams = JSONArray()
                grouped.forEach { (key, entries) ->
                    val level = key.substringBefore("|")
                    val tag = key.substringAfter("|")

                    val values = JSONArray()
                    entries.forEach { entry ->
                        val pair = JSONArray()
                        pair.put(entry.timestamp.toString())
                        pair.put(entry.message)
                        values.put(pair)
                    }

                    streams.put(JSONObject().apply {
                        put("stream", JSONObject().apply {
                            put("app", appName)
                            put("device_id", deviceId)
                            put("device_model", Build.MODEL)
                            put("os_version", "Android ${Build.VERSION.RELEASE}")
                            put("app_version", appVersion)
                            put("session", sessionId)
                            put("level", level)
                            put("tag", tag)
                        })
                        put("values", values)
                    })
                }

                val payload = JSONObject().apply {
                    put("streams", streams)
                }

                val url = URL("$endpoint/loki/api/v1/push")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Log.w("LokiLogger", "Push failed: HTTP $responseCode")
                    buffer.addAll(batch)
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.w("LokiLogger", "Push failed: ${e.message}")
                buffer.addAll(batch)
            }
        }
    }
}

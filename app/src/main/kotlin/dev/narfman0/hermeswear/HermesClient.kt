package dev.narfman0.hermeswear

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the client needs to know about the server. */
interface ServerConfig {
    val baseUrl: String
    val apiKey: String
    val conversation: String
    val configured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
}

/** Server address and key, stored on the watch. Set via adb in debug builds. */
class Prefs(context: Context) : ServerConfig {
    private val sp = context.applicationContext.getSharedPreferences("hermes", Context.MODE_PRIVATE)

    override var baseUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v.trim().trimEnd('/')).apply()

    override var apiKey: String
        get() = sp.getString("key", "") ?: ""
        set(v) = sp.edit().putString("key", v.trim()).apply()

    /** Server-side conversation name, so the watch keeps one running thread. */
    override var conversation: String
        get() = sp.getString("conversation", "pixel-watch") ?: "pixel-watch"
        set(v) = sp.edit().putString("conversation", v.trim()).apply()
}

class HermesException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Talks to the Hermes API server (`POST /v1/responses`, streaming).
 * Named conversations let the server hold the history, so the watch sends only the new line.
 */
class HermesClient(private val prefs: ServerConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // The server sends a keepalive comment every 10 s, so a 45 s read timeout means it really stalled.
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Emits text deltas as they arrive; completes when the response is done. */
    fun ask(text: String): Flow<String> = flow {
        if (!prefs.configured) throw HermesException("Set the server URL and key first")

        val body = buildJsonObject {
            put("model", "hermes-agent")
            put("input", text)
            put("conversation", prefs.conversation)
            put("stream", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${prefs.baseUrl}/v1/responses")
            .header("Authorization", "Bearer ${prefs.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = http.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw HermesException(
                        when (response.code) {
                            401, 403 -> "Wrong API key"
                            404 -> "Server has no /v1/responses"
                            else -> "Server error ${response.code}"
                        },
                    )
                }
                val source = response.body.source()
                var completed = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    // Skip blank lines, "event:" lines and ": keepalive" comments.
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    when (obj.string("type")) {
                        "response.output_text.delta" -> obj.string("delta")?.let { emit(it) }
                        "response.completed" -> { completed = true; break }
                        "response.failed", "error" -> throw HermesException(obj.errorText())
                    }
                }
                if (!completed) throw HermesException("Connection ended early")
            }
        } catch (e: HermesException) {
            throw e
        } catch (e: IOException) {
            throw HermesException("Can't reach server", e)
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.errorText(): String {
        val nested = (this["error"] as? JsonObject) ?: (this["response"] as? JsonObject)?.get("error") as? JsonObject
        return nested?.get("message")?.jsonPrimitive?.contentOrNull ?: "Hermes reported an error"
    }
}

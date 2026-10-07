package ai.deepseek.dshmobile.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Streaming events from an OpenAI-compatible chat-completions endpoint. */
sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent
    data object Done : StreamEvent
    data class Failed(val message: String) : StreamEvent
}

/**
 * Minimal OpenAI-compatible chat client.
 *
 * This is the "basic DSH functionality" path: it talks directly to
 * `POST {base}/v1/chat/completions` with `stream: true` and surfaces text and
 * reasoning deltas.
 */
class ChatApi {

    data class Msg(val role: String, val content: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var activeCall: Call? = null

    private fun endpoint(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        // Accept both `https://api.deepseek.com` and a base already ending in /v1.
        return if (base.endsWith("/v1")) "$base/$path" else "$base/v1/$path"
    }

    /** List model ids, used to populate the model picker. */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val url = endpoint(baseUrl, "models")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val resp = try {
            client.newCall(req).execute()
        } catch (t: Throwable) {
            throw RuntimeException(TransportErrors.message(url, t, TransportErrors.INTERNET_HINT))
        }
        resp.use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: ${text.take(200)}")
            val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() }
        }
    }

    /**
     * Stream one completion.
     *
     * Emits [StreamEvent.Delta] per content chunk and [StreamEvent.Reasoning]
     * per reasoning chunk, then exactly one [StreamEvent.Done] or
     * [StreamEvent.Failed].
     */
    fun streamCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Msg>,
    ): Flow<StreamEvent> = callbackFlow {
        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })

        val req = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(req)
        activeCall = call

        val response: Response = try {
            call.execute()
        } catch (t: Throwable) {
            // The endpoint is an arbitrary internet host, so the LAN/plugin advice
            // the remote path uses would be actively misleading here.
            trySend(StreamEvent.Failed(TransportErrors.message(endpoint(baseUrl, "chat/completions"), t, TransportErrors.INTERNET_HINT)))
            close()
            return@callbackFlow
        }

        if (!response.isSuccessful) {
            val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
            trySend(StreamEvent.Failed("HTTP ${response.code}: ${extractApiError(body)}"))
            response.close()
            close()
            return@callbackFlow
        }

        val body = response.body
        if (body == null) {
            trySend(StreamEvent.Failed("响应为空"))
            response.close()
            close()
            return@callbackFlow
        }
        val reader = body.source().inputStream().bufferedReader()

        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break

                val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                val delta = choice.optJSONObject("delta") ?: continue

                delta.optString("reasoning_content").takeIf { it.isNotEmpty() }
                    ?.let { trySend(StreamEvent.Reasoning(it)) }
                delta.optString("content").takeIf { it.isNotEmpty() }
                    ?.let { trySend(StreamEvent.Delta(it)) }
            }
            trySend(StreamEvent.Done)
        } catch (t: Throwable) {
            if (call.isCanceled()) trySend(StreamEvent.Done)
            else trySend(
                StreamEvent.Failed(
                    TransportErrors.message(
                        endpoint(baseUrl, "chat/completions"),
                        t,
                        TransportErrors.INTERNET_HINT,
                    )
                )
            )
        } finally {
            runCatching { reader.close() }
            runCatching { response.close() }
            activeCall = null
            close()
        }

        awaitClose { runCatching { call.cancel() } }
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        activeCall?.cancel()
        activeCall = null
    }

    private fun extractApiError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: body.take(200)
}

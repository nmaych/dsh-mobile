package ai.deepseek.dshmobile.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the `dsh-mobile-connect` plugin's LAN gateway.
 *
 * The gateway is a small server the desktop plugin runs in front of `dsh web`.
 * It exists because `dsh web` binds loopback only, so a phone cannot reach it
 * without port forwarding. Pairing once with a 6-digit code is what turns a
 * phone into a known device; from then on the device token is the credential.
 */
class GatewayClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** What `GET /.dsh-mobile-connect/info` reports about a reachable gateway. */
    data class Info(
        val service: String,
        val version: String,
        val name: String,
        val deviceCount: Int,
    )

    /** A gateway found by probing the local network. */
    data class Found(val baseUrl: String, val info: Info)

    class GatewayException(message: String, val reason: String = "error") : Exception(message)

    /**
     * Ask a host whether it is running the gateway.
     *
     * @return the gateway's info, or null when nothing answers or the answer is
     *   not a gateway (so a random web server on the port is not mistaken for one).
     */
    suspend fun probe(baseUrl: String): Info? = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        runCatching {
            val req = Request.Builder().url("$base/.dsh-mobile-connect/info").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = JSONObject(resp.body?.string().orEmpty())
                if (body.optString("service") != "dsh-mobile-connect") return@use null
                Info(
                    service = "dsh-mobile-connect",
                    version = body.optString("version", "?"),
                    name = body.optString("name", "DSH Harness"),
                    deviceCount = body.optInt("deviceCount", 0),
                )
            }
        }.getOrNull()
    }

    /**
     * Trade a pairing code for a device token.
     *
     * @param deviceName a label the desktop will show in its device list.
     * @throws GatewayException with a user-facing message on failure.
     */
    suspend fun pair(baseUrl: String, code: String, deviceName: String): String =
        withContext(Dispatchers.IO) {
            val base = baseUrl.trim().trimEnd('/')
            val payload = JSONObject()
                .put("code", code.trim())
                .put("name", deviceName)
                .toString()

            val req = Request.Builder()
                .url("$base/.dsh-mobile-connect/pair")
                .post(payload.toRequestBody(JSON))
                .build()

            val (status, text) = runCatching {
                http.newCall(req).execute().use { resp ->
                    resp.code to resp.body?.string().orEmpty()
                }
            }.getOrElse {
                throw GatewayException(
                    "连接不上 ${base}。请确认手机和电脑在同一个 Wi-Fi，" +
                        "并且电脑上已启用 dsh-mobile-connect。",
                    "unreachable",
                )
            }

            val body = runCatching { JSONObject(text) }.getOrNull()
            if (status == 200 && body?.optBoolean("ok") == true) {
                val token = body.optString("token")
                if (token.isBlank()) throw GatewayException("桌面端没有返回配对凭据。", "no-token")
                return@withContext token
            }

            // Prefer the desktop's own wording: it knows which failure this was.
            val message = body?.optString("message")?.takeIf { it.isNotBlank() }
            val reason = body?.optString("reason").orEmpty()
            throw GatewayException(
                message ?: when (status) {
                    429 -> "配对码已被锁定。请在桌面端重新生成一个。"
                    401 -> "配对码不正确，请核对后重试。"
                    else -> "配对失败（HTTP $status）。"
                },
                reason.ifBlank { "http-$status" },
            )
        }

    /**
     * Scan the local network for gateways.
     *
     * Only the /24 the phone is on is probed, which is where a desktop on the
     * same Wi-Fi will be. This is a convenience: typing the address the desktop
     * printed always works too.
     *
     * @param localIpv4 the phone's own IPv4 address, used to derive the subnet.
     * @param onProgress called with the number of hosts tried so far.
     */
    suspend fun discover(
        localIpv4: String,
        port: Int = 19387,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Found> = withContext(Dispatchers.IO) {
        val prefix = localIpv4.substringBeforeLast('.', "")
        if (prefix.isEmpty()) return@withContext emptyList()

        val hosts = (1..254).map { "$prefix.$it" }
        val found = java.util.Collections.synchronizedList(mutableListOf<Found>())
        val done = java.util.concurrent.atomic.AtomicInteger(0)

        // Probe in bounded parallel batches: fast enough to feel instant, gentle
        // enough not to look like a scan to a home router.
        val parallelism = 24
        for (chunk in hosts.chunked(parallelism)) {
            chunk.map { host ->
                async(Dispatchers.IO) {
                    val info = probe("http://$host:$port")
                    if (info != null) found.add(Found("http://$host:$port", info))
                    onProgress(done.incrementAndGet(), hosts.size)
                }
            }.awaitAll()
        }
        found.toList()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** The port the plugin uses by default. */
        const val DEFAULT_PORT = 19387
    }
}

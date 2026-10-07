package ai.deepseek.dshmobile.net

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Turns a transport failure into a Chinese sentence the user can act on.
 *
 * OkHttp's own text is accurate and completely unactionable. A dead phone on the
 * wrong network reports:
 *
 *     failed to connect to /192.168.3.103 (port 19387) from
 *     /192.168.3.104 (port 39694) after 20000ms
 *
 * which names the remote IP, the *local* ephemeral port and the millisecond
 * budget — three facts the user cannot do anything with. The same failure has
 * three genuinely different fixes (join the right Wi-Fi, start the desktop side,
 * or correct the address), and only the exception type tells them apart.
 *
 * Two rules this object exists to enforce:
 *
 *  1. **No English reaches the UI.** The fallback arm deliberately does *not*
 *     echo `Throwable.message`. OkHttp and the platform produce a long tail of
 *     wording (`Software caused connection abort`, `unexpected end of stream`,
 *     `stream was reset: CANCEL`), and every one of them would be a regression of
 *     the bug this file fixes. The original text is preserved as the *cause* so
 *     it still reaches logcat.
 *
 *  2. **The fix depends on which back end failed.** The remote path talks to the
 *     `dsh-mobile-connect` gateway over the LAN; the API path talks to an
 *     arbitrary OpenAI-compatible host on the internet; the updater talks to a
 *     manifest URL. Telling an API-mode user to enable `dsh-mobile-connect` is
 *     worse than saying nothing, so the trailing advice is a parameter.
 */
object TransportErrors {

    /** The remote path's advice: both ends are on one Wi-Fi and the plugin runs the door. */
    const val LAN_HINT =
        "请确认电脑上的 dsh-mobile-connect 仍在运行，且手机与电脑在同一个 Wi-Fi。"

    /** The API/updater path's advice: no LAN and no plugin are involved. */
    const val INTERNET_HINT = "请检查手机的网络连接，以及填写的地址是否正确。"

    /**
     * `http://192.168.3.103:19387/?t=…` → `192.168.3.103:19387`.
     *
     * Handles the `ws://` form too, because a mux socket failure carries the
     * WebSocket URL rather than the HTTP origin, and both name the same host:port.
     */
    fun display(url: String): String {
        val trimmed = url.trim()
        val withoutScheme = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
            .removePrefix("ws://")
        val hostPort = withoutScheme
            // `/api/remote.mux` and `/api/session/list` both follow the authority.
            .substringBefore("/api/")
            .substringBefore("/?")
            .substringBefore('?')
            .trimEnd('/')
        return hostPort.ifBlank { trimmed }
    }

    /**
     * A user-facing sentence for a failed request to [url].
     *
     * @param hint the trailing advice, chosen by the caller for its own back end.
     */
    fun message(url: String, t: Throwable, hint: String = LAN_HINT): String {
        val where = display(url)
        // OkHttp and the platform both wrap: a connect failure arrives as a
        // ConnectException whose cause is the real socket error, and OkHttp's own
        // RouteException is a RuntimeException rather than an IOException. Testing
        // only the outermost type therefore missed the commonest case.
        val chain = causeChain(t)
        return when {
            chain.any { it is UnknownHostException } ->
                "找不到主机 $where。请检查地址是否填写正确。$hint"

            chain.any { it is SocketTimeoutException } ->
                "连接 $where 超时。$hint"

            chain.any { it is NoRouteToHostException } ->
                "到 $where 没有可用路由。$hint"

            chain.any { it is ConnectException } ->
                "连不上 $where。$hint"

            chain.any { it is SSLException } ->
                "与 $where 的安全连接失败。$hint"

            chain.any { it is SocketException } ->
                "与 $where 的连接被中断。$hint"

            // Never `t.message`: see the class comment.
            else ->
                "无法连接 $where。$hint"
        }
    }

    /** The throwable and its causes, outermost first, bounded against a cycle. */
    private fun causeChain(t: Throwable): List<Throwable> {
        val out = mutableListOf<Throwable>()
        var current: Throwable? = t
        var guard = 0
        while (current != null && guard < 10) {
            out += current
            current = current.cause
            guard++
        }
        return out
    }
}

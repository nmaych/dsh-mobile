package ai.deepseek.dshmobile.net

import java.io.InterruptedIOException
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

            // Three different failures arrive as a timeout, and the advice is
            // different for each — see the helpers below. Collapsing them into one
            // "连接…超时" told a user whose desktop was merely slow that their Wi-Fi
            // was broken, and told a user whose socket had stalled to go check a
            // network the app was already reconnecting by itself.
            //
            // The test is the *parent* type on purpose. `SocketTimeoutException`
            // extends `InterruptedIOException`, and okio's own read timeout arrives
            // as a plain `InterruptedIOException("timeout")` — which used to fall
            // through to the catch-all "无法连接", the one arm that is definitely
            // wrong, because that socket did connect. Testing the subclass alone
            // would leave that case misreported.
            chain.any { it is InterruptedIOException } -> when {
                isHeartbeatStall(chain) ->
                    "与 $where 的连接中断了（心跳超时），正在自动重连。$hint"

                isConnectTimeout(t) -> "连接 $where 超时。$hint"

                else ->
                    "已连上 $where，但桌面端没有及时返回结果。它可能正忙，请稍后重试。$hint"
            }

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

    /**
     * True when a WebSocket's own ping/pong keep-alive expired.
     *
     * This is the "新建对话选择模型时提示连接超时" report, and the wording it
     * used to produce was actively misleading. `DshClient` sets
     * `pingInterval(20s)`, and OkHttp fails a WebSocket with a bare
     * `SocketTimeoutException("sent ping but didn't receive pong within …")`
     * when a pong does not come back in time. That message contains no
     * "connect" and no "read": it is the *only* signal that separates a stalled
     * socket from a host that cannot be reached, and the app used to render all
     * three as the same sentence.
     *
     * It fires in exactly the reported situation. Selecting a model on a fresh
     * conversation creates the session, which opens the `session/follow` mux
     * stream, and the mux is the one long-lived socket on the phone. When the
     * phone's Wi-Fi power-saves — the screen is on but the radio naps — the
     * pong is late, the socket is declared dead, and the *model* call that was
     * riding it reports a timeout. The fix for the user is not "check your
     * Wi-Fi": the stream retries by itself, and saying "连接超时" hid that.
     */
    private fun isHeartbeatStall(chain: List<Throwable>): Boolean =
        chain.any { it is SocketTimeoutException && it.message?.contains("pong") == true }

    /**
     * True when the TCP connect itself expired, which is the one timeout that
     * really is a network problem.
     *
     * Only this case justifies sending the user to check their Wi-Fi. It is also
     * the only failure the app may retry, so the answer has to be *provable*
     * rather than merely plausible.
     *
     * The wording is platform-specific, and the two platforms disagree:
     *
     *  - **Android** never says `connect timed out`. `IoBridge` hardcodes the
     *    long form, which names the remote address, the *local* ephemeral port
     *    and the millisecond budget:
     *
     *        failed to connect to /192.168.3.103 (port 19387) from
     *        /192.168.3.104 (port 39694) after 20000ms
     *
     *  - **OpenJDK** says `Connect timed out` (capital C on JDK 17, lowercase on
     *    JDK 8) — which is why the match below is case-insensitive. It matters
     *    on the JVM and in tests, not on the phone.
     *
     * OkHttp does not rewrite either one: `RealConnection` wraps only
     * `ConnectException`, so a connect *timeout* passes through verbatim.
     *
     * Both wordings are matched, instead of the bare substring `connect`, so the
     * test is anchored to the two real producers. A loose substring also matched
     * any future message that merely mentioned connecting, and this predicate
     * gates a retry — a false positive there re-sends a request the desktop may
     * already have acted on.
     *
     * Public because [ai.deepseek.dshmobile.data.DshClient] also uses it to decide
     * whether a request may be retried, and the two must agree: the app may only
     * repeat a request it has proven never reached the desktop. Deriving that
     * answer twice is how a retry eventually double-applies a prompt.
     */
    fun isConnectTimeout(t: Throwable): Boolean =
        causeChain(t).any { cause ->
            cause is SocketTimeoutException &&
                CONNECT_TIMEOUT_WORDINGS.any { wording ->
                    cause.message?.contains(wording, ignoreCase = true) == true
                }
        }

    /**
     * The two wordings a real TCP connect timeout carries.
     *
     * Kept as data so the classifier and the test that guards it name the same
     * strings. Neither appears in the heartbeat message
     * (`sent ping but didn't receive pong within …`), which is the other
     * `SocketTimeoutException` this app can see, so the two never collide.
     */
    private val CONNECT_TIMEOUT_WORDINGS = listOf(
        // Android: IoBridge.createMessageForException
        "failed to connect to",
        // OpenJDK: Socket.connect(endpoint, timeout)
        "connect timed out",
    )

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

package ai.deepseek.dshmobile.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import ai.deepseek.dshmobile.BuildConfig
import ai.deepseek.dshmobile.net.TransportErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One published release, as described by the remote manifest. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
    val mandatory: Boolean,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val percent: Int, val received: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val file: File, val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update.
 *
 * The app polls a small JSON manifest. When it advertises a higher
 * `versionCode`, the APK is downloaded, its SHA-256 verified, and the system
 * package installer is invoked through a FileProvider URI.
 *
 * Manifest schema (see docs/UPDATE.md):
 * ```json
 * {
 *   "versionCode": 2,
 *   "versionName": "1.1.0",
 *   "apkUrl": "https://example.com/dsh-mobile-1.1.0.apk",
 *   "sha256": "<hex>",
 *   "notes": "…",
 *   "mandatory": false
 * }
 * ```
 */
class UpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** Fetch and parse the manifest; null when the app is already current. */
    suspend fun check(manifestUrl: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").get().build()
        // A failed connect would otherwise reach the settings screen as OkHttp's
        // own "failed to connect to /… (port …) … after 20000ms".
        val resp = try {
            client.newCall(req).execute()
        } catch (t: Throwable) {
            throw RuntimeException(
                TransportErrors.message(manifestUrl, t, TransportErrors.INTERNET_HINT)
            )
        }
        resp.use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RuntimeException("检查更新失败：HTTP ${response.code}")
            val o = JSONObject(text)
            val code = o.optInt("versionCode", 0)
            val info = UpdateInfo(
                versionCode = code,
                versionName = o.optString("versionName", code.toString()),
                apkUrl = o.optString("apkUrl"),
                sha256 = o.optString("sha256").lowercase(),
                notes = o.optString("notes"),
                mandatory = o.optBoolean("mandatory", false),
            )
            if (code > currentVersionCode && info.apkUrl.isNotBlank()) info else null
        }
    }

    /**
     * Download the APK, reporting progress.
     *
     * @return the verified file, ready to hand to the package installer.
     */
    fun download(info: UpdateInfo): Flow<UpdateState> = flow {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        val target = File(dir, "dsh-mobile-${info.versionName}.apk")
        val partial = File(dir, "${target.name}.part")

        val req = Request.Builder().url(info.apkUrl).get().build()
        val resp = try {
            client.newCall(req).execute()
        } catch (t: Throwable) {
            emit(
                UpdateState.Failed(
                    TransportErrors.message(info.apkUrl, t, TransportErrors.INTERNET_HINT)
                )
            )
            return@flow
        }
        resp.use { response ->
            if (!response.isSuccessful) {
                emit(UpdateState.Failed("下载失败：HTTP ${response.code}"))
                return@flow
            }
            val body = response.body ?: run {
                emit(UpdateState.Failed("下载失败：响应为空"))
                return@flow
            }
            val total = body.contentLength()

            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        received += n
                        val percent =
                            if (total > 0) ((received * 100) / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            emit(UpdateState.Downloading(percent, received, total))
                        }
                    }
                }
            }
        }

        // Verify before the file is allowed anywhere near the installer.
        if (info.sha256.isNotBlank()) {
            val actual = sha256Of(partial)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                partial.delete()
                emit(UpdateState.Failed("校验失败：期望 ${info.sha256.take(16)}…，实际 ${actual.take(16)}…"))
                return@flow
            }
        }

        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            emit(UpdateState.Failed("无法保存安装包"))
            return@flow
        }
        emit(UpdateState.ReadyToInstall(target, info))
    }.flowOn(Dispatchers.IO)

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when this install is allowed to launch the package installer. */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Open the "install unknown apps" screen for this app. */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Hand a verified APK to the system installer. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

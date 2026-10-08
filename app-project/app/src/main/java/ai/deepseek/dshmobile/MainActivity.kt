package ai.deepseek.dshmobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.deepseek.dshmobile.ui.AppShell
import ai.deepseek.dshmobile.ui.ChatViewModel
import ai.deepseek.dshmobile.ui.UpdateStateHolder
import ai.deepseek.dshmobile.ui.theme.DshTheme
import ai.deepseek.dshmobile.update.UpdateInfo
import ai.deepseek.dshmobile.update.UpdateManager
import ai.deepseek.dshmobile.update.UpdateState
import kotlinx.coroutines.launch
import java.io.File

/**
 * The pairing link the desktop embeds in its QR code:
 * `dshmobile://pair?host=192.168.1.5&port=19387&code=021088`.
 */
private const val PAIR_SCHEME = "dshmobile"
private const val PAIR_HOST = "pair"

class MainActivity : ComponentActivity() {

    /**
     * Set when a pairing link arrives, cleared once the UI has consumed it.
     *
     * Held in the Activity rather than read from `intent` during composition
     * because a warm app receives links through [onNewIntent], and because
     * `intent.data` survives configuration changes — reading it directly would
     * re-run pairing on every rotation.
     */
    private var pendingPairLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars and let the app bar fill the status bar
        // strip. Before this the window kept the default insets, and the strip the
        // system bar sits in was painted by the platform — from a hardcoded dark
        // `statusBarColor`, so a light-mode phone showed a near-black band above a
        // white app bar (the 1.1.4 "屏幕上方有黑边" report). `enableEdgeToEdge`
        // also derives the bar icon appearance from the theme, so the icons stay
        // legible when the user switches light/dark.
        enableEdgeToEdge()
        pendingPairLink = pairLinkFrom(intent)
        setContent {
            DshTheme {
                DshRoot(
                    pendingPairLink = pendingPairLink,
                    onPairLinkConsumed = { pendingPairLink = null },
                    // A scanned QR is routed through the *same* `pendingPairLink`
                    // slot a deep link uses, so there is exactly one path from "we
                    // have a pairing link" to "we are connected". Scanning in-app
                    // and scanning with the system camera therefore cannot drift:
                    // both end in `ChatViewModel.pairFromLink`.
                    onPairLink = { pendingPairLink = it },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairLinkFrom(intent)?.let { pendingPairLink = it }
    }

    /** Extract a pairing URL, or null when this Intent is not one. */
    private fun pairLinkFrom(intent: Intent?): String? {
        val uri: Uri = intent?.data ?: return null
        if (intent.action != Intent.ACTION_VIEW) return null
        if (!uri.scheme.equals(PAIR_SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(PAIR_HOST, ignoreCase = true)) return null
        return uri.toString()
    }
}

@Composable
private fun DshRoot(
    pendingPairLink: String? = null,
    onPairLinkConsumed: () -> Unit = {},
    onPairLink: (String) -> Unit = {},
) {
    val vm: ChatViewModel = viewModel()
    val state by vm.state.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember { UpdateManager(context.applicationContext) }
    val updateHolder = remember { UpdateStateHolder(UpdateState.Idle) }
    val scope = rememberCoroutineScope()

    // A scanned pairing QR arrives as a pairing link. Pairing starts the moment
    // it is seen, so scanning the code on the desktop's terminal is the whole
    // setup — no copying an address, no typing a code.
    LaunchedEffect(pendingPairLink) {
        val link = pendingPairLink ?: return@LaunchedEffect
        vm.pairFromLink(link)
        onPairLinkConsumed()
    }

    // Ask about updates once per launch, quietly.
    LaunchedEffect(Unit) {
        runCatching {
            val info = updater.check(vm.updateManifestUrl)
            if (info != null) updateHolder.value = UpdateState.Available(info)
        }
    }

    // Reconnect to the desktop the user already paired with, so opening the app
    // is enough — no tapping through settings every time.
    LaunchedEffect(Unit) { vm.autoConnect() }

    AppShell(
        state = state,
        serverUrl = vm.serverUrl,
        apiBaseUrl = vm.apiBaseUrl,
        apiKey = vm.apiKey,
        apiSystemPrompt = vm.apiSystemPrompt,
        updateManifestUrl = vm.updateManifestUrl,
        updateState = updateHolder,
        appVersion = "${updater.currentVersionName} (${updater.currentVersionCode})",
        onSend = vm::send,
        onStop = vm::stop,
        onNewSession = vm::createSession,
        onOpenSession = vm::openSession,
        onRefresh = vm::refreshSessions,
        onDismissError = vm::clearError,
        onBackendChange = vm::setBackend,
        onPair = vm::pair,
        onPairWithCode = vm::pairWithCode,
        onDiscover = vm::discoverGateways,
        onPickServer = vm::setServerAddress,
        onPairLink = onPairLink,
        onUnpair = vm::unpair,
        onReconnect = vm::reconnect,
        onApiConfigChange = { base, key, model, sys -> vm.setApiConfig(base, key, model, sys) },
        onLoadModels = vm::loadModels,
        onSelectModel = vm::selectModel,
        onLoadRemoteModels = vm::loadRemoteModels,
        onSelectRemoteModel = { provider, model, effort ->
            vm.selectRemoteModel(provider, model, effort)
        },
        onRefreshWorkspaces = vm::refreshWorkspaces,
        onSelectWorkspace = vm::selectWorkspace,
        onAddWorkspace = vm::addWorkspace,
        onUpdateManifestChange = vm::setUpdateManifestUrl,
        onCheckUpdate = {
            scope.launch {
                updateHolder.value = UpdateState.Checking
                runCatching { updater.check(vm.updateManifestUrl) }
                    .onSuccess { info ->
                        updateHolder.value = if (info == null) {
                            UpdateState.UpToDate
                        } else {
                            UpdateState.Available(info)
                        }
                    }
                    .onFailure {
                        updateHolder.value = UpdateState.Failed(it.message ?: "检查更新失败")
                    }
            }
        },
        onInstallUpdate = {
            // Hand a verified APK to the system installer.
            //
            // The permission is checked *here*, immediately before the hand-off,
            // rather than when the download started: the user may have gone to the
            // system screen and granted it in the meantime, in which case this
            // installs straight away and no extra tap is needed. The state is only
            // moved to `InstallPermissionRequired` on a genuine refusal, and that
            // state carries the already-verified file, so a retry never downloads
            // the APK a second time.
            fun installVerified(file: File, info: UpdateInfo) {
                if (!updater.canInstallPackages()) {
                    updateHolder.value = UpdateState.InstallPermissionRequired(file, info)
                    updater.requestInstallPermission()
                    return
                }
                // A device with no package-installer activity at all throws here
                // rather than returning, so a refusal has to be reported — the
                // alternative is a button that looks dead.
                if (!updater.install(file)) {
                    updateHolder.value = UpdateState.Failed(
                        "无法启动系统安装器，请手动打开已下载的安装包。"
                    )
                }
            }

            when (val current = updateHolder.value) {
                // "下载并安装". This branch previously had no button at all, which
                // left `UpdateManager.download` unreachable from the UI: checking
                // for updates could report a new version but never fetch it.
                is UpdateState.Available -> {
                    val existing = updater.downloadedFile(current.info)
                    if (existing != null) {
                        // Verified on an earlier attempt (typically one that stopped
                        // at the permission prompt); reuse it instead of spending
                        // another 12 MB.
                        installVerified(existing, current.info)
                    } else {
                        scope.launch {
                            updater.download(current.info).collect { updateHolder.value = it }
                        }
                    }
                }
                is UpdateState.InstallPermissionRequired -> installVerified(current.file, current.info)
                is UpdateState.ReadyToInstall -> installVerified(current.file, current.info)
                else -> Unit
            }
        },
    )
}

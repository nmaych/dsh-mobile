package ai.deepseek.dshmobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import ai.deepseek.dshmobile.update.UpdateManager
import ai.deepseek.dshmobile.update.UpdateState
import kotlinx.coroutines.launch

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
        pendingPairLink = pairLinkFrom(intent)
        setContent {
            DshTheme {
                DshRoot(
                    pendingPairLink = pendingPairLink,
                    onPairLinkConsumed = { pendingPairLink = null },
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
            val current = updateHolder.value
            when (current) {
                is UpdateState.Available -> {
                    if (!updater.canInstallPackages()) {
                        updater.requestInstallPermission()
                        updateHolder.value = UpdateState.Failed(
                            "请先允许本应用安装未知来源应用，然后再次点击安装。"
                        )
                    } else {
                        scope.launch {
                            updater.download(current.info).collect { updateHolder.value = it }
                        }
                    }
                }
                is UpdateState.ReadyToInstall -> updater.install(current.file)
                else -> Unit
            }
        },
    )
}

package ai.deepseek.dshmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.deepseek.dshmobile.ui.AppShell
import ai.deepseek.dshmobile.ui.ChatViewModel
import ai.deepseek.dshmobile.ui.UpdateStateHolder
import ai.deepseek.dshmobile.ui.theme.DshTheme
import ai.deepseek.dshmobile.update.UpdateManager
import ai.deepseek.dshmobile.update.UpdateState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DshTheme {
                DshRoot()
            }
        }
    }
}

@Composable
private fun DshRoot() {
    val vm: ChatViewModel = viewModel()
    val state by vm.state.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember { UpdateManager(context.applicationContext) }
    val updateHolder = remember { UpdateStateHolder(UpdateState.Idle) }
    val scope = rememberCoroutineScope()

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

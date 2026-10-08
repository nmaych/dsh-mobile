package ai.deepseek.dshmobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.deepseek.dshmobile.data.Backend
import ai.deepseek.dshmobile.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: ChatUiState,
    serverUrl: String,
    apiBaseUrl: String,
    apiKey: String,
    apiSystemPrompt: String,
    updateManifestUrl: String,
    updateState: UpdateState,
    appVersion: String,
    onBack: () -> Unit,
    onBackendChange: (Backend) -> Unit,
    onPair: (String) -> Unit,
    onPairWithCode: (String, String) -> Unit,
    onDiscover: () -> Unit,
    onPickServer: (String) -> Unit,
    /**
     * Pair from a scanned `dshmobile://pair?…` link.
     *
     * Separate from [onPair], which takes the `dsh web` URL and needs a `?token=`.
     * The two are different paths — one goes through the plugin's gateway, the
     * other straight at the Harness — and a scanned pairing QR belongs to the
     * first. Routing a scanned link into [onPair] would fail on the missing token.
     */
    onPairLink: (String) -> Unit,
    onUnpair: () -> Unit,
    onReconnect: () -> Unit,
    onApiConfigChange: (String, String, String, String) -> Unit,
    onLoadModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onUpdateManifestChange: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    var directInput by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(state.discovered.firstOrNull()?.baseUrl ?: serverUrl) }
    var code by remember { mutableStateOf("") }
    var apiBase by remember { mutableStateOf(apiBaseUrl) }
    var apiKeyText by remember { mutableStateOf(apiKey) }
    var apiModel by remember { mutableStateOf(state.selectedModel) }
    var apiSystem by remember { mutableStateOf(apiSystemPrompt) }
    var manifest by remember { mutableStateOf(updateManifestUrl) }

    // Hoisted above the backend branches: `rememberLauncherForActivityResult`
    // registers with the Activity's result registry, and doing that inside a
    // conditional composable makes the registration come and go with the branch.
    val scanQr = rememberQrScanner { onPairLink(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------------------------------------------------- mode
            SectionCard("运行模式") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.backend == Backend.REMOTE,
                        onClick = { onBackendChange(Backend.REMOTE) },
                        label = { Text("远程控制桌面端", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = state.backend == Backend.API,
                        onClick = { onBackendChange(Backend.API) },
                        label = { Text("独立 API 对话", fontSize = 12.sp) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.backend == Backend.REMOTE) {
                        "通过桌面端 `dsh web` 的 HTTP/WebSocket 接口操控它：读取会话、发送消息、停止生成。"
                    } else {
                        "不依赖桌面端，直接调用 OpenAI 兼容接口进行对话。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
            }

            // -------------------------------------------------------- pairing
            if (state.backend == Backend.REMOTE) {
                SectionCard("连接桌面端") {
                    if (state.connected && state.viaGateway) {
                        // Already paired: state it plainly, then offer the exit.
                        Text(
                            "已连接",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            serverUrl,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "下次打开会自动连接。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onUnpair) {
                            Text("断开连接", fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "断开后需要重新输入配对码。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "先在电脑上装好 dsh-mobile-connect 插件。启动后桌面端会显示一个配对码。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 17.sp,
                        )
                        Spacer(Modifier.height(10.dp))

                        // --- scan it ----------------------------------------
                        // First and widest, because it is the only one of the
                        // three ways in that needs nothing typed: the desktop is
                        // already showing a QR with the address *and* the code.
                        Button(
                            onClick = scanQr,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.connecting,
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("扫描二维码连接", fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "对着电脑上 dsh-mobile-connect 显示的二维码扫一下，地址和配对码都会自动填好。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )

                        Spacer(Modifier.height(12.dp))

                        // A failure has to be shown *here*, not only on the chat
                        // screen. Settings is a separate screen from the one that
                        // renders `state.error`, so a scan that failed — the wrong
                        // QR, a bad code, an unreachable desktop — dropped the user
                        // back on this card with no explanation at all, which reads
                        // as "the button does nothing".
                        //
                        // Only the error is shown, not `state.info`: that field also
                        // carries unrelated notices ("已添加工作区…"), and a pairing
                        // card is the wrong place to report them. Success needs no
                        // line here either — a successful pair flips this card to
                        // the connected state below.
                        state.error?.let { message ->
                            Text(
                                message,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                                lineHeight = 17.sp,
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        // --- find it for me ---------------------------------
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = onDiscover, enabled = !state.searching) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.searching) "搜索中…" else "搜索电脑", fontSize = 13.sp)
                            }
                            if (state.searching) {
                                Spacer(Modifier.width(10.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }

                        if (state.discovered.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            for (server in state.discovered) {
                                Surface(
                                    onClick = {
                                        // Fill the field as well as storing the
                                        // choice. Picking a desktop is exactly
                                        // "use this address", and the address
                                        // box is what the 连接 button reads, so
                                        // a tap that only stored the value left
                                        // the button disabled on a blank field.
                                        address = server.baseUrl
                                        onPickServer(server.baseUrl)
                                    },
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(server.name, fontSize = 13.sp)
                                            Text(
                                                server.baseUrl,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            "选择",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // --- or type it -------------------------------------
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("连接地址", fontSize = 12.sp) },
                            placeholder = { Text("192.168.1.5:19387", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { input ->
                                // Digits only, so a paste with spaces still works.
                                code = input.filter { it.isDigit() }.take(6)
                            },
                            label = { Text("配对码", fontSize = 12.sp) },
                            placeholder = { Text("桌面端显示的 6 位数字", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                letterSpacing = 4.sp,
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { onPairWithCode(address, code) },
                            enabled = address.isNotBlank() && code.length == 6 && !state.connecting,
                        ) {
                            Text(if (state.connecting) "连接中…" else "连接", fontSize = 13.sp)
                        }

                        if (serverUrl.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "上次连接：$serverUrl",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = onReconnect, enabled = !state.connecting) {
                                Text("重试连接", fontSize = 13.sp)
                            }
                        }
                    }

                    // --- advanced: the direct path, no plugin ------------------
                    Spacer(Modifier.height(12.dp))
                    var showDirect by remember { mutableStateOf(false) }
                    Text(
                        if (showDirect) "收起高级连接方式" else "高级：不装插件，直接粘贴链接",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showDirect = !showDirect },
                    )
                    if (showDirect) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "适用于已经用端口转发或隧道打通的情况。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = directInput,
                            onValueChange = { directInput = it },
                            label = { Text("dsh web 打印的完整链接", fontSize = 12.sp) },
                            placeholder = {
                                Text("http://192.168.1.5:19387/?token=…", fontSize = 12.sp)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onPair(directInput) },
                            enabled = directInput.isNotBlank() && !state.connecting,
                        ) {
                            Text("用链接配对", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ------------------------------------------------------------ api
            if (state.backend == Backend.API) {
                SectionCard("API 配置") {
                    OutlinedTextField(
                        value = apiBase,
                        onValueChange = { apiBase = it },
                        label = { Text("Base URL", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = { apiKeyText = it },
                        label = { Text("API Key", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = apiModel,
                            onValueChange = { apiModel = it },
                            label = { Text("模型", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        ModelPicker(
                            models = state.models,
                            onLoad = onLoadModels,
                            onPick = {
                                apiModel = it
                                onSelectModel(it)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiSystem,
                        onValueChange = { apiSystem = it },
                        label = { Text("系统提示词（可选）", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        onApiConfigChange(apiBase, apiKeyText, apiModel, apiSystem)
                    }) {
                        Text("保存", fontSize = 13.sp)
                    }
                }
            }

            // --------------------------------------------------------- update
            SectionCard("应用更新") {
                InfoRow("当前版本", appVersion)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manifest,
                    onValueChange = {
                        manifest = it
                        onUpdateManifestChange(it)
                    },
                    label = { Text("更新清单地址", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    minLines = 2,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onCheckUpdate,
                        enabled = updateState !is UpdateState.Checking &&
                            updateState !is UpdateState.Downloading,
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("检查更新", fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                when (val u = updateState) {
                    is UpdateState.Idle -> Text(
                        "点击“检查更新”从清单地址获取最新版本。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is UpdateState.Checking -> Text(
                        "正在检查…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is UpdateState.UpToDate -> Text(
                        "已是最新版本。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is UpdateState.Available -> Column {
                        Text(
                            "发现新版本 ${u.info.versionName}（${u.info.versionCode}）",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (u.info.notes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                u.info.notes,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                    is UpdateState.Downloading -> Column {
                        Text(
                            "下载中 ${u.percent}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { u.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is UpdateState.ReadyToInstall -> Column {
                        Text(
                            "已下载并校验通过：${u.info.versionName}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onInstallUpdate) {
                            Text("立即安装", fontSize = 13.sp)
                        }
                    }
                    is UpdateState.Failed -> Text(
                        u.message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // -------------------------------------------------------- project
            SectionCard("关于本项目") {
                val context = androidx.compose.ui.platform.LocalContext.current
                Surface(
                    onClick = { openUrl(context, PROJECT_URL) },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("GitHub 项目地址", fontSize = 12.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                PROJECT_URL,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "在浏览器中打开",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "源码、问题反馈与历史版本都在这里。应用内更新也默认从这个仓库的 Release 读取。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }

            Text(
                "DSH Mobile · 非官方客户端\nDeepSeek Harness 是 DeepSeek 的产品，本应用与其无隶属关系。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }
}

/**
 * The project's source repository.
 *
 * It is shown as a real, tappable link rather than plain text: this app is a
 * non-official client of someone else's product, so "where did this come from,
 * and what is in it" is the first thing a cautious user wants to check. A URL
 * they would have to retype by hand does not answer that.
 */
private const val PROJECT_URL = "https://github.com/nmaych/dsh-mobile"

/**
 * Open [url] in whatever the user has for web pages.
 *
 * `FLAG_ACTIVITY_NEW_TASK` is required because the caller may not be an
 * Activity context. `runCatching` guards the no-browser case: a device with no
 * handler at all would otherwise crash on a settings tap.
 */
private fun openUrl(context: android.content.Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            content()
        }
    }
}

@Composable
private fun CodeLine(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun ModelPicker(
    models: List<String>,
    onLoad: () -> Unit,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = {
            open = true
            if (models.isEmpty()) onLoad()
        }) {
            Text("拉取", fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("没有可用的模型列表", fontSize = 12.sp) },
                    onClick = { open = false },
                )
            }
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m, fontSize = 12.sp) },
                    onClick = {
                        onPick(m)
                        open = false
                    },
                )
            }
        }
    }
}

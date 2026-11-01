@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lmj.labyrinthauto.ui

import android.content.Context
import com.lmj.labyrinthauto.logic.EventEntry
import com.lmj.labyrinthauto.logic.EventPick
import com.lmj.labyrinthauto.logic.EventSettings
import com.lmj.labyrinthauto.data.GameData
import com.lmj.labyrinthauto.data.GuildPool
import com.lmj.labyrinthauto.data.PoolForm
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import com.lmj.labyrinthauto.core.Accounts
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.DiagPack
import com.lmj.labyrinthauto.core.LogFile
import com.lmj.labyrinthauto.core.UpdateChecker
import com.lmj.labyrinthauto.logic.team.TeamRunLog
import com.lmj.labyrinthauto.frida.FridaServerInstaller
import com.lmj.labyrinthauto.frida.RootCheck
import com.lmj.labyrinthauto.net.bilibili.CaptchaChallenge
import com.lmj.labyrinthauto.net.bilibili.GameServer
import com.lmj.labyrinthauto.net.bilibili.GeetestPageBuilder
import com.lmj.labyrinthauto.reroll.RerollConfig
import com.lmj.labyrinthauto.reroll.RerollSettings
import com.lmj.labyrinthauto.reroll.RerollUseCase
import com.lmj.labyrinthauto.service.AutoForegroundService
import com.lmj.labyrinthauto.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 助手的唯一界面（2026-10-17 重排 → 当天按用户意见分三页）。
 *
 * | 标签页 | 放什么 |
 * |---|---|
 * | **自动迷宫** | 权限（root / 悬浮窗 / 通知）＋ 运行次数 ＋ 运行进度；**底部主操作只在这一页** |
 * | **刷开局** | 账号（折叠）＋ 刷开局按钮在底部拇指区 ＋ 高级设置（折叠，尽量做成选项） |
 * | **策略** | "具体怎么决策"的开关（遗物怎么选、商店买什么、选人规则…）—— **大多还没做**，先占位 |
 *
 * 日志默认只露一条 peek，点开才是面板（三页都有）。
 * 调试用入口（单独注入 / 只走导航 / 只跑迷宫 / PROBE 对拍）已从界面移除，需要时走 adb 动作。
 */
@Composable
fun HomeScreen(
    darkOn: Boolean,
    onToggleDark: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by AppState.running.collectAsStateWithLifecycle()
    val rootOk by AppState.rootOk.collectAsStateWithLifecycle()
    val log by AppState.log.collectAsStateWithLifecycle()
    val mazeProgress by AppState.mazeProgress.collectAsStateWithLifecycle()
    val overlayGranted by AppState.overlayGranted.collectAsStateWithLifecycle()
    val rerollBusy by AppState.rerollBusy.collectAsStateWithLifecycle()

    var mazeRuns by remember { mutableStateOf(RerollSettings.loadMazeRuns(context).toString()) }
    var overlayOn by remember { mutableStateOf(RerollSettings.loadOverlayOn(context)) }
    var failStrategy by remember { mutableStateOf(RerollSettings.loadFailStrategy(context)) }
    // 账号/密码提到首页层：刷开局主按钮挪到「拇指区」底部栏，需要在这里拿到。
    // 2026-10-28：改成多账号可切换（Accounts 表），这里只保存"当前选中账号"的凭证；
    //   切换/编辑账号由 [AccountsCard] 回填，Accounts 同时把凭证镜像给旧的刷开局链路。
    val initAccount = remember { Accounts.selected(context) }
    var account by remember { mutableStateOf(initAccount?.loginId.orEmpty()) }
    var password by remember { mutableStateOf(initAccount?.secret.orEmpty()) }
    var accountServer by remember { mutableStateOf(initAccount?.server ?: GameServer.CN_BILIBILI) }
    var logOpen by remember { mutableStateOf(false) }
    // ★反馈（2026-11-01 用户）：右上角入口，填昵称 + 内容 + 可选诊断包 → 直接开 Gitee issue
    var feedbackOpen by remember { mutableStateOf(false) }
    // ★「更新记录」已并进说明弹窗的第二个标签页（2026-11-01 用户：不要塞在长文底部）
    // ★自动更新（2026-10-25）：**检查动作在 `LmjApp` 启动时做**（和运行组件预热一起），
    //   这里只读结果、点亮「版本•」。debug 包不参与自更新（签名与发布包不同，装了会冲突）。
    val updInfo by AppState.updateInfo.collectAsStateWithLifecycle()
    val compStatus by AppState.component.collectAsStateWithLifecycle()
    var updateOpen by remember { mutableStateOf(false) }
    val newVerCode = remember(updInfo) {
        updInfo?.versionCode?.takeIf { it != UpdateChecker.skipVersionCode(context) }
    }
    // ★运行组件状态（2026-10-26 用户方案）：首次要备 42MB 的 frida-server，不是错误、不该弹窗打断 ——
    //   在标题下面挂一条常驻细条，下载中给进度，移动网络/失败给可点入口，就绪后整条消失。
    val compDownloading = compStatus?.startsWith("运行组件下载中") == true
    // ★上次进程是否突然消失（CrashGuard 判定，启动时写一次）→ 首页横幅，点「知道了」清掉
    val abnormalExit by AppState.abnormalExit.collectAsStateWithLifecycle()
    val compPct = compStatus?.let { s ->
        Regex("(\\d+)%").find(s)?.groupValues?.get(1)?.toIntOrNull()
    }
    // ★刷开局方式（接口 / 局内）提到首页层：底部「刷开局」按钮要按它决定走哪条路
    var rerollMode by remember { mutableStateOf(RerollSettings.loadRerollMode(context)) }
    // ★悬浮窗可拖动提到首页层（2026-10-20 用户：强制锁定后 UI 开关也要跟着关）
    var overlayDraggable by remember { mutableStateOf(RerollSettings.loadOverlayDraggable(context)) }
    // ★首次使用说明：状态 + 首次进首页自动弹出（标记持久化在 RerollSettings）
    var guideOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!RerollSettings.loadGuideShown(context)) guideOpen = true
    }
    // ★有新版就**自动弹窗**（2026-10-26 用户："能不能开启应用就检查，并且自动弹出更新窗口"）：
    //   检查动作在 LmjApp 启动时做，结果落在 AppState.updateInfo，这里一看到"有新版且没被忽略"
    //   就把对话框弹出来，不用用户再去点「版本•」。
    //   ★首次使用说明优先：guideOpen 开着时先别叠第二个弹窗（用户看完说明关掉，这里会再触发一次）。
    LaunchedEffect(newVerCode, guideOpen) {
        if (newVerCode != null && !guideOpen) updateOpen = true
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // 类型要显式写：lambda 体最后一个表达式是 `scope.launch {}`（返回 Job），
    // 让编译器自己推 `() -> Unit` 会触发 "unit conversions on arbitrary expressions" 实验特性 ✗
    val onRequestRoot: () -> Unit = { scope.launch { RootCheck.run("手动检查") } }
    val onRequestOverlay: () -> Unit = {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    LaunchedEffect(overlayGranted, overlayOn) {
        if (overlayGranted == true && overlayOn) OverlayWindow.show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("黎明界自动化", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        // ★反馈（2026-11-01）：填昵称/内容/可选诊断包，直接开 issue
                        TextButton(onClick = { feedbackOpen = true }) { Text("反馈") }
                        // ★「版本」并进「说明」（2026-11-01 用户）：顶栏只留这一个入口，版本/更新记录在弹窗里
                        TextButton(onClick = { guideOpen = true }) { Text("说明") }
                        TextButton(onClick = { logOpen = !logOpen }) { Text(if (logOpen) "日志▾" else "日志▴") }
                        TextButton(onClick = { onToggleDark(!darkOn) }) { Text(if (darkOn) "浅色" else "深色") }
                    },
                )
                // ★运行组件状态条（替代原来标题下那行小字）：就绪后整条消失，未就绪时可点
                ComponentBar(compStatus) {
                    scope.launch {
                        runCatching { FridaServerInstaller.prepare(context, wifiOnly = false) }
                    }
                }
                // ★上次异常中断的横幅（2026-10-26）：进程被杀后用户只看到"什么都没了"，给一句话
                abnormalExit?.let {
                    AbnormalExitBar(it) { AppState.setAbnormalExit(null) }
                }
            }
        },
        // ★主操作放在底部「拇指区」：自动迷宫页 = 一键黎明界 + 停止；刷开局页 = 登录并刷开局
        bottomBar = {
            when (tab) {
                0 -> Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            // ★出发前校验（2026-10-18 用户："一键黎明界启动的时候还需要检查一下选项，
                            //   开局角色选择必须每个公会都选了3人"）
                            val missingGuilds = guildPicksIncomplete(context)
                            // ★刷开局方式=接口时才需要账号（2026-10-19 用户确认：免账号模式两个启动按钮都不查账号）
                            val needAccount =
                                RerollSettings.loadRerollMode(context) == RerollSettings.REROLL_API
                            when {
                                bossWhitelistEmpty(context) ->
                                    Toast.makeText(context, "3 区 / 5 区各至少选一个 Boss", Toast.LENGTH_LONG).show()
                                missingGuilds.isNotEmpty() ->
                                    Toast.makeText(
                                        context,
                                        "开局角色没选满 3 人：${missingGuilds.joinToString("、")}" +
                                            "（去「策略 → 开局角色选择」补上）",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                // ★渠道服走的是 uid / access_key 直通（2026-10-29 接通），
                                //   所以这里不再按服务器拦截，只看"凭证齐不齐"。
                                needAccount && (account.isBlank() || password.isBlank()) ->
                                    Toast.makeText(
                                        context,
                                        if (accountServer.isChannelServer) {
                                            "渠道服需要 uid / access_key（去「刷开局 → 账号 → 编辑 → 抓取」）"
                                        } else {
                                            "接口刷开局需要账号密码（去「刷开局」页填写，或把刷开局方式改成局内）"
                                        },
                                        Toast.LENGTH_LONG,
                                    ).show()
                                else -> {
                                    // ★先落盘当前「账号」卡里填的凭证，再跑：否则一键黎明界会读到空的旧凭证
                                    //   （旧设备因反复测试早已存过、没问题；新设备没存过才暴露）
                                    forceOverlayDragOff(context) { overlayDraggable = it }
                                    RerollSettings.saveCredentials(context, account, password)
                                    AutoForegroundService.send(context, AutoForegroundService.ACTION_RUN_FULL)
                                }
                            }
                        },
                        // ★组件还在下（首次约 42MB）时按钮自己说清在等什么，别让它看起来"点了没反应"
                        enabled = mazeProgress == null && !compDownloading,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            when {
                                mazeProgress != null -> "运行中…"
                                compDownloading -> "准备组件 ${compPct ?: 0}%…"
                                else -> "一键黎明界"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { AutoForegroundService.send(context, AutoForegroundService.ACTION_STOP) },
                        enabled = running,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("停止") }
                }
                1 -> Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Button(
                        onClick = {
                            if (bossWhitelistEmpty(context)) {
                                Toast.makeText(context, "3 区 / 5 区各至少选一个 Boss", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            // ★按「刷开局方式」分流（2026-10-19 用户：这个按钮原来只认接口方式）：
                            //   局内 → 交给服务在后台跑（要注入/切前台/自愈，可能好几分钟，不能占着 UI 协程）；
                            //   接口 → 照旧（要账号密码、可能弹验证码）。
                            if (rerollMode == RerollSettings.REROLL_IN_GAME) {
                                forceOverlayDragOff(context) { overlayDraggable = it }
                                AutoForegroundService.send(
                                    context,
                                    AutoForegroundService.ACTION_REROLL_ONLY,
                                )
                            } else {
                                // B 服（账密 → SDK）与渠道服（uid/access_key 直通）都走这一条：
                                // 具体用哪套由 LabyrinthLoginClient 按【选中账号】的服务器决定。
                                forceOverlayDragOff(context) { overlayDraggable = it }
                                scope.launch { RerollUseCase.login(context) }
                            }
                        },
                        enabled = !rerollBusy &&
                            (rerollMode == RerollSettings.REROLL_IN_GAME ||
                                (account.isNotBlank() && password.isNotBlank())),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            when {
                                rerollBusy -> "进行中…"
                                rerollMode == RerollSettings.REROLL_IN_GAME -> "局内刷开局"
                                else -> "登录并刷开局"
                            },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("自动迷宫") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("刷开局") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("策略") })
            }
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> MazeTab(
                        rootOk = rootOk,
                        onRequestRoot = onRequestRoot,
                        overlayGranted = overlayGranted,
                        overlayOn = overlayOn,
                        onOverlayToggle = { on ->
                            overlayOn = on
                            RerollSettings.saveOverlayOn(context, on)
                            if (on) OverlayWindow.show() else OverlayWindow.hide()
                        },
                        onRequestOverlay = onRequestOverlay,
                        mazeRuns = mazeRuns,
                        onMazeRuns = {
                            mazeRuns = it
                            RerollSettings.saveMazeRuns(context, mazeRuns.toIntOrNull() ?: 0)
                        },
                        mazeProgress = mazeProgress,
                        failStrategy = failStrategy,
                        onFailStrategy = { v ->
                            failStrategy = v
                            RerollSettings.saveFailStrategy(context, v)
                        },
                        // ★悬浮窗可拖动（状态提首页层：强制锁定时 UI 开关也要跟着关）
                        overlayDraggable = overlayDraggable,
                        onOverlayDraggable = { v ->
                            overlayDraggable = v
                            RerollSettings.saveOverlayDraggable(context, v)
                            OverlayWindow.applyDraggable(context, v)
                        },
                    )
                    1 -> RerollTab(
                        // 账号卡（多账号切换 / 服务器 / 抓取入口）作为插槽传进去，避免 HomeScreen 再长
                        accountSection = {
                            AccountsCard(
                                onSelected = { a ->
                                    account = a?.loginId.orEmpty()
                                    password = a?.secret.orEmpty()
                                    accountServer = a?.server ?: GameServer.CN_BILIBILI
                                },
                            )
                        },
                        rerollMode = rerollMode,
                        onRerollMode = { v ->
                            rerollMode = v
                            RerollSettings.saveRerollMode(context, v)
                        },
                    )
                    else -> StrategyTab()
                }
                // 日志：默认只有一条 peek（不占屏）；点开变成覆盖式面板
                LogDock(
                    log = log,
                    open = logOpen,
                    onToggle = { logOpen = !logOpen },
                    onClear = { AppState.clearLog() },
                )
            }
        }
    }

    // ★首次使用说明：关掉即写标记，之后不再自动弹（但右上角「说明」仍可手动开）
    if (guideOpen) {
        GuideDialog(
            overlayGranted = overlayGranted,
            rootOk = rootOk,
            onRequestRoot = onRequestRoot,
            onRequestOverlay = onRequestOverlay,
            // ★「版本」并进「说明」（2026-11-01 用户）：说明弹窗分两个标签页，更新记录不再塞在底部
            versionName = UpdateChecker.versionName(context),
            hasNewVersion = newVerCode != null,
            onCheckUpdate = {
                guideOpen = false
                RerollSettings.saveGuideShown(context)
                updateOpen = true
            },
            onDismiss = {
                guideOpen = false
                RerollSettings.saveGuideShown(context)
                // ★首次关闭说明后，若悬浮窗仍未授权，再提示一次（运行时看不到状态条，容易错过）
                if (!Settings.canDrawOverlays(context)) {
                    Toast.makeText(
                        context,
                        "悬浮窗权限未开启：运行时看不到状态条，请到「自动迷宫」页点「状态悬浮窗 → 去授权」开启",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }

    // ★反馈（2026-11-01 用户）：右上角入口，不带预选附件
    if (feedbackOpen) {
        FeedbackDialog(onDismiss = { feedbackOpen = false })
    }

    if (updateOpen) {
        UpdateDialog(
            ctx = context,
            initial = updInfo,
            onDismiss = { updateOpen = false },
        )
    }
}

/** 标签页 ①：自动迷宫 —— 权限 + 运行次数 + 战斗失败处理 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MazeTab(
    rootOk: Boolean?,
    onRequestRoot: () -> Unit,
    overlayGranted: Boolean?,
    overlayOn: Boolean,
    onOverlayToggle: (Boolean) -> Unit,
    onRequestOverlay: () -> Unit,
    mazeRuns: String,
    onMazeRuns: (String) -> Unit,
    mazeProgress: String?,
    failStrategy: Int,
    onFailStrategy: (Int) -> Unit,
    /** ★悬浮窗可拖动（首页层 state；强制锁定时首页层同步置 false） */
    overlayDraggable: Boolean,
    onOverlayDraggable: (Boolean) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("权限")
                    StatusLine(
                        "root 权限",
                        when (rootOk) {
                            true -> "已获得 ✓"
                            false -> "没有"
                            null -> "检查中…"
                        },
                        when (rootOk) {
                            true -> StatusColors.good
                            false -> StatusColors.bad
                            null -> StatusColors.idle
                        },
                    )
                    // ★"没有 / 还没探完"时才给按钮（已经有了就没有可申请的东西）
                    if (rootOk != true) {
                        FilledTonalButton(onClick = onRequestRoot) { Text("申请 root") }
                    }
                    Text(
                        "必须获得 root 权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("状态悬浮窗", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (overlayGranted == false) {
                            // ★醒目填充按钮，避免用户漏开悬浮窗（运行时看不到状态条）
                            Button(onClick = onRequestOverlay) { Text("去授权") }
                        } else {
                            Switch(
                                checked = overlayOn,
                                enabled = overlayGranted == true,
                                onCheckedChange = onOverlayToggle,
                            )
                        }
                    }
                    Text(
                        when (overlayGranted) {
                            true -> if (overlayOn) "已开启" else "已关闭"
                            false -> "没有权限"
                            null -> "检查中…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overlayGranted == false) StatusColors.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (overlayGranted == false) {
                        Text(
                            "未授权 → 运行时不显示状态条；点上面「去授权」跳系统设置开启",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.bad,
                        )
                    }
                    // ★悬浮窗外观（2026-10-20 用户设计）：悬浮窗开着时才显示；两项都持久化
                    if (overlayOn) {
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                        val ctx = LocalContext.current
                        var alphaPct by remember { mutableStateOf(RerollSettings.loadOverlayAlpha(ctx)) }
                        Column {
                            Text(
                                "透明度 ${alphaPct / 100f}（可调 0.20 ~ 0.79）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = alphaPct / 100f,
                                onValueChange = { v ->
                                    alphaPct = (v * 100).toInt().coerceIn(20, 79)
                                    OverlayWindow.applyAlpha(alphaPct)
                                },
                                onValueChangeFinished = { RerollSettings.saveOverlayAlpha(ctx, alphaPct) },
                                valueRange = 0.20f..0.79f,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "悬浮窗可拖动（拖完建议关，拖动时窗口不穿透）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = overlayDraggable,
                                onCheckedChange = onOverlayDraggable,
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("运行次数")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberField("运行次数", mazeRuns, onMazeRuns, hint = "必须 ≥ 1（0 会被拒绝）")
                    }
                    Text(
                        "自动迷宫的开局继承刷开局配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mazeProgress != null) {
                        Text(
                            "运行进度：$mazeProgress",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("战斗失败处理")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            RerollSettings.FAIL_STOP to "中断运行",
                            RerollSettings.FAIL_RETREAT to "撤退重跑",
                            RerollSettings.FAIL_WAIT to "等待接手",
                        ).forEach { (v, label) ->
                            FilterChip(
                                selected = failStrategy == v,
                                onClick = { onFailStrategy(v) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        when (failStrategy) {
                            RerollSettings.FAIL_RETREAT ->
                                "同一节点失败 3 次 → 撤退本局（作废、不算完成），自动开下一局"
                            RerollSettings.FAIL_WAIT ->
                                "同一节点失败 3 次 → 回到编队页后什么都不做，等你组队开战后再继续"
                            else -> "同一节点失败 3 次 → 停止本轮运行"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 标签页 ③：策略 —— "迷宫内决策设置"：每条占一个可折叠区域（类似账号/高级设置） */
@Composable
private fun StrategyTab() {
    val context = LocalContext.current
    val entries = remember {
        EventPick.loadEntries { name ->
            context.assets.open(name).bufferedReader().use { it.readText() }
        }
            // ★只有一个选项的事件不显示
            .filter { it.options.size > 1 }
            // ★按需求重排：完美！紧跟料理完成！，最后一发！紧跟再较量一次！
            .let { list ->
                val ordered = list.toMutableList()
                fun moveAfter(targetId: Int, afterId: Int) {
                    val ti = ordered.indexOfFirst { it.id == targetId }
                    if (ti < 0) return
                    val item = ordered.removeAt(ti)
                    val ai = ordered.indexOfFirst { it.id == afterId }
                    ordered.add((ai + 1).coerceAtMost(ordered.size), item)
                }
                moveAfter(3315, 2315) // 完美！ → 料理完成了！ 下面
                moveAfter(3316, 2316) // 最后一发！ → 再较量一次！ 下面
                ordered
            }
    }
    var picks by remember { mutableStateOf(EventSettings.loadPicks(context).toMutableMap()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("迷宫内决策设置")
                    Text(
                        "已有内置逻辑，部分逻辑暂不支持编辑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { StrategyCard("遗物选择", implemented = false) }
        item {
            var shopBuy2Star by remember { mutableStateOf(RerollSettings.loadShopBuy2Star(context)) }
            StrategyCard("商店购买顺序（未完成）", implemented = true) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("允许购买2星遗物", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = shopBuy2Star,
                        onCheckedChange = {
                            shopBuy2Star = it
                            RerollSettings.saveShopBuy2Star(context, it)
                        },
                    )
                }
            }
        }
        item {
            StrategyCard("开局角色选择", implemented = true) {
                GuildPickEditor(context)
            }
        }
        item { StrategyCard("编队", implemented = false) }
        item {
            // 事件默认折叠（2026-11-01 用户）：条目多，展开后整页被它占满
            StrategyCard("事件") {
                entries.forEach { entry ->
                    EventRow(
                        entry = entry,
                        selected = picks[entry.id] ?: entry.defaultPick,
                        onPick = { ord ->
                            val ids = entry.effectiveIds
                            picks = picks.toMutableMap().also { m -> ids.forEach { m[it] = ord } }
                            EventSettings.savePicks(context, picks)
                            ids.forEach { EventPick.EVENT_PICK[it] = ord }
                        },
                    )
                }
            }
        }
        // ★凹分模式放最后（2026-11-01 用户）：总开关关闭时，卡片下面的内容整块不显示
        item { ScoreChaseCard(context) }
    }
}

/**
 * ★「凹分模式」（2026-11-01 用户需求）：监测最终分数，主动放弃分数不够的局。
 *
 * 按用户定的几条约束：
 *  · 总开关关掉 → 卡片下面**什么都不显示**（和「状态悬浮窗」一样的做法）；
 *  · 子条件 1 可以独立开关，但总开关关着时就整块不出现（那时它没意义）；
 *  · 难度是下拉框 1~5、初值 5；阈值是滑块，区间是**基础值** 23000~27000、100 一档、初值 25000，
 *    界面上显示的是「基础值 × 难度倍率」（难度 1~5 → 1.0/1.2/1.4/1.7/2.0）。
 *
 * 检测点只有一个：5 层 Boss 前的商店（节点 50701）购物之后 —— 5 层 Boss 的分数是固定的，
 * 过了那里分数基本不会再变，没必要等打完再判断。
 */
@Composable
private fun ScoreChaseCard(context: Context) {
    var on by remember { mutableStateOf(RerollSettings.loadScoreChaseOn(context)) }
    var retreatOn by remember { mutableStateOf(RerollSettings.loadScoreChaseRetreatOn(context)) }
    var base by remember { mutableIntStateOf(RerollSettings.loadScoreChaseBase(context)) }
    // ★难度不在这里另设：直接用「刷开局」页选的那个难度（2026-11-01 用户）。
    //   实际运行时也是「基础值 × 该难度倍率」，两处各存一份只会打架。
    val diff = remember { RerollSettings.loadConfig(context).difficulty.coerceIn(1, 5) }
    val threshold = RerollSettings.scoreThreshold(diff, base)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ★总条目只放标题 + 开关，不写任何说明：凹分模式是**一组规则的集合**，
            //   不是"只看分数"，写一句"分数不够就放弃"反而把范围说窄了（2026-11-01 用户）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("凹分模式", Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = {
                        on = it
                        RerollSettings.saveScoreChaseOn(context, it)
                    },
                )
            }
            if (on) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                // ── 子条件 1（可独立开关）──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("条件 1 · 分数不足自动撤退", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (retreatOn) {
                                "难度 $diff 时，分数 < ${fmtScore(threshold)} 就撤退重开"
                            } else {
                                "已关闭：这一条件不做任何检测"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = retreatOn,
                        onCheckedChange = {
                            retreatOn = it
                            RerollSettings.saveScoreChaseRetreatOn(context, it)
                        },
                    )
                }
                if (retreatOn) {
                    // 阈值滑块：滑块本身是**基础值**，上面显示乘完倍率的实际阈值
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "撤退阈值",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            fmtScore(threshold),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = base.toFloat(),
                        onValueChange = { v ->
                            val snapped = (v / RerollSettings.SCORE_BASE_STEP).roundToInt() *
                                RerollSettings.SCORE_BASE_STEP
                            base = snapped.coerceIn(
                                RerollSettings.SCORE_BASE_MIN,
                                RerollSettings.SCORE_BASE_MAX,
                            )
                        },
                        // 拖动中只更新界面，松手再落盘（同悬浮窗透明度滑块）
                        onValueChangeFinished = { RerollSettings.saveScoreChaseBase(context, base) },
                        valueRange = RerollSettings.SCORE_BASE_MIN.toFloat()..
                            RerollSettings.SCORE_BASE_MAX.toFloat(),
                        steps = (RerollSettings.SCORE_BASE_MAX - RerollSettings.SCORE_BASE_MIN) /
                            RerollSettings.SCORE_BASE_STEP - 1,
                    )
                }
            }
        }
    }
}

/** 分数带千分位，例：50,000 */
private fun fmtScore(n: Int): String = "%,d".format(n)

/** 一条策略决策：可折叠卡片，标题即选项名（不带冒号说明）。
 *  - implemented = true：整行可点击切换展开/收起，右侧保留「展开/收起」文字提示，内容由 [content] 提供（事件）。
 *  - implemented = false（未开放）：标题追加「（未开放）」，整行不可点、不渲染内容。 */
/**
 * ★黎明界开局「自选角色」编辑器：按迷宫公会列出花名册（已排除 FES/公主），每个公会最多选 3 个角色，
 * 点形态 chip 切换；不选则运行时走花名册默认 trio。选择实时存进 [RerollSettings]。
 */
@Composable
private fun GuildPickEditor(context: Context) {
    val pool = remember { GameData.guildPool(context) }
    var picks by remember { mutableStateOf(RerollSettings.loadGuildPicks(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "每个公会默认已勾选固定三人，可自行改选 3 个（点 chip 切换，「默认」恢复固定三人）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pool.guilds.toSortedMap().forEach { (labId, g) ->
            GuildPickCard(
                g = g,
                selected = picks[labId] ?: g.defaultPicks,
                onEdit = { newList ->
                    picks = picks.toMutableMap().apply { put(labId, newList) }
                    RerollSettings.saveGuildPicks(context, picks)
                },
            )
        }
    }
}

/** 单个公会的自选卡片：整个公会的可选单位平铺成一排 chip（不分角色），最多选 3 个。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuildPickCard(
    g: GuildPool,
    selected: List<Int>,
    onEdit: (List<Int>) -> Unit,
) {
    val allForms = remember(g) { g.pool.flatMap { it.forms } }
    val formIds = remember(allForms) { allForms.map { it.unitId }.toHashSet() }
    // 选中集只保留池里实际存在的卡（默认里若有已被排除的卡，如 FES，这里自动忽略）
    var selSet by remember(selected, formIds) {
        mutableStateOf<MutableSet<Int>>(selected.filter { it in formIds }.toMutableSet())
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                g.cn, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "已选 ${selSet.size}/3", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                val d = g.defaultPicks.filter { it in formIds }.toHashSet()
                selSet = d
                onEdit(d.toList())
            }) { Text("默认") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            allForms.forEach { f ->
                val on = f.unitId in selSet
                FilterChip(
                    selected = on,
                    onClick = {
                        val m = selSet.toMutableSet()
                        if (on) m.remove(f.unitId)
                        else {
                            if (m.size >= 3) return@FilterChip
                            m.add(f.unitId)
                        }
                        selSet = m
                        onEdit(m.toList())
                    },
                    label = { Text(f.cn, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun StrategyCard(
    title: String,
    defaultOpen: Boolean = false,
    implemented: Boolean = true,
    content: @Composable () -> Unit = {},
) {
    var open by remember { mutableStateOf(defaultOpen) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (implemented) Modifier.clickable { open = !open } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle(
                    if (implemented) title else "$title（未开放）",
                    Modifier.weight(1f),
                )
                if (implemented) {
                    Text(if (open) "收起" else "展开")
                }
            }
            if (implemented && open) content()
        }
    }
}

/** 一个事件：提示文字 + 各选项单选（选项为具体文字）。
 *  - [entry.lockedOpts]：被锁定的选项 ord（禁用、不可改），如「料理完成！」选项2、「完美！」两选项。
 *  - [entry.optionNote]：某 ord 选项下的附注（如「料理完成！」选项2 标未实现自选角色逻辑）。
 *  - [entry.allIds] 多个：合并事件（如「魔物们斗技场」各金币档位统一选择，描述里已并写金币）。 */
@Composable
private fun EventRow(entry: EventEntry, selected: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            entry.text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        entry.options.forEach { opt ->
            val locked = opt.ord in entry.lockedOpts
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == opt.ord,
                    enabled = !locked,
                    onClick = { onPick(opt.ord) },
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(opt.comment, style = MaterialTheme.typography.bodyMedium)
                        if (locked) {
                            Text(
                                "（锁定）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    if (opt.desc.isNotBlank()) {
                        Text(
                            opt.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.optionNote[opt.ord]?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

/** 黎明界「初始公会」表（来自 PC reroll.py GUILDS，master 库 labyrinth_enter_guild）。默认 id=3 = 咲恋救济院 */
private val REROLL_GUILDS: List<Pair<String, Int>> = listOf(
    "美食殿堂" to 1,
    "破晓之星" to 2,
    "咲恋救济院" to 3,
    "王宫骑士团（NIGHTMARE）" to 4,
    "拉比林斯" to 5,
)

/**
 * ★启动运行前强制关闭悬浮窗可拖动（2026-10-20 用户设计："不是检查，是直接关"）：
 * 可拖动模式开着时悬浮窗会拦截其区域内的触摸（穿透失效）⇒ 跑流程前必须回到穿透态。
 * 设置直接写 false + 悬浮窗在显示中就实时应用（恢复 NOT_TOUCHABLE）。
 */
private fun forceOverlayDragOff(context: Context, onUiState: (Boolean) -> Unit) {
    onUiState(false)   // ★UI 开关同步置 false（2026-10-20 用户：强制锁定后按钮也要改过来）
    if (RerollSettings.loadOverlayDraggable(context)) {
        RerollSettings.saveOverlayDraggable(context, false)
        OverlayWindow.applyDraggable(context, false)
    }
}

/** Boss 白名单（3 区 / 5 区）任一为空集 ⇒ 任何 Boss 都判不合格、运行必然卡死，出发前拒绝执行 */
private fun bossWhitelistEmpty(context: Context): Boolean {
    val c = RerollSettings.loadConfig(context)
    return c.bossArea3.isEmpty() || c.bossArea5.isEmpty()
}

/**
 * ★出发前校验「开局角色选择」：**每个迷宫公会都必须选满 3 个池内单位**
 * （没手动选过的按花名册默认 trio 算，与运行时 [GameData.guildPool] 的口径一致）。
 *
 * 为什么卡在启动：开局选人页是按公会现点的，某家缺人时这一局从起点就是残阵，
 * 再跑完整程纯属浪费（用户 2026-10-18："开局角色选择必须每个公会都选了3人"）。
 *
 * @return 没选满的公会中文名（空 = 全部合格；读不到花名册时不拦，交给运行时兜底）
 */
private fun guildPicksIncomplete(context: Context): List<String> {
    val data = runCatching { GameData.guildPool(context) }.getOrNull() ?: return emptyList()
    if (data.guilds.isEmpty()) return emptyList()
    val saved = RerollSettings.loadGuildPicks(context)
    val bad = ArrayList<String>()
    for ((labId, g) in data.guilds.toSortedMap()) {
        val formIds = g.pool.flatMap { it.forms }.mapTo(HashSet()) { it.unitId }
        val chosen = (saved[labId] ?: emptyList()).ifEmpty { g.defaultPicks }
        if (chosen.count { it in formIds } < 3) bad.add(g.cn)
    }
    return bad
}

/** 标签页 ②：刷开局 —— 账号（折叠）＋ 刷开局按钮在底部 ＋ 高级设置（折叠，尽量做成选项） */
@Composable
private fun RerollTab(
    // 账号卡整块由调用方提供（2026-10-28：多账号切换 / 选服务器 / 渠道服抓取入口都在 AccountsCard 里）
    accountSection: @Composable () -> Unit,
    // ★刷开局方式提到首页（底部「刷开局」按钮也要用），这里只负责改
    rerollMode: Int,
    onRerollMode: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by AppState.rerollStatus.collectAsStateWithLifecycle()
    val summary by AppState.rerollResultSummary.collectAsStateWithLifecycle()
    val captcha by AppState.rerollCaptcha.collectAsStateWithLifecycle()

    // 高级设置：选项变更即落盘（login 内部会 loadConfig 读取），无需单独的"保存"按钮
    var cfg by remember { mutableStateOf(RerollSettings.loadConfig(context)) }
    fun updateCfg(block: RerollConfig.() -> RerollConfig) {
        cfg = cfg.block()
        RerollSettings.saveConfig(context, cfg)
    }
    // ★3/5 区 Boss：5 个固定 Boss 多选，默认全选（state 直接存 Set<Int>）
    val area3Bosses = listOf(
        "巨型魔像" to 301206, "暗黑滴水嘴兽" to 303306, "毒液沙罗曼蛇" to 306604,
        "厄勒克特拉夫人" to 312505, "冰霜魔狼" to 319604,
    )
    val area5Bosses = listOf(
        "炸脖龙" to 301701, "奇美拉" to 302501, "愤怒巨龙" to 310103,
        "领主哥布林" to 315004, "究极守护者" to 319401,
    )
    var bossArea3Sel by remember { mutableStateOf(cfg.bossArea3) }
    var bossArea5Sel by remember { mutableStateOf(cfg.bossArea5) }

    // 账号卡的展开/密码显示开关随账号卡一起搬进 AccountsCard 了
    var advanced by remember { mutableStateOf(true) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 账号（2026-10-28 换成 AccountsCard：多账号切换 / 选服务器 / 渠道服抓取入口）
        item { accountSection() }
        // 刷开局结果与状态：跟账号卡分开，收起账号时也看得见。
        // ★2026-10-30 用户："还没有刷开局结果，这行提示应该去掉。刷开局只是个附带功能，
        //   我们主要还是要用一键黎明界，不要引导到刷开局这里来" ⇒ 没有结果/状态时整块不渲染，
        //   也不再写引导文案。真正的主线在「自动迷宫」页的「一键黎明界」。
        if (summary != null || status != null) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (summary != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    summary!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { RerollUseCase.reset() }) { Text("清除结果") }
                            }
                        }
                        if (status != null) {
                            Text(status!!, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.clickable { advanced = !advanced },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle("高级设置", Modifier.weight(1f))
                        Text(if (advanced) "收起" else "展开")
                    }
                    if (advanced) {
                        ChoiceChips(
                            "刷开局方式",
                            listOf(
                                "接口（快，需账号）" to RerollSettings.REROLL_API,
                                "局内（慢，免账号）" to RerollSettings.REROLL_IN_GAME,
                            ),
                            rerollMode,
                            onRerollMode,
                        )
                        Text(
                            if (rerollMode == RerollSettings.REROLL_IN_GAME)
                                "局内：在游戏里正常开一局，读地图不满意就撤退重开（不用账号密码，每局十几秒）"
                            else "接口：直接调 enter/retire 刷（快，但需要账号密码）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ChoiceChips(
                            "难度",
                            (1..5).map { "$it" to it },
                            cfg.difficulty,
                        ) { updateCfg { copy(difficulty = it) } }
                        ChoiceChips(
                            "初始公会",
                            REROLL_GUILDS,
                            cfg.guildId,
                        ) { updateCfg { copy(guildId = it) } }
                        ChoiceChips(
                            "事件倾向",
                            listOf("偏遗物" to 100, "偏事件" to 350),
                            cfg.eventValue,
                        ) { updateCfg { copy(eventValue = it) } }
                        ChoiceChips(
                            "价值偏差",
                            listOf("满分" to 0, "50" to 50, "100" to 100, "200" to 200),
                            cfg.maxValueGap,
                        ) { updateCfg { copy(maxValueGap = it) } }
                        ChoiceChips(
                            "最大次数",
                            listOf("不限" to -1, "30" to 30, "50" to 50, "100" to 100),
                            cfg.maxCount,
                        ) { updateCfg { copy(maxCount = it) } }
                        // ★判 Boss 白名单 / 判价值偏差：永远执行，已无开关（不判 Boss = 5 个全选即可）
                        BossMultiSelect(
                            "3 区 Boss（多选，默认全选）",
                            area3Bosses,
                            bossArea3Sel,
                        ) { id ->
                            bossArea3Sel = if (id in bossArea3Sel) bossArea3Sel - id else bossArea3Sel + id
                            updateCfg { copy(bossArea3 = bossArea3Sel) }
                        }
                        BossMultiSelect(
                            "5 区 Boss（多选，默认全选）",
                            area5Bosses,
                            bossArea5Sel,
                        ) { id ->
                            bossArea5Sel = if (id in bossArea5Sel) bossArea5Sel - id else bossArea5Sel + id
                            updateCfg { copy(bossArea5 = bossArea5Sel) }
                        }
                    }
                }
            }
        }
    }

    if (captcha != null) {
        CaptchaDialog(
            challenge = captcha!!,
            onSolved = { validate -> scope.launch { RerollUseCase.submitCaptcha(context, validate) } },
            onDismiss = { AppState.setRerollCaptcha(null); AppState.setRerollBusy(false) },
        )
    }
}

/** 一组单选 chip（选项 → Int 值，套一层映射落盘到 RerollConfig），超出宽度可横滑 */
@Composable
private fun ChoiceChips(
    label: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (text, value) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

/** 一组「固定 Boss 多选」：横向 chip，放不下自动换行；默认全选，取消勾即不放入白名单 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BossMultiSelect(
    label: String,
    bosses: List<Pair<String, Int>>,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            bosses.forEach { (name, id) ->
                FilterChip(
                    selected = id in selected,
                    onClick = { onToggle(id) },
                    label = { Text("$name ($id)", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

/** 小号数字输入（只收数字、最多 3 位），旁边跟一句提示 */
@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValue(it.filter { c -> c.isDigit() }.take(3)) },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一行状态：色点 + 名称 + 值 */
@Composable
private fun StatusLine(label: String, value: String, dot: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.58f),
        )
    }
}

/** 区块标题：一小段主色竖条 + 标题（代替"一堆小字"的分段方式） */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(16.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** 日志区：默认一条 peek（最后一行），点开是覆盖式面板（分享/清空/收起都在里面） */
@Composable
private fun BoxScope.LogDock(log: List<String>, open: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    val ctx = LocalContext.current
    // 2026-10-27：这个入口改成中性的「日志文件」，真正的分享动作挪到二级（点开某一次运行之后）
    var exportOpen by remember { mutableStateOf(false) }
    Column(Modifier.align(Alignment.BottomCenter)) {
        if (open) {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("日志 · ${log.size} 行", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { exportOpen = true }) { Text("日志文件") }
                        TextButton(onClick = onClear) { Text("清空") }
                        TextButton(onClick = onToggle) { Text("收起") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(log) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
        Surface(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("日志", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(
                    log.lastOrNull()?.substringAfter("  ")?.take(46) ?: "（还没有日志）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (open) "收起 ▾" else "展开 ▴",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (exportOpen) {
        LogExportDialog(onDismiss = { exportOpen = false })
    }
}

/**
 * 日志与诊断包（2026-10-27 用户口径，两级 = **本次运行 → 单局**）。
 *
 * ```
 * ▸ 本次运行 10-27 21:47   成功 5/5 局 · 1小时50分        [生成诊断包]
 *     ├ 第 1 局 21:49 成功 26分钟 51000分                [选这一局]
 *     └ 第 5 局 23:40 成功 27分钟 51600分
 * ```
 *
 * * 顶层「生成诊断包」= session + **该次运行的全部单局**（+ 同时段的崩溃记录）；
 * * 某一局「选这一局」= session + **这一局**；
 * * 只打用户选中的，**不扫全部历史**；
 * * 生成只落在 cache，界面上「保存到本地」才写进公共「下载」（= 以前的"复制到下载"）；
 * * 老的三件套（分享 / 打开 / 复制到下载）仍保留，但降级到"点某一行才展开"的原始文件层；
 * * **只有会话日志的运行折叠成一行**（每次启动都会新增一条，摊开会把有用的运行挤出屏幕）。
 */
@Composable
private fun LogExportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions = remember { LogFile.listSessions(20) }
    // ★只有会话日志的运行（开一下应用就被关掉）单独折叠成一行（2026-10-27 用户："折叠一下这些仅会话的"）：
    //   每次启动都会新增一条，摊开摆会把真正有单局的运行挤出屏幕。
    //   例外：**本次运行**哪怕还没跑出单局，也留在上面（它才是用户最可能要的那条）
    val withRuns = remember(sessions) { sessions.filter { it.runs.isNotEmpty() || it.current } }
    val emptyOnly = remember(sessions) { sessions.filter { it.runs.isEmpty() && !it.current } }
    // 默认展开最近一次**有单局**的运行：用户八成就是来发"刚才那次"的
    var openSession by remember {
        mutableStateOf<String?>((withRuns.firstOrNull { it.runs.isNotEmpty() } ?: withRuns.firstOrNull())?.key)
    }
    var emptyOpen by remember { mutableStateOf(false) }
    var openFiles by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pack by remember { mutableStateOf<DiagPack.Result?>(null) }
    // ★「提交反馈」时暂存这个包（反馈页开着的时候把诊断包页收起来，避免两层弹窗叠着）
    var feedbackPack by remember { mutableStateOf<DiagPack.Result?>(null) }

    fun makePack(title: String, entries: List<LogFile.LogEntry>) {
        if (busy) return
        busy = true
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) { DiagPack.build(ctx, title, entries.map { it.f }) }
            }
            busy = false
            r.onSuccess { pack = it }
                .onFailure { Toast.makeText(ctx, "生成失败：${it.message ?: "原因未知"}", Toast.LENGTH_LONG).show() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("日志与诊断包") },
        text = {
            Column {
                Text(
                    "选一整次运行 = 会话 + 该次全部单局；选某一局 = 只打这一局。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(400.dp)) {
                    if (sessions.isEmpty()) {
                        item { Text("还没有日志（跑一局就有了）", style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(withRuns) { s ->
                        SessionRow(
                            s = s,
                            expanded = openSession == s.key,
                            onToggle = { openSession = if (openSession == s.key) null else s.key },
                            onPack = { makePack(s.title, s.files) },
                        )
                        if (openSession == s.key) {
                            for (r in s.runs) {
                                RunRow(
                                    r = r,
                                    filesOpen = openFiles == r.key,
                                    onToggleFiles = { openFiles = if (openFiles == r.key) null else r.key },
                                    onPack = {
                                        // 单局诊断包 = 会话 + 这一局（会话日志有就带上，没有就只带这一局）
                                        makePack(
                                            "${s.title} / ${r.title}",
                                            listOfNotNull(s.sessionFile) + r.files,
                                        )
                                    },
                                )
                            }
                            if (s.runs.isEmpty()) {
                                Text(
                                    "这次还没有单局日志（会话日志就在这一条里）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }
                    if (emptyOnly.isNotEmpty()) {
                        item {
                            // 折叠行：一次点开，里面是原样的 session 行（每条仍可单独生成诊断包）
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { emptyOpen = !emptyOpen }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (emptyOpen) "▾" else "▸",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "其余 ${emptyOnly.size} 次运行（只有会话日志）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (emptyOpen) {
                                for (s in emptyOnly) {
                                    SessionRow(
                                        s = s,
                                        expanded = false,
                                        onToggle = {},
                                        onPack = { makePack(s.title, s.files) },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        TeamRunEntry(remember { TeamRunLog.stats() })
                    }
                }
            }
        },
    )

    pack?.takeIf { feedbackPack == null }?.let { p ->
        AlertDialog(
            // 用户口径（2026-10-27）：只有「取消 / 保存到本地」，不要"发送/分享"——
            // 模拟器里一般不装微信/QQ，分享面板点了也是空的；保存到本地 = 以前的「复制到下载」。
            // 2026-11-01 用户新增：「提交反馈」——带着这个包直接开 issue（反馈对话框会自动选中它）。
            onDismissRequest = {
                DiagPack.discard(p)
                pack = null
            },
            title = { Text("诊断包") },
            text = {
                Column {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${p.fileCount} 份日志 · ${(p.bytes + 1023) / 1024} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row {
                    // ★带着这个包直接提反馈（2026-11-01 用户）：反馈对话框里已自动选中它
                    TextButton(onClick = { feedbackPack = p }) { Text("提交反馈") }
                    TextButton(onClick = {
                        pack = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { DiagPack.save(ctx, p) } }
                                .onSuccess {
                                    Toast.makeText(ctx, "已保存到 $it", Toast.LENGTH_LONG).show()
                                    DiagPack.discard(p)
                                }
                                .onFailure {
                                    Toast.makeText(ctx, "保存失败：${it.message ?: "原因未知"}", Toast.LENGTH_LONG).show()
                                }
                        }
                    }) { Text("保存到本地") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    DiagPack.discard(p)
                    pack = null
                }) { Text("取消") }
            },
        )
    }

    // 反馈页（从这个包进来）：关掉后回到「诊断包」页，包还在，用户仍可保存或取消
    feedbackPack?.let { fp ->
        FeedbackDialog(initialPack = fp, onDismiss = { feedbackPack = null })
    }
}

/**
 * 一级行 = **本次运行**：中文标题 + 状态徽章 + 「生成诊断包」。
 * 点标题区展开/收起二级（单局列表）。
 */
@Composable
private fun SessionRow(
    s: LogFile.SessionEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${s.timeText} ｜ ${s.detail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            LogBadge(s.badge, s.tone)
            TextButton(onClick = onPack) { Text("诊断包", style = MaterialTheme.typography.labelSmall) }
        }
        // 单局行（二级）由对话框按 s.runs 逐个渲染 —— 这里只管这一行本身
        HorizontalDivider()
    }
}

/**
 * 二级行 = **单局**：`第 1 局 · 21:49` + 成功/失败徽章 + 时长/总分 + 「选这一局」。
 * 点行本身展开原始文件（分享 / 打开 / 复制到下载）——分享面板就降在这一层。
 */
@Composable
private fun RunRow(
    r: LogFile.RunEntry,
    filesOpen: Boolean,
    onToggleFiles: () -> Unit,
    onPack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleFiles)
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${r.timeText} ｜ ${r.detail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            LogBadge(r.badge, r.tone)
            TextButton(onClick = onPack) { Text("选这一局", style = MaterialTheme.typography.labelSmall) }
        }
        if (filesOpen) {
            Column(Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                for (e in r.files) LogFileRow(e)
            }
        }
    }
}

/** 原始文件行：文件名（等宽，对日志时能直接念出来）+ 三个老动作 */
@Composable
private fun LogFileRow(e: LogFile.LogEntry) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(
            e.name,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${e.time} ｜ ${e.sizeKb} KB" + (if (e.dir.isNotEmpty()) " ｜ ${e.dir}/" else ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ★三个动作（2026-10-19 用户：模拟器上没微信/QQ，分享面板没用）：
        //   分享 = 系统分享面板；打开 = 文件管理器/文本查看器；复制 = 拷进公共 Download
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                runCatching { LogFile.share(ctx, e.f) }
                    .onFailure { err ->
                        Toast.makeText(ctx, "分享失败：${err.message ?: "原因未知"}", Toast.LENGTH_LONG).show()
                    }
            }) { Text("分享", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                if (!LogFile.openWithViewer(ctx, e.f)) {
                    Toast.makeText(ctx, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
                }
            }) { Text("打开", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = {
                runCatching { LogFile.copyToDownloads(ctx, e.f) }
                    .onSuccess { Toast.makeText(ctx, "已复制到下载目录", Toast.LENGTH_SHORT).show() }
                    .onFailure { err ->
                        Toast.makeText(ctx, "复制失败：${err.message ?: "原因未知"}", Toast.LENGTH_LONG).show()
                    }
            }) { Text("复制到下载", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** 状态徽章（一级行右侧）：语义色 + 14% 底色，比一行 `done/`、`abort/` 前缀好认 */
@Composable
private fun LogBadge(text: String, tone: LogFile.Tone) {
    val c = when (tone) {
        LogFile.Tone.GOOD -> StatusColors.good
        LogFile.Tone.WARN -> StatusColors.warn
        LogFile.Tone.BAD -> StatusColors.bad
        LogFile.Tone.IDLE -> StatusColors.idle
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = c,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 「组队记录」也收成一级条目（2026-10-24 用户：导出按钮放进日志导出页；
 * 2026-10-27 按同一套层级放进二级）。
 *
 * 内容是 `logs/team_runs.jsonl` —— 每次**编队决策**一行 JSON，字段与 PC 版
 * `team_build.log_run()` 对齐（stage/池/遗物/目标队+理由/属性/权重快照/分数/questId…），
 * `run_id` 全球唯一（`stage|时间|随机`）⇒ 多台设备的记录合并时不会重复。
 */
@Composable
private fun TeamRunEntry(s: TeamRunLog.Stats) {
    val ctx = LocalContext.current
    val f = remember { TeamRunLog.currentFile() }
    var open by remember { mutableStateOf(false) }
    val ready = f != null && f.exists() && s.runs > 0
    val file = if (open && ready) f else null
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = ready, onClick = { open = !open })
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("组队记录", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (ready) {
                        "${s.runs} 条 ｜ ${(s.bytes + 1023) / 1024} KB" +
                            (if (s.files > 1) " ｜ 另有 ${s.files - 1} 份历史" else "")
                    } else {
                        "还没有组队记录 —— 跑一局、进一次编组页就会自动记一行"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            LogBadge(if (ready) "可导出" else "空", LogFile.Tone.IDLE)
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (file != null) {
            // 每次编队一行（关卡 / 池 / 遗物 / 目标队 / 分数），导出后可在 PC 端复盘
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    runCatching { LogFile.share(ctx, file) }
                        .onFailure { Toast.makeText(ctx, "分享失败：${it.message ?: "原因未知"}", Toast.LENGTH_LONG).show() }
                }) { Text("分享", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = {
                    if (!LogFile.openWithViewer(ctx, file)) {
                        Toast.makeText(ctx, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("打开", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = {
                    runCatching { LogFile.copyToDownloads(ctx, file) }
                        .onSuccess { Toast.makeText(ctx, "已复制到下载目录", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(ctx, "复制失败：${it.message ?: "原因未知"}", Toast.LENGTH_LONG).show() }
                }) { Text("复制到下载", style = MaterialTheme.typography.labelSmall) }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun CaptchaDialog(
    challenge: CaptchaChallenge,
    onSolved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("完成官方验证") },
        text = {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                factory = { ctx ->
                    val solved = onSolved
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onSolved(validate: String) = solved(validate)

                                @JavascriptInterface
                                fun onError(msg: String) = Unit
                            },
                            "AndroidCaptcha",
                        )
                        loadDataWithBaseURL(
                            "https://static.geetest.com",
                            GeetestPageBuilder.build(challenge),
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                },
            )
        },
    )
}

/** 首次启动自动弹出、右上角「说明」可再次打开的使用指引。 */
@Composable
private fun GuideDialog(
    overlayGranted: Boolean?,
    rootOk: Boolean?,
    onRequestRoot: () -> Unit,
    onRequestOverlay: () -> Unit,
    versionName: String,
    hasNewVersion: Boolean,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val needsOverlay = overlayGranted != true
    val needsRoot = rootOk != true
    // ★两个标签页（2026-11-01 用户："更新记录不要塞在长文底部"）：
    //   0 = 使用说明，1 = 更新记录（内容就是 assets/CHANGELOG.md）
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 分段 + 小标题：原来四条长句糊在一起，用户扫一眼抓不到重点（2026-10-26 用户："改得更好一点"）
    val sections = listOf(
        "一、准备" to listOf(
            "设备需要有 Root 权限（模拟器里打开 Root 开关，或 Magisk 授权）。",
            "设备必须是 x86_64 架构：只在 MuMu、雷电等「x86 版」模拟器上工作；" +
                "手机等 ARM 设备（ARM 真机 / ARM 云机）暂不支持，开跑时会直接提示，不会白试。",
            "安装本应用，并在游戏里登录好账号。",
            "游戏里登录的账号，必须和「刷开局 → 账号」里选中的那个账号是同一个。",
            "支持 B 站官服与各联运渠道服（小米 / 华为 / vivo / OPPO / 九游 / 4399 / MuMu / 应用宝）；" +
                "渠道服不用账密，点「抓取」自动取 uid + access_key。",
            "凭证加密保存在本机（系统密钥库），只会发给对应渠道的官方接口。",
        ),
        "二、授权" to listOf(
            "首次运行会弹 Root 授权框，请允许（建议勾选「永久记住」）。",
            "授予「悬浮窗」权限，否则运行时看不到状态条。",
        ),
        "三、配置" to listOf(
            "「刷开局」页 · 账号：选好服务器；B 服填账密，渠道服点「抓取」。刷开局方式选「接口」时需要账号，选「局内」则不需要。",
            "「策略」页：Boss 区、运行次数、开局角色等按需调整。",
        ),
        "四、开跑" to listOf(
            "回到「自动迷宫」页，点「一键黎明界」开始。",
            "运行中把游戏切到后台会自动停止；要停下来点「停止」。",
        ),
    )
    // ★用自定义 Dialog 而不是 AlertDialog：AlertDialog 的内容没有可靠的高度约束，
    //   给内容区写死 maxHeight 后底部（版本 / 检查更新）会被裁掉。这里把弹窗高度限制在屏幕 86%，
    //   内容区用 weight(1f) 自适应 + 内部滚动，头部标签、底部版本行都固定不动。
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenH * 0.86f),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(
                    if (hasNewVersion) "使用说明 · 有新版" else "使用说明",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    listOf("使用说明", if (hasNewVersion) "更新记录 •" else "更新记录")
                        .forEachIndexed { i, label ->
                            Tab(
                                selected = tab == i,
                                onClick = { tab = i },
                                text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                }
                Spacer(Modifier.height(8.dp))
                // 内容区：占满剩余高度后内部滚动，切标签不改变弹窗高度
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (tab == 0) {
                        sections.forEach { (head, items) ->
                            Text(head, style = MaterialTheme.typography.titleSmall)
                            items.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                            Spacer(Modifier.height(4.dp))
                        }
                        // ★游戏内三项只能在游戏里改，脚本碰不到 —— 但**不必提前设好**，跑起来再改也行
                        //   （2026-10-26 用户要求补这条）
                        Text("五、游戏里需要你自己设的三项", style = MaterialTheme.typography.titleSmall)
                        listOf(
                            "战斗界面右下角的「SET」",
                            "战斗倍速（1× / 2× / 4×）",
                            "关闭「获得角色时的动画」",
                        ).forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            "这三项脚本不会替你改。不用提前设好 —— 脚本运行中随时改都可以，不影响正在跑的任务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // ★把"授权"做成可直接点的动作，避免用户漏开悬浮窗（运行时看不到状态，容易错过）
                        if (needsOverlay || needsRoot) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text(
                                "还没授权的话，直接点下面按钮开启：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (needsRoot) {
                                FilledTonalButton(onClick = onRequestRoot, Modifier.fillMaxWidth()) {
                                    Text("申请 Root 权限")
                                }
                            }
                            if (needsOverlay) {
                                Button(onClick = onRequestOverlay, Modifier.fillMaxWidth()) {
                                    Text("去开启「悬浮窗」权限")
                                }
                            }
                        }
                    } else {
                        MarkdownColumn(remember(tab) { readChangelog(ctx) })
                    }
                }
                // ★版本 + 检查更新固定在底部，两个标签页都看得到（2026-11-01 用户："把版本并进说明"）
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "版本 $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCheckUpdate) {
                        Text(if (hasNewVersion) "检查更新 •" else "检查更新")
                    }
                    Button(onClick = onDismiss) { Text("知道了") }
                }
            }
        }
    }
}

/**
 * ★应用内「更新记录」（2026-10-23 用户："在应用内可以看更新记录，内容以当前 changelog 为准"）。
 *
 * 数据来源是 **assets/CHANGELOG.md** —— 该文件由 Gradle 在构建前从**仓库根目录**复制过来
 * （见 `app/build.gradle.kts` 的 `copyChangelogAsset`），所以应用里看到的就是仓库里那份，
 * 不会再出现"应用内一份、仓库一份"不同步。
 *
 * 渲染只做很轻的 Markdown 识别：`## 版本`、`### 小节`、`- 条目`，其余按正文显示。
 */
private fun readChangelog(ctx: Context): String = runCatching {
    ctx.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() }
}.getOrElse { "读不到更新记录" }

// ★原独立的「更新记录」弹窗已删除（2026-11-01）：现在是说明弹窗的第二个标签页，
//   「检查更新」按钮固定在说明弹窗底部。

/**
 * 行内 `**加粗**` → AnnotatedString（其余按普通文字）。
 * ★2026-10-30 用户：CHANGELOG 里大量 `**…**`，之前当正文原样显示，"两个星号"很扎眼。
 * 按 `**` 切分后**奇数段加粗**即可（成对出现；落单的 `**` 只会少加粗一段，不会吃掉文字）。
 */
private fun mdInline(s: String): AnnotatedString = buildAnnotatedString {
    s.split("**").forEachIndexed { i, seg ->
        if (seg.isEmpty()) return@forEachIndexed
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seg) }
        else append(seg)
    }
}

/**
 * 很轻的 Markdown 渲染：`## 版本` / `### 小节` / `- 条目` / 行内 `**加粗**`，其余按正文 ——
 * 「更新记录」和「检查更新」（新版本说明来自 latest.json 的 notes）共用同一套排版。
 */
@Composable
private fun MarkdownColumn(text: String) {
    text.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.startsWith("# ") -> Unit              // 文件顶部「# 更新日志」不重复显示
            line.startsWith("## ") -> Text(
                mdInline(line.removePrefix("## ")),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
            line.startsWith("### ") -> Text(
                mdInline(line.removePrefix("### ")),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
            line.startsWith("- ") -> Text(
                mdInline("· " + line.removePrefix("- ")),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            )
            line.isBlank() -> Spacer(Modifier.height(2.dp))
            else -> Text(mdInline(line), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 运行组件状态条（2026-10-26 用户方案："初次运行下载必备组件应该是合理行为"）。
 *
 * 首次要备一个约 42MB 的 frida-server，这既不是错误、也不需要用户决策，所以**不弹窗**，
 * 就在标题下面挂一条常驻细条：
 *  * 下载中 → `运行组件下载中 45%（19/42MB）` + 细进度条（不可点）
 *  * 移动网络没下 → `运行组件未下载…` + 点一下用流量下（不偷跑流量，但给出口）
 *  * 失败 → 红条 + 点一下重试
 *  * 已就绪 → **整条消失**（不留"已完成"这种噪音）
 */
@Composable
private fun ComponentBar(status: String?, onFetch: () -> Unit) {
    if (status == null || status.startsWith("运行组件已就绪")) return
    val downloading = status.startsWith("运行组件下载中")
    val missing = status.startsWith("运行组件不可用")       // 架构没发布件，点也没用
    val failed = status.startsWith("运行组件下载失败") || missing
    val pct = Regex("(\\d+)%").find(status)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val tint = when {
        failed -> MaterialTheme.colorScheme.error
        downloading -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    val base = Modifier
        .fillMaxWidth()
        .background(tint.copy(alpha = 0.10f))
    Column(
        (if (downloading || missing) base else base.clickable { onFetch() })
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = when {
                downloading || missing -> status
                failed -> "$status · 点此重试"
                else -> "$status · 用流量下载 42MB"
            },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (downloading) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
    }
}

/**
 * 「上次运行异常中断」横幅（2026-10-26）。
 *
 * 来自真实反馈日志的复盘：Android 15 那台死在**进入战斗后 1 秒**，日志断在 `页面 → ?(29)` 之后
 * 什么都没有（连战斗中每 30s 的心跳都没来得及打）⇒ 进程是被**当场**带走的。用户看到的现象只有
 * "游戏和工具都没了"，既不知道发生了什么，也不知道还能不能再跑。
 *
 * 所以这里只做一件事：把 CrashGuard 判出来的结论用一句人话摆出来（时间 + 当时在干什么），
 * 点「知道了」就清掉；真正的原因（崩溃栈 / tombstone / 最后状态）仍然只在日志里。
 */
@Composable
private fun AbnormalExitBar(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("知道了") }
    }
}

/**
 * 去掉更新说明开头的 `## 0.3.4（2026-10-26）` 标题行。
 *
 * 弹窗上方已经用大标题写了「发现新版本 0.3.4」，notes 里再来一遍版本号纯属重复
 * （2026-10-26 用户："发现更新的页面可以写一下更新了哪些内容"——要的是内容，不是版本号）。
 */
private fun stripVersionHeading(md: String): String {
    val lines = md.lines()
    val first = lines.indexOfFirst { it.isNotBlank() }
    if (first < 0) return ""
    return if (lines[first].trimStart().startsWith("## ")) {
        lines.drop(first + 1).joinToString("\n").trim()
    } else {
        md.trim()
    }
}

/**
 * ★「检查更新」弹窗（2026-10-25 用户："0.3.0 要带自动更新"）。
 *
 * 一个对话框走完全部状态：检查中 → 有新版/已最新/失败 → 下载（进度条）→ 安装（交给系统安装器）。
 * 之所以不用"先检查再单独弹窗"，是因为伸手点两下才能更新的体验太差。
 *
 * 不能静默安装：Android 侧只能拉起系统安装器让用户点「安装」（见 [UpdateChecker.install]）；
 * 首次还会被引导去打开「安装未知应用」开关。
 */
@Composable
private fun UpdateDialog(ctx: Context, initial: UpdateChecker.Info?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val localVer = remember { UpdateChecker.versionName(ctx) }
    var info by remember { mutableStateOf(initial) }
    var phase by remember { mutableStateOf(if (initial != null) "new" else "checking") }
    var msg by remember { mutableStateOf("") }
    var pct by remember { mutableIntStateOf(0) }
    var apkPath by remember { mutableStateOf<String?>(null) }

    fun check() {
        phase = "checking"
        msg = ""
        scope.launch {
            when (val r = UpdateChecker.check(ctx)) {
                is UpdateChecker.Check.New -> { info = r.info; phase = "new" }
                is UpdateChecker.Check.Latest -> { phase = "latest"; msg = "已经是最新版本了" }
                is UpdateChecker.Check.Failed -> {
                    // 失败要给原因（2026-10-26 用户）；日志里再留一份
                    AppState.addLog("检查更新失败：${r.message}")
                    phase = "failed"
                    msg = "检查失败：${r.message}"
                }
            }
        }
    }

    fun download() {
        val i = info ?: return
        if (UpdateChecker.isDebuggable(ctx)) {
            phase = "failed"
            msg = "当前是开发版，不能自动更新；请安装正式发布版"
            return
        }
        phase = "downloading"
        pct = 0
        scope.launch {
            try {
                val f = UpdateChecker.download(ctx, i) { p, _, _ -> pct = p }
                apkPath = f.absolutePath
                // ★下载完**直接拉起系统安装器**（2026-10-26 用户："能不能下载完自动弹出安装页面"）：
                //   原来还要停在"下载完成，可以安装了"再让用户点一下，纯属多余的一步。
                val err = UpdateChecker.install(ctx, f)
                if (err == null) {
                    onDismiss()                 // 安装器已经接管，这个弹窗不用留
                } else {
                    // 只有"要授权 / 拉不起来"这种没法自动继续的情况，才退回来让用户点「安装」
                    phase = "ready"
                    msg = err
                }
            } catch (t: Throwable) {
                // 失败要给原因（2026-10-26 用户）：日志留完整异常，界面给简短原因
                AppState.addLog("更新下载失败：${t.javaClass.simpleName}: ${t.message}")
                phase = "failed"
                msg = "下载失败：${t.message ?: "未知原因"}"
            }
        }
    }

    LaunchedEffect(Unit) { if (initial == null) check() }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 自动弹出时标题直接说"发现新版本"，比统一的"检查更新"更像人话
        title = {
            Text(
                when (phase) {
                    "new" -> "发现新版本"
                    "ready" -> "安装更新"
                    else -> "检查更新"
                },
            )
        },
        confirmButton = {
            when (phase) {
                "new" -> TextButton(onClick = { download() }) { Text("下载更新") }
                "ready" -> TextButton(onClick = {
                    val path = apkPath ?: return@TextButton
                    val err = UpdateChecker.install(ctx, java.io.File(path))
                    if (err != null) msg = err else onDismiss()
                }) { Text("安装") }
                "downloading" -> TextButton(onClick = {}, enabled = false) { Text("下载中…") }
                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            Row {
                if (phase == "new" || phase == "latest" || phase == "failed") {
                    TextButton(onClick = { check() }) { Text("重新检查") }
                }
                if (phase == "new") {
                    TextButton(onClick = {
                        info?.let { UpdateChecker.saveSkipVersionCode(ctx, it.versionCode) }
                        onDismiss()
                    }) { Text("忽略此版") }
                }
                if (phase != "ready" && phase != "downloading") {
                    TextButton(onClick = onDismiss) { Text("稍后") }
                }
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "当前版本 $localVer",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                when (phase) {
                    "checking" -> {
                        Text("正在检查…", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    "new" -> if (info == null) {
                        Text("（没有新版本信息，重新检查一下）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        val i = info!!
                        Text(
                            "发现新版本 ${i.versionName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (i.isBlocked(UpdateChecker.versionCode(ctx))) {
                            Text(
                                "这个版本必须更新后才能继续使用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        // ★只显示"更新了哪些内容"（2026-10-26 用户）：notes 开头那行是 CHANGELOG 的
                        //   `## 0.3.4（日期）`，上面已经写了「发现新版本 0.3.4」⇒ 去掉，避免版本号重复两遍。
                        val notesBody = stripVersionHeading(i.notes)
                        if (notesBody.isBlank()) {
                            Text("（这个版本没写更新说明）", style = MaterialTheme.typography.bodySmall)
                        } else {
                            MarkdownColumn(notesBody)
                        }
                        Spacer(Modifier.height(8.dp))
                        // 下载完会自动拉起安装页，所以只说大小，不再教用户"点安装"
                        Text(
                            "安装包约 ${i.size / 1048576}MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    "downloading" -> {
                        // ★只留进度条（2026-10-26 用户："下载期间可以继续使用…去掉这行，这怎么用啊"）
                        //   弹窗是模态的，用户在下载期间根本点不到别的，写"可以继续使用"是自相矛盾。
                        Text("正在下载…$pct%", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    "ready" -> {
                        // ★正常情况下载完会自动拉起安装器，走不到这里；
                        //   停在这儿基本只有一种原因：还没允许「安装未知应用」（2026-10-26 用户：提示别啰嗦）
                        Text(
                            "下载完成，点「安装」继续",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> Text(msg, style = MaterialTheme.typography.bodySmall)
                }
                if (msg.isNotEmpty() && phase != "failed") {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                // ★失败时不显示更新源 URL（2026-10-26 用户：sha256/签名/地址这类技术细节用户看不懂）；
                //   真正排查靠日志里的「检查更新失败：<原因>」，界面上只给一句人话。
                if (phase == "failed") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "可以检查一下网络，或稍后再试",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

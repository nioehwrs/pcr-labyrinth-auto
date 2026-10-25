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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.LogFile
import com.lmj.labyrinthauto.logic.team.TeamRunLog
import com.lmj.labyrinthauto.frida.RootCheck
import com.lmj.labyrinthauto.net.bilibili.CaptchaChallenge
import com.lmj.labyrinthauto.net.bilibili.GeetestPageBuilder
import com.lmj.labyrinthauto.reroll.RerollConfig
import com.lmj.labyrinthauto.reroll.RerollSettings
import com.lmj.labyrinthauto.reroll.RerollUseCase
import com.lmj.labyrinthauto.service.AutoForegroundService
import com.lmj.labyrinthauto.ui.theme.StatusColors
import kotlinx.coroutines.launch

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
    // 账号/密码提到首页层：刷开局主按钮挪到「拇指区」底部栏，需要在这里拿到
    val cred = remember { RerollSettings.loadCredentials(context) }
    var account by remember { mutableStateOf(cred.first) }
    var password by remember { mutableStateOf(cred.second) }
    var logOpen by remember { mutableStateOf(false) }
    // ★应用内「更新记录」（2026-10-23 用户）：内容就是仓库根目录那份 CHANGELOG.md
    var changelogOpen by remember { mutableStateOf(false) }
    // ★刷开局方式（接口 / 局内）提到首页层：底部「刷开局」按钮要按它决定走哪条路
    var rerollMode by remember { mutableStateOf(RerollSettings.loadRerollMode(context)) }
    // ★悬浮窗可拖动提到首页层（2026-10-20 用户：强制锁定后 UI 开关也要跟着关）
    var overlayDraggable by remember { mutableStateOf(RerollSettings.loadOverlayDraggable(context)) }
    // ★首次使用说明：状态 + 首次进首页自动弹出（标记持久化在 RerollSettings）
    var guideOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!RerollSettings.loadGuideShown(context)) guideOpen = true
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
            TopAppBar(
                title = { Text("黎明界自动化", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    TextButton(onClick = { guideOpen = true }) { Text("说明") }
                    TextButton(onClick = { changelogOpen = true }) { Text("版本") }
                    TextButton(onClick = { logOpen = !logOpen }) { Text(if (logOpen) "日志▾" else "日志▴") }
                    TextButton(onClick = { onToggleDark(!darkOn) }) { Text(if (darkOn) "浅色" else "深色") }
                },
            )
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
                                needAccount && (account.isBlank() || password.isBlank()) ->
                                    Toast.makeText(
                                        context,
                                        "接口刷开局需要账号密码（去「刷开局」页填写，或把刷开局方式改成局内）",
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
                        enabled = mazeProgress == null,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) { Text(if (mazeProgress != null) "运行中…" else "一键黎明界") }
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
                                forceOverlayDragOff(context) { overlayDraggable = it }
                                RerollSettings.saveCredentials(context, account, password)
                                scope.launch { RerollUseCase.login(context, account, password) }
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
                        account = account,
                        onAccount = { v -> account = v; RerollSettings.saveCredentials(context, v, password) },
                        password = password,
                        onPassword = { v -> password = v; RerollSettings.saveCredentials(context, account, v) },
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

    // ★应用内「更新记录」（2026-10-23 用户）：直接渲染 CHANGELOG.md 原文
    if (changelogOpen) {
        ChangelogDialog(onDismiss = { changelogOpen = false })
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
            StrategyCard("事件", defaultOpen = true) {
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
    }
}

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
    account: String,
    onAccount: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
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

    var accountOpen by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(true) }
    // 密码明文开关：右侧眼睛图标切换
    var showPwd by remember { mutableStateOf(false) }

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
                    Row(
                        Modifier.clickable { accountOpen = !accountOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle("账号", Modifier.weight(1f))
                        Text(if (accountOpen) "收起" else "展开")
                    }
                    if (accountOpen) {
                        OutlinedTextField(
                            account, onAccount,
                            label = { Text("B 站账号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            password, onPassword,
                            label = { Text("B 站密码") },
                            singleLine = true,
                            visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPwd = !showPwd }) {
                                    Icon(
                                        imageVector = if (showPwd) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showPwd) "隐藏密码" else "显示密码",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
    var shareOpen by remember { mutableStateOf(false) }
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
                        TextButton(onClick = { shareOpen = true }) { Text("分享日志") }
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
    if (shareOpen) {
        ShareLogDialog(onDismiss = { shareOpen = false })
    }
}

/**
 * 挑一份日志分享出去（2026-10-19 用户："让其他用户提供某一次的日志，能不能一键"）。
 * 列最近 40 份（根目录 = 被杀的 / done/ 完整跑完 / abort/ 中途退出），点一下进系统分享面板，
 * 微信 / QQ / 邮件都能直接把 .log 文件发出去。
 */
@Composable
private fun ShareLogDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val files = remember { LogFile.listRecent(40) }
    val teamRec = remember { TeamRunLog.stats() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("分享日志") },
        text = {
            Column {
                // ★组队记录导出（2026-10-24 用户："导出按钮设置在日志导出页面里面"）
                TeamRunExportBlock(ctx, teamRec)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "按时间倒序，点一份即弹出分享（微信/QQ/邮件）。命名：run_开始时间_类型__结果_结束时间，" +
                        "类型 MAZE=跑迷宫一局、REROLL=只刷开局一次；条目前的 done/ = 完整跑完、" +
                        "abort/ = 中途退出，没有目录前缀 = 进程被杀（没来得及收尾）。" +
                        "发「刚才那一次」按结束时间找就行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text("还没有日志文件（跑一局就有了）")
                } else {
                    LazyColumn(Modifier.height(380.dp)) {
                    items(files.size) { i ->
                        val e = files[i]
                        // ★三个动作（2026-10-19 用户：模拟器上没微信/QQ，分享面板没用）：
                        //   分享 = 系统分享面板；打开 = 文件管理器/文本查看器；复制 = 拷进公共 Download
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (e.dir.isNotEmpty()) "${e.dir}/ " else "") + e.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                )
                                Text(
                                    "${e.time} ｜ ${e.sizeKb} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                runCatching { LogFile.share(ctx, e.f) }
                                    .onFailure { err ->
                                        Toast.makeText(ctx, "分享失败：${err.message}", Toast.LENGTH_LONG).show()
                                    }
                            }) { Text("分享", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = {
                                if (!LogFile.openWithViewer(ctx, e.f)) {
                                    Toast.makeText(ctx, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("打开", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = {
                                runCatching { LogFile.copyToDownloads(ctx, e.f) }
                                    .onSuccess { Toast.makeText(ctx, "已复制到 $it", Toast.LENGTH_LONG).show() }
                                    .onFailure { err ->
                                        Toast.makeText(ctx, "复制失败：${err.message}", Toast.LENGTH_LONG).show()
                                    }
                            }) { Text("复制", style = MaterialTheme.typography.labelSmall) }
                        }
                        HorizontalDivider()
                    }
                }
            }
            }
        },
    )
}

/**
 * 「组队记录」导出块（2026-10-24 用户要求：导出按钮放进日志导出页）。
 *
 * 内容是 `logs/team_runs.jsonl` —— 每次**编队决策**一行 JSON，字段与 PC 版
 * `team_build.log_run()` 对齐（stage/池/遗物/目标队+理由/属性/权重快照/分数/questId…），
 * `run_id` 全球唯一（`stage|时间|随机`）⇒ 多台设备的记录合并时不会重复。
 * 三个动作与下面的 .txt 列表一致：分享（微信/QQ）/ 打开（文件管理器）/ 复制到公共 Download。
 */
@Composable
private fun TeamRunExportBlock(ctx: Context, s: TeamRunLog.Stats) {
    val f = remember { TeamRunLog.currentFile() }
    Text("组队记录（team_runs.jsonl）", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(2.dp))
    if (f == null || !f.exists() || s.runs == 0) {
        Text(
            "还没有组队记录 —— 跑一局、进一次编组页就会写一行（文件在 logs/ 下）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        "${s.runs} 条 ｜ ${(s.bytes + 1023) / 1024} KB" +
            (if (s.files > 1) " ｜ 另有 ${s.files - 1} 份历史（team_runs_*.jsonl 同目录）" else "") +
            "\n每次编队一行 JSON（关卡/池/遗物/目标队+理由/分数/唯一 run_id）；" +
            "导出后可直接粘进 PC 的 team_runs.jsonl 复盘",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            runCatching { LogFile.share(ctx, f) }
                .onFailure { Toast.makeText(ctx, "分享失败：${it.message}", Toast.LENGTH_LONG).show() }
        }) { Text("分享", style = MaterialTheme.typography.labelSmall) }
        TextButton(onClick = {
            if (!LogFile.openWithViewer(ctx, f)) {
                Toast.makeText(ctx, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
            }
        }) { Text("打开", style = MaterialTheme.typography.labelSmall) }
        TextButton(onClick = {
            runCatching { LogFile.copyToDownloads(ctx, f) }
                .onSuccess { Toast.makeText(ctx, "已复制到 $it", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(ctx, "复制失败：${it.message}", Toast.LENGTH_LONG).show() }
        }) { Text("复制到下载", style = MaterialTheme.typography.labelSmall) }
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
    onDismiss: () -> Unit,
) {
    val needsOverlay = overlayGranted != true
    val needsRoot = rootOk != true
    val steps = listOf(
        "1. 确认设备已获取 Root 权限（Magisk 等），并已安装本应用与游戏客户端。",
        "2. 授权权限：首次运行会弹出 Root（超级用户）授权，请允许；并在系统设置中授予「悬浮窗」权限（状态悬浮窗用，建议开启）。",
        "3. 配置：切到「刷开局」页填入 B 站账号密码；切到「策略」页按需调整 Boss 区选择、运行次数等设置。",
        "4. 启动：回到「自动迷宫」页，点「一键黎明界」开始；运行中把游戏切到后台会自动停止。",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text("使用说明（首次启动）") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                steps.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                // ★把"授权"做成可直接点的动作，避免用户漏开悬浮窗（运行时看不到状态，容易错过）
                if (needsOverlay || needsRoot) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        "还没授权的话，直接点下面按钮去开启（悬浮窗不开启则看不到运行状态条）：",
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
            }
        },
    )
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
}.getOrElse { "读不到更新记录（assets/CHANGELOG.md 缺失）：${it.message}" }

@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember { readChangelog(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("更新记录") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                text.lineSequence().forEach { raw ->
                    val line = raw.trimEnd()
                    when {
                        line.startsWith("# ") -> Unit          // 文件顶部「# 更新日志」不重复显示
                        line.startsWith("## ") -> Text(
                            line.removePrefix("## "),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                        line.startsWith("### ") -> Text(
                            line.removePrefix("### "),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        line.startsWith("- ") -> Text(
                            "· " + line.removePrefix("- "),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                        )
                        line.isBlank() -> Spacer(Modifier.height(2.dp))
                        else -> Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

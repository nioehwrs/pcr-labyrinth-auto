@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lmj.labyrinthauto.ui

import android.content.Context
import com.lmj.labyrinthauto.logic.EventEntry
import com.lmj.labyrinthauto.logic.EventFamily
import com.lmj.labyrinthauto.logic.EventPick
import com.lmj.labyrinthauto.logic.EventRetreatRule
import com.lmj.labyrinthauto.logic.EventRuleData
import com.lmj.labyrinthauto.logic.EventSettings
import com.lmj.labyrinthauto.logic.SampleLog
import com.lmj.labyrinthauto.logic.shop.ShopSampleLog
import com.lmj.labyrinthauto.data.GameData
import com.lmj.labyrinthauto.data.GuildPool
import com.lmj.labyrinthauto.data.PoolForm
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import com.lmj.labyrinthauto.core.Accounts
import com.lmj.labyrinthauto.core.ExEquipRecord
import com.lmj.labyrinthauto.core.ExEquipService
import com.lmj.labyrinthauto.core.ExEquipStore
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
import com.lmj.labyrinthauto.reroll.AreaPattern
import com.lmj.labyrinthauto.reroll.RerollConfig
import com.lmj.labyrinthauto.reroll.RerollSettings
import com.lmj.labyrinthauto.reroll.RerollUseCase
import com.lmj.labyrinthauto.service.AutoForegroundService
import com.lmj.labyrinthauto.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ★2026-11-13（用户）：全部用户反馈的列表（放在「日志与诊断包」标题旁，不在每条日志里） */
private const val FEEDBACK_LIST_URL = "https://gitee.com/nioehwrs/labyrinth-auto-release/issues?q=is%3Aall"

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
    // ★「选择多项」= 按初始公会分别设局数（2026-11-08）：状态**提到首页层**，
    //   因为底部主按钮要按它判断"能不能点"（放 MazeTab 内部 remember 的话外面拿不到）。
    var multiGuild by remember { mutableStateOf(RerollSettings.loadMultiGuild(context)) }
    var guildRuns by remember { mutableStateOf(RerollSettings.loadGuildRuns(context)) }
    // ★新增 5 个公会按 UTC+8 时间自动开放（公告「11/13 维护后」）：每 60s 刷一次，
    //   App 一直开着也能到点解锁，不用重进；判定集中在 RerollSettings.newGuildsEnabled。
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    val newGuildsOn = RerollSettings.newGuildsEnabled(nowMillis)
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
    // ★EX 装备（2026-11-06 用户）：只有"上次没跑到结束"才需要还原，且**要问一句**——
    //   玩家中途可能自己改过装备，不希望被覆盖。点「一键黎明界」时只保存、不还原。
    var pendingExEquip by remember { mutableStateOf<ExEquipRecord?>(null) }
    val exScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val id = ExEquipStore.pendingRecordId(context)
        if (id > 0L) pendingExEquip = ExEquipStore.all(context).firstOrNull { it.id == id }
    }
    // ★开关关掉就**连询问也不做**（2026-11-06 用户）：pending 留着不动，等开关打开再说
    if (pendingExEquip != null && ExEquipStore.autoRestore(context)) {
        val rec = pendingExEquip!!
        var restoring by remember { mutableStateOf(false) }
        AlertDialog(
            // 还原过程中不许点外面关掉（2026-11-06 用户：点了要能看见"正在执行"）
            onDismissRequest = { if (!restoring) pendingExEquip = null },
            title = { Text(if (restoring) "正在还原…" else "上次运行没有正常结束") },
            text = {
                Text(
                    if (restoring) {
                        "正在按 ${rec.unitCount} 个角色 / ${rec.equipCount} 件装备的快照还原，请稍候…"
                    } else {
                        "中断前保存过一份 EX 装备（${rec.unitCount} 个角色 / ${rec.equipCount} 件）。\n" +
                            "要现在还原吗？如果你之后自己调过装备，可以选「不用了」。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !restoring,
                    onClick = {
                        restoring = true
                        exScope.launch {
                            when (val r = ExEquipService.restore(context, rec)) {
                                is ExEquipService.Result.Ok -> {
                                    ExEquipStore.clearPending(context)
                                    Toast.makeText(context, r.value, Toast.LENGTH_LONG).show()
                                    pendingExEquip = null
                                }
                                is ExEquipService.Result.Err ->
                                    Toast.makeText(context, "还原失败：${r.message}", Toast.LENGTH_LONG).show()
                            }
                            restoring = false
                        }
                    },
                ) { Text(if (restoring) "还原中…" else "还原") }
            },
            dismissButton = {
                TextButton(
                    enabled = !restoring,
                    onClick = {
                        ExEquipStore.clearPending(context)
                        pendingExEquip = null
                    },
                ) { Text("不用了") }
            },
        )
    }
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
            // ★次数是否有效（用户 2026-11-08："选择多项时都是 0 或没输入 / 不选多项时是 0 或没输入
            //   ⇒ 一键黎明界不可点"）。做成**按钮不可点**，而不是点了再弹提示。
            val runsValid = if (multiGuild) {
                RerollSettings.multiGuildTotal(guildRuns, nowMillis) > 0
            } else {
                (mazeRuns.toIntOrNull() ?: 0) > 0
            }
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
                            // ★多公会模式：只校验**这次真的要跑的那几个公会**的开局角色
                            //   （不然只跑公会 1 时，其它公会没配也会被拦）
                            val multiNow = RerollSettings.loadMultiGuild(context)
                            val guildRunsNow = RerollSettings.loadGuildRuns(context)
                            val multiTotal = RerollSettings.multiGuildTotal(guildRunsNow, nowMillis)
                            val onlyGuilds = if (multiNow) {
                                RerollSettings.enabledGuildIds(nowMillis)
                                    .filter { guildRunsNow.getOrElse(it) { 0 } > 0 }.toSet()
                            } else {
                                null
                            }
                            val missingGuilds = guildPicksIncomplete(context, onlyGuilds)
                            // ★刷开局方式=接口时才需要账号（2026-10-19 用户确认：免账号模式两个启动按钮都不查账号）
                            val needAccount =
                                RerollSettings.loadRerollMode(context) == RerollSettings.REROLL_API
                            when {
                                // ★多公会模式下各次数都是 0 ⇒ 不给跑（用户 2026-11-08 明确要求）
                                multiNow && multiTotal <= 0 ->
                                    Toast.makeText(
                                        context,
                                        "多公会模式：各公会的局数都是 0，至少给一个公会给 1 局",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                // ★单公会模式配置里残留了未开放的新公会（老配置/旧包选过）⇒ 不给跑
                                !multiNow && !RerollSettings.guildEnabled(
                                    RerollSettings.loadConfig(context).guildId, nowMillis,
                                ) ->
                                    Toast.makeText(
                                        context,
                                        "当前初始公会不可用，请先在「刷开局」换一个",
                                        Toast.LENGTH_LONG,
                                    ).show()
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
                        // ★次数为 0（或没输入）⇒ 也不可点（见上面 `runsValid`）
                        enabled = mazeProgress == null && !compDownloading && runsValid,
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
                            // ★单公会模式：配置里残留未开放的新公会时，这个独立入口也要拦
                            if (!RerollSettings.guildEnabled(
                                    RerollSettings.loadConfig(context).guildId, nowMillis,
                                )
                            ) {
                                Toast.makeText(context, "当前初始公会不可用", Toast.LENGTH_LONG).show()
                                return@Button
                            }
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
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("其他") })
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
                        multiGuild = multiGuild,
                        onMultiGuild = {
                            multiGuild = it
                            RerollSettings.saveMultiGuild(context, it)
                        },
                        guildRuns = guildRuns,
                        newGuildsOn = newGuildsOn,
                        onGuildRuns = {
                            guildRuns = it
                            RerollSettings.saveGuildRuns(context, it)
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
                        newGuildsOn = newGuildsOn,
                        onRerollMode = { v ->
                            rerollMode = v
                            RerollSettings.saveRerollMode(context, v)
                        },
                    )
                    2 -> StrategyTab()
                    else -> OtherTab()
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
    /** ★多公会模式（按初始公会分别设局数）；状态在首页层，主按钮也要用它判可点性 */
    multiGuild: Boolean,
    onMultiGuild: (Boolean) -> Unit,
    /** 下标 = 初始公会 id（1..[RerollSettings.GUILD_MAX]），[0] 占位 */
    guildRuns: IntArray,
    /** ★新增公会（6..10）是否已到 UTC+8 开放时刻；未开放时输入框禁用 */
    newGuildsOn: Boolean,
    onGuildRuns: (IntArray) -> Unit,
    mazeProgress: String?,
    failStrategy: Int,
    onFailStrategy: (Int) -> Unit,
    /** ★悬浮窗可拖动（首页层 state；强制锁定时首页层同步置 false） */
    overlayDraggable: Boolean,
    onOverlayDraggable: (Boolean) -> Unit,
) {
    val context = LocalContext.current   // ★「选择多项」要直接读写设置
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
                    // ★「选择多项」（2026-11-08 用户需求）：按**初始公会**分别设局数。
                    //   ★形式：**按钮**（点一下生效、再点取消），不用 Switch
                    //     （用户 2026-11-08："原来点和不点的切换挺好的，改成开关不好看"）；
                    //   位置：紧挨标题右边（不是最右）；状态落盘，重开应用保持。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("运行次数")
                        Spacer(Modifier.width(10.dp))
                        if (!multiGuild) {
                            OutlinedButton(
                                onClick = { onMultiGuild(true) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text("选择多项", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Button(
                                onClick = { onMultiGuild(false) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text("选择多项 ✓", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (!multiGuild) {
                        NumberField("运行次数", mazeRuns, onMazeRuns, hint = "必须 ≥ 1（0 会被拒绝）")
                    } else {
                        // ★每行最多 5 个，但每个框**不小于 96dp**（用户 2026-11-08 确认）：
                        //   固定 5 个在窄屏 / 分屏 / 大字体下会被 weight 压到 label 与数字都看不清，
                        //   这里按可用宽度动态算列数（宽屏仍 5 个，窄屏自动降为 4/3/2 个一行）。
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val gap = 6.dp
                            val minField = 96.dp
                            // n 个框 + (n-1) 个间距 ⇒ 每框宽 = (W-(n-1)·gap)/n ≥ minField
                            //   ⇔ n ≤ (W+gap)/(minField+gap)
                            val perRow = ((maxWidth + gap) / (minField + gap)).toInt().coerceIn(1, 5)
                            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                RerollSettings.GUILD_IDS.chunked(perRow).forEach { rowIds ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(gap),
                                    ) {
                                        for (g in rowIds) {
                                            OutlinedTextField(
                                                // ★★始终有内容（0 也算）：Compose 的 label 只有"框里有值/获得焦点"
                                                //   才浮到边框上 ⇒ 空串时公会名会**缩回框内居中**，和「运行次数」不一致。
                                                //   用户 2026-11-08："原来必须里面有内容才会在边缘显示，初始值设一个 0 吧"。
                                                value = "${guildRuns[g]}",
                                                onValueChange = { s ->
                                                    val n = s.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                                                    onGuildRuns(
                                                        guildRuns.copyOf().also { it[g] = n.coerceAtMost(999) },
                                                    )
                                                },
                                                label = {
                                                    Text(
                                                        REROLL_GUILDS.firstOrNull { it.second == g }?.first ?: "$g",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Clip,
                                                        softWrap = false,
                                                    )
                                                },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodyMedium,
                                                // ★未开放的新公会：灰掉、不能填（已存的值保留，但不计入计划）
                                                enabled = newGuildsOn || g < RerollSettings.NEW_GUILD_FROM,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "共 ${RerollSettings.multiGuildTotal(guildRuns)} 局（0 = 跳过该公会）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
        // ★EX 装备已移到「其他」标签页（2026-11-06 用户：不要放在自动迷宫页）
    }
}

/**
 * 「EX 装备」卡片：保存 / 还原（按记录）。**纯 API 实现**（`load/index` + `unit/equip_ex`），
 * 不碰内存、不依赖 frida；凭证沿用账号卡里那套（B 服账密 / 渠道服 uid+access_key）。
 */
@Composable
private fun ExEquipCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(ExEquipStore.all(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var pickOpen by remember { mutableStateOf(false) }

    fun fmt(rec: ExEquipRecord): String {
        val t = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(rec.ts))
        // 来源标签：开跑前自动存的 vs 手动点的，一眼区分（2026-11-06 用户）
        val tag = if (rec.note.contains("自动")) "【自动】" else "【手动】"
        return "$tag $t ｜ ${rec.unitCount} 角色 / ${rec.equipCount} 件"
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("装备存档")
            Text(
                "跑黎明界会自动换装、把原本的装备拆下来。开跑前存一份，整轮结束后可以还原。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        msg = "正在读取…"
                        scope.launch {
                            when (val r = ExEquipService.snapshot(ctx)) {
                                is ExEquipService.Result.Ok -> {
                                    val rec = ExEquipStore.save(ctx, r.value)
                                    records = ExEquipStore.all(ctx)
                                    msg = "已保存：${rec.unitCount} 个角色 / ${rec.equipCount} 件装备"
                                }
                                is ExEquipService.Result.Err -> msg = r.message
                            }
                            busy = false
                        }
                    },
                ) { Text("保存当前装备") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        // ★点开时**重新读**：开跑前自动存的那份是别处写的，不刷新就看不到
                        records = ExEquipStore.all(ctx)
                        pickOpen = true
                    },
                ) { Text("还原…") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotBlank()) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (records.isNotEmpty()) {
                Text(
                    "已存 ${records.size} 条 ｜ 最近：${fmt(records.first())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (pickOpen) {
        AlertDialog(
            onDismissRequest = { pickOpen = false },
            title = { Text("选择要还原的记录") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    records.forEach { rec ->
                        TextButton(
                            onClick = {
                                pickOpen = false
                                busy = true
                                msg = "正在还原…"
                                scope.launch {
                                    when (val r = ExEquipService.restore(ctx, rec)) {
                                        is ExEquipService.Result.Ok -> msg = r.value
                                        is ExEquipService.Result.Err -> msg = "还原失败：${r.message}"
                                    }
                                    busy = false
                                }
                            },
                        ) { Text(fmt(rec), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickOpen = false }) { Text("取消") }
            },
        )
    }
}

/** 标签页 ④：其他 —— EX 装备保存/还原（2026-11-06 用户：从自动迷宫页挪出来单独放） */
@Composable
private fun OtherTab() {
    val ctx = LocalContext.current
    var autoRestore by remember { mutableStateOf(ExEquipStore.autoRestore(ctx)) }
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
                    SectionTitle("装备还原")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("跑完自动还原", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "整轮运行正常结束时，按开跑前保存的快照还原；中途异常退出则在下次打开时询问。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoRestore,
                            onCheckedChange = {
                                autoRestore = it
                                ExEquipStore.setAutoRestore(ctx, it)
                            },
                        )
                    }
                }
            }
        }
        item { ExEquipCard() }
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
        // ★「角色选择」（2026-11-13 用户）：属性印记顺序 + 不使用 NPC；默认折叠，放在「遗物选择」上面
        item {
            StrategyCard("角色选择") {
                PickSettingsCard(context)
            }
        }
        item { StrategyCard("遗物选择", implemented = false) }
        // ★「商店购买」卡（2026-11-03 用户）：买什么、买几件、刷不刷全部由内置换算法算，
        //   **没有任何可调项** —— 所以这里既不摆开关也不摆下拉框，展开只有一段说明 + 算法文档链接。
        //   卡片保持可展开/收起（而不是像以前那样标"未开放"）：它不是没做，是做了但不需要人来调。
        item {
            StrategyCard("商店购买（内置算法）") {
                Text(
                    "买什么、买几件、买完要不要刷新，都由内置算法自动决定：每次进店或刷新之后，" +
                        "按当时的货架、金币与剩余刷新次数算出一张购买清单，再照单购买。" +
                        "3 层与 5 层使用该算法，4 层仍按基础规则 —— 此处没有可调项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "查看算法说明（模型与推导）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(SHOP_ALGO_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
        }
        item {
            StrategyCard("开局角色选择", implemented = true) {
                GuildPickEditor(context)
            }
        }
        item {
            // ★「编队」（2026-11-14 用户）：编队方法（规则/模型）+ 首领编队数
            StrategyCard("编队") {
                TeamSettingsCard(context)
            }
        }
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
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                // ── 子条件 2（2026-11-03 用户需求）：按「遇到的事件 + 结算结果」判撤退 ──
                //   右侧下拉框单选：无 / 断言模式 / 逻辑模式（逻辑模式暂未实现，选了等于不判定）。
                //   ★选「无」只是**不参与判定**：断言内容原样留着，切回来立刻恢复。
                var mode2 by remember { mutableStateOf(RerollSettings.loadScoreChaseMode2(context)) }
                // ★两种模式**各自记住自己那套条件**（用户 2026-11-03）：切到哪个模式就显示哪个模式
                //   上次配的，互不覆盖。没有额外的"保存"动作——改了就落盘，和别处一样。
                var conds by remember(mode2) {
                    mutableStateOf(RerollSettings.loadScoreChaseConds(context, mode2))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("条件 2 · 遇到指定事件/结果自动撤退", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (mode2) {
                                EventRetreatRule.Mode.NONE -> "已关闭：不按事件/结果做任何判定"
                                EventRetreatRule.Mode.ASSERT ->
                                    "断言模式：命中任意一条条件就撤退（已配 ${conds.count { it.ready }} 条）"
                                EventRetreatRule.Mode.LOGIC ->
                                    "逻辑模式：条件照常编辑/记住，但**暂不生效**（已配 ${conds.count { it.ready }} 条）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SimpleDropdown(
                        items = EventRetreatRule.Mode.entries.map { DropItem(it.cn) },
                        selected = mode2.ordinal,
                        onPick = { i ->
                            val m = EventRetreatRule.Mode.entries[i]
                            mode2 = m
                            RerollSettings.saveScoreChaseMode2(context, m)
                        },
                    )
                }
                if (mode2 != EventRetreatRule.Mode.NONE) {
                    EventAssertEditor(
                        context = context,
                        conds = conds,
                        onChange = {
                            conds = it
                            RerollSettings.saveScoreChaseConds(context, it, mode2)
                        },
                    )
                }
            }
        }
    }
}

/**
 * ★条件 2「断言模式」编辑器（2026-11-03 用户需求）。
 *
 * **只有一层**：条件 2 底下就是一张条件列表，不再有"断言"那一层
 *   （用户："这两层是一样的，不要断言 123 这样分层，只保留条件层"）。
 *
 * 每条条件 = 「当遇到事件是 X 时，且结果是 Y 时」—— 一个事件下拉 + 一个结果下拉，
 *   两个下拉都是**行内式**（[InlineDropdown]），和前后文写在**同一行**里：
 *   `当遇到事件是 #2315 料理完成了！… ▾ 时，且结果是 卡1 这样应该就足够了？ → 印记 ▾ 时`
 *
 * 条件之间是**「或」**：任意一条被满足就撤退；最下方「＋ 增加条件」随意加，
 *   上限 [EventRetreatRule.MAX_CONDS] 条。
 *
 * ★事件的候选不是原始事件表，而是**手工整理的"事件族"**（[EventFamily]）：
 *   钓鱼/料理/老虎机各 3 格合成一条，结果名也由那张表定（用户 2026-11-03 整理）。
 */
@Composable
private fun EventAssertEditor(
    context: Context,
    conds: List<EventRetreatRule.Cond>,
    onChange: (List<EventRetreatRule.Cond>) -> Unit,
) {
    val families = remember { EventFamily.all }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // ★归一化一次：事件 id 补成整族、slot 归 0、结果 id 换成"结果"的规范 id
        LaunchedEffect(conds) {
            val fixed = conds.map { normalizeCond(it) }
            if (fixed != conds) onChange(fixed)
        }
        if (families.isEmpty()) {
            Text(
                "没有登记任何事件族（EventFamily.all 为空）⇒ 暂时没有可选的事件/结果",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            conds.forEachIndexed { i, cond ->
                ConditionRow(
                    cond = cond,
                    families = families,
                    onEdit = { nc -> onChange(conds.toMutableList().also { it[i] = nc }) },
                    onDelete = { onChange(conds.toMutableList().also { it.removeAt(i) }) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onChange(conds + EventRetreatRule.Cond()) },
                    enabled = conds.size < EventRetreatRule.MAX_CONDS,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("增加条件")
                }
                Text(
                    " ${conds.size}/${EventRetreatRule.MAX_CONDS}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 按事件 id 找它所属的**事件族**。
 *
 * ★**没选事件时必须返回 null**：`ids.containsAll(空)` 恒为 true，
 *   直接那么写会把"刚点增加条件、还没选事件"的空条件误判成第一条（还会被归一化写回）。
 */
private fun findFamily(ids: List<Int>): EventFamily.Family? =
    if (ids.isEmpty()) null
    else EventFamily.all.firstOrNull { it.eventIds.containsAll(ids) }

/**
 * ★条件的归一化（跟运行期 [MazeLoop.normalizeCond] 同一套口径）：
 *   · 事件 id 补成**整族**（钓鱼 = 1314/2314/3314 …）；
 *   · slot 一律归 0（选项不进句子、也不参与判定）；
 *   · 结果 id 换成 [EventFamily] 里"结果"的规范 id（老数据存的是具体 rid）；
 *   · 这个族**没有随机结果**（公会/竞技场）⇒ 降级成「遇到就撤」。
 */
private fun normalizeCond(c: EventRetreatRule.Cond): EventRetreatRule.Cond {
    val fam = findFamily(c.eventIds) ?: return c
    var out = if (c.slot == 0) c else c.copy(slot = 0)
    if (out.eventIds != fam.eventIds) out = out.copy(eventIds = fam.eventIds)
    if (!out.useResult) return out
    // ★这个族没有随机结果 ⇒ 结果那一段没意义
    if (!fam.hasRandom) return out.copy(resultId = 0, useResult = false)
    // ★"已经点开【+】但还没选结果"必须原样保留：否则这里会把它关回去，
    //   界面表现为「点 + 没反应」，运行时还会退化成"遇到就撤"（非常危险）
    if (out.resultId <= 0) return out
    val canon = EventFamily.canonical(out.resultId) ?: return out.copy(resultId = 0, useResult = false)
    return if (canon != out.resultId) out.copy(resultId = canon) else out
}

/**
 * 一条条件：整句写完「当遇到事件是 X 时（且结果是 Z 时），自动撤退」。
 *
 * ★句子里**没有"选项"那一截**（用户 2026-11-03："选项是…这一截压根不用出现在句子里"）。
 * ★事件下拉列的是**手工整理的事件族**（[EventFamily]），结果下拉列的是族里登记的结果名。
 *
 * ★【−】/【+】是**开关**（用户 2026-11-03）：放在"且"的前面 ——
 *   · 状态 A（要结果）：`当遇到事件是 X 时 [−] 且结果是 Z 时，自动撤退`
 *     【−】= **仅删掉"且…结果…"这一段** ⇒ 无视随机结果，遇到这个事件就撤；
 *   · 状态 B（不要结果）：`当遇到事件是 X 时 [+]，自动撤退`
 *     【+】= 加回"且结果是…" ⇒ **仅在落到选中的结果时**才撤。
 *   （状态 B 下没选结果 ⇒ 条件未配好、不生效 —— 不能退化成"无视结果"，否则两个按钮没区别。）
 *
 * ★族**没有随机结果**时（公会管理协会/魔物竞技场）⇒ 整段"且结果是…"不出现。
 */
@Composable
private fun ConditionRow(
    cond: EventRetreatRule.Cond,
    families: List<EventFamily.Family>,
    onEdit: (EventRetreatRule.Cond) -> Unit,
    onDelete: () -> Unit,
) {
    val fam = findFamily(cond.eventIds)
    val outcomes = fam?.outcomes.orEmpty()
    val showResult = fam?.hasRandom == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "当遇到事件是",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = 4.dp),
        )
        InlineDropdown(
            items = families.map { DropItem(it.name) },
            // ★没选事件时必须显示「（请选择）」：不能命中第一个（`containsAll(空)` 恒为 true）
            selected = if (cond.eventIds.isEmpty()) -1
            else families.indexOfFirst { it.eventIds.containsAll(cond.eventIds) },
            onPick = { i ->
                val f = families[i]
                // 换事件 ⇒ 结果重置（结果属于具体事件）
                onEdit(cond.copy(eventIds = f.eventIds, slot = 0, resultId = 0, useResult = false))
            },
            maxWidth = 260.dp,
        )
        Text(
            "时",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp),
        )
        if (showResult) {
            // 【−】删掉"且结果是…" / 【+】加回来
            IconButton(
                onClick = { onEdit(cond.copy(useResult = !cond.useResult)) },
                modifier = Modifier.size(26.dp),
            ) {
                Icon(
                    if (cond.useResult) Icons.Filled.Remove else Icons.Filled.Add,
                    contentDescription = if (cond.useResult) "无视随机结果" else "加上随机结果条件",
                    modifier = Modifier.size(14.dp),
                )
            }
            if (cond.useResult) {
                Text(
                    "且结果是",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                InlineDropdown(
                    // 结果名就是事件族表里写的那句人话（如「遗物(★1)」「成功→随机印记×2」）
                    items = outcomes.map { DropItem(it.name) },
                    selected = outcomes.indexOfFirst { it.id == cond.resultId },
                    onPick = { k ->
                        val o = outcomes.getOrNull(k) ?: return@InlineDropdown
                        onEdit(cond.copy(slot = 0, resultId = o.id))
                    },
                    maxWidth = 260.dp,
                )
                Text(
                    "时",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
                if (cond.resultId <= 0) {
                    Text(
                        "（还没选结果，这条暂时不生效）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Text(
            "，自动撤退",
            style = MaterialTheme.typography.bodySmall,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "删除这条条件")
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

/**
 * ★三选一目标配置（2026-11-14 用户）：每项 = 权重 + 生效区间。
 *
 * 权重 0 = 不参与；条件按当前节点 blockId 判断（3图前 = 只到 20601，3图后 = 30101 起，
 * 3图boss后 = 30701 起）。**每个非 0 项都会让选人多算一轮**（1 次基线 + 3 个候选）。
 */
@Composable
private fun PickTargetsEditor(context: Context) {
    var list by remember { mutableStateOf(RerollSettings.loadPickTargets(context)) }
    // ★2026-11-14（用户）：宽屏下名称和控件被拉到两端、看着费劲。整块靠右，并给 900dp 横向上限
    //   （手机窄屏时 widthIn 不生效，仍是一列铺满）。
    // ★2026-11-14（用户）：整块**靠左**排列，宽度最多 500dp —— 宽屏下控件不会飘到屏幕最右边，
    //   窄屏（手机）时 widthIn 不生效，退化成铺满一列。
    Column(
        modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("三选一目标", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "权重 0 = 不用。每多一个非 0 项，选人都要多算一轮（约 0.7 秒）—— 6 项全开约 5 秒。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        list.forEachIndexed { i, t ->
            var menu by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                // ★权重 0 = 该项不参与 ⇒ 整行置灰（只改透明度，不动背景色）
                modifier = Modifier.alpha(if (t.weight <= 0.0) 0.38f else 1f),
            ) {
                // ★名称给**固定宽度**（不用 weight 撑开）：weight 会把控件顶到行尾，
                //   宽屏下名称和控件隔着一大片空白，看着像两块东西。
                Text(
                    t.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(140.dp),
                )
                // 权重：带边框的 − 数值 + 三段
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 2.dp),
                ) {
                    TextButton(
                        onClick = {
                            if (t.weight > 0) {
                                list = list.toMutableList().also { it[i] = t.copy(weight = t.weight - 1) }
                                RerollSettings.savePickTargets(context, list)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text("−", style = MaterialTheme.typography.labelMedium) }
                    Text(
                        "%.0f".format(t.weight),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(18.dp),
                    )
                    TextButton(
                        onClick = {
                            if (t.weight < 10) {
                                list = list.toMutableList().also { it[i] = t.copy(weight = t.weight + 1) }
                                RerollSettings.savePickTargets(context, list)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) { Text("+", style = MaterialTheme.typography.labelMedium) }
                }
                // 条件：下拉框样式（带 ▾）
                Box {
                    OutlinedButton(
                        onClick = { menu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            (RerollSettings.PICK_CONDS.firstOrNull { it.first == t.cond }?.second
                                ?: "通用") + " ▾",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        RerollSettings.PICK_CONDS.forEach { (k, v) ->
                            DropdownMenuItem(
                                text = { Text(v, style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    menu = false
                                    list = list.toMutableList().also { it[i] = t.copy(cond = k) }
                                    RerollSettings.savePickTargets(context, list)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 计算方法二选一（规则 / 模型），「角色选择」「编队」两张卡共用。 */
@Composable
private fun MethodRow(
    title: String,
    desc: String,
    options: List<Pair<String, String>>,
    selected: String,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, label) ->
                FilterChip(
                    selected = selected == key,
                    onClick = { onChange(key) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

/** ★策略页「编队」（2026-11-14 用户）：编队方法 + 3 区首领编队数。 */
@Composable
private fun TeamSettingsCard(context: Context) {
    var teamMode by remember { mutableStateOf(RerollSettings.loadTeamMode(context)) }
    var bossCount by remember { mutableIntStateOf(RerollSettings.loadBossPartyCount(context)) }
    val modelState by AppState.model.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MethodRow(
            title = "编队计算方法",
            desc = "旧版规则（默认）：按关卡档的规则表算（会补坦克/破甲/治疗）；" +
                "也可以改用推理模型，或混合（第一次模型、打不过切规则）。",
            options = listOf(
                RerollSettings.TEAM_RULE to "旧版规则",
                RerollSettings.TEAM_MODEL to "推理模型",
                RerollSettings.TEAM_MIXED to "混合",
            ),
            selected = teamMode,
        ) {
            teamMode = it
            RerollSettings.saveTeamMode(context, it)
        }
        HorizontalDivider()
        Text("3 区首领编队数", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "默认只编 1 队；该节点失败过会自动改编 3 队。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = bossCount == 1,
                onClick = {
                    bossCount = 1
                    RerollSettings.saveBossPartyCount(context, 1)
                },
                label = { Text("1 队", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = bossCount == 3,
                onClick = {
                    bossCount = 3
                    RerollSettings.saveBossPartyCount(context, 3)
                },
                label = { Text("3 队", style = MaterialTheme.typography.labelSmall) },
            )
        }
        HorizontalDivider()
        Text("模型组件", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            modelState ?: "未下载（首次使用时后台自动下载，约 13MB）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ★策略页「角色选择」（2026-11-13 用户）：
 *  ① 属性印记（门票）三选一的优先顺序 —— 一行 chip，**按住左右拖动**即可排序，前 3 个高亮表示"可能选到"；
 *  ② 「不使用 NPC 角色」开关 —— 战后角色三选一优先不选 NPC 卡、组队优先不编 NPC。
 * 改动即时落盘（与「开局角色选择」一致，没有"确定"按钮）。
 */
@Composable
private fun PickSettingsCard(context: Context) {
    var order by remember { mutableStateOf(RerollSettings.loadAttrOrder(context)) }
    var excludeNpc by remember { mutableStateOf(RerollSettings.loadExcludeNpc(context)) }
    var byModel by remember { mutableStateOf(RerollSettings.loadPickByModel(context)) }
    // 拖动排序状态：被拖的 attr / 手指水平位移 / 目标位置
    var dragAttr by remember { mutableStateOf<String?>(null) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var targetIdx by remember { mutableIntStateOf(-1) }
    val chipWidths = remember { HashMap<String, Int>() }
    val gapPx = with(LocalDensity.current) { 6.dp.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MethodRow(
            title = "战后三选一计算方法",
            desc = "战后三选一用哪套逻辑：旧版规则（默认）按角色评分排序；推理模型会看「这张卡对后续首领战的帮助」。",
            options = listOf("rule" to "旧版规则", "model" to "推理模型"),
            selected = if (byModel) "model" else "rule",
        ) {
            byModel = it == "model"
            RerollSettings.savePickByModel(context, byModel)
        }
        if (byModel) {
            PickTargetsEditor(context)
        }
        HorizontalDivider()
        Text(
            "属性印记选择顺序",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "门票（印记）三选一按这个顺序取第一张；前 3 个会优先拿到。按住 chip 左右拖动即可调整。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ★拖动排序（2026-11-14 用户："点一下提到最前"反直觉）：
        //   拖的时候**不重排列表**（否则重组会打断手势），只让被拖的 chip 跟手、其余按目标位置平移让位；
        //   松手才真正落到新位置并落盘。
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val drag = dragAttr
            val from = if (drag == null) -1 else order.indexOf(drag)
            order.forEachIndexed { i, attr ->
                val isDrag = drag == attr
                val step = (chipWidths[attr] ?: 56).toFloat() + gapPx
                val shift = when {
                    drag == null || isDrag || targetIdx < 0 -> 0f
                    from < targetIdx && i in (from + 1)..targetIdx -> -step
                    from > targetIdx && i in targetIdx until from -> step
                    else -> 0f
                }
                FilterChip(
                    selected = i < 3,          // 前三高亮 = 更可能被选到
                    onClick = {},              // 只拖动排序；点击不重排，避免误触
                    label = { Text("${i + 1} $attr", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier
                        .onSizeChanged { chipWidths[attr] = it.width }
                        .graphicsLayer {
                            translationX = if (isDrag) dragDx else shift
                            alpha = if (isDrag) 0.85f else 1f
                            scaleX = if (isDrag) 1.06f else 1f
                            scaleY = if (isDrag) 1.06f else 1f
                        }
                        .pointerInput(attr) {
                            detectDragGestures(
                                onDragStart = {
                                    dragAttr = attr
                                    dragDx = 0f
                                    targetIdx = order.indexOf(attr)
                                },
                                onDragEnd = {
                                    val to = targetIdx
                                    val f = order.indexOf(attr)
                                    if (to in order.indices && to != f) {
                                        val next = order.toMutableList().apply { add(to, removeAt(f)) }
                                        order = next
                                        RerollSettings.saveAttrOrder(context, next)
                                    }
                                    dragAttr = null
                                    dragDx = 0f
                                    targetIdx = -1
                                },
                                onDragCancel = {
                                    dragAttr = null
                                    dragDx = 0f
                                    targetIdx = -1
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDx += amount.x
                                    val f = order.indexOf(attr)
                                    val stepPx = (chipWidths[attr] ?: 56).toFloat() + gapPx
                                    targetIdx = (f + (dragDx / stepPx).roundToInt())
                                        .coerceIn(0, (order.size - 1).coerceAtLeast(0))
                                },
                            )
                        },
                )
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("不使用 NPC 角色", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "开启后，战后角色三选一优先不选 NPC 卡（三张都是 NPC 时照常选）；" +
                        "组队优先只用非 NPC，凑不满一队时再用 NPC 补齐。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = excludeNpc,
                onCheckedChange = {
                    excludeNpc = it
                    RerollSettings.saveExcludeNpc(context, it)
                },
            )
        }
    }
}

/**
 * 商店购买算法的公开文档（release 仓库）。App 内不解释算法，只给入口
 * —— 2026-11-03 用户："附上关于本算法的链接，表明当前购买策略已有算法控制"。
 */
private const val SHOP_ALGO_URL =
    "https://gitee.com/nioehwrs/labyrinth-auto-release/blob/master/shop-purchase-algorithm.md"

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

/**
 * 黎明界「初始公会」表（master 库 `labyrinth_enter_guild`）。默认 id=3 = 咲恋救济院。
 * ★2026-11-13：新增 5 家的 id 已用维护后的 master 库校准为 6..10，顺序同公告
 *   （原先按公告顺序的猜测成立；同时看 [RerollSettings.GUILD_MAX]）。
 */
private val REROLL_GUILDS: List<Pair<String, Int>> = listOf(
    "美食殿堂" to 1,
    "破晓之星" to 2,
    "咲恋救济院" to 3,
    "王宫骑士团（NIGHTMARE）" to 4,
    "拉比林斯" to 5,
    "慈乐之音" to 6,
    "恶魔伪王国军" to 7,
    "伊丽莎白牧场" to 8,
    "墨丘利财团" to 9,
    "暮光流星群" to 10,
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
private fun guildPicksIncomplete(context: Context, onlyGuilds: Set<Int>? = null): List<String> {
    val data = runCatching { GameData.guildPool(context) }.getOrNull() ?: return emptyList()
    if (data.guilds.isEmpty()) return emptyList()
    val saved = RerollSettings.loadGuildPicks(context)
    val bad = ArrayList<String>()
    for ((labId, g) in data.guilds.toSortedMap()) {
        // ★多公会模式只校验这次真的要跑的公会（2026-11-08）
        if (onlyGuilds != null && labId !in onlyGuilds) continue
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
    /** ★新增公会（6..10）是否已到 UTC+8 开放时刻；未开放时 chip 禁用 */
    newGuildsOn: Boolean,
    onRerollMode: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by AppState.rerollStatus.collectAsStateWithLifecycle()
    val busy by AppState.rerollBusy.collectAsStateWithLifecycle()
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
        // 刷开局状态：跟账号卡分开，收起账号时也看得见。
        // ★2026-10-30 用户："还没有刷开局结果，这行提示应该去掉。刷开局只是个附带功能，
        //   我们主要还是要用一键黎明界，不要引导到刷开局这里来" ⇒ 没有结果/状态时整块不渲染。
        // ★2026-11-03 用户："刷开局页面里有个标签，会显示记录，感觉没有必要" ⇒
        //   **成功后不再留一条记录**（原来 summary + 「完成：…」两行内容基本重复，还常驻页面）。
        //   现在只显示「正在刷」的进度与失败原因 —— 进行中要能看出卡在第几次，失败要知道为什么。
        val statusText = status?.takeIf { busy || !it.startsWith("完成") }
        if (statusText != null) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            statusText!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (busy) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
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
                        GuildChoiceChips(
                            "初始公会",
                            REROLL_GUILDS,
                            cfg.guildId,
                            newGuildsOn,
                        ) { updateCfg { copy(guildId = it) } }
                        // ★判 Boss 白名单：两种判定模式都执行（不判 Boss = 5 个全选即可）
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
                        // ★判定模式（2026-11-03 用户）：经典 = 分数价值；自定义 = 按每区每列的节点类型
                        ChoiceChips(
                            "判定模式",
                            listOf(
                                "经典（比分数）" to RerollConfig.MODE_CLASSIC,
                                "自定义（选节点）" to RerollConfig.MODE_CUSTOM,
                            ),
                            cfg.judgeMode,
                        ) { updateCfg { copy(judgeMode = it) } }
                        if (cfg.judgeMode == RerollConfig.MODE_CUSTOM) {
                            Text(
                                "5 个区域都要能找到一条符合下面顺序的路线才算合格；" +
                                    "灰色不可改的是该列只有一种节点（含「普通战/EX战」的列固定取 EX 战）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AreaPatternEditor(
                                plans = AreaPattern.decode(cfg.areaPlan),
                                onChange = { plans -> updateCfg { copy(areaPlan = AreaPattern.encode(plans)) } },
                            )
                        } else {
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

/**
 * ★自定义判定模式：每个区域每个节点的类型（用户 2026-11-03）。
 *
 * 一行写完一条路线：`起始 → 普通战 → [印记] → 普通战 → [事件] → 遗物`
 *  · 该列只有一种可能、或池里同时有「普通战/EX战」⇒ **固定文字**，不可点（EX 规则见 [AreaPattern.locked]）；
 *  · 有多种可能 ⇒ 行内下拉（跟事件撤退条件同一套 [InlineDropdown]）。
 * 宽度不够时自动换行（[FlowRow]），区域 4/5 有 8 列也不会挤爆。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AreaPatternEditor(
    plans: List<AreaPattern.AreaPlan>,
    onChange: (List<AreaPattern.AreaPlan>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (plan in plans) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "区域 ${plan.area}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // ★固定文字和下拉**同字号 + 同高 + 垂直居中**，否则下拉那截看起来比前后文字
                    //   偏下、还大一档（用户 2026-11-03 指出）
                    val ts = MaterialTheme.typography.bodySmall
                    val slotH = 20.dp
                    plan.cols.forEachIndexed { i, want ->
                        val col = i + 1
                        val pool = AreaPattern.poolOf(plan.area, col)
                        if (col > 1) {
                            Box(Modifier.height(slotH), contentAlignment = Alignment.Center) {
                                Text(
                                    "→",
                                    style = ts,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Box(Modifier.height(slotH), contentAlignment = Alignment.Center) {
                            if (plan.locked(col) || pool.size <= 1) {
                                Text(
                                    // ★区域4 第5/6列：两列合计只有一个 EX（位置不固定）⇒ 显示成一个整体
                                    if (plan.area == 4 && col in 5..6) "（EX战+普通战）"
                                    else AreaPattern.name(want),
                                    style = ts,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                InlineDropdown(
                                    items = pool.map { DropItem(AreaPattern.name(it)) },
                                    selected = pool.indexOf(want).coerceAtLeast(0),
                                    onPick = { k ->
                                        val n = plan.cols.toMutableList().also { it[i] = pool[k] }
                                        onChange(plans.map { if (it.area == plan.area) it.copy(cols = n) else it })
                                    },
                                    maxWidth = 72.dp,
                                    textStyle = ts,
                                )
                            }
                        }
                    }
                }
            }
        }
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

/**
 * 「初始公会」专用：每行最多 5 个，chip 按文字**自然宽度**（不 weight 拉长、不截断），
 * 放不下自动换行（用户 2026-11-08："不用像第一页的输入框那样强行拉长，还是保持自然长度"）。
 * ★`selected` 仍是单个 Int、点某个 chip 就把 cfg.guildId 换成它 ⇒ 始终**只能选一个**。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuildChoiceChips(
    label: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    /** ★新增公会（6..10）是否已到 UTC+8 开放时刻；未开放时那 5 个 chip 灰掉不可点 */
    newGuildsOn: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 5,
        ) {
            options.forEach { (text, value) ->
                FilterChip(
                    enabled = newGuildsOn || value < RerollSettings.NEW_GUILD_FROM,
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
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("日志与诊断包")
                Spacer(Modifier.width(8.dp))
                // ★2026-11-13（用户）：**全部用户反馈**的列表入口放在这一级（不是每条日志里），
                //   用户能自己看处理进度；App 侧不收集任何额外用户信息。
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_LIST_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("查看反馈", style = MaterialTheme.typography.labelSmall) }
            }
        },
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
                        SampleStatsSection()
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
 * ★「信息统计」（2026-11-13 用户）：把原来散在日志页的「组队记录」「商店统计」和新加的
 *   「战斗 / 三选一遗物 / 事件」样本收成一个入口，点开展开子项。
 */
@Composable
private fun SampleStatsSection() {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("信息统计", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "组队 · 商店 · 战斗 · 遗物 · 事件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            LogBadge(if (open) "已展开" else "点开", LogFile.Tone.IDLE)
            Spacer(Modifier.width(6.dp))
            Text(
                if (open) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (open) {
            Column(Modifier.padding(start = 8.dp)) {
                TeamRunEntry(remember { TeamRunLog.stats() })
                ShopSampleEntry()
                RandomSampleEntry()
            }
        }
        HorizontalDivider()
    }
}

/**
 * 「战斗 / 遗物 / 事件」样本（2026-11-13 用户）：`logs/event_samples.jsonl` 的条数统计。
 *
 * 每局记：战斗节点 `{节点, 关卡(敌人组)}`、三选一遗物 `{节点, 候选星级}`、事件 `{节点, 事件编号}`；
 * 同一节点每局只记一次，见 [SampleLog]。
 */
@Composable
private fun RandomSampleEntry() {
    val ctx = LocalContext.current
    val stats = remember { SampleLog.stats(ctx) }
    Column(Modifier.fillMaxWidth()) {
        for (k in SampleLog.Kind.values()) {
            val one = stats[k] ?: SampleLog.One()
            val sub = when (k) {
                SampleLog.Kind.BATTLE -> "战斗 ${one.count}"
                SampleLog.Kind.RELIC ->
                    if (one.count2 > 0) "遗物 ${one.count} ｜ 事件触发 ${one.count2}" else "遗物 ${one.count}"
                SampleLog.Kind.EVENT -> "事件 ${one.count}"
            }
            SampleFileRow(
                title = k.cn + "样本",
                sub = if (one.total > 0) "$sub ｜ ${(one.bytes + 1023) / 1024} KB" else "还没有样本",
                file = SampleLog.fileOf(ctx, k),
                enabled = one.total > 0,
            )
        }
        HorizontalDivider()
    }
}

/** 单个样本文件的一行（点击展开 → 分享/打开/复制到下载） */
@Composable
private fun SampleFileRow(title: String, sub: String, file: java.io.File, enabled: Boolean) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = { open = !open })
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            LogBadge(if (enabled) "可导出" else "空", LogFile.Tone.IDLE)
        }
        if (open && enabled) {
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

/**
 * 「商店统计」（2026-11-4 用户）：`logs/shop_samples.jsonl` 的星级分布。
 *
 * 用途：验证刷新出的 1/2/3★ 是否真「各 1/3」——
 *   H_B（按星级均匀）⇒ 恒定 1/3；H_A（按件均匀）⇒ `(25−h_r)/(75−h)`，持有越多越难出。
 * 每屏（进店 / 刷新后）记一条，含 3 件的 id/星级 + 当前持有，见 [ShopSampleLog]。
 */
@Composable
private fun ShopSampleEntry() {
    val ctx = LocalContext.current
    val s = remember { ShopSampleLog.stats(ctx) }
    val f = remember { ShopSampleLog.currentFile(ctx) }
    var open by remember { mutableStateOf(false) }
    val ready = s.screens > 0 && f.exists()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = ready, onClick = { open = !open })
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("商店统计", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (ready) s.line() + " ｜ ${(s.bytes + 1023) / 1024} KB" else s.line(),
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
        if (open && ready) {
            Text(
                "每屏 3 件遗物。若占比明显偏离 33/33/33，或持有某星级越多该星级越少 ⇒ 说明不是「按星级均匀」。" +
                    "样本攒到 100 屏以上再判断。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    runCatching { LogFile.share(ctx, f) }
                        .onFailure { Toast.makeText(ctx, "分享失败：${it.message ?: "原因未知"}", Toast.LENGTH_LONG).show() }
                }) { Text("分享", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = {
                    if (!LogFile.openWithViewer(ctx, f)) {
                        Toast.makeText(ctx, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("打开", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = {
                    runCatching { LogFile.copyToDownloads(ctx, f) }
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
            "MuMu 模拟器 Android 15：打开 Root 后，还要到桌面上的「KernelSU」里再授权一次 Root（只开模拟器开关不够）。",
            "对模拟器分辨率没有要求（横屏即可，脚本按实际屏幕坐标自适应）。",
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
    // ★2026-11-14：三个组件统一由 ComponentPrep 串行准备，文案变成「组件准备 1/3…」「组件已就绪」，
    //   这里两种都要认（老文案"运行组件已就绪"仍可能来自 FridaServerInstaller 的旧路径）。
    if (status == null || status.startsWith("运行组件已就绪") || status.startsWith("组件已就绪")) return
    val downloading = status.startsWith("运行组件下载中") || status.startsWith("组件准备")
    val missing = status.startsWith("运行组件不可用")       // 架构没发布件，点也没用
    val failed = status.startsWith("运行组件下载失败") || status.startsWith("组件未就绪") || missing
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
            // ★2026-11-14：组件准备不再限制 WiFi（用户："游戏没网就是跑不了"），
            //   所以不再提示"用流量下载 42MB"；只有失败/未就绪时才给"点此重试"。
            text = when {
                downloading || missing -> status
                failed -> "$status · 点此重试"
                else -> status
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

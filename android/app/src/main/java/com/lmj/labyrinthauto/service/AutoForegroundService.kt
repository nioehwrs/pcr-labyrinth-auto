package com.lmj.labyrinthauto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.lmj.labyrinthauto.MainActivity
import com.lmj.labyrinthauto.R
import com.lmj.labyrinthauto.core.Accounts
import com.lmj.labyrinthauto.core.ExEquipService
import com.lmj.labyrinthauto.core.ExEquipStore
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.CrashGuard
import com.lmj.labyrinthauto.core.LogFile
import com.lmj.labyrinthauto.core.OverlayStatus
import com.lmj.labyrinthauto.frida.AbiRepair
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.frida.AgentBridge
import com.lmj.labyrinthauto.frida.FridaInjector
import com.lmj.labyrinthauto.frida.GameProcess
import com.lmj.labyrinthauto.frida.RootCheck
import com.lmj.labyrinthauto.frida.RootShell
import com.lmj.labyrinthauto.input.Foreground
import com.lmj.labyrinthauto.input.InputBackend
import com.lmj.labyrinthauto.input.InputBackends
import com.lmj.labyrinthauto.logic.MazeLoop
import com.lmj.labyrinthauto.logic.MazeSummary
import com.lmj.labyrinthauto.logic.RunRecord
import com.lmj.labyrinthauto.logic.Navigator
import com.lmj.labyrinthauto.reroll.InGameReroll
import com.lmj.labyrinthauto.reroll.RerollSettings
import com.lmj.labyrinthauto.reroll.RerollUseCase
import com.lmj.labyrinthauto.ui.OverlayWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 常驻前台服务：跑连跑循环（`ACTION_RUN_FULL`）、维持注入/自愈、把状态送到通知栏。
 * 通知栏只留一个动作 —— **停止**（2026-10-17 移除暂停/继续：状态机不读暂停标志，
 * 而"切回本 app 看进度"本身就会让状态机按前台安全闸门收手，暂停键从来按不到有效状态）。
 *
 * 来源说明（PLAN.md §2）：通知栏那套（渠道 + 动作 + 前台声明）
 * 结构参考 `limingjie-assistant/android/.../automation/overlay/AndroidAutomationNotificationHost.kt`，
 * 本工程按自己的需要重写。
 */
class AutoForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    /** ★连跑迷宫的那个协程（ACTION_STOP 要能把它一起停掉，否则"停止"只停看门狗 ✗） */
    private var runFull: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // ★模型组件后台预热（2026-11-14）：**不阻塞任何流程**。
        //   MainActivity 启动时也会调一次；这里补"服务被系统重启 / 没走过 Activity"的场景，
        //   否则首局编队会静默回落规则版（默认设置是模型版，用户会觉得"没生效"）。
        //   下载失败/没网都无所谓 —— LabModel 看不到文件就当模型不可用。
        scope.launch {
            runCatching { com.lmj.labyrinthauto.core.ComponentPrep.run(applicationContext) }
                .onFailure { AppState.addLog("组件准备失败：${it.message}") }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ★目标客户端（2026-10-30 用户："按当前账号的渠道启动对应的客户端"）：
        //   每个意图进来都先同步一次 —— 之后 kill / launch / pid / 前台判定 / 架构自检
        //   全部走 GameProcess.pkg()，选中的是渠道服账号就走渠道包，不再写死国服。
        // ★跑局中冻结目标客户端（2026-11-13）：连跑期间收到任意意图（PROBE/TREE/START…）都重同步，
        //   会把 GameProcess.pkg() 换成另一个渠道包；注入循环每拍按新包查 pid ⇒ 把在跑的游戏判死、
        //   甚至调起别的客户端。整轮结束（runFull/watcher 都停了）再允许同步。
        if (runFull?.isActive != true && watcher?.isActive != true) {
            GameProcess.syncTargetFromAccount(applicationContext)
        }
        when (intent?.action) {
            ACTION_INJECT -> {
                FridaInjector.start(applicationContext)
                return START_NOT_STICKY
            }

            // P2 对拍用：对一组导出逐个调用并把结果打进日志 + files/probe_app.txt
            //（PC 版跑 tools/pc_probe.py 对照）。要求前台服务已经在跑（agent 已回连）。
            ACTION_PROBE -> {
                scope.launch { AgentApi.probeAll(java.io.File(filesDir, "probe_app.txt")) }
                return START_NOT_STICKY
            }

            // ★UI 结构采集（2026-10-18，做「局内刷初始」用）：把当前页 DialogArea 的节点树
            //（类名/GameObject 名/坐标）+ labStatus 一起落到 files/tree_<tag>.json。
            // 用法：am startservice -n <pkg>/.service.AutoForegroundService -a ...TREE --es tag g01
            ACTION_TREE -> {
                val tag = intent?.getStringExtra("tag")?.takeIf { it.isNotBlank() } ?: "ui"
                scope.launch {
                    val nodes = AgentApi.tree()
                    val st = AgentApi.labStatus()
                    val obj = org.json.JSONObject()
                        .put("tag", tag)
                        .put("status", st ?: org.json.JSONObject.NULL)
                        .put("nodes", nodes ?: org.json.JSONArray())
                    val f = java.io.File(filesDir, "tree_$tag.json")
                    runCatching { f.writeText(obj.toString()) }
                    AppState.addLog("UI 树已导出（$tag）：${f.name}，${nodes?.length() ?: 0} 个节点")
                }
                return START_NOT_STICKY
            }

            // ★战斗设定读取自检（2026-10-22）：**只读不写**，验证能不能拿到 SaveDataManager 三项
            //   （连结爆发立即发动 / 发动时解除 / 自动战斗·战斗速度）。结果直接进日志。
            //   用法：am startservice -n <pkg>/.service.AutoForegroundService -a ...BATTLESET
            ACTION_BATTLESET -> {
                scope.launch {
                    val r = AgentApi.battleSettings()
                    AppState.addLog("战斗设定读取：" + (r?.toString() ?: "agent 没回（未连接/超时）"))
                }
                return START_NOT_STICKY
            }

            // ★直调按钮自检（2026-11-15，实验 · 分支 feat/direct-trigger）：**不点屏幕**，
            //   直接触发节点的点击回调（agent：常驻钩子 + onClick → EventDelegate.Execute）。
            //   用法：am startservice -n <pkg>/.service.AutoForegroundService \
            //           -a com.lmj.labyrinthauto.action.PRESS --es node btn_back [--es root dialog] [--ez probe true]
            //   probe=true 只探不点（先把"这颗按钮的 onClick 里到底挂了什么"打出来再动手）。
            ACTION_PRESS -> {
                val node = intent?.getStringExtra("node")?.takeIf { it.isNotBlank() }
                if (node == null) {
                    AppState.addLog("直调按钮：缺参数 --es node（节点名正则）")
                    return START_NOT_STICKY
                }
                val root = intent?.getStringExtra("root")?.takeIf { it.isNotBlank() }
                val probe = intent?.getBooleanExtra("probe", false) ?: false
                scope.launch {
                    val r = AgentApi.pressNode(node, root, probe)
                    AppState.addLog(
                        "直调按钮「$node」" + (if (probe) "（只探不点）" else "") + "：" +
                            (r?.toString() ?: "agent 没回（未连接/超时）"),
                    )
                }
                return START_NOT_STICKY
            }

            // ★日志落盘自检（2026-10-17）：不跑游戏也能确认"日志存到哪、后缀对不对"。
            //   写一局**假的** run（beginRun → 1 行日志 → endRun(DONE)），然后把路径报进日志；
            //   顺手也把**状态悬浮窗**推一段示例文字（开了开关的话能直接看到那一行字长什么样）。
            ACTION_LOG_TEST -> {
                scope.launch {
                    LogFile.init(applicationContext)
                    LogFile.beginRun("selftest")
                    delay(400)
                    val p = LogFile.runFilePath()
                    AppState.addLog("日志自检：写一行测试日志（beginRun 之后）")
                    delay(200)
                    LogFile.endRun(done = true, tag = "DONE", reason = "自检（假的一局，用来验证落盘）")
                    delay(400)
                    AppState.addLog(
                        "日志自检完成：刚那局 = " + p.ifEmpty { "(未拿到路径，看下面 session 路径)" } +
                            "（收尾后归档到 done/，名字带结束时间）｜ session = " + LogFile.sessionFilePath(),
                    )
                    // 悬浮窗自检：推进一段示例状态，2.5s 后清掉（不跑游戏也能确认"那一行字"在不在、位置对不对）
                    if (OverlayWindow.hasPermission() && RerollSettings.loadOverlayOn(this@AutoForegroundService)) {
                        OverlayWindow.show()
                        OverlayStatus.setRun("第 1/1 局")
                        OverlayStatus.setFloor("刷开局中")
                        OverlayStatus.setState("自检：示例状态（跑起来后会变成真实状态）")
                        AppState.addLog("悬浮窗自检：正在显示示例状态 2.5s（看游戏画面偏上、横向居中的那一行）")
                        delay(2_500)
                        // 自检结束 ⇒ 回到空闲"未运行"（其余两格清空、等级复位 IDLE）
                        OverlayStatus.setRun("未运行")
                        OverlayStatus.setFloor("")
                        OverlayStatus.setState("")
                        OverlayStatus.setLevel(OverlayStatus.Level.IDLE)
                        AppState.addLog("悬浮窗自检完成（示例状态已清掉）")
                    } else {
                        AppState.addLog("悬浮窗自检跳过：没权限或开关关着（助手首页「悬浮窗」里开）")
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_GOTO_LAB -> {
                scope.launch {
                    val input = InputBackends.pick(applicationContext)
                    if (input == null) {
                        AppState.addLog("停手：没有可用的点击后端（没有 root：frida/点击都做不了）")
                        return@launch
                    }
                    Navigator.goToLabyrinthTop(input, ctx = applicationContext)
                    refreshNotification()
                }
                return START_NOT_STICKY
            }

            // P5：走到黎明界 top → （挑战中才）出发 → 迷宫内状态机（骨架：只分类不点）
            ACTION_RUN_MAZE -> {
                scope.launch {
                    val input = InputBackends.pick(applicationContext)
                    if (input == null) {
                        AppState.addLog("停手：没有可用的点击后端（没有 root：frida/点击都做不了）")
                        return@launch
                    }
                    // 跑迷宫期间开启自愈总闸：这段时间崩了/卡死由本进程杀进程/重启；一结束就关掉
                    FridaInjector.autoRecover = true
                    try {
                        // 已在迷宫内（玩家自己进的 / 上次跑到一半）→ 不用再走前面那串，直接进状态机
                        // （这也是 P6"断线重连后接着跑"要用的入口形状）
                        // ★悬浮窗第二项：进入迷宫阶段显示「进入迷宫中」
                        OverlayStatus.setFloor("进入迷宫中")
                        if (Navigator.currentViewId() == 264) {
                            AppState.addLog("已在迷宫地图 → 直接进迷宫内状态机")
                            MazeLoop.run(input, applicationContext)
                            return@launch
                        }
                        if (!enterMazeWithRetry(input)) return@launch
                        MazeLoop.run(input, applicationContext)
                    } finally {
                        FridaInjector.autoRecover = false
                        refreshNotification()
                    }
                }
                return START_NOT_STICKY
            }

            // 一键黎明界（可**连跑**）：先刷开局（含 0 次——当前已是合格开局就直接沿用，不撤退），
            // 再进迷宫跑完全程。reroll 走服务器 API，成功后客户端打开游戏就是「进行中」，
            // 因此这里直接走「进迷宫」那套（enterMaze 会 resume 到这个合格开局）。
            // ★运行次数 = 「运行次数」设置：**一局 = 刷开局 → 跑迷宫 → 正常结算退出**。
            //   必须 ≥ 1（0/负 ⇒ 拒绝运行，提示「次数必须大于 0」）。1 = 老行为（跑一局就停）。
            //   任一步失败 ⇒ 本局判负并**结束运行**
            //   （不带着异常状态开下一局，免得把剩余次数一口气烧光）。局间不休息（固定 0：中间含刷开局过程，无需额外等待）。
            ACTION_RUN_FULL -> {
                runFull = scope.launch {
                    // ★运行前先确认 root：没 root 直接停手（frida 注入 / 点击都做不了）
                    if (RootShell.detect() == null) {
                        AppState.addLog("未检测到root权限，请检查~")
                        return@launch
                    }
                    // ★前置（2026-10-23 MuMu 15 闪退）：游戏必须装在**同一用户空间**里。
                    //   登录协议要读游戏包的签名/版本（`getPackageInfo`），查不到时会在刷开局
                    //   第一步抛 NameNotFoundException —— 与其崩在几百行深处，不如这里一句话说清楚。
                    //   ★2026-10-30：包名跟着**当前选中账号的渠道**（GameProcess.pkg()），
                    //   不再写死国服 —— 否则渠道服用户会被这里误判成"没装游戏"。
                    val gamePkg = GameProcess.pkg()
                    val serverName = Accounts.selected(applicationContext)?.server?.displayName
                    val gameInstalled = runCatching {
                        applicationContext.packageManager.getPackageInfo(gamePkg, 0)
                    }.isSuccess
                    if (!gameInstalled) {
                        AppState.addLog(
                            "停手：这台设备/这个用户空间里查不到「${serverName ?: "当前账号"}」的客户端（$gamePkg）。" +
                                "常见三种原因：① 账号页选的服务器和实际装的客户端不一致" +
                                "（换账号或改账号的服务器再试）；② 游戏装在多开/分身或工作资料里" +
                                "（本 app 看不到）；③ 压根没装。" +
                                "可先在终端跑 `pm list packages | grep priconne` 确认",
                        )
                        return@launch
                    }
                    // ★开跑前：先把"上次没还原的"补上，再存一份新的（2026-11-06 用户）。
                    //   跑黎明界会自动换装、把原本的拆下来，所以这里快照，整轮结束后按记录还原。
                    //   走的是游戏 API（load/index + unit/equip_ex），不依赖 frida；失败只记日志、不拦运行。
                    // ★先点亮"准备中…"（2026-11-06 用户：点了按钮没反应）：存快照要登录+拉数据，
                    //   要好几秒；不先置位的话按钮一直可点，看起来像"没点上"。
                    AppState.setMazeProgress("准备中…")
                    try {
                        prepareExEquipBeforeRun()
                    } finally {
                        AppState.setMazeProgress(null)
                    }

                    // 走到这里 root 必然就绪，点击后端一定是 root（InputBackends 仅 root 一种），不会为 null
                    val input = InputBackends.pick(applicationContext)!!
                    // ★★「选择多项」= 按**初始公会**分别设局数（2026-11-08 用户需求）。
                    //   展开成"每局该跑哪个公会"的计划表（`guildPlan[i]` = 第 i 局（0 基）的公会 id）；
                    //   单公会模式下这张表是空的（不切公会）。
                    //   ★换公会 = 改**刷开局的「初始公会」**（`RerollConfig.guildId`），而每次刷开局都会
                    //     `loadConfig()` 现读 ⇒ 局间改它就生效，不涉及任何游戏内 UI 操作。
                    val multi = RerollSettings.loadMultiGuild(applicationContext)
                    val guildRuns = RerollSettings.loadGuildRuns(applicationContext)
                    // ★未开放的新公会（6..10）：单公会模式直接拦，多公会模式从计划里剔除
                    //   （开放时刻按 UTC+8 判定，见 RerollSettings.newGuildsEnabled）
                    if (!multi) {
                        val curGuild = RerollSettings.loadConfig(applicationContext).guildId
                        if (!RerollSettings.guildEnabled(curGuild)) {
                            AppState.addLog(
                                "初始公会 ${RerollSettings.guildName(curGuild)} 不可用 → 已停止",
                            )
                            return@launch
                        }
                    }
                    val guildPlan: List<Int> = if (multi) {
                        RerollSettings.enabledGuildIds().flatMap { g ->
                            List(guildRuns.getOrElse(g) { 0 }.coerceAtLeast(0)) { g }
                        }
                    } else {
                        emptyList()
                    }
                    val total = if (multi) guildPlan.size else RerollSettings.loadMazeRuns(applicationContext)
                    if (total <= 0) {
                        AppState.addLog(
                            if (multi) "各公会局数都是 0 —— 至少要给一个公会给 1 局"
                            else "运行次数需要大于 0 ~",
                        )
                        return@launch
                    }
                    val totalTxt = "$total"
                    if (multi) {
                        AppState.addLog(
                            "── 多公会模式：共 $total 局 ── " +
                                RerollSettings.enabledGuildIds()
                                    .filter { guildRuns.getOrElse(it) { 0 } > 0 }
                                    .joinToString(" · ") {
                                        "${RerollSettings.guildName(it)}×${guildRuns.getOrElse(it) { 0 }}"
                                    },
                        )
                    }
                    // ★每轮运行单独一份 session（2026-11-02 用户）：否则同一次 app 启动里
                    //   连着跑「1 局」和「3 局」，日志页会并成"成功 4/3 局 · 有未完成"
                    LogFile.rotateSession()
                    AppState.addLog("── 一键黎明界：运行 $totalTxt 次 ──")

                    // 冷启动兜底：确保前台服务 + watcher + frida 注入都起来了（已在前台则空转返回）
                    startIfNeeded()

                    var done = 0
                    var idx = 0
                    // ★2026-11-11（用户反馈：撤退重开被顶号 6002）：本轮是否"被中止/没跑完"。
                    //   还原装备要走游戏 API 登录，会挤掉正在操作的客户端 ⇒ 人工中止时**不立即还原**，
                    //   只保留 pending，下次开跑前再补（那时脚本要接管，不会和玩家抢登录态）。
                    var endedByStop = false
                    MazeSummary.reset()                 // ★整轮开始：清掉上一轮的汇总
                    try {
                        while (isActive && idx < total) {
                            idx++
                            // ★多公会：开跑前把"刷开局的初始公会"切到这一局该跑的那家。
                            //   同一公会内不重复设置（避免每局都写配置 + 无谓强刷）。
                            if (multi) {
                                val g = guildPlan.getOrElse(idx - 1) { 0 }
                                if (g > 0) {
                                    val cfgNow = RerollSettings.loadConfig(applicationContext)
                                    if (cfgNow.guildId != g) {
                                        RerollSettings.saveConfig(
                                            applicationContext, cfgNow.copy(guildId = g),
                                        )
                                        // ★★必须强制重刷：不重刷就会沿用一个**别的公会**刷出来的开局
                                        //   （角色池/Boss 都不对）。
                                        //   ★两种刷法各有自己的标志位，**必须都置位**：
                                        //     · 接口刷 → `RerollUseCase.forceRerollNext`（跳过"沿用已合格开局"）
                                        //     · 局内刷 → `InGameReroll.forceRerollNext`（跳过 `takeoverCurrentMaze`
                                        //       的"当前局合格就沿用"分支，直接撤退重开）
                                        RerollUseCase.forceRerollNext = true
                                        InGameReroll.forceRerollNext = true
                                        val n = guildPlan.take(idx).count { it == g }
                                        AppState.addLog(
                                            "── 初始公会 → ${RerollSettings.guildName(g)}" +
                                                "（该公会第 $n/${guildRuns.getOrElse(g) { 0 }} 局）──",
                                        )
                                    }
                                }
                            }
                            AppState.setMazeProgress("第 $idx/$totalTxt 局")
                            OverlayStatus.setRun("第 $idx/$totalTxt 局")   // ★悬浮窗第一段
                            OverlayStatus.setLevel(OverlayStatus.Level.RUNNING)
                            AppState.addLog("════ 第 $idx/$totalTxt 局开始 ════")
                            // ★每局计时：局气泡末尾补 elapsed（如 `第 2/3 局 · 3:21`），每秒刷新
                            val runStart = System.currentTimeMillis()
                            val ticker = launch {
                                while (isActive) {
                                    val ms = System.currentTimeMillis() - runStart
                                    OverlayStatus.setRun("第 $idx/$totalTxt 局 · " + fmtElapsed(ms))
                                    delay(1000)
                                }
                            }
                            // ★本局局号（日志页按局展示用）：写进本局日志文件头
                            MazeLoop.roundLabel = "$idx/$totalTxt"
                            // ★★2026-11-12（用户反馈 iiii：0.6.14 "设置跑 10 次，刷完一次就运行结束"）：
                            //   上一局**正常完成**时会 [bringAssistantToFront] 把助手带回前台；
                            //   下一局如果直接进 MazeLoop，主循环第一轮读到"前台=助手"就判切走 →
                            //   「第 2 局没跑完 → 运行结束」。⇒ 每局开始前先把游戏顶回前台（am start
                            //   不重启进程、保留游戏内状态），再等它真的到前台。
                            if (idx > 1) {
                                val fg = runCatching {
                                    com.lmj.labyrinthauto.input.Foreground.packageName(force = true)
                                }.getOrNull()
                                if (fg != GameProcess.pkg()) {
                                    AppState.addLog("第 $idx 局开始前：前台是 ${fg ?: "读不到"} → 先把游戏带回前台")
                                    GameProcess.launch(applicationContext)
                                    val ft = SystemClock.elapsedRealtime()
                                    while (SystemClock.elapsedRealtime() - ft < 10_000L) {
                                        val p = runCatching {
                                            com.lmj.labyrinthauto.input.Foreground.packageName(force = true)
                                        }.getOrNull()
                                        if (p == GameProcess.pkg()) break
                                        delay(300)
                                    }
                                }
                            }
                            if (!runOneMaze(input)) {
                                ticker.cancel()
                                // ★策略「撤退重跑」：本局作废（不算完成、不消耗计划次数）→ 撤销当前局后继续下一局
                                if (MazeLoop.retreatRequested) {
                                    MazeLoop.retreatRequested = false
                                    AppState.addLog("════ 第 $idx 局按策略撤退 → 本局作废，继续下一局 ════")
                                    if (RerollSettings.loadRerollMode(applicationContext) == RerollSettings.REROLL_API) {
                                        // ★API 刷（2026-10-19 三修，用户纠正了"客户端在线被拒"的错误推断）：
                                        //   客户端不维持登录态、别处登录无条件挤下线 ⇒ 每局开始"reroll 登录 →
                                        //   杀进程重启游戏"后，**客户端冷启动自动登录已把 API 会话挤掉**。
                                        //   所以此刻 retire 拿旧会话必失败（"连接中断。回到标题界面。"）。
                                        //   正确顺序：杀进程（清失败页）→ **用保存的凭据重新登录**（挤掉客户端
                                        //   ——局已作废无所谓）→ retire。
                                        GameProcess.kill()
                                        delay(1_000)
                                        if (RerollUseCase.ensureLogin(applicationContext)) {
                                            RerollUseCase.retire()
                                        }
                                        // ★无论 retire 成败：下一局不许沿用（作废局的路线本来就合格，
                                        //   沿用 = 再进一遍打不过的图 → 死循环）
                                        RerollUseCase.forceRerollNext = true
                                    }
                                    // 局内刷：不调 retire（没会话，静默失败）—— 下一局 runOneMaze
                                    // 的局内刷接管会识别战斗失败页，自己点「结束」→「撤退」
                                    idx--                       // 不消耗计划次数
                                    OverlayStatus.setLevel(OverlayStatus.Level.RUNNING)
                                    continue
                                }
                                OverlayStatus.setLevel(OverlayStatus.Level.ERROR)
                                endedByStop = true
                                AppState.addLog("════ 第 $idx 局没跑完 → 运行结束（不带着异常状态开下一局）════")
                                break
                            }
                            ticker.cancel()
                            done++
                            // ★记下这局（成绩 + 奖励），供整轮结束汇总
                            MazeSummary.add(RunRecord(idx, MazeLoop.lastScore, MazeLoop.lastRewards))
                            AppState.addLog("════ 第 $idx 局完成 ✓（累计成功 $done 次）════")
                            // ★2026-11-15：局内刷开局模式下，第1局结束**不**把游戏推后台——
                            // 局内刷依赖游戏内"进行中迷宫局"的 client 状态（黎明界 top 的"挑战中"标志），
                            // 一旦推后台，模拟器回收进程 / B 服超时回收进行中迷宫局会让该会话断裂，
                            // 下一局顶回时 259 顶层标志已失效、误判"登错号"终止（小鹿日志 20261115_130256）。
                            // API 刷每局从服务器重新 enter，不依赖 client 会话，保持原样推后台。
                            if (RerollSettings.loadRerollMode(applicationContext) != RerollSettings.REROLL_IN_GAME) {
                                bringAssistantToFront()    // 正常退出 ⇒ 退回自动化工具（下一局会再顶起游戏）
                            }
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce                            // 「停止」是正常取消，不算异常
                    } catch (t: Throwable) {
                        // ★★2026-10-23（MuMu 15 "闪退"的真凶）：这里原来**只有 try/finally、没有 catch**
                        //   ⇒ `runOneMaze` 抛的任何异常（当时是"查不到游戏包 ⇒ 读不到签名"）都会冒到
                        //   `scope`（SupervisorJob 只隔离子协程，**不捕获**）⇒ 未捕获异常 ⇒ **整个 app
                        //   进程被杀**。日志停在"agent 监听已开启"，所以看着像"frida 一启动就闪退"。
                        //   任何一局出异常都不该带走进程 —— 记一行、归档日志、收尾退出即可。
                        endedByStop = true
                        AppState.addLog("════ 运行异常，本轮中止：${t.javaClass.simpleName}: ${t.message}")
                        runCatching {
                            LogFile.endRun(done = false, tag = "ABORT_EXIT", reason = "异常：${t.message}")
                        }
                    } finally {
                        // ★整轮运行**结束**（自然跑完 / 某局失败 break / 手动停止取消 / 异常）：
                        //   第一格显示"运行结束 成功/计划"，其余两格清空，等级复位 IDLE
                        AppState.setMazeProgress(null)
                        OverlayStatus.setRun("运行结束 $done/$totalTxt")
                        // ★2026-11-14（用户）：某局停手（需要人接手）时**保留**第 3 格的醒目提示，
                        //   别在这里清空 —— 否则用户根本看不到"已经停了、要你处理"。
                        if (!endedByStop) {
                            OverlayStatus.setFloor("")
                            OverlayStatus.setState("")
                        }
                        OverlayStatus.setLevel(
                            if (endedByStop) OverlayStatus.Level.ERROR else OverlayStatus.Level.IDLE,
                        )
                        // ★整轮汇总（自然跑完 / 某局失败 break / 手动停止取消 / 异常都走这里）：
                        //   只输出**已完成**的局；中断时前面跑完的也算。
                        if (done > 0) {
                            MazeSummary.summaryLines(total, done).forEach { AppState.addLog(it) }
                        }
                    }
                    // ★整轮结束：按开跑前的快照还原 EX 装备（2026-11-06 用户）。
                    //   ★2026-11-11（用户反馈"撤退重开被顶号 6002"）：**只有正常跑完才立即还原**。
                    //   还原要登录游戏 API，会挤掉正在操作的客户端 ⇒ 人工中止 / 某局失败停手时
                    //   只保留 pending，下次开跑前（或打开 App 时）再补，避免和"快速重开"抢登录态。
                    if (endedByStop) {
                        AppState.addLog(
                            "EX 装备：本轮被中止/未跑完 → 保留待还原，下次开跑前再补（避免顶号）",
                        )
                    } else {
                        restoreExEquipAfterRun()
                    }
                    // ★运行已结束 ⇒ 立刻补一次心跳（running=false）。否则进程很快被杀时，
                    //   磁盘上最后一份心跳还是 running=true，下次启动会误报"上次突然中断"（2026-11-06 用户）。
                    AppState.setRunning(false)
                    CrashGuard.beatNow("运行结束")
                    AppState.addLog("═══ 运行结束：成功 $done 次 / 计划 $totalTxt ═══")
                    FridaInjector.autoRecover = false
                    refreshNotification()
                }
                return START_NOT_STICKY
            }

            // ★只刷开局（局内模式）：刷到合格开局就**停手**，不进迷宫
            //   （和 ACTION_RUN_FULL 的区别：不做后面的 MazeLoop，跑完停在角色选择页给人接手）。
            //   job 挂到 [runFull] 上 ⇒ 通知栏/首页的「停止」也能把它取消。
            ACTION_REROLL_ONLY -> {
                runFull = scope.launch {
                    if (RootShell.detect() == null) {
                        AppState.addLog("未检测到root权限，请检查~")
                        return@launch
                    }
                    val input = InputBackends.pick(applicationContext)
                    if (input == null) {
                        AppState.addLog("停手：没有可用的点击后端（没有 root：frida/点击都做不了）")
                        return@launch
                    }
                    startIfNeeded()
                    AppState.addLog("── 只刷开局（局内，不进迷宫）──")
                    AppState.setRerollBusy(true)
                    OverlayStatus.setRun("刷开局")
                    OverlayStatus.setFloor("刷开局中（局内）")
                    OverlayStatus.setLevel(OverlayStatus.Level.RUNNING)
                    FridaInjector.autoRecover = true
                    // ★只刷开局同样由业务层独占进程操作（架构闸门重装 + InGameReroll 的 launch）
                    GameProcess.beginExternalOp()
                    // ★刷开局也要有**独立的运行日志**（2026-10-19 用户问"刷开局现在没有日志吗"）：
                    //   之前只有跑迷宫（MazeLoop）开 run_ 文件，刷开局的日志全混在 session 里，
                    //   用户想发"某一次刷开局"根本挑不出来。现在独立成 run_*_REROLL.txt，
                    //   完整（刷到合格）→ done/，失败/中断 → abort/。
                    LogFile.beginRun("reroll")
                    var ok = false
                    try {
                        // ★架构闸门（2026-10-30）：只刷开局（局内）同样要修 —— 架构不对时
                        //   InGameReroll 第 0 步就连不上 agent。重装杀进程无害（它自己会 launch）。
                        val abiFix = AbiRepair.ensure(GameProcess.pkg())
                        if (!abiFix.ok) {
                            AppState.addLog("⚠ 游戏架构修复没成（局内刷开局会读不到页面）：${abiFix.detail}")
                        }
                        ok = if (abiFix.fatal) {
                            AppState.addLog("⇒ 确定性原因（换包/装游戏/给 root 才能解决），本次不刷，避免反复杀游戏白折腾")
                            false
                        } else {
                            InGameReroll.run(input, applicationContext)
                        }
                        AppState.addLog(
                            if (ok) {
                                "刷开局完成 ✓：已停在合格开局（角色选择页），可以手动接手；" +
                                    "要自动跑迷宫请回「自动迷宫」页点一键黎明界"
                            } else {
                                "刷开局失败（看上面日志）"
                            },
                        )
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce                            // 「停止」是正常取消
                    } catch (e: Throwable) {
                        // ★同样不能让异常带走进程（2026-10-23）：原来这里 `throw e` ⇒ 未捕获 ⇒ 整个
                        //   app 闪退（和 runFull 那个是同一个坑的两处）。
                        AppState.addLog("════ 刷开局异常中止：${e.javaClass.simpleName}: ${e.message}")
                        LogFile.endRun(done = false, tag = "ABORT_EXIT", reason = "异常：${e.message}")
                    } finally {
                        GameProcess.resetExternalOps()
                        // 正常结束在这里收尾；异常分支已收尾过（endRun 幂等：runSinks 空了就跳过）
                        LogFile.endRun(
                            done = ok,
                            tag = if (ok) "DONE" else "ABORT_FAIL",
                            reason = "局内刷开局结束",
                        )
                        FridaInjector.autoRecover = false
                        AppState.setRerollBusy(false)
                        // ★第一格给一句**结束提示**再清空（2026-10-19 用户：跑迷宫结束有提示，
                        //   刷开局结束也应该有，不然闪一下就"未运行"看不出刚才跑没跑成；
                        //   顺带写上实际跑了多少轮——第 1 轮就合格和刷了 17 轮差别很大）
                        val n = InGameReroll.lastAttempt
                        OverlayStatus.setRun(
                            if (ok) "刷开局结束 ✓·$n 轮" else "刷开局结束 ✗·$n 轮",
                        )
                        OverlayStatus.setFloor("")
                        OverlayStatus.setState("")
                        OverlayStatus.setLevel(if (ok) OverlayStatus.Level.IDLE else OverlayStatus.Level.ERROR)
                        refreshNotification()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                stopAutomation()
                return START_NOT_STICKY
            }

            else -> Unit
        }
        startIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        FridaInjector.stop()
        AgentBridge.stop()
        scope.cancel()
        AppState.setRunning(false)
        super.onDestroy()
    }

    /**
     * 开跑前：只**保存**快照（2026-11-06 用户：开跑时不要自动还原——玩家可能自己改过装备）。
     * 上次异常中断留下的 pending 由 **App 启动时弹窗询问**，不在这里处理。
     * ★API 登录会把游戏客户端挤掉线，但这和"两台手机互挤"一样，不会引发错误，无需回避。
     */
    private suspend fun prepareExEquipBeforeRun() {
        when (val r = ExEquipService.snapshot(applicationContext)) {
            is ExEquipService.Result.Ok -> {
                val rec = ExEquipStore.save(applicationContext, r.value, "开跑前自动保存")
                ExEquipStore.markPending(applicationContext, rec.id)
                AppState.addLog(
                    "EX 装备已保存：${rec.unitCount} 个角色 / ${rec.equipCount} 件（跑完会还原）",
                )
            }
            is ExEquipService.Result.Err ->
                AppState.addLog("EX 装备保存失败：${r.message}（继续运行，结束后不会自动还原）")
        }
    }

    /** 有"未还原"的记录就还原（上次异常退出/中断的情况） */
    private suspend fun restoreExEquipIfPending() {
        val id = ExEquipStore.pendingRecordId(applicationContext)
        if (id <= 0L) return
        val rec = ExEquipStore.all(applicationContext).firstOrNull { it.id == id } ?: run {
            ExEquipStore.clearPending(applicationContext)
            return
        }
        when (val r = ExEquipService.restore(applicationContext, rec)) {
            is ExEquipService.Result.Ok -> {
                AppState.addLog("EX 装备：${r.value}")
                ExEquipStore.clearPending(applicationContext)
            }
            is ExEquipService.Result.Err ->
                AppState.addLog("EX 装备还原失败：${r.message}（下次打开还会提示）")
        }
    }

    /** 整轮结束：按本轮开跑前的快照还原（受「其他」页的开关控制） */
    private suspend fun restoreExEquipAfterRun() {
        if (!ExEquipStore.autoRestore(applicationContext)) {
            AppState.addLog("EX 装备：已关闭自动还原（可在「其他」页开启），本次不还原")
            return
        }
        restoreExEquipIfPending()
    }

    private fun startIfNeeded() {
        if (AppState.running.value) {
            refreshNotification()
            return
        }
        // ★Android 14/15 的 startForeground 是**会抛异常**的（2026-10-26）：
        //   `ForegroundServiceStartNotAllowedException`（后台启动被拒）、`SecurityException`（缺类型权限）、
        //   `InvalidForegroundServiceTypeException` … 以前裸调 ⇒ 抛在 onStartCommand 主线程上就是
        //   **FATAL，整个进程瞬间消失**（"工具突然没了"最典型的一种死法，且不会有任何我们自己的日志）。
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        if (started.isFailure) {
            val e = started.exceptionOrNull()
            AppState.addLog("前台服务起不来（Android 14/15 限制）：${e?.javaClass?.simpleName}: ${e?.message}")
            AppState.addLog("→ 请把助手切到前台再点开始（后台不允许拉起前台服务）；本次不自启，已退出")
            stopSelf()
            return
        }
        AppState.setRunning(true)
        AppState.addLog("服务已启动")
        watcher = scope.launch { watchLoop() }
        // P1：注入链路的状态一变就刷通知栏（否则只有心跳里那两件事变时才刷）
        scope.launch {
            combine(
                AppState.fridaStatus,
                AppState.viewLabel,
                AppState.pageName,
                AgentBridge.connected,
            ) { _, _, _, _ -> }.collect { refreshNotification() }
        }
        // 启动时自检两项**先决条件**（2026-10-17 用户："启动自动化助手的时候，检验 1、root 2、悬浮窗"）：
        //   root（frida-server/注入/点击全都要它）+ 悬浮窗权限（状态悬浮窗要它）。
        scope.launch {
            delay(3_000)
            // ★三项**各自独立**（2026-10-30 实测教训）：原来是顺序直调，只要 reportOverlay 抛一次异常
            //   （它要 OverlayWindow.init/hasPermission），后面的架构自检就整条被吞掉 ——
            //   日志里连一行都没有，只有 logcat 看得到。现在各自 try/catch，并且把"某项自己炸了"
            //   也写进日志，避免再出现"静默没跑"。
            // ★三项**各自 launch**（2026-10-30 二次修正）：哪怕某一项内部卡住或被取消，
            //   也不会挡住另外两项 —— 顺序执行时一条协程出事就是全灭，且不留下任何日志。
            launch { selfCheck("root") { reportRoot("启动自检") } }
            launch { selfCheck("悬浮窗") { reportOverlay("启动自检") } }
            launch { selfCheck("游戏架构") { reportAbi("启动自检") } }
        }
        // P1：服务一起来就把感知链路拉起来（申请 root → 注入 agent → 读页面名）
        FridaInjector.start(applicationContext)
    }

    /**
     * ★**一局完整流程**（2026-10-17 用户；2026-11-15 调顺序）：
     *   **先杀上一局的游戏** → 接口刷开局（游戏不在，系统正好回收）→ 启动同步开局 →
     *   等 agent 重连 → 进迷宫 → 跑到**正常结算退出**。
     *
     * @return true = 这一局完整跑完（连跑据此决定要不要开下一局）；false = 任一步失败/中断。
     */
    private suspend fun runOneMaze(input: InputBackend): Boolean {
        // ★悬浮窗第二项：刷开局阶段显示「刷开局中」
        OverlayStatus.setFloor("刷开局中")

        // ★★局内刷开局（2026-10-18 用户）：不走接口、不需要账号密码 —— 在游戏里正常开一局，
        //   读地图不满意就「撤退（无报酬）」，满意就停在角色选择页**直接交给 MazeLoop**（不用杀进程重启）。
        if (RerollSettings.loadRerollMode(applicationContext) == RerollSettings.REROLL_IN_GAME) {
            OverlayStatus.setFloor("刷开局中（局内）")
            FridaInjector.autoRecover = true
            // ★显式进程操作窗口：局内刷里 `AbiRepair.ensure`（重装会杀游戏）和 InGameReroll 的
            //   三处 launch 都由业务层独占；注入循环这段照常注入/读页，只是不抢发 am start。
            GameProcess.beginExternalOp()
            // ★2026-11-15：标记"是否已交给 MazeLoop"。局内刷阶段（还没进迷宫主循环）失败/中断时，
            //   finally 会清掉第二格「刷开局 第N轮」、第三格「不合格：…」这些阶段信息。
            var inMaze = false
            return try {
                // ★架构闸门（2026-10-30 用户："局内模式也要修，杀进程不是硬限制"）：
                //   局内一般不需要杀进程，但**架构不对时注入根本连不上**，不修这一步也没法跑。
                //   重装会杀进程 —— 无害：InGameReroll.run() 第 0 步 ensureGameReady() 本来就
                //   会"没进程 → GameProcess.launch → 等 agent 60s"，会自己把游戏重新拉起来。
                val abiFix = AbiRepair.ensure(GameProcess.pkg())
                if (!abiFix.ok) {
                    AppState.addLog("⚠ 游戏架构修复没成（局内刷开局会读不到页面）：${abiFix.detail}")
                    if (abiFix.fatal) {
                        AppState.addLog("⇒ 确定性原因（换包/装游戏/给 root 才能解决），本局不跑，避免反复杀游戏白折腾")
                        return false
                    }
                }
                if (!InGameReroll.run(input, applicationContext)) {
                    AppState.addLog("局内刷开局未成功 → 本局不进入迷宫")
                    return false
                }
                // ★窗口到此为止：后面 MazeLoop 看门狗 kill 之后是要靠注入器把游戏拉起来的
                GameProcess.endExternalOp()
                OverlayStatus.setFloor("进入迷宫中")
                if (Navigator.currentViewId() != 264) {
                    // 极少见（刷完却不在迷宫地图上）→ 按老路再进一次
                    if (!enterMazeWithRetry(input)) return false
                }
                inMaze = true
                MazeLoop.run(input, applicationContext)
            } finally {
                // ★2026-11-15（用户）：局内刷开局阶段（还没交给 MazeLoop）失败/中断时，
                //   清掉第二格「刷开局 第N轮」、第三格「不合格：…」这些阶段信息 —— 否则运行结束
                //   （endedByStop=true 时外层不再清）会让它们一直残留、把「运行结束」挡在后面看不清。
                //   （迷宫里切屏中断时 MazeLoop 自己会收尾清；这条是给「进迷宫之前」补上。）
                if (!inMaze) {
                    OverlayStatus.setFloor("")
                    OverlayStatus.setState("")
                }
                GameProcess.resetExternalOps()
                FridaInjector.autoRecover = false
            }
        }

        // ★★2026-11-15（用户）：顺序改为「**先杀游戏 → 再刷开局 → 启动同步**」。
        //   理由：① 上一局刚结束，游戏空闲，正常杀掉比"刷完立刻杀"更稳；
        //        ② 刷开局的十几秒正好让系统回收进程；这段时间不会有代码去读
        //           **刚死进程**的 `/proc/<pid>/cmdline`（root shell 卡 4s 的那条路径）；
        //        ③ 客户端不在线时 reroll 登录不会把 API 会话挤掉。
        //   ⚠"刷"仍必须在"launch"之前（reroll 只改服务器状态，客户端要冷启动才能同步）。
        OverlayStatus.setFloor("刷开局中")
        // 杀之前先关自愈：否则注入循环会自己 am start，把"已杀"状态又拉起来。
        FridaInjector.autoRecover = false
        GameProcess.beginExternalOp()
        val oldPid = FridaInjector.gamePid()
        try {
            // ★架构闸门提前：`AbiRepair.ensure` 重装时会杀游戏，放在刷开局前最安全
            //   （原来放在刷之后，若在这里重装会把刚刷好的开局流程打断）。
            val abiFix = AbiRepair.ensure(GameProcess.pkg())
            if (!abiFix.ok) {
                AppState.addLog("⚠ 游戏架构修复没成：${abiFix.detail}")
                if (abiFix.fatal) {
                    AppState.addLog("⇒ 确定性原因（换包/装游戏/给 root 才能解决），本局不跑，避免白等/反复重启游戏")
                    return false
                }
            }
            if (oldPid > 0) {
                AppState.addLog("上一局结束 → 先杀游戏进程（刷开局期间让系统回收）")
                GameProcess.kill()
                val goneAt = android.os.SystemClock.elapsedRealtime()
                while (android.os.SystemClock.elapsedRealtime() - goneAt < 4_000L) {
                    val p = FridaInjector.gamePid()
                    if (p <= 0 || p != oldPid) break
                    delay(300)
                }
            }
            // ★主动断旧 agent 连接：否则 connected 滞后为 true 时，注入循环会误判「进程还活着」
            AgentBridge.noteStale("上一局结束，准备刷开局")
        } finally {
            GameProcess.endExternalOp()
        }

        // ① 刷开局（登录 → enter/retire 循环；验证码出现会自动挂起等用户在弹窗解完）
        //    ★此时游戏不在，这十几秒正好让系统整理进程
        if (!RerollUseCase.reroll(applicationContext)) {
            AppState.addLog("刷开局未成功（含验证码未过 / 未刷到合格开局）→ 本局不进入迷宫")
            return false
        }

        // ② 启动游戏同步新开局（冷启动才会读到刷好的局）→ 等 agent 就绪
        OverlayStatus.setFloor("进入迷宫中")
        FridaInjector.autoRecover = true
        GameProcess.beginExternalOp()
        return try {
            // ★本次启动的代际：下面等的是「这一代游戏」的 agent 已就绪，不是任何一次 socket 连上
            val restartGen = GameProcess.kills
            AppState.addLog("刷开局完成 → 启动游戏进程同步开局")
            GameProcess.launch(applicationContext)
            val upAt = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - upAt < 10_000L) {
                val p = FridaInjector.gamePid()
                if (p > 0 && p != oldPid) break
                delay(300)
            }
            // ★窗口保持到「新 pid 已出现」：launch 之后进程还没冒出来的这几秒里，
            //   注入循环若恢复自主，会判 pid<=0 又发一次 am start ⇒ 两个启动意图打架。
            GameProcess.endExternalOp()

            // ②-b 等注入循环把这一代游戏的 agent 起来。
            // ★注入循环是常驻的，[FridaInjector.start] 幂等（服务一启动就跑过），这里只兜底确保已启动。
            // ★等 [FridaInjector.readyGen] 而不是裸 connected：它只在「agent 已连 + il2cpp domain 就绪」
            //   且代际 >= 本次 kill 时发布。裸 connected 只证明有 socket —— 残留进程里 eternalize 的
            //   脚本重连、或 domain 还没建好，都会让上层带着错状态往下跑（启动阶段的结构性缺口）。
            FridaInjector.start(applicationContext)
            val ready = withTimeoutOrNull(90_000L) {
                FridaInjector.readyGen.first { it >= restartGen }
            } != null
            if (!ready) {
                AppState.addLog("重启后 90s 内 agent 没就绪（冷启动/注入/il2cpp domain？）→ 本局停手，不碰迷宫")
                AppState.addLog("诊断：注入循环停在「${FridaInjector.stage}」；上层已放弃，下层可能仍在自愈（看上面的注入日志）")
                return false
            }

            // ③ 进迷宫跑完全程（冷启动后从登录/首页走到黎明界 top，再进这个开局）
            if (Navigator.currentViewId() == 264) {
                AppState.addLog("已在迷宫地图 → 直接进状态机")
                return MazeLoop.run(input, applicationContext)
            }
            if (!enterMazeWithRetry(input)) return false
            MazeLoop.run(input, applicationContext)
        } finally {
            GameProcess.resetExternalOps()       // 异常路径也不能把窗口留着挡住注入循环
            FridaInjector.autoRecover = false
        }
    }

    /**
     * ★悬浮窗权限自检（2026-10-17 用户："启动自动化助手的时候，检验 1、root 2、悬浮窗"）。
     * 有权限 + 用户在助手里开着开关 ⇒ 直接把状态悬浮窗显示出来（连跑期间它一直跟着 OverlayStatus 刷新）。
     * 没权限 ⇒ 只喊一声，让用户回助手首页「悬浮窗 → 去授权」（不打断正在跑的东西）。
     */
    /**
     * ★游戏架构自检（只检测、只提示，不动手）—— 实现已下沉到 [AbiRepair.report]，
     * 因为同一个结论在「打开助手」（MainActivity）和「服务启动」两处都要报，
     * 而服务只有点「开始 / 一键黎明界」才会起。
     */
    private suspend fun reportAbi(where: String) {
        AbiRepair.report(where, GameProcess.pkg())
    }

    private fun reportOverlay(where: String) {
        OverlayWindow.init(applicationContext)
        val ok = OverlayWindow.hasPermission()
        AppState.setOverlayGranted(ok)
        if (ok) {
            if (RerollSettings.loadOverlayOn(this)) OverlayWindow.show()
        } else {
            AppState.addLog("⚠ 没有悬浮窗权限（$where）：状态悬浮窗不显示（回助手首页「悬浮窗 → 去授权」）")
        }
        refreshNotification()
    }

    /**
     * 「点到黎明界 top → 出发（挑战中才点）」**带重试**。
     *
     * ★为什么必须重试（2026-10-16 实测）：一键黎明界刚 kill→launch 时，无障碍常被摘掉、
     *   只剩 `su dumpsys` 读前台，而重启后那一两秒它常返回空 ⇒ 老的 `goToLabyrinthTop` 一次读空
     *   就 `return false`、调用方直接 `return@launch` ⇒ **整条自动链路无声收摊**（游戏停在 TITLE，
     *   日志只剩"停手/自动黎明界结束"）。这里给 3 次机会，中间等 5s。
     *
     * ★但"到了 259 却没有进行中的开局"（[Navigator.EnterResult.NOT_CHALLENGING]）**不重试**（用户 2026-10-25）：
     *   那是确定性状态——正常流程先刷开局再启动游戏，查不到开局基本是登错号；重试只会空转十来秒、
     *   还把"登错号"写成"连续 3 次都没成"。此时 Navigator 已打出「请检查当前登录账号」，这里直接收手。
     */
    private suspend fun enterMazeWithRetry(input: InputBackend): Boolean {
        repeat(ENTER_RETRY) { i ->
            if (Navigator.goToLabyrinthTop(input, ctx = applicationContext)) {
                when (Navigator.enterMaze(input)) {
                    Navigator.EnterResult.ENTERED -> return true
                    Navigator.EnterResult.NOT_CHALLENGING -> return false   // 登错号/没开局：重试无意义
                    Navigator.EnterResult.FAILED -> Unit
                }
            }
            if (i < ENTER_RETRY - 1) {
                AppState.addLog("进迷宫第 ${i + 1} 次没成 → 等 ${ENTER_RETRY_GAP_MS / 1000}s 重来（冷启动那几秒前台常读不到）")
                delay(ENTER_RETRY_GAP_MS)
            }
        }
        AppState.addLog("进迷宫连续 $ENTER_RETRY 次都没成 → 停手（不再碰游戏）")
        return false
    }

    /**
     * ★一局正常结算退出后，把自动化工具（本 App）带回前台。
     * 运行期间游戏在台上，跑完退出时设备停在游戏末屏/桌面；这里显式把助手 Activity
     * 提到最前（REORDER_TO_FRONT：已有实例就直接前置，不新建），方便用户看进度/结果。
     * 下一局会由 `GameProcess.launch` 重新把游戏顶上来，互不打断。
     */
    private fun bringAssistantToFront() {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        applicationContext.startActivity(intent)
        AppState.addLog("↩ 已退回自动化工具")
    }

    /** 毫秒 → `M:SS`（超 1h 加小时位），给局气泡的每局计时用 */
    private fun fmtElapsed(ms: Long): String {
        val s = ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss)
        else String.format("%d:%02d", mm, ss)
    }

    private fun stopAutomation() {
        watcher?.cancel()
        watcher = null
        runFull?.cancel()          // ★连跑也要停（否则按了"停止"还在跑下一局 ✗）
        runFull = null
        AppState.setMazeProgress(null)
        GameProcess.resetExternalOps()      // 停服务一律清显式窗口，别把注入循环永久挡住
        FridaInjector.autoRecover = false   // 停服务一律关自愈，别留着把游戏重启了
        FridaInjector.stop()
        AgentBridge.stop()
        AppState.setRunning(false)
        CrashGuard.beatNow("已停止")     // ★把 running=false 立刻落盘，别让下次启动误报"突然中断"
        AppState.addLog("⏹ 已停止")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /**
     * "心跳"：只观察一件事并**变化时**记一行（不刷屏）—— 当前前台包名
     * （自动点击的安全闸门就看它；无障碍那条线已移除）。
     */
    private suspend fun watchLoop() {
        var lastPkg: String? = null
        while (true) {                       // 取消靠 delay() 抛 CancellationException（scope.cancel()）
            delay(WATCH_INTERVAL_MS)
            // ★2026-11-10：心跳必须**现查**（不走 2s TTL 缓存）—— 它是"切屏锁存"最稳的
            //   实时来源。局内刷开局期间点击不走 MazeLoop.tap，光靠 frontOk() 的缓存读法，
            //   切走后又恰好被自愈顶回游戏就会漏判（雷电 9 实测）。
            // ★★2026-11-18：**改用 Unity `isFocused`（零 shell）** —— 原来这里每 2s 一次
            //   `su dumpsys input`（30 次/分钟），是全机最后一条高频 dumpsys；`dumpsys` 要
            //   system_server 序列化整个服务状态，长跑会把系统拖到 lmkd 的
            //   "device is not responding" 连环杀（实测内存充裕也照杀）。
            //   · true  ⇒ 前台就是游戏
            //   · false ⇒ 游戏失焦 ⇒ 走锁存（等价于读到"别的包名"）
            //   · null  ⇒ agent 还没连上（启动阶段）⇒ 才退回 dumpsys 现查一次
            val focused = runCatching { com.lmj.labyrinthauto.frida.AgentApi.gameFocused() }.getOrNull()
            val pkg: String? = when (focused) {
                true -> com.lmj.labyrinthauto.frida.GameProcess.pkg()
                false -> {
                    Foreground.noteLostFocus()
                    "（游戏失焦）"
                }
                null -> Foreground.packageName(force = true)
            }
            if (pkg != lastPkg) {
                lastPkg = pkg
                AppState.addLog("前台切换 → ${pkg ?: "?"}")
                refreshNotification()
            }
        }
    }

    /**
     * 自检一次 root，并把结论写进 **日志 + 界面横幅 + 通知栏**（三者同一句话，免得各说各的）。
     * 只在"状态变化 / 启动 / 手动恢复后"调用，不做定时刷屏。
     */
    /**
     * 自检一次 **root**（唯一的先决条件：frida-server、注入、点击全都要它），
     * 结论写进 **日志 + 界面横幅 + 通知栏**（三者同一句话）。启动 / 需要复查时调用。
     */
    /**
     * 跑一项启动自检，**任何异常都不许影响其它项**（2026-10-30）。
     *
     * 背景：三项原来顺序直调，`reportOverlay` 一抛异常就把后面的架构自检跳过，
     * 而且异常只进 logcat、不进会话日志 ⇒ 表现成"这项压根没实现"。现在异常本身也落日志。
     * `CancellationException` 必须原样抛出，否则服务停止时协程会当成普通异常吞掉。
     */
    private suspend fun selfCheck(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // ⚠ `TimeoutCancellationException` 也是 CancellationException 的子类：
            //   一律重抛的话，"某项内部超时"会被当成"整个协程被取消"而**静默结束、不留一行日志**
            //   （2026-10-30 实测：架构自检就是这么消失的）。只有服务真的停了才往外抛。
            if (!currentCoroutineContext().isActive) throw e
            AppState.addLog("⚠ $name 自检被取消：${e.message ?: e.javaClass.simpleName}")
        } catch (t: Throwable) {
            AppState.addLog("⚠ $name 自检异常：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun reportRoot(where: String) {
        scope.launch {
            RootCheck.run(where)
            refreshNotification()
        }
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            // ★最低优先级（2026-10-17 用户："感觉不用在状态栏显示通知了"）：
            //   这条通知**不能删** —— 前台服务必须挂一条，否则系统判定没走前台流程会直接杀掉服务
            //   （我们靠这个服务做 frida 注入 + socket 通道）。所以只能做到"最不显眼"：
            //   IMPORTANCE_MIN = 不出声、不弹横幅、收在通知栏最下面一档。
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        mgr.createNotificationChannel(channel)
    }

    private fun refreshNotification() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val running = AppState.running.value
        // ★通知压到**最小**（2026-10-17 用户："感觉不用在状态栏显示通知了"）：
        //   它删不掉（前台服务的硬要求），那就只留一行"运行中"——agent/页面/连跑进度这些以前
        //   放在通知里的信息，已经由**状态悬浮窗**承担了（那才是为此做的东西），没必要再重复一遍。
        //   同时去掉 BigTextStyle：不下拉就什么都看不到。
        val state = if (running) "运行中" else "已停止"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lab)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(state)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            // 保留一个动作：**停止** —— 这是"不切回 app 也能停掉"唯一入口。
            // （暂停/继续已移除：状态机本来就不读暂停标志，切回本 app 看进度本身就会让状态机收手。）
            .addAction(0, getString(R.string.notif_action_stop), serviceAction(ACTION_STOP))
            .build()
    }

    private fun serviceAction(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, AutoForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** 「进迷宫」重试次数 / 间隔（见 [enterMazeWithRetry]）：冷启动那几秒前台常读不到，一次失败不该收摊 */
        private const val ENTER_RETRY = 3
        private const val ENTER_RETRY_GAP_MS = 5_000L

        const val ACTION_START = "com.lmj.labyrinthauto.action.START"
        const val ACTION_INJECT = "com.lmj.labyrinthauto.action.INJECT"
        const val ACTION_PROBE = "com.lmj.labyrinthauto.action.PROBE"
        const val ACTION_GOTO_LAB = "com.lmj.labyrinthauto.action.GOTO_LAB"

        /** 日志落盘自检（见 `onStartCommand` 里同名分支） */
        const val ACTION_LOG_TEST = "com.lmj.labyrinthauto.action.LOG_TEST"
        const val ACTION_RUN_MAZE = "com.lmj.labyrinthauto.action.RUN_MAZE"
        const val ACTION_RUN_FULL = "com.lmj.labyrinthauto.action.RUN_FULL"
        const val ACTION_STOP = "com.lmj.labyrinthauto.action.STOP"

        /**
         * ★只刷开局（不进迷宫）——「刷开局」页那个按钮在**局内模式**下走这里
         * （2026-10-19 用户："刷开局方式只对一键黎明界有效，对刷开局页面的按钮无效"）。
         * 接口模式仍在 UI 里直接调 [RerollUseCase.login]（要账号/要弹验证码）。
         */
        const val ACTION_REROLL_ONLY = "com.lmj.labyrinthauto.action.REROLL_ONLY"

        /** ★UI 结构采集（2026-10-18）：导出当前页节点树到 files/tree_${tag}.json */
        const val ACTION_TREE = "com.lmj.labyrinthauto.action.TREE"

        /** ★战斗设定读取自检（2026-10-22，只读）：把 SaveDataManager 三项打进日志 */
        const val ACTION_BATTLESET = "com.lmj.labyrinthauto.action.BATTLESET"

        /** ★直调按钮自检（2026-11-15，实验）：不点屏幕，直接触发节点点击回调（--es node / --es root / --ez probe） */
        const val ACTION_PRESS = "com.lmj.labyrinthauto.action.PRESS"

        private const val CHANNEL_ID = "labyrinth_auto"
        private const val NOTIFICATION_ID = 1001
        private const val WATCH_INTERVAL_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, AutoForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 通知栏动作/UI 按钮共用：给服务发一条带动作的意图。 */
        fun send(context: Context, action: String) {
            val intent = Intent(context, AutoForegroundService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action == ACTION_START) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

package com.lmj.labyrinthauto.core

import android.util.Log
import com.lmj.labyrinthauto.net.bilibili.CaptchaChallenge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内共享状态（P0 只做"看得见"）：
 *  - 服务在不在跑 / 暂停没有；
 *  - **当前前台包名**（这是 P1 纵切的种子：以后换成"当前页面名"）；
 *  - 一行行日志（app 内看，PLAN.md §7 不用网页）。
 *
 * 说明：状态放内存 + StateFlow，UI 直接 collectAsStateWithLifecycle()；
 * 日志同时会落一份到 app 私有目录（P6 再做导出），P0 先只在内存里。
 */
object AppState {
    private const val LOG_CAP = 800
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * root 自检结论（null = 还没自检）。
     * ★没有 root 就**什么都做不了**：frida-server 起不来 ⇒ agent 注入不了 ⇒ 读不到页面，
     *   "怎么点"也就无从谈起 —— 所以无障碍整条线已移除（2026-10-16 用户定）。
     */
    private val _rootOk = MutableStateFlow<Boolean?>(null)
    val rootOk: StateFlow<Boolean?> = _rootOk.asStateFlow()

    /** P1：agent 报回来的"当前最上层对话框名"（就是这一页到底在哪一页） */
    private val _pageName = MutableStateFlow<String?>(null)
    val pageName: StateFlow<String?> = _pageName.asStateFlow()

    /** P1：注入链路当前走到哪一步（准备/申请 root/注入器已起/运行中/失败原因） */
    private val _fridaStatus = MutableStateFlow<String?>(null)
    val fridaStatus: StateFlow<String?> = _fridaStatus.asStateFlow()

    /** 当前视图（agent 的 viewId 换算成人话，如 LABYRINTH_TOP(259)）——"在哪个页面"的真身份 */
    private val _viewLabel = MutableStateFlow<String?>(null)
    val viewLabel: StateFlow<String?> = _viewLabel.asStateFlow()

    /** root 不可用时的**原因说明**（null = 没问题）；界面横幅/通知栏都读它 */
    private val _rootIssue = MutableStateFlow<String?>(null)
    val rootIssue: StateFlow<String?> = _rootIssue.asStateFlow()

    // ── 刷开局（reroll）状态 ──
    private val _rerollBusy = MutableStateFlow(false)
    val rerollBusy: StateFlow<Boolean> = _rerollBusy.asStateFlow()

    private val _rerollStatus = MutableStateFlow<String?>(null)
    val rerollStatus: StateFlow<String?> = _rerollStatus.asStateFlow()
    private val _rerollCaptcha = MutableStateFlow<CaptchaChallenge?>(null)
    val rerollCaptcha: StateFlow<CaptchaChallenge?> = _rerollCaptcha.asStateFlow()

    /**
     * ★连跑迷宫的进度（2026-10-17 用户："设定好可以跑几次迷宫，一次跑迷宫是从刷开局开始，
     * 到正常结算退出结束"）。形如 `第 2/5 局`；没在连跑 = null。
     */
    private val _mazeProgress = MutableStateFlow<String?>(null)
    val mazeProgress: StateFlow<String?> = _mazeProgress.asStateFlow()

    fun setMazeProgress(p: String?) {
        _mazeProgress.value = p
    }

    /**
     * ★悬浮窗权限（null = 还没检查）。false ⇒ 助手首页给「去授权」入口；
     * 服务启动时会自检一次（用户 2026-10-17："启动自动化助手的时候，检验 1、root 2、悬浮窗"）。
     */
    private val _overlayGranted = MutableStateFlow<Boolean?>(null)
    val overlayGranted: StateFlow<Boolean?> = _overlayGranted.asStateFlow()

    fun setOverlayGranted(v: Boolean?) {
        _overlayGranted.value = v
    }

    private val _rerollResultSummary = MutableStateFlow<String?>(null)
    val rerollResultSummary: StateFlow<String?> = _rerollResultSummary.asStateFlow()

    fun setRerollBusy(value: Boolean) {
        _rerollBusy.value = value
    }

    fun setRerollStatus(status: String?) {
        _rerollStatus.value = status
    }

    fun setRerollCaptcha(challenge: CaptchaChallenge?) {
        _rerollCaptcha.value = challenge
    }

    fun setRerollResultSummary(summary: String?) {
        _rerollResultSummary.value = summary
    }

    /**
     * ★运行组件（huabanxie-server）的准备状态（2026-10-25 用户："下载解压放在启动的时候"）。
     * null = 还没开始；"已就绪" = 可以直接开跑；"下载中 37%…" = 后台在准备。
     * 由 [com.lmj.labyrinthauto.frida.FridaServerInstaller.prepare] 更新，首页显示一行。
     */
    private val _component = MutableStateFlow<String?>(null)
    val component: StateFlow<String?> = _component.asStateFlow()

    fun setComponent(status: String?) {
        _component.value = status
    }

    /** ★启动时检查到的新版本（null = 没查/已是最新）。首页据此点亮「版本•」 */
    private val _updateInfo = MutableStateFlow<UpdateChecker.Info?>(null)
    val updateInfo: StateFlow<UpdateChecker.Info?> = _updateInfo.asStateFlow()

    fun setUpdateInfo(info: UpdateChecker.Info?) {
        _updateInfo.value = info
    }

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setRootOk(value: Boolean?) {
        _rootOk.value = value
    }

    fun setPageName(name: String?) {
        _pageName.value = name
    }

    fun setFridaStatus(status: String) {
        _fridaStatus.value = status
    }

    fun setViewLabel(label: String?) {
        _viewLabel.value = label
    }

    fun setRootIssue(issue: String?) {
        _rootIssue.value = issue
    }

    /**
     * 打一行日志（自动加时间戳；超过上限丢最老的）。
     *
     * 同时往 logcat 打一份：自动化跑起来时**游戏必须在前台**（app 只能在后台），
     * 这时界面上看不到日志，用 `adb logcat -s LabAuto` 才是唯一的观测口子。
     */
    fun addLog(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        _log.value = (_log.value + stamped).takeLast(LOG_CAP)
        runCatching { Log.i(TAG, stamped) }
        // ★落盘（2026-10-17 用户："把完整跑完的日志保存下来"）：内存上限 800 行、logcat 会被轮转，
        //   只有文件能留。这里只是**入队**（单线程写、每行 flush），几乎零开销，见 [LogFile]。
        runCatching { LogFile.append(stamped) }
    }

    private const val TAG = "LabAuto"

    fun clearLog() {
        _log.value = emptyList()
    }
}

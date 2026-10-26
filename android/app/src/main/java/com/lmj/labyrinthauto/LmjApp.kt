package com.lmj.labyrinthauto

import android.app.Application
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.core.CrashGuard
import com.lmj.labyrinthauto.core.LogFile
import com.lmj.labyrinthauto.core.UpdateChecker
import com.lmj.labyrinthauto.frida.FridaServerInstaller
import com.lmj.labyrinthauto.logic.team.TeamRunLog
import com.lmj.labyrinthauto.ui.OverlayWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 进程入口，做三件"越早越好"的事：
 *  ① **把日志落盘打开**（2026-10-17 用户要求）—— 自动化跑起来时界面在后台、而 logcat 会被轮转清空，
 *     一旦日志只在内存/ logcat 里，一局跑完过一会儿就**什么都查不到**（今天上午就是这样 ✗）。
 *     在这里 init ⇒ 从进程启动第一行日志起就写文件（含"注入 frida / 进迷宫"之前的那些日志 ✓）。
 *  ② 记住 applicationContext 给**状态悬浮窗**用（它靠 WindowManager 加窗口，进程活着才在）。
 *  ③ **启动就预热**（2026-10-25 用户："不能把下载解压检查更新什么的放在启动的时候，而不是运行的时候吗"）
 *     —— 运行组件（huabanxie-server，42MB 压缩包）和"检查更新"都在启动后几秒内后台做掉，
 *     点「一键黎明界」时几乎即开即用；原来要等 20 多秒下载，游戏都进不去（真实日志见 CHANGELOG）。
 */
class LmjApp : Application() {

    /** 应用级协程（跟进程同生命周期；预热/检查都是"做不做都行"的活，失败只记日志） */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // ★崩溃兜底（2026-10-26）：必须**最先**装 —— 之后任何一行代码崩了都要能留下死因，
        //   否则"游戏和工具一起消失"这类问题查起来是空白的（详见 CrashGuard 顶部）。
        runCatching { CrashGuard.install(this) }
        runCatching { LogFile.init(this) }
        // ★组队记录（2026-10-24）：每次编队一行 JSON → logs/team_runs.jsonl（PC 组队记录页可直接读）
        runCatching { TeamRunLog.init(this) }
        runCatching { OverlayWindow.init(this) }
        // 上次进程"突然死亡"的结论（要在 LogFile.init 之后，才能落到文件里）
        runCatching { CrashGuard.report() }
        startPrewarm()
    }

    /**
     * 启动预热（延迟 6s：先让首屏和 root 自检跑起来 —— 查"设备上是否已有 huabanxie-base"要用 root，
     * 太早 su 还没就绪会被判成"没有"，白白下 42MB）。
     *
     * 两件事并行做，互不阻塞：
     *  * [FridaServerInstaller.prepare]：缓存 → 设备已有副本 → 下载（**只在 WiFi 下自动下**，省流量）；
     *  * [UpdateChecker.check]：查最新版本，结果放进 [AppState.updateInfo] 给首页点亮「版本•」。
     */
    private fun startPrewarm() {
        // ① 检查更新：**进程启动立刻发**（2026-10-26 用户："为什么要延迟？"）——
        //    它只是一次 HTTPS，跑在 IO 线程，不挡首屏；结果一设置，首页订阅到就会自动弹窗。
        //    原来的 1.5s 延迟纯属保守，没有实际理由。
        appScope.launch {
            runCatching {
                if (UpdateChecker.isDebuggable(this@LmjApp)) return@runCatching
                when (val r = UpdateChecker.check(this@LmjApp)) {
                    is UpdateChecker.Check.New -> {
                        AppState.setUpdateInfo(r.info)
                        AppState.addLog("发现新版本 ${r.info.versionName}")
                    }
                    is UpdateChecker.Check.Failed -> AppState.addLog("检查更新失败：${r.message}")
                    else -> Unit
                }
            }
        }
        // ② 运行组件预热：**不延迟**（2026-10-26 用户："如果 0s 都不会产生误判，那就设定为 0s"）。
        //    不会误判 —— RootShell.detect() 自己会阻塞等授权结果，拿不到 root 就返回"没有"，
        //    与几秒后才调用结论一致；并发重复探测的问题已在 RootShell.detect 里用 Mutex 合并。
        appScope.launch {
            runCatching { FridaServerInstaller.prepare(this@LmjApp, wifiOnly = true) }
                .onFailure { AppState.addLog("运行组件预热失败：${it.message}") }
        }
    }
}

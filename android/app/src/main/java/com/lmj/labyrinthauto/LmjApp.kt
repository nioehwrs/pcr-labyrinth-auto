package com.lmj.labyrinthauto

import android.app.Application
import com.lmj.labyrinthauto.core.LogFile
import com.lmj.labyrinthauto.logic.team.TeamRunLog
import com.lmj.labyrinthauto.ui.OverlayWindow

/**
 * 进程入口，只做两件"越早越好"的事：
 *  ① **把日志落盘打开**（2026-10-17 用户要求）—— 自动化跑起来时界面在后台、而 logcat 会被轮转清空，
 *     一旦日志只在内存/ logcat 里，一局跑完过一会儿就**什么都查不到**（今天上午就是这样 ✗）。
 *     在这里 init ⇒ 从进程启动第一行日志起就写文件（含"注入 frida / 进迷宫"之前的那些日志 ✓）。
 *  ② 记住 applicationContext 给**状态悬浮窗**用（它靠 WindowManager 加窗口，进程活着才在）。
 */
class LmjApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { LogFile.init(this) }
        // ★组队记录（2026-10-24）：每次编队一行 JSON → logs/team_runs.jsonl（PC 组队记录页可直接读）
        runCatching { TeamRunLog.init(this) }
        runCatching { OverlayWindow.init(this) }
    }
}

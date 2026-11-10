package com.lmj.labyrinthauto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.frida.AbiRepair
import com.lmj.labyrinthauto.frida.AgentApi
import com.lmj.labyrinthauto.frida.AgentBridge
import com.lmj.labyrinthauto.frida.FridaInjector
import com.lmj.labyrinthauto.frida.GameProcess
import com.lmj.labyrinthauto.frida.RootCheck
import com.lmj.labyrinthauto.reroll.RerollSettings
import com.lmj.labyrinthauto.ui.HomeScreen
import com.lmj.labyrinthauto.ui.OverlayWindow
import com.lmj.labyrinthauto.ui.theme.LabyrinthTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity：设次数 → 一键连跑 → 看日志。
 *
 * ★**不再申请通知权限**（2026-10-17 用户："感觉不用在状态栏显示通知了，权限也可以去掉"）：
 *   前台服务那条通知删不掉（系统硬要求，否则服务会被判前台流程缺失而杀掉），但：
 *   · 我们已经把它压到最小（一行"运行中"、最低优先级、锁屏隐藏）；
 *   · Android 13+ **不授予** POST_NOTIFICATIONS 时，前台服务照常运行，通知只是不显示 ⇒
 *     正好满足"状态栏别有东西"，而且不用再多一个权限弹窗。
 *   （真要停服务：助手首页「自动迷宫」页的「停止」，或通知里那个「停止」动作。）
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayWindow.init(applicationContext)
        // ★默认浅色（用户 2026-10-18："刚装应用时界面初始改为浅色"），记住上一次的选择
        var darkOn by mutableStateOf(RerollSettings.loadDarkOn(this))

        // ★助手一启动就探 root（2026-10-17 用户："助手启动的时候就知道自己有没有 root 授权吧"）：
        //   以前只有前台服务起来 3s 后才探 ⇒ 光打开助手时那一行永远是"（还没自检）"✗。
        //   第一次探会弹模拟器的超管授权框（这正是"申请 root 授权"的那一下）。
        lifecycleScope.launch { RootCheck.run("助手启动") }

        // ★架构自检（2026-10-30 实测定案）：**只检测不动手**（这一刻用户可能正自己玩着游戏）。
        //   原来只挂在 AutoForegroundService.onCreate 里 —— 那服务要等点「开始 / 一键黎明界」才起，
        //   光打开助手它从来不跑（日志里连"服务已启动"都没有）。这里和 root 自检并列。
        //   包名跟着**当前选中的账号**走（渠道服就是渠道包），别写死国服。
        //   顺手把目标包名写进 GameProcess —— 打开助手这一刻就定好，后面服务/注入直接用。
        lifecycleScope.launch {
            GameProcess.syncTargetFromAccount(applicationContext)
            AbiRepair.report("助手启动", GameProcess.pkg())
        }

        // ★UI 结构采集（2026-10-18，做「局内刷初始」时用来抓节点名）：
        //   `am start -n com.lmj.labyrinthauto/.MainActivity --es uiTag diff`
        //   → 把当前页 DialogArea 节点树落到 files/tree_<tag>.json（服务不导出，只能从 Activity 进）
        maybeDumpTree(intent)

        setContent {
            // ★深色为主、日间浅色可切（2026-10-17 用户选的方向）：状态由这里持有，
            //   因为主题必须在 HomeScreen **之外**才能生效 ⇒ 开关和值都在 Activity 这一层。
            LabyrinthTheme(dark = darkOn) {
                HomeScreen(
                    darkOn = darkOn,
                    onToggleDark = { on ->
                        darkOn = on
                        RerollSettings.saveDarkOn(this@MainActivity, on)
                    },
                )
            }
        }
    }

    /** singleTop：再次 `am start --es uiTag X` 时走这里（不重建 Activity，agent 连接不丢） */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        maybeDumpTree(intent)
    }

    /**
     * ★UI 树导出（调试用）：`tag` 非空就把当前页节点树（类名/节点名/坐标）写成
     * `files/tree_<tag>.json`。**只在手工采集 UI 结构时用**，正常跑迷宫不走这里。
     */
    private fun maybeDumpTree(intent: android.content.Intent?) {
        val tag = intent?.getStringExtra("uiTag")?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            if (!AgentBridge.connected.value) {
                // ★平时只有"跑迷宫"窗口才注入（autoRecover=false 时注入器待命），采集期间临时打开
                FridaInjector.autoRecover = true
                FridaInjector.start(applicationContext)
                var i = 0
                while (!AgentBridge.connected.value && i++ < 60) delay(1000)
                if (!AgentBridge.connected.value) {
                    AppState.addLog("UI 树导出失败：agent 未连上（游戏没起 / 注入失败）")
                    return@launch
                }
            }
            val nodes = AgentApi.tree()
            val st = AgentApi.labStatus()
            val obj = org.json.JSONObject()
                .put("tag", tag)
                .put("status", st ?: org.json.JSONObject.NULL)
                .put("diffNum", AgentApi.obj("labDiffNum", timeoutMs = 3_000L) ?: org.json.JSONObject.NULL)
                .put("nodes", nodes ?: org.json.JSONArray())
            val f = java.io.File(filesDir, "tree_$tag.json")
            runCatching { f.writeText(obj.toString()) }
            AppState.addLog("UI 树已导出（$tag）：${f.name}，${nodes?.length() ?: 0} 个节点")
        }
    }

    /**
     * ★从系统"悬浮窗权限"设置页回来时**重新查一次**（2026-10-17）：权限是用户在那个页面里给的，
     * app 这边只有回到前台才会知道。查到"刚给了权限 + 用户开着开关"就顺手把悬浮窗显示出来。
     * ★2026-11-10：开关默认值改成「开」⇒ 从授权页回来就会自动显示，不用再手动拨一次。
     */
    override fun onResume() {
        super.onResume()
        val granted = OverlayWindow.hasPermission()
        AppState.setOverlayGranted(granted)
        if (granted && RerollSettings.loadOverlayOn(this)) OverlayWindow.show()
    }
}

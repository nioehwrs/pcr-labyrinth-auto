package com.lmj.labyrinthauto.input

import android.content.Context
import com.lmj.labyrinthauto.core.AppState
import com.lmj.labyrinthauto.frida.RootShell

/**
 * 点击后端：**只认 root**（`su -c input tap/swipe`，与 PC 版同一条路）。
 * ★不再有"无障碍兜底"：没 root 时 frida-server 起不来 ⇒ agent 注入不了 ⇒ 读不到页面，
 *   点击后端也就无从谈起（2026-10-16 用户定，无障碍整条线已移除）。
 * 两个都没有 → null（调用方必须停手，不许"猜着点"）。
 */
object InputBackends {

    suspend fun pick(ctx: Context): InputBackend? {
        if (RootShell.detect() != null) {
            AppState.addLog("点击后端 = root（input tap/swipe/keyevent，与 PC 版同一条路）")
            return RootInputBackend()
        }
        // ★没有 root 就**什么都做不了**：frida-server 起不来 ⇒ agent 注入不了 ⇒ 读不到页面
        //   （感知链路第一步就要 root），那"怎么点"也就没有意义了（用户 2026-10-16）。
        //   所以**不再退无障碍**：直接说清原因、停手。
        AppState.addLog("没有 root：frida 注入/读页面都做不了（点击后端也就无从谈起）→ 停手")
        return null
    }
}

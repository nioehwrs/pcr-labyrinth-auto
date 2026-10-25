package com.lmj.labyrinthauto.frida

import com.lmj.labyrinthauto.core.AppState

/**
 * ★root 授权的"探测 + 状态落库"（2026-10-17 用户："助手启动的时候就知道自己有没有 root 授权吧？
 * 或者也搞个小按钮，能申请 root 授权的，或者写已获得 root 权限"）。
 *
 * 为什么单独一层：探测本身在 [RootShell.detect]（跑 `su -c id`，**第一次会弹模拟器的授权框**），
 * 而"结论写到哪"（AppState 的三个字段 + 日志）以前只写在前台服务里 ⇒ 助手启动时如果服务还没起，
 * 界面上只能显示"（还没自检）"✗。现在**助手启动**（MainActivity）和**服务启动**、以及界面上的
 * 「检查 / 申请 root」按钮都走这一个函数，三处结论完全一致。
 *
 * ⚠失败**不缓存**（[RootShell] 只缓存成功的写法）⇒ 按钮可以反复点：每次都会再弹一次授权框，
 * 用户在模拟器里选"允许/永久记住"之后立刻就能变成"已获得 root 权限 ✓"。
 */
object RootCheck {

    /**
     * 探测一次 root 并把结论写进 AppState（`rootOk` / `rootIssue`）+ 日志。
     * @param where 出现在日志里的"谁在问"（启动自检 / 手动检查 / 服务启动）
     * @return true = 已获得 root 授权
     */
    suspend fun run(where: String): Boolean {
        val ok = RootShell.detect() != null
        AppState.setRootOk(ok)
        if (ok) {
            // detect() 自己会打"root 就绪（写法：…）"，这里只把横幅原因清掉，不重复刷日志
            if (AppState.rootIssue.value != null) AppState.setRootIssue(null)
        } else {
            AppState.setRootIssue(
                "没有 root：frida 注入 / 读页面 / 点击都做不了（模拟器设置里打开 Root 权限，并在授权框里允许本 app）",
            )
            AppState.addLog(
                "⚠ 没有 root（$where）：frida 注入/读页面/点击都做不了" +
                    " → 先在模拟器设置里打开 Root（MuMu 新版本默认关），再点界面上的「检查 / 申请 root」",
            )
        }
        return ok
    }
}

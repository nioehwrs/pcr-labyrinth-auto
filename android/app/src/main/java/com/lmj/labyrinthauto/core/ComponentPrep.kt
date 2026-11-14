package com.lmj.labyrinthauto.core

import android.content.Context
import com.lmj.labyrinthauto.frida.FridaCore
import com.lmj.labyrinthauto.frida.FridaServerInstaller
import com.lmj.labyrinthauto.labmodel.LabModelInstaller
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 三个下载组件（2026-11-14 用户：「都变成必须下载的，都放同一个进度条里」）。
 *
 * 原来两处各下各的：`LmjApp` 下运行组件（server + bridge）、`MainActivity` 下模型，
 * 两边都写 [AppState.setComponent]，并行时进度会互相覆盖、看起来像倒退。
 *
 * 现在统一走这里 —— **串行**：运行组件 → 注入桥 → 模型组件，同一个进度条按阶段推进；
 * 每阶段内部仍由各自的下载逻辑报百分比（`运行组件下载中 42%`），阶段文案是 `组件准备 2/3：…`。
 *
 * ★不再限制"只在 WiFi 下自动下"：这套工具本来就是联网用的，245MB 一次下完更省心；
 * 失败就保持"组件未就绪"，下次启动/开跑时重试。
 */
object ComponentPrep {

    private val lock = Mutex()

    @Volatile
    private var done = false

    val ready: Boolean get() = done

    /** 幂等、并发安全：三个都就绪后直接返回 true，不再重复下载。 */
    suspend fun run(ctx: Context): Boolean {
        if (done) return true
        return lock.withLock {
            if (done) return true
            val app = ctx.applicationContext

            AppState.setComponent("组件准备 1/3：运行组件…")
            val server = runCatching { FridaServerInstaller.prepare(app, wifiOnly = false) }
                .onFailure { AppState.addLog("运行组件准备失败：${it.message}") }
                .getOrNull()

            AppState.setComponent("组件准备 2/3：运行组件…")
            val bridge = runCatching { FridaServerInstaller.prepareBridge(app, wifiOnly = false) }
                .onFailure { AppState.addLog("注入桥准备失败：${it.message}") }
                .getOrNull()
            if (bridge != null) runCatching { FridaCore.ensureLoaded(app) }

            AppState.setComponent("组件准备 3/3：模型组件…")
            val model = runCatching {
                // ★2026-11-14（用户："游戏没网就是跑不了，没必要因为没网降级"）：
                //   模型和运行组件同级 —— 下载/校验/加载失败就算组件未就绪，不再静默退规则版。
                //   只有"关卡没学过 / 认识的角色不足 5 人"这种模型能力边界才回落（那不是网络问题）。
                LabModelInstaller.ensure(app) && com.lmj.labyrinthauto.labmodel.LabModel.load(app)
            }.onFailure { AppState.addLog("模型组件准备失败：${it.message}") }
                .getOrDefault(false)

            // 能跑的最低要求：注入桥 + 模型（server 可能复用设备上的副本，不能只看它是否为 null）
            val ok = bridge != null && model
            done = ok
            AppState.setComponent(if (ok) "组件已就绪" else "组件未就绪（联网后自动重试）")
            AppState.addLog(
                "组件准备完成：运行组件=${if (server == null) "复用/设备已有" else "已下载"}" +
                    "、注入桥=${if (bridge == null) "未就绪" else "已就绪"}" +
                    "、模型=${if (model) "已就绪" else "先用规则版"}",
            )
            ok
        }
    }
}

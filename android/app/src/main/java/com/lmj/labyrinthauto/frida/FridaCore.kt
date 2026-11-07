package com.lmj.labyrinthauto.frida

import android.content.Context
import com.lmj.labyrinthauto.core.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 方案 B 的 Kotlin 门面：app 内嵌 `libfrida-core`（静态链进 `libfrida_bridge.so`），
 * **当 frida-server 的客户端**去注入 agent —— 不是自己注入。
 *
 * 为什么只能当客户端：见 `tools/frida_bridge/bridge.c` 顶部那段实测结论
 * （自己注入的两种实现都会被这个游戏的反作弊打死，frida-server 那条路安全）。
 *
 * ★**2026-11-4**：`libfrida_bridge.so` 有 121MB（占 APK 体积 90%），已从 APK 里摘出来，
 *   与 huabanxie-server 一样**首次启动按需下载**（见 [FridaServerInstaller.prepareBridge]）。
 *   这里改成从私有目录 `System.load(绝对路径)`，并在 **版本不符时绝不加载**：
 *   `bridge.c` 的 JNI 签名与 Kotlin 的 `external fun` 是紧耦合，加载错版本会
 *   `UnsatisfiedLinkError` 甚至 native 崩溃，宁可提示"组件未就绪"。
 */
object FridaCore {

    @Volatile
    private var loaded = false

    @Volatile
    private var loadError: String? = null

    val available: Boolean get() = loaded

    /**
     * 从私有目录加载运行组件（幂等）。**应用启动时**在 [FridaServerInstaller.prepareBridge] 之后调，
     * 注入前也会兜底再调一次。
     * @return true = 已加载；false = 还没准备好或加载失败（原因见 [loadError]）
     */
    fun ensureLoaded(ctx: Context): Boolean {
        if (loaded) return true
        val f = FridaServerInstaller.bridgeFile(ctx)
        if (f == null) {
            loadError = "运行组件未就绪（首次使用需联网下载约 46MB）"
            return false
        }
        return try {
            System.load(f.absolutePath)
            loaded = true
            loadError = null
            AppState.addLog("运行组件已加载")
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
            AppState.addLog("运行组件加载失败：$loadError")
            false
        }
    }

    /**
     * 让 frida-server 把脚本注入到 pid。
     * @return 空串 = 成功；否则是人话错误。
     */
    suspend fun attachAndLoad(remoteAddress: String, pid: Int, scriptPath: String, eternalize: Boolean = true): String {
        if (!loaded) return "运行组件没准备好：${loadError ?: "尚未初始化"}"
        return withContext(Dispatchers.IO) {
            try {
                attachAndLoadAgent(remoteAddress, pid, scriptPath, eternalize)
            } catch (t: Throwable) {
                "native 调用异常：${t.message}"
            }
        }
    }

    fun logAvailability() {
        if (loaded) {
            AppState.addLog("运行组件已加载（方案 B：内嵌运行组件，当运行组件的客户端）")
        } else {
            AppState.addLog("运行组件不可用：${loadError ?: "尚未初始化"}")
        }
    }

    /**
     * 借 frida-server 的进程枚举查 pid（免 root）。
     * @return pid；-1 = 没找到/出错。
     */
    suspend fun findPid(remoteAddress: String, processName: String): Int {
        if (!loaded) return -1
        return withContext(Dispatchers.IO) {
            runCatching { findPidNative(remoteAddress, processName) }.getOrDefault(-1)
        }
    }

    /** JNI（实现见 tools/frida_bridge/bridge.c） */
    private external fun attachAndLoadAgent(
        addr: String,
        pid: Int,
        scriptPath: String,
        eternalize: Boolean,
    ): String

    private external fun findPidNative(addr: String, name: String): Int
}

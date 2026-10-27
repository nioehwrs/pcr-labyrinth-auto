package com.lmj.labyrinthauto.frida

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
 * `libfrida_bridge.so` 用 `tools/build_frida_bridge.ps1` 编（NDK + frida-core devkit）。
 */
object FridaCore {

    @Volatile
    private var loadError: String? = null

    val available: Boolean get() = loadError == null

    init {
        try {
            System.loadLibrary("frida_bridge")
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
        }
    }

    /**
     * 让 frida-server 把脚本注入到 pid。
     * @return 空串 = 成功；否则是人话错误。
     */
    suspend fun attachAndLoad(remoteAddress: String, pid: Int, scriptPath: String, eternalize: Boolean = true): String {
        if (loadError != null) return "libfrida_bridge.so 没加载上：$loadError"
        return withContext(Dispatchers.IO) {
            try {
                attachAndLoadAgent(remoteAddress, pid, scriptPath, eternalize)
            } catch (t: Throwable) {
                "native 调用异常：${t.message}"
            }
        }
    }

    fun logAvailability() {
        if (loadError == null) {
            AppState.addLog("libfrida_bridge.so 已加载（方案 B：内嵌注入桥，当运行组件的客户端）")
        } else {
            AppState.addLog("libfrida_bridge.so 加载失败：$loadError")
        }
    }

    /**
     * 借 frida-server 的进程枚举查 pid（免 root）。
     * @return pid；-1 = 没找到/出错。
     */
    suspend fun findPid(remoteAddress: String, processName: String): Int {
        if (loadError != null) return -1
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

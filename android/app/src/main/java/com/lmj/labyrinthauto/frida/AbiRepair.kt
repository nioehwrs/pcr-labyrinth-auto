package com.lmj.labyrinthauto.frida

import android.os.Build
import android.os.SystemClock
import com.lmj.labyrinthauto.core.AppState
import kotlinx.coroutines.delay

/**
 * 游戏 ABI 异常修复（2026-10-29 实测定案）。
 *
 * ## 为什么需要它
 * 注入链路**硬依赖 x86_64**：所有 hook 脚本都按 x86_64 写（`libil2cpp.so` + 硬编码 RVA +
 * `xmm0` 传浮点），而且 Houdini 转译层下 x86_64 宿主进程的模块表里**根本没有** `libil2cpp.so`
 * （`/proc/<pid>/maps` 里也 grep 不到），`waitIl2CppMapped()` 必然超时、注入后也摸不到 il2cpp。
 *
 * ## 为什么会变成 arm64
 * MuMu 里有一张云端下发的「按包名指定 ABI」表
 * （`/system/etc/mumu-configs/abi-select-android12.config`，由 `file-update.config` 从
 * `mumu.nie.netease.com` 拉取）。读取它的是 **MuMu 魔改过的 system_server**（日志 tag `NativeHelper`），
 * 只在该包**安装/更新时**决策一次：
 * ```
 * D NativeHelper: com.bilibili.priconne found in abi White list config, value is: x86. The index in supportAbis:-1
 * W NativeHelper: com.bilibili.priconne in abi white list config but not in supportAbiList:[armeabi]
 * I NativeHelper: com.bilibili.priconne use max number of library use abi:armeabi-v7a index:0
 * ```
 * 表里给公主连结写的是 **x86（32 位）**，而现行 APK 早就没有 `lib/x86`（实测 `lib/x86` 条目数 = 0）
 * ⇒ 白名单命中但索引 -1 ⇒ 回退「库数量最多」策略 ⇒ `arm64-v8a`。渠道服（`*.yofun.mumu`）
 * 压根不在表里（整串匹配，`com\.bilibili\.priconne` 不匹配它）⇒ 同样 arm64。
 *
 * ## 修法：本地重装，零下载
 * Android 安装后 APK 一直留在 `/data/app/~~<hash>/<pkg>-<hash>/base.apk`（`pm path` 可取），
 * 那就是应用的运行实体，不是缓存。所以可以原地重装：
 * ```
 * pm install -r --abi x86_64 <base.apk>
 * ```
 * 实测（MuMu 12 / Android 12 / 官服 11.7.2，1.87GB 包）：
 *  * 耗时 **8 秒**，输出 `Success`；
 *  * `primaryCpuAbi` arm64-v8a → **x86_64**，`lib/` 解压目录 arm64 → x86_64（14 个 so）；
 *  * `firstInstallTime` 不变、`/data/data` 保留 ⇒ 是 update，**存档不丢**；
 *  * 不联网、不下载任何包（用的是设备上那份 base.apk）。
 *
 * 显式 `--abi` **优先级高于那张白名单表**（重装日志里白名单仍是 `x86`、仍报 `index:-1`，
 * 最终 ABI 依旧是 x86_64），所以**不需要改表**（`/system` 是只读块设备，改表反而要 remount，
 * 实测 `mount -o rw,remount /system` 会长时间阻塞）。
 *
 * ⚠️ 表不改的副作用：MuMu 商店下次**自动更新**游戏时会再按表选一次 ⇒ 又回 arm64
 * ⇒ 所以本模块是「每次启动前幂等检查一次」的设计，而不是一次性的。
 *
 * ⚠️ 不适用于 App Bundle 按 ABI 拆分的包（那种设备上只有 `split_config.arm64_v8a.apk`，
 * 没有 x86_64 那一份，`--abi` 会 `INSTALL_FAILED_NO_MATCHING_ABIS`）。
 * 现行 PCR 包是单 APK、三套 ABI 齐全，实测可用；本模块用 [x86LibCount] 预检兜住这种例外。
 */
object AbiRepair {

    const val TARGET_ABI = "x86_64"

    private const val READ_TIMEOUT_MS = 8_000L
    private const val LIST_TIMEOUT_MS = 20_000L

    /** 1.9GB 的包在 MuMu 虚拟磁盘上实测 8s 装完，给足冗余（另加 su 授权框等待）。 */
    private const val INSTALL_TIMEOUT_MS = 180_000L

    /** 重装后等旧进程真正退出的上限（`pm install` 杀进程是异步的，见 [waitGone]）。 */
    private const val GONE_TIMEOUT_MS = 5_000L

    /**
     * ★这台**设备本身**支不支持 x86_64（2026-10-30 用户："如果是 arm 的设备装了 x64 版本会怎么样"）。
     *
     * 只有 x86/x86_64 模拟器（MuMu、雷电 x86 版…）支持；ARM 真机、ARM 云机都不支持 ——
     * Android 没有 x86-on-ARM 的转译层（Houdini 是反方向：在 x86 上跑 ARM）。
     *
     * 用途：在"要不要修游戏架构"之前先问这个 —— ARM 设备上把目标当成"该修成 x86_64"是错的，
     * 会白跑一次注定 `INSTALL_FAILED_NO_MATCHING_ABIS` 的重装（还会白杀游戏一次）。
     */
    fun deviceSupportsX86_64(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals(TARGET_ABI, ignoreCase = true) }

    /** 设备 ABI 列表的人话形式（日志用），如 `arm64-v8a/armeabi-v7a/armeabi`。 */
    fun deviceAbiText(): String = Build.SUPPORTED_ABIS.joinToString("/").ifBlank { "?" }

    /** ARM 设备上统一的结论句（两处复用，保证口径一致）。 */
    private fun unsupportedDeviceText(): String =
        "这台设备的 CPU 是 ${deviceAbiText()}，不支持 $TARGET_ABI：本工具目前只在 x86_64 模拟器" +
            "（MuMu / 雷电等 x86 版）上工作，ARM 真机暂不支持"

    data class Result(
        val ok: Boolean,
        /** 本次是否真的做了重装（false = 本来就对 / 没装成） */
        val changed: Boolean,
        val before: String,
        val after: String,
        /**
         * ★true = **确定性修不了**，再试也没用（包内没有 x86_64 库 / 游戏没装 / 没有 root）。
         * 上层应据此停手，别再走「等映射 120s → 注入失败 3 次 → 杀游戏 3 轮 → 暂停 2 分钟」
         * 那条白折腾的路（2026-10-30 审查补）。
         */
        val fatal: Boolean,
        /** 人话结论，直接进日志 */
        val detail: String,
    )

    /** 读已安装包的 primaryCpuAbi（空串 = 读不到：没装 / 没有 root）。 */
    suspend fun readAbi(pkg: String): String {
        val r = RootShell.exec("dumpsys package $pkg | grep -m1 primaryCpuAbi", READ_TIMEOUT_MS)
        return r.out.lineSequence()
            .firstOrNull { it.contains("primaryCpuAbi=") }
            ?.substringAfter("primaryCpuAbi=")
            ?.trim()
            .orEmpty()
    }

    /**
     * 只做「检测 + 说清楚」，**不动手重装**。
     *
     * 用在"用户可能正自己玩着游戏"的时刻（打开助手、服务启动）：重装会杀进程，所以这里只报结论，
     * 真正修复放到用户明确要求接管之后（一键黎明界 / 局内刷开局 / 进迷宫 / 抓凭证）。
     *
     * ★2026-10-30 实测定案：这段原来只挂在 `AutoForegroundService.onCreate` 里，
     *   而那个服务要等用户点「开始 / 一键黎明界」才启动 ⇒ 光打开助手它**从来不跑**
     *   （日志里连"服务已启动"都没有）。现在同时挂在 `MainActivity.onCreate`（与 root 自检并列）。
     */
    suspend fun report(where: String, pkg: String) {
        // 先落一行"进到这里了"：之前结论整行消失、又没有任何异常日志时无从判断到底跑没跑
        AppState.addLog("架构自检（$where）：读游戏 ABI…")
        // ★设备本身就不支持 x86_64（ARM 真机）⇒ 再看游戏是什么架构也没意义，一句话说完就返回
        if (!deviceSupportsX86_64()) {
            AppState.addLog("⚠ ${unsupportedDeviceText()}（$where）")
            return
        }
        val abi = readAbi(pkg)
        when {
            abi.isBlank() -> {
                // ★V4：别把"没 root 读不到"报成"没装"
                val noRoot = RootShell.detect() == null
                AppState.addLog(
                    if (noRoot) "⚠ 没有 root（$where）：读不到游戏架构，注入也做不了"
                    else "⚠ 读不到游戏架构（$where）：可能没装 $pkg",
                )
            }
            abi == TARGET_ABI -> AppState.addLog("✓ 游戏架构 $abi（$where）")
            else -> AppState.addLog(
                "⚠ 游戏架构是 $abi（$where）：走 ARM 翻译层，注入摸不到 il2cpp ⇒ " +
                    "点「一键黎明界 / 抓凭证」时会自动本地重装修复为 $TARGET_ABI",
            )
        }
    }

    /** 取设备内那个 base.apk 的绝对路径（`pm path` 的输出）。 */
    suspend fun baseApk(pkg: String): String? {
        val r = RootShell.exec("pm path $pkg", READ_TIMEOUT_MS)
        return r.out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .firstOrNull { it.endsWith("base.apk") }
    }

    /**
     * 数 APK 里 `lib/x86_64/` 的条目数。
     * @return -1 = 判不了（设备没有 `unzip`）；0 = 确实没有 x86_64 库（不能修）
     *
     * 这一步是「先问能不能修，再动手」，避免对着缺库的包白装一遍、再把
     * `INSTALL_FAILED_NO_MATCHING_ABIS` 误报成「重装没生效」。
     * （pcrtool 的 `C_ABI` 就缺这一道，报错只剩"重装后校验仍非 x86_64"。）
     */
    suspend fun x86LibCount(apk: String): Int {
        val r = RootShell.exec("unzip -l $apk | grep -c 'lib/x86_64/'", LIST_TIMEOUT_MS)
        val n = r.out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("\\d+")) }
            ?.toIntOrNull()
        if (n == null) {
            AppState.addLog("ABI 预检：这台设备上用不了 unzip，跳过库检查（直接尝试重装）")
        }
        return n ?: -1
    }

    /**
     * 幂等保证 [pkg] 跑在 x86_64 上；不是就在本地重装一次。
     *
     * 调用时机：**用户明确要求接管游戏之后**（一键黎明界 / 局内刷开局 / 进迷宫 / 抓凭证）。
     * 服务启动自检只读不修 —— 那一刻用户可能正自己玩着游戏。
     */
    suspend fun ensure(pkg: String): Result {
        // ★先问设备本身：ARM 真机没有 x86_64 支持 ⇒ 这不是"游戏装错架构"，是**根本跑不了**。
        //   直接 fatal 停手，既不去 `pm install`（那边必然 INSTALL_FAILED_NO_MATCHING_ABIS，
        //   还会白杀一次游戏），也不让上层继续等映射/重试。
        if (!deviceSupportsX86_64()) {
            return Result(false, false, "", "", true, unsupportedDeviceText())
        }
        val before = readAbi(pkg)
        if (before == TARGET_ABI) {
            return Result(true, false, before, before, false, "已是 $TARGET_ABI")
        }
        if (before.isBlank()) {
            // ★V4（2026-10-30）：区分"没 root 读不到"和"没装"，别一律报成"可能没装"
            val noRoot = RootShell.detect() == null
            return Result(
                false, false, "", "", true,
                if (noRoot) "没有 root，读不到 $pkg 的架构（先在设备上允许本应用的 root 授权）"
                else "读不到 $pkg 的 primaryCpuAbi（这台设备/用户空间里没装这个包？）",
            )
        }

        val apk = baseApk(pkg)
            ?: return Result(false, false, before, before, true, "拿不到 base.apk 路径（pm path 无输出）")

        when (val n = x86LibCount(apk)) {
            0 -> return Result(
                false, false, before, before, true,
                "这个安装包内没有 lib/x86_64（只有 ARM 库），换不了架构，需要换全 ABI 的安装包",
            )
            -1 -> AppState.addLog("ABI 预检跳过，直接尝试重装")
            else -> AppState.addLog("ABI 预检：包内含 $n 个 lib/x86_64 库")
        }

        AppState.addLog("游戏架构是 $before（ARM 翻译层，注入摸不到 il2cpp）→ 本地重装修复为 $TARGET_ABI…")
        AppState.addLog("  · 用设备内已有的包（不下载、不联网）：$apk")

        // ★路径里含 ~~ 和 == 但无空格，不加引号也能安全传给 sh
        val inst = RootShell.exec("pm install -r --abi $TARGET_ABI $apk", INSTALL_TIMEOUT_MS)
        val success = inst.out.lineSequence().any { it.trim().equals("Success", ignoreCase = true) }

        if (!success) {
            // ★V2（2026-10-30）：`su` 侧超时（exit=-1）时设备上的 `pm install` 往往仍在跑并已成功，
            //   只按输出判会把"其实修好了"报成失败（上层还会据此停手）⇒ 先复查一次 ABI 再下结论。
            val after = readAbi(pkg)
            if (after == TARGET_ABI) {
                AppState.addLog("安装输出里没抓到 Success，但复核 ABI 已是 $TARGET_ABI → 按成功处理")
                waitGone(pkg)
                return Result(true, true, before, after, false, "已切到 $TARGET_ABI（输出缺失，已复核）")
            }
            return Result(
                false, false, before, after, false,
                "本地重装没成功：${inst.text.takeLast(240).ifBlank { "（无输出，可能 su 授权超时）" }}",
            )
        }

        val after = readAbi(pkg)
        if (after != TARGET_ABI) {
            return Result(
                false, false, before, after, false,
                "重装成功但复核 primaryCpuAbi=$after（期望 $TARGET_ABI）",
            )
        }

        // ★V3（2026-10-30）：`pm install` 杀进程是异步的 —— 不等它退干净，上层紧接着 `gamePid()`
        //   可能拿到"将死的旧 pid"，于是多走一轮「等映射 → 读到 No such process → 杀游戏重开」。
        waitGone(pkg)

        AppState.addLog("架构修复完成：$before → $after（本地重装，存档保留）")
        return Result(true, true, before, after, false, "已切到 $TARGET_ABI")
    }

    /** 等目标包真的没有进程了（最多 [GONE_TIMEOUT_MS]；等不到也往下走）。 */
    private suspend fun waitGone(pkg: String) {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < GONE_TIMEOUT_MS) {
            val r = RootShell.exec("pidof $pkg", 4_000L)
            if (r.out.trim().isEmpty()) return
            delay(300)
        }
        AppState.addLog("重装后 ${GONE_TIMEOUT_MS / 1000}s 仍有 $pkg 进程残留（继续往下走）")
    }
}

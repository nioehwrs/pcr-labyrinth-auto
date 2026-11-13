package com.lmj.labyrinthauto.frida

/**
 * ★自愈判据（2026-11-13）：`waitIl2CppMapped()` 的返回值到底是「进程坏了」还是「正常再等一轮」。
 *
 * 事故（0.6.20 诊断包 `lab-diag_20261113_151720`）：第 2 局重启后 `waitIl2CppMapped` 返回
 * 「起游戏后 15s 内 libil2cpp.so 一直没映射（进程卡死/闪退残留）」，注入循环只认
 * 「僵死 / 提前退出」两种字样，于是 `delay(2s)` 后对**同一个坏 pid** 又等 15s，重复 4 轮，
 * 直到上层 90s 超时停手。该函数自己的注释明明写着「判卡死，交给注入循环 kill + launch」。
 *
 * 抽成纯函数是为了能单测：误判成坏进程会杀掉正在跑迷宫的健康游戏，必须钉死。
 */
object InjectRecoveryPolicy {

    /** true = 进程已坏，注入循环应当 kill + 重新调起；false = 正常的「下轮再看」，绝不能杀游戏。 */
    fun badProcess(why: String): Boolean =
        why.contains("僵死") ||
            why.contains("提前退出") ||
            why.contains("一直没映射") ||
            why.contains("映射超时")

    /**
     * 注入失败时：true = 其实只是「进程换代」—— attach 打到了上一局已死的旧 pid，
     * 而当前已经有个新 pid 活着（上层刚 kill→launch 起来的）。此时**绝不能 force-stop**：
     * 杀掉的是健康的新进程，且 kill 之后没人再 am start（0.6.20 事故的另一半根因）。
     *
     * ★2026-11-13 修正：判据只看 pid，不再掺 `injectFails` —— 旧写法在第 3 次失败时会
     *   把健康的新进程杀掉。连续换代的次数上限由调用方用独立计数 `pidChangeTries` 负责。
     */
    fun pidChanged(attachPid: Int, alivePid: Int): Boolean =
        alivePid > 0 && alivePid != attachPid
}

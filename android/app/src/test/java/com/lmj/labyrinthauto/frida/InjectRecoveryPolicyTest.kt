package com.lmj.labyrinthauto.frida

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 坏进程判据：宁可漏一次自愈，也绝不能把健康游戏杀掉（2026-11-13 事故复盘）。 */
class InjectRecoveryPolicyTest {

    @Test
    fun `进程坏掉的各种文案都要触发自愈`() {
        listOf(
            "进程提前退出（pid 21741 没了）",
            "进程提前退出（pid 21741 的 /proc 没了）",
            "进程僵死/已退出（pid 21741 状态=T）",
            // ★0.6.20 诊断包里连等 4 轮的那句，旧判据漏了它
            "起游戏后 15s 内 libil2cpp.so 一直没映射（进程卡死/闪退残留）",
            "等 libil2cpp.so 映射超时（120s；最后输出：）",
        ).forEach { assertTrue(it, InjectRecoveryPolicy.badProcess(it)) }
    }

    @Test
    fun `正常的时间兜底闸门绝不能杀游戏`() {
        assertFalse(InjectRecoveryPolicy.badProcess("游戏刚起来（时间兜底闸门），下轮再看"))
        assertFalse(InjectRecoveryPolicy.badProcess(""))
        assertFalse(InjectRecoveryPolicy.badProcess("其它未知原因，下轮再看"))
    }

    @Test
    fun `注入撞上旧 pid 时新进程不能被杀`() {
        // 0.6.20 诊断包现场：attach 21741（已死），pidof=22124（上层刚调起的新进程）
        assertTrue(InjectRecoveryPolicy.pidChanged(21741, 22124))
        // 同一种进程：交给原来的「原地重试 / 杀游戏」逻辑，不当换代
        assertFalse(InjectRecoveryPolicy.pidChanged(21741, 21741))
        // 进程真没了：必须由 kill + 重新调起接手
        assertFalse(InjectRecoveryPolicy.pidChanged(21741, -1))
        // ★判据与失败次数无关：第 3 次失败也不能因此杀掉新进程（重试上限由调用方独立计数）
        assertTrue(InjectRecoveryPolicy.pidChanged(21741, 22124))
    }
}

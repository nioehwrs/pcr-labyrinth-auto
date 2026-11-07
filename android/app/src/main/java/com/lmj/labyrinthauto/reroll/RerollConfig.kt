package com.lmj.labyrinthauto.reroll

import kotlinx.serialization.Serializable

/**
 * 刷开局配置（对应 PC `config.json` 的 reroll 相关字段）。
 * 默认值（国服）：初始公会 3(咲恋救济院) / 难度 5 / 事件价值 350(偏事件) / 价值偏差 0(满分)。
 * Boss 白名单：3/5 区各 5 个固定 Boss（数据库 labyrinth_boss.json 查得），多选、默认全选；
 * 判 Boss / 判价值偏差**永远执行**（不再设开关）。
 */
@Serializable
data class RerollConfig(
    val guildId: Int = 3,
    val difficulty: Int = 5,
    /** 最多 enter/retire 重开多少次；<=0 = 不限次数（一直刷到合格为止） */
    val maxCount: Int = -1,
    /** 事件格价值：350 = 偏事件 / 100 = 偏遗物（已去掉「均衡」选项） */
    val eventValue: Int = 350,
    /** 允许的「理论最高分 - 最优路线实得分」分数差；选项固定 0/50/100/200，默认 0 = 满分。
     *  ★不再有「偏差区数」这一项——是否合格完全由本分数差决定。 */
    val maxValueGap: Int = 0,
    /** 3 区 Boss 白名单（unitId），5 个固定 Boss、默认全选；空集 = 不检查该区 */
    val bossArea3: Set<Int> = setOf(301206, 303306, 306604, 312505, 319604),
    /** 5 区 Boss 白名单（unitId），5 个固定 Boss、默认全选；空集 = 不检查该区 */
    val bossArea5: Set<Int> = setOf(301701, 302501, 310103, 315004, 319401),
    /**
     * 判定模式（用户 2026-11-03）：
     *  · [MODE_CLASSIC] 经典 = 分数价值判定（事件倾向 + 价值偏差）；
     *  · [MODE_CUSTOM]  自定义 = 按**每区每列的节点类型**判定（见 [AreaPattern]），不再算分。
     */
    val judgeMode: Int = MODE_CLASSIC,
    /** 自定义模式的期望（JSON：`{"1":[1,2,4,2,5,6],…}`），空串 = 用默认值 */
    val areaPlan: String = "",
    /** 每轮 enter/retire 之间小睡几毫秒（别把服务器打急） */
    val sleepBetweenMs: Long = 150L,
) {
    companion object {
        const val MODE_CLASSIC = 0
        const val MODE_CUSTOM = 1
    }
}

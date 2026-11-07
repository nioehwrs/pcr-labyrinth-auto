package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.logic.EventRetreatRule
import com.lmj.labyrinthauto.logic.EventRuleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EventRetreatRuleTest {
    private val assets = File(findRepoRoot(), "android/app/src/main/assets")

    private fun loadEvents() =
        EventRuleData.load { name -> File(assets, name).readText() }

    @Test
    fun `条件：事件对上且结果对上才算满足`() {
        val c = EventRetreatRule.Cond(listOf(2315), resultId = 231521, useResult = true)
        assertTrue(c.satisfiedBy(2315, 0, 231521))
        assertFalse(c.satisfiedBy(2315, 0, 231511))   // 事件对、结果不对
        assertFalse(c.satisfiedBy(2314, 0, 231521))   // 结果对、事件不对
        assertFalse(c.satisfiedBy(2315, 0, null))     // 读不到结果
    }

    @Test
    fun `条件：选了选项就必须落在那张卡上`() {
        val c = EventRetreatRule.Cond(listOf(2315), slot = 2, resultId = 231521, useResult = true)
        assertTrue(c.satisfiedBy(2315, 2, 231521))    // 选项对
        assertFalse(c.satisfiedBy(2315, 1, 231521))   // 选项不对
        assertFalse(c.satisfiedBy(2315, 0, 231521))   // 读不到选的是哪张卡
    }

    @Test
    fun `条件：不选选项 = 任意选项都算`() {
        val c = EventRetreatRule.Cond(listOf(2315), slot = 0, resultId = 231521, useResult = true)
        assertTrue(c.satisfiedBy(2315, 1, 231521))
        assertTrue(c.satisfiedBy(2315, 2, 231521))
    }

    @Test
    fun `条件：无视随机结果时 = 遇到这个事件就满足`() {
        val c = EventRetreatRule.Cond(listOf(2315), useResult = false)
        assertTrue(c.ready)
        assertTrue(c.satisfiedBy(2315, 0, 231521))
        assertTrue(c.satisfiedBy(2315, 0, 231511))
        assertFalse(c.satisfiedBy(2314, 0, 231521))
    }

    @Test
    fun `条件：要求结果但还没选 = 未配好，不触发`() {
        val c = EventRetreatRule.Cond(listOf(2315), useResult = true)
        assertFalse(c.ready)
        assertFalse(c.satisfiedBy(2315, 0, 231521))
    }

    @Test
    fun `条件：合并后的选项同时指向多个事件 id`() {
        val c = EventRetreatRule.Cond(
            listOf(1105, 1109, 1113, 1117), resultId = 110911, useResult = true,
        )
        assertTrue(c.satisfiedBy(1109, 1, 110911))
        assertFalse(c.satisfiedBy(1109, 1, 110911 + 1))
        assertFalse(c.satisfiedBy(1104, 1, 110411))
    }

    @Test
    fun `条件：没选事件 = 永不满足`() {
        val c = EventRetreatRule.Cond(emptyList(), useResult = false)
        assertFalse(c.valid)
        assertFalse(c.satisfiedBy(2315, 0, 231521))
    }

    @Test
    fun `条件之间是或：任意一条满足就命中`() {
        val tracker = EventRetreatRule.Tracker(
            listOf(
                EventRetreatRule.Cond(listOf(2315), resultId = 231521, useResult = true),
                EventRetreatRule.Cond(listOf(1315), resultId = 131512, useResult = true),
            ),
        )
        // 第一个条件满足 ⇒ 立刻命中（不用等第二个）
        assertEquals(listOf(0), tracker.feed(2315, 0, 231521))
        assertEquals(0, tracker.hitIndex())
        // 另一个条件满足 ⇒ 命中的是它自己那条
        val t2 = EventRetreatRule.Tracker(
            listOf(
                EventRetreatRule.Cond(listOf(1108), resultId = 110812, useResult = true),
                EventRetreatRule.Cond(listOf(1315), resultId = 131512, useResult = true),
            ),
        )
        assertEquals(listOf(1), t2.feed(1315, 0, 131512))
        assertEquals(1, t2.hitIndex())
    }

    @Test
    fun `没选事件的条件不会命中，但不影响其它条件`() {
        val tracker = EventRetreatRule.Tracker(
            listOf(
                EventRetreatRule.Cond(emptyList(), useResult = false),
                EventRetreatRule.Cond(listOf(2315), resultId = 231521, useResult = true),
            ),
        )
        assertEquals(listOf(1), tracker.feed(2315, 0, 231521))
        assertEquals(1, tracker.hitIndex())
    }

    @Test
    fun `条件：JSON 往返不丢东西`() {
        val conds = listOf(
            EventRetreatRule.Cond(listOf(2315), slot = 2, resultId = 231521, useResult = true),
            EventRetreatRule.Cond(listOf(1105, 1109, 1113, 1117), useResult = false),
        )
        val back = EventRetreatRule.decode(EventRetreatRule.encode(conds))
        assertEquals(2, back.size)
        assertEquals(listOf(2315), back[0].eventIds)
        assertEquals(2, back[0].slot)
        assertEquals(231521, back[0].resultId)
        assertTrue(back[0].useResult)
        assertEquals(listOf(1105, 1109, 1113, 1117), back[1].eventIds)
        assertFalse(back[1].useResult)
    }

    @Test
    fun `条件：兼容旧格式（数字 e 与旧的无结果等于无视）`() {
        val old = EventRetreatRule.decode("""[{"e":1109,"r":110911}]""")
        assertEquals(1, old.size)
        assertEquals(listOf(1109), old[0].eventIds)
        assertEquals(110911, old[0].resultId)
        assertTrue(old[0].useResult)          // 旧写法：选了结果就要匹配
        val old2 = EventRetreatRule.decode("""[{"e":1109,"r":0}]""")
        assertFalse(old2[0].useResult)         // 旧写法：没选结果 = 无视结果
        val old3 = EventRetreatRule.decode("""[{"c":[{"e":1109,"r":0}]}]""")
        assertEquals(listOf(1109), old3[0].eventIds)
    }

    @Test
    fun `条件：脏数据退化成空表（不会每局都撤）`() {
        assertTrue(EventRetreatRule.decode(null).isEmpty())
        assertTrue(EventRetreatRule.decode("").isEmpty())
        assertTrue(EventRetreatRule.decode("不是 json").isEmpty())
    }

    @Test
    fun `条件：上限 30 条，超出的截掉`() {
        val many = (1..40).map { EventRetreatRule.Cond(listOf(1000 + it), resultId = it, useResult = true) }
        val back = EventRetreatRule.decode(EventRetreatRule.encode(many))
        assertEquals(EventRetreatRule.MAX_CONDS, back.size)
    }

    @Test
    fun `模式：脏 key 一律当成"无"`() {
        assertEquals(EventRetreatRule.Mode.NONE, EventRetreatRule.Mode.of(null))
        assertEquals(EventRetreatRule.Mode.NONE, EventRetreatRule.Mode.of("乱写"))
        assertEquals(EventRetreatRule.Mode.ASSERT, EventRetreatRule.Mode.of("assert"))
        assertEquals(EventRetreatRule.Mode.LOGIC, EventRetreatRule.Mode.of("logic"))
    }

    @Test
    fun `事件表：能读出事件、选项与随机结果`() {
        val events = loadEvents()
        assertTrue(events.isNotEmpty())
        val e2315 = events.firstOrNull { it.ids.contains(2315) }
        assertTrue(e2315 != null)
        // 2315 有两张卡（选项）：卡1「这样应该就足够了？」/ 卡2「我还没吃够呢☆」
        assertEquals(2, e2315!!.options.size)
        val opt1 = e2315!!.option(1)!!      // 随机印记×2 ⇒ 只有一个结果，没有分支结果
        val opt2 = e2315!!.option(2)!!      // 50％概率 ⇒ 两个结果，有分支结果
        assertEquals(1, opt1.slot)
        assertFalse(opt1.branching)
        assertEquals(1, opt1.results.size)
        assertEquals(231521, opt1.results.first().rid)
        assertTrue(opt2.branching)
        assertEquals(2, opt2.results.size)
        assertTrue(opt2.results.any { it.rid == 231511 })
        assertTrue(opt2.results.any { it.rid == 231512 })
        // 效果码翻出来要是人能看懂的（4=印记、1=金币=500、7=连锁事件）
        assertTrue(opt1.results.first().label.contains("印记"))
        assertTrue(opt2.results.first { it.rid == 231512 }.label.contains("500"))
    }

    @Test
    fun `事件表：魔物竞技场与公会管理协会没有分支结果`() {
        val events = loadEvents()
        val arena = events.firstOrNull { it.short == "魔物竞技场" }
        val guild = events.firstOrNull { it.short == "公会管理协会" }
        assertTrue(arena != null)
        assertTrue(guild != null)
        // 公会管理协会 = 1101~1104，魔物竞技场 = 1105/1109/1113/1117
        assertEquals(listOf(1101, 1102, 1103, 1104), guild!!.ids)
        assertEquals(listOf(1105, 1109, 1113, 1117), arena!!.ids)
        // ★每个选项在每个事件下都只有一个结果 ⇒ 没有分支结果（用户口径）
        assertTrue(arena!!.options.all { !it.branching })
        assertTrue(guild!!.options.all { !it.branching })
        // 但选项本身还是各自的（卡1 一试身手吧 / 卡2 与强敌战斗…）
        assertEquals(2, arena!!.options.size)
        assertTrue(arena!!.options.any { it.comment.contains("一试身手") })
        // 合并组里同一槽位的结果合起来（1105/1109/1113/1117 的卡1 共 4 个 rid）
        assertEquals(4, arena!!.option(1)!!.results.size)
        assertTrue(arena!!.option(1)!!.results.any { it.rid == 110911 })
        assertTrue(arena!!.option(1)!!.results.any { it.rid == 111711 })
    }

    @Test
    fun `事件表：合并组里同一卡位的文案不一致时，标题只写卡N并列出各事件文案`() {
        val events = loadEvents()
        val guild = events.firstOrNull { it.short == "公会管理协会" }!!
        // 公会管理协会的"卡1"在 1101 是"想要坦克型"、在 1104 是"想要攻击型" ⇒ 不能只抄第一个
        val opt1 = guild.option(1)!!
        assertEquals("卡1", opt1.label)
        assertTrue(opt1.sub.contains("1101"))
        assertTrue(opt1.sub.contains("1104"))
        assertTrue(opt1.sub.contains("坦克"))
        assertTrue(opt1.sub.contains("攻击"))
    }

    @Test
    fun `事件表：合并组里文案一致时照常用原来的标题`() {
        val events = loadEvents()
        val arena = events.firstOrNull { it.short == "魔物竞技场" }!!
        // 魔物竞技场的卡1 在 4 个事件里都是"一试身手吧！" ⇒ 标题照旧
        assertEquals("卡1 一试身手吧！", arena.option(1)!!.label)
    }

    @Test
    fun `条件：不限选项且无视结果 = 遇到这个事件、选哪张卡都撤`() {
        val c = EventRetreatRule.Cond(listOf(2315), slot = 0, useResult = false)
        assertTrue(c.ready)
        assertTrue(c.satisfiedBy(2315, 1, 231521))
        assertTrue(c.satisfiedBy(2315, 2, 231511))
        assertFalse(c.satisfiedBy(2314, 2, 231511))
    }

    @Test
    fun `事件表：有没有随机结果看事件，不看某张卡`() {
        val events = loadEvents()
        // 2315：卡1 只有一个结果、卡2 有两个 ⇒ 这个事件"有随机结果"（不选卡也要能配结果）
        assertTrue(events.first { it.ids.contains(2315) }.hasRandom)
        // 魔物竞技场/公会：所有卡都只有一个结果 ⇒ 没有随机结果，整段"且结果是…"不出现
        assertFalse(events.first { it.short == "魔物竞技场" }.hasRandom)
        assertFalse(events.first { it.short == "公会管理协会" }.hasRandom)
    }

    @Test
    fun `事件表：不选选项时也能列出全部随机结果（带卡号）`() {
        val e = loadEvents().first { it.ids.contains(2315) }
        val all = e.resultsFor(0)
        // 卡1 一条 + 卡2 两条
        assertEquals(3, all.size)
        assertTrue(all.any { (o, r) -> o.slot == 1 && r.rid == 231521 })
        assertTrue(all.any { (o, r) -> o.slot == 2 && r.rid == 231512 })
        // 限定了卡2 ⇒ 只剩卡2 的两条
        val only2 = e.resultsFor(2)
        assertEquals(2, only2.size)
        assertTrue(only2.all { (o, _) -> o.slot == 2 })
    }

    @Test
    fun `运行时表：事件级有没有随机结果与UI一致`() {
        com.lmj.labyrinthauto.logic.EventResult.load { name -> File(assets, name).readText() }
        assertTrue(com.lmj.labyrinthauto.logic.EventResult.evHasRandom(2315))
        assertFalse(com.lmj.labyrinthauto.logic.EventResult.evHasRandom(1105))
        assertFalse(com.lmj.labyrinthauto.logic.EventResult.groupHasRandom(1105))
        assertFalse(com.lmj.labyrinthauto.logic.EventResult.groupHasRandom(1101))
        assertTrue(com.lmj.labyrinthauto.logic.EventResult.groupHasRandom(2315))
    }
}

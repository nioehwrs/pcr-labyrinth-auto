package com.lmj.labyrinthauto

import com.lmj.labyrinthauto.logic.LogicRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逻辑模式规则引擎单测（最小闭环，2026-11-18）。
 * 覆盖：比较符、事件原子（含角色差结果）、且/或、顺序敏感、撤退中断、token 钳制、编解码、脏数据。
 */
class LogicRuleTest {

    private val noEvents = emptyList<LogicRule.EventFact>()

    // ── 比较符 ──
    @Test
    fun `比较符：六种都按数学语义`() {
        assertTrue(LogicRule.Op.LT.test(2, 3))
        assertTrue(LogicRule.Op.GT.test(4, 3))
        assertTrue(LogicRule.Op.EQ.test(3, 3))
        assertTrue(LogicRule.Op.LE.test(3, 3))
        assertTrue(LogicRule.Op.GE.test(3, 3))
        assertFalse(LogicRule.Op.LT.test(3, 3))
    }

    // ── token 与层数比较 ──
    @Test
    fun `单条比较规则：token 与层数为真才命中`() {
        val e = LogicRule.Engine(
            listOf(
                LogicRule.Rule(
                    a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GT, 3),
                    act = LogicRule.Act.Retreat,
                ),
            ),
        )
        assertTrue(e.fire(LogicRule.Ctx(4, noEvents)).size == 1)   // 层数 4 > 3
        assertTrue(e.fire(LogicRule.Ctx(3, noEvents)).isEmpty())  // 层数 3 不 > 3
    }

    // ── 事件原子 ──
    @Test
    fun `事件原子：族 id + 结果 rid 命中；无视结果时遇到就命中`() {
        // 料理族 1315/2315/3315，结果"成功→随机印记×2" = 231521（canonical 覆盖 331521）
        val e = LogicRule.Engine(
            listOf(
                LogicRule.Rule(
                    a = LogicRule.Atom.EventAtom(listOf(1315, 2315, 3315), resultId = 231521),
                    act = LogicRule.Act.Retreat,
                ),
            ),
        )
        val facts = listOf(LogicRule.EventFact(2315, 331521, 2, null))
        assertEquals(1, e.fire(LogicRule.Ctx(1, facts)).size)      // 331521 归一到 231521
        assertTrue(e.fire(LogicRule.Ctx(1, listOf(LogicRule.EventFact(1107, 110731, 1, null)))).isEmpty())

        val anyResult = LogicRule.Engine(
            listOf(
                LogicRule.Rule(
                    a = LogicRule.Atom.EventAtom(listOf(2315)),
                    act = LogicRule.Act.Retreat,
                ),
            ),
        )
        assertEquals(1, anyResult.fire(LogicRule.Ctx(1, listOf(LogicRule.EventFact(2315, 231511, 2, null)))).size)
    }

    @Test
    fun `事件原子：获得角色N型按角色差判（同单位差可同时命中多档）`() {
        // 111221 是"获得角色<3"的 outcome id；unitBelow 型不看 rid 本身，看 du
        val e = LogicRule.Engine(
            listOf(
                LogicRule.Rule(
                    a = LogicRule.Atom.EventAtom(listOf(1112), resultId = 111221),
                    act = LogicRule.Act.Retreat,
                ),
            ),
        )
        assertEquals(1, e.fire(LogicRule.Ctx(1, listOf(LogicRule.EventFact(1112, 111221, 1, unitGain = 2)))).size)
        assertTrue(e.fire(LogicRule.Ctx(1, listOf(LogicRule.EventFact(1112, 111221, 1, unitGain = 5)))).isEmpty())
    }

    // ── 且 / 或 ──
    @Test
    fun `且：两个条件都真才命中；或：任一真即命中`() {
        val a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GT, 3)
        val b = LogicRule.Atom.CompareAtom(LogicRule.Variable.Tok(0), LogicRule.Op.GE, 1)
        val and = LogicRule.Engine(
            listOf(LogicRule.Rule(a, LogicRule.Combine.AND, b, LogicRule.Act.Retreat)),
        )
        and.tokens[0] = 1
        assertEquals(1, and.fire(LogicRule.Ctx(4, noEvents)).size)
        assertTrue(and.fire(LogicRule.Ctx(2, noEvents)).isEmpty())

        val or = LogicRule.Engine(
            listOf(LogicRule.Rule(a, LogicRule.Combine.OR, b, LogicRule.Act.Retreat)),
        )
        or.tokens[0] = 0
        assertEquals(1, or.fire(LogicRule.Ctx(4, noEvents)).size)  // 层数真即可
        assertTrue(or.fire(LogicRule.Ctx(2, noEvents)).isEmpty())
    }

    // ── 顺序敏感 + 撤退中断 ──
    @Test
    fun `顺序敏感：改 token 后后面的规则立刻看到新值`() {
        // 规则1：层数>3 → T1 += 1；规则2：T1 >= 1 → 撤退
        val e = LogicRule.Engine(
            listOf(
                LogicRule.Rule(
                    a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GT, 3),
                    act = LogicRule.Act.TokAct(0, LogicRule.TokOp.ADD, 1),
                ),
                LogicRule.Rule(
                    a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Tok(0), LogicRule.Op.GE, 1),
                    act = LogicRule.Act.Retreat,
                ),
            ),
        )
        val fired = e.fire(LogicRule.Ctx(4, noEvents))
        assertEquals(2, fired.size)               // 两条都命中
        assertEquals(1, e.token(0))
        assertTrue(fired.last().act is LogicRule.Act.Retreat)
    }

    @Test
    fun `撤退中断：第一条撤退后，后面的规则不再跑`() {
        val e = LogicRule.Engine(
            listOf(
                LogicRule.Rule(
                    a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GE, 1),
                    act = LogicRule.Act.Retreat,
                ),
                LogicRule.Rule(
                    a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GE, 1),
                    act = LogicRule.Act.TokAct(0, LogicRule.TokOp.ADD, 5),
                ),
            ),
        )
        val fired = e.fire(LogicRule.Ctx(4, noEvents))
        assertEquals(1, fired.size)
        assertEquals(0, e.token(0))               // 规则2 没跑
    }

    // ── token 钳制 ──
    @Test
    fun `token 增减与钳制上下限`() {
        val e = LogicRule.Engine(
            listOf(
                LogicRule.Rule(
                    a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GE, 1),
                    act = LogicRule.Act.TokAct(0, LogicRule.TokOp.SUB, 5),
                ),
            ),
        )
        e.fire(LogicRule.Ctx(1, noEvents))         // 0 - 5 → 0（钳制）
        assertEquals(LogicRule.TOKEN_MIN, e.token(0))

        val e2 = LogicRule.Engine(
            listOf(
                LogicRule.Rule(
                    a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GE, 1),
                    act = LogicRule.Act.TokAct(1, LogicRule.TokOp.ADD, 200),
                ),
            ),
        )
        e2.fire(LogicRule.Ctx(1, noEvents))
        assertEquals(LogicRule.TOKEN_MAX, e2.token(1))
    }

    @Test
    fun `reset：每局把 8 个 token 清零`() {
        val e = LogicRule.Engine()
        e.tokens[3] = 7
        e.reset()
        assertEquals(0, e.token(3))
        assertEquals(LogicRule.TOKEN_COUNT, e.tokens.size)
    }

    // ── 编解码 ──
    @Test
    fun `编解码往返：三种原子与两种动作都不丢`() {
        val rules = listOf(
            LogicRule.Rule(
                a = LogicRule.Atom.EventAtom(listOf(1315, 2315, 3315), resultId = 231521),
                combine = LogicRule.Combine.AND,
                b = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GE, 4),
                act = LogicRule.Act.TokAct(2, LogicRule.TokOp.ADD, 1),
            ),
            LogicRule.Rule(
                a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Tok(0), LogicRule.Op.GE, 2),
                act = LogicRule.Act.Retreat,
            ),
        )
        val back = LogicRule.decode(LogicRule.encode(rules))
        assertEquals(2, back.size)
        assertEquals(LogicRule.Atom.EventAtom(listOf(1315, 2315, 3315), 231521), back[0].a)
        assertEquals(LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GE, 4), back[0].b)
        assertEquals(LogicRule.Act.TokAct(2, LogicRule.TokOp.ADD, 1), back[0].act)
        assertEquals(LogicRule.Act.Retreat, back[1].act)
    }

    @Test
    fun `脏数据退化成空表（不会每局都撤）`() {
        assertTrue(LogicRule.decode(null).isEmpty())
        assertTrue(LogicRule.decode("").isEmpty())
        assertTrue(LogicRule.decode("不是 json").isEmpty())
        // 旧断言格式（无 a/x 字段）也当空表
        assertTrue(LogicRule.decode("""[{"e":[2315],"r":231521,"m":1}]""").isEmpty())
    }

    @Test
    fun `上限 30 条，超出的截掉`() {
        val many = (1..40).map {
            LogicRule.Rule(
                a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.GE, it),
                act = LogicRule.Act.Retreat,
            )
        }
        assertEquals(LogicRule.MAX_RULES, LogicRule.decode(LogicRule.encode(many)).size)
    }

    @Test
    fun `显示文字：整条规则可读`() {
        val text = LogicRule.ruleText(
            LogicRule.Rule(
                a = LogicRule.Atom.CompareAtom(LogicRule.Variable.Tok(0), LogicRule.Op.GE, 2),
                combine = LogicRule.Combine.OR,
                b = LogicRule.Atom.CompareAtom(LogicRule.Variable.Area, LogicRule.Op.LT, 4),
                act = LogicRule.Act.Retreat,
            ),
        )
        assertTrue(text.contains("T1"))
        assertTrue(text.contains("或"))
        assertTrue(text.contains("撤退"))
    }
}

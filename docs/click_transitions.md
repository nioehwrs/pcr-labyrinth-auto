# 点击 → 窗口转移表（状态机驱动点击）

> **进度标记**：`[ ]` 待改造 ｜ `[~]` 已接入观察中 ｜ `[x]` 已完成（稳定） ｜ `[—]` 不适用

## 一、模型：`tapAndCheck` 两阶段

```kotlin
tapAndCheck(input, x, y, what,
            from: Set<String>,          // 当前所在窗口（可多个，如结算页三种壳）
            expect: Set<String>,        // 阶段 2 要等到的目标窗口（空集=只要求离开）
            settleMs = 150,             // 每次点击后停多久再看
            retries = 1,                // 阶段 1 最多点几次
            expectTimeoutMs = 6_000)    // 阶段 2 最多等多久
```

```
阶段 1（点击）：反复点 → 停 settleMs → 读 labTop
               只要 top **不在 from 集合里** ⇒ 这一下生效，进入阶段 2
               控制量：retries（次数）

阶段 2（等待）：**完全不点击**，每 100ms 看一眼
               直到 top ∈ expect ⇒ 状态机完成
               控制量：expectTimeoutMs（时间）
```

两个阶段控制量不同，互不穿透：

- 阶段 1 回答“**这一下点上了吗**”（坐标会失效，所以要次数）；
- 阶段 2 回答“**目标页面到了吗**”（页面慢，所以要超时）。

特殊目标 `@battle`：进战斗后没有对话框（`labTop` 为空），改用 `AgentApi.labInBattle()`。

### 为什么判据是“离开 from”而不是“到达 expect”作点击终止

进到下一屏就已经离开了 from，自然会停，不会多点；`expect` 只用于阶段 2 确认。

---

## 二、窗口读取的轻量接口（2026-11-07 新增）

| RPC | 内容 | 实测耗时 |
|---|---|---|
| `labTop` | 只读 `DialogArea` 顶层子节点名（归一化去 `(Clone)`） | **中位 4ms / 最大 27ms** |
| `labView` | 只读 ViewManager→当前 View 类名 + `inBattle` | ~5~20ms（比 labStatus 快一个数量级） |
| `labPartyIdx` | `labTop` + 编组页 `nowSelectParty`（`+0x208 → +0x148`） | 待实测 |
| `labBattleResult` | `inBattle` / `resultEnable`（`ViewBattle+0xF0`）/ `gameState`（`BattleManager+0x1DC`） | 待实测 |
| `labStatus` | 全量快照（地图节点、顶框、编队、遗物…） | **150~300ms** |

> 结论：只判某个字段时**不要用 `labStatus`**，用对应的轻量接口。

---

## 三、流程总览：地图 → 战斗

```
地图（top = ""）
 └─点节点→ DialogCommonSmall（通用确认小窗）
      └─点确认→ DialogQuestDetail（关卡预览，带 questId）
           └─点「开打」→ DialogCommonPartyEdit（编组页）
                └─编好队点「战斗开始」→ 战斗中（无顶框，vịew=ViewBattle）
                     └─打完→ 结算页（QuestResultWin / LabyrinthExResult / BackKeyCapture）
                          └─点「下一步」→ DialogLabyrinthSelection（战后选人 / 选遗物）
```

---

## 四、各类点击现状

### A. 地图 / 导航

| 进度 | 动作 | from | 说明 |
|---|---|---|---|
| [ ] | 前进到节点 XXX | `-`（地图） | 第一跳都是 `DialogCommonSmall`；**按节点类型收紧的是第二跳** |
| [—] | 屏幕中心（推进动画） | 任意 | 不换窗口，不适用 |
| [ ] | ButtonQuest / 出发 | `-` | 待观测 |

### B. 战斗 / 结算

| 进度 | 动作 | from | expect（阶段2） | 参数 |
|---|---|---|---|---|
| [~] | 预览·开打（进编组） | `DialogQuestDetail` | `DialogCommonPartyEdit` | 200ms × 8 |
| [~] | 战斗开始（普通） | `DialogCommonPartyEdit` | **`@battle`** | 200ms × 6 |
| [—] | 决定·跳过本队（Boss 空队） | `DialogCommonPartyEdit` | — | 只点一次 + delay(800)，见下“Boss 多队” |
| [~] | 结算·下一步 | `{BackKeyCapture, LabyrinthExResult, QuestResultWin}` | `{DialogLabyrinthSelection, DialogLabyrinthResult}` | 200ms × 25 |

> **终点分叉**（2026-11-07 实测）：
> - 普通战 / 非最终 Boss → `DialogLabyrinthSelection`（战后选人 / 选遗物）；
> - **50801 最终 Boss → `DialogLabyrinthResult`（迷宫结算页）**，之后是
>   `DialogAnimationViewer → DialogCommonFullSize → DialogCommonBig`（由结算 handler 负责点掉）。
>   两种都要放进 `expect`，否则最终 Boss 那场会白等 6 秒。
| [ ] | 结算·关闭 | 同上 | — | 低频 |
| [—] | 结算后加速·点中心 | 任意 | — | 不换窗口 |

**结算静置**：`RESULT_SETTLE_MS` 由 **3500 → 2000 → 200ms**（2026-11-07）。
按钮落位时间不稳定，“等”不如“多试”：留 200ms 只给起始帧，容错全交给
「下一步」的 `retries = 25`（≈5 秒），前几下落空无害。

**Boss 多队（3 队）**：三队挤在同一个 `DialogCommonPartyEdit` 里，点「决定/战斗开始」多数是**切到下一队**（顶框不变）⇒ 不能重试，否则 2/3 队被跳过。

### 分支现状（2026-11-07 实测修正）

```kotlin
val bossAdv = isBoss && !fillOk
```

`fillOk` 来自 `PartyPlan.partyFillOk`，语义是“**这一队还该不该编**”：

- 3 层 Boss 只编第 1 队 ⇒ 第 2/3 队 `fillOk=false`；
- **第 1 队编满之后**也不再需要编 ⇒ `fillOk` 同样 `false`。

所以 `bossAdv` 的真实含义是「**这一队不需要（再）编**」，同时覆盖“空队”和“已编满的第 1 队”。
日志佐证：`点了 决定·跳过本队（5 人，允许空）` —— 5 人已满却走“跳过本队”。

⇒ **`else if (isBoss)` 那个“编满单次点击”分支实际走不到，属于死分支，待删**。
Boss 三队全流程都在 `bossAdv` 分支里：确认第 1 队 → 跳过第 2 队 → 跳过/开战。

### 已具备的判据（尚未接入）

```kotlin
val (t0, n0) = AgentApi.labPartyIdx()      // (窗口, nowSelectParty)
tap(...)
delay(200)
val (t1, n1) = AgentApi.labPartyIdx()
when {
    t1 !in setOf("DialogCommonPartyEdit") -> 开战 ✔
    n1 != n0                              -> 切队 ✔（继续编下一队）
    else                                  -> 没点上，重试
}
```

两个分支（`bossAdv` / `isBoss`）目前都只打观测行：

```
◧ Boss 跳过：[$top idx=0] → [$top idx=1]
◧ Boss 编组：[$top idx=0] → [$top idx=1]
```

待 `idx` 递增被实测确认后，再把判据接进 `bossAdv`。

### C. 三选一 / 遗物 / 门票（同一窗口名，三种用途）

| 进度 | 动作 | from | expect | 参数 |
|---|---|---|---|---|
| [~] | 战后选人 | `DialogLabyrinthSelection` | `DialogCommonMiddle` | 150ms × 1（外层 10 次重读重算） |
| [~] | 选择遗物 | `DialogLabyrinthSelection` | `DialogCommonBig` | 同上 |
| [~] | 门票：选 N | `DialogLabyrinthSelection` | `DialogCommonBig` | 同上 |

外层重试（`handleLabSelection`）：

```
tapAndCheck(..., retries = 1)        // 点一次，停 150ms
  ├─ 离开 → 进入阶段 2 等 expect
  └─ 没离开 → 清 lastSelSig → 120ms → **重新读卡片位置/属性** → 重新决策 → 再点（最多 10 次）
```

**Boss 两轮选人**：点完仍在 Selection 时，重读卡片算新 `sig`：

- `sig` 变了 ⇒ 新一轮（Boss 第二轮），清计数、正常决策；
- `sig` 不变 ⇒ 上次没点上，计入重试。

日志：`… 战后选人 页：卡片已刷新（新一轮选人），按新卡片重新决策`

### D. 商店

| 进度 | 动作 | from | expect |
|---|---|---|---|
| [ ] | 商店·买#N | `DialogLabyrinthShop` | 离开 → `DialogCommonMiddle` |
| [ ] | 商店·更新 / 关闭 | `DialogLabyrinthShop` | 离开 |

### E. 编队 / 特别装备

| 进度 | 动作 | from | 备注 |
|---|---|---|---|
| [—] | 编队 add/remove | `DialogCommonPartyEdit` | 不换窗口，不适用 |
| [ ] | 打开特别装备 | `DialogCommonPartyEdit` | → `DialogCommonFullSize` |
| [ ] | 特别装备·ThreeButtonCenter | `DialogCommonFullSize` | → `DialogCommonBig` |
| [ ] | 特别装备·TwoButtonRight | `DialogCommonBig` | → `DialogCommonFullSize` |
| [ ] | 特别装备·ThreeButtonRight | `DialogCommonFullSize` | → `DialogCommonPartyEdit` |

### F. 通用弹窗（同壳不同内容，只能靠 `(窗口, 按钮名)` 区分）

| 进度 | 动作 | from | to |
|---|---|---|---|
| [ ] | TwoButtonRight | `DialogCommonSmall` | `-`（关闭）×43 |
| [ ] | OneButtonWhite | `DialogCommonMiddle` | `-`×29 |
| [ ] | OneButtonBlue | `DialogCommonSmall` | `DialogLabyrinthShop`×22 |
| [ ] | TwoButtonRight | `DialogCommonMiddle` | `DialogCommonSmall`×22 |
| [ ] | OneButtonWhite | `DialogCommonBig` | `DialogCommonMiddle`×10 |

---

## 五、待标定

### 战斗胜负枚举（`gameState`）

`labBattleResult()` 已能读 `gameState`，但**语义未标定**。当前结算判断仍靠“等按钮出现 + 按钮名（重新挑战=失败）”。

标定方法：跑一胜一负，对比日志里两行

```
◧ 战斗结果原始值：inBattle=true resultEnable=? gameState=?
```

值确定后可把“静置 + 等按钮”改成“轮询 `resultEnable`/`gameState`”，再省 2 秒。

### 其他

- **A-1**：前进到节点的第二跳期望（NORMAL/HARD→QuestDetail、TICKET/RELIC→Selection、EVENT→LabyrinthEvent、SHOP→LabyrinthShop、BOSS→QuestDetail）；
- **Boss 三队**：把 `◧ Boss 编组` 观测升级为二元判据（窗口 + `nowSelectParty`）；
- **D/E/F** 尚未接入。

---

## 六、观测开关

- `MazeLoop.TRANS_VERBOSE`：命中时打 `⤷ [from] --动作--> [to] Nms（第 i 次点击已离开）`；
- 转移观测 `⟳`：点击后主循环下一轮比对（只记录，不改行为）；
- `labTop` 耗时采样：每进程一次（`labTopProbed`）。

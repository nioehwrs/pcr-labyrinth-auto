# 点击 → 窗口转移表（状态机驱动点击）

> 标记：✅ 已做并实测 ｜ 🔍 **已改、待下次实测** ｜ ⏳ 未做
>
> **最后更新：2026-11-10**（2026-11-10：地图点节点的「抓确认框出现 + 抓确认框消失」两步、商店读取不再用实例缓存 —— 代码都已落，实测状态见 §〇 与 §五）

## 〇、本轮状态总览

| # | 事项 | 状态 | 实测依据 |
|---|---|---|---|
| 1 | `tapAndCheck` 抽成 `TapCheck.run`（MazeLoop + Navigator 共用一份实现） | ✅ | 编译通过，MazeLoop 全部调用点行为不变 |
| 2 | **EX 三连页**：按钮改按字段直取 + 按需读 `labExBtn` | ✅ | 三连页 3 次全通，**1.3s/次**（原 3~4s），单次读 30ms |
| 3 | **主循环分派读**：`labStatus` → `labStatusLite`，ids/choices 走 `labExtra` | ✅ | 18 个节点跑通，Boss/EX/normal 判定全对 |
| 4 | **进入迷宫前**（Navigator）：`tap` + 固定 700/900ms → `tapUntilChange`（探针） | ✅ | `[5\|] --ButtonQuest--> [20\|]（探 1 次已离开）`，三处判据全对 |
| 5 | **遗物**：`tap` → `tapAndCheck`，判据 `statusType\|pageTop` | 🔍 | 上次实测暴露"顶框名 3.9s 不变"，已改判据，待复测 |
| 6 | **选人/门票**：`xyAt` 每下现取坐标 + `probe 2→5`（220→580ms） | 🔍 | 上次实测点 4 下才中（窗口太短），已加长，待复测 |
| 7 | **地图移动**：顶框非空 ⇒ 不点节点 | ✅ | 2026-11-10 日志命中 2 次「地图上还盖着「…」→ 先不点节点」 |
| 8 | **事件页**：改用 **`labEvent`**（字段读）取卡片，按"文案非空"筛掉**空槽**；UI 树只作兜底 + "页面没铺开就不找" | 🔍 | ⚠15:53 实测：UI 树会扫到**空槽按钮**（屏幕正中）⇒ 选错卡；`labEvent` 空槽文案为空可直接筛 |
| 9 | **商店读取**：不再用跨轮实例缓存；刷新后 `avail==0` 当未就绪；清单↔货架一致性；买不起重算；每局清状态 | ✅ | `来源=top 69 次 / cache 0`，刷新后清单重算正常；本次两局兜底一次没触发（说明读取本身已对） |
| 10 | 商店买/刷新**点击**、通用弹窗转移 | ⏳ | 点击仍是 `tap + 固定等待`，见 §四 D / F |
| 11 | **地图点节点第二跳**：抓「是否前往」确认框出现（`labDlgPair`）→ 点确认 → 抓开始消失（`labDlgClosing`） | ✅（补点待复测） | 出现 60 次、`+85~148ms`、读 `7~10ms`；旧版「出现即点」59/60 点空 ⇒ 已改「出现即点 + 最多 5 次补点」 |

## 一、模型：`tapAndCheck` 两阶段

```kotlin
// logic/TapCheck.kt —— 全项目唯一实现（MazeLoop.tapAndCheck 是它的薄包装）
TapCheck.run(
    tapFn, x, y, what,
    from: Set<String>,          // 当前所在位置（可多个，如结算页三种壳）
    expect: Set<String>,        // 阶段 2 要等到的目标（空集=只要求离开）
    settleMs = 150,             // 点完第一次探之前停多久
    retries = 1,                // 阶段 1 最多点几次
    probe = 0, probeMs = 150,   // 点完连探几次（探到"变了"就停）
    readTop = null,             // "当前在哪"的探针（null = labTop）
    unknownTop = "",            // 探针读失败时的标记（= 还没离开，绝不误判成生效）
    xyAt = null,                // 每次点击前现取坐标（null 返回 = 这一下不点，不算重试）
    reached = null,             // 阶段 1 目标判据（EX 用："到目标页了没"）
    skipMax = 6, skipDelayMs = 60, verbose = true,
    expectTimeoutMs = 6_000)    // 阶段 2 最多等多久
```

```
阶段 1（点击）：点 → 停 settleMs → 读 labTop；top 不在 from 里 ⇒ 生效，进入阶段 2
阶段 2（等待）：完全不点击，每 100ms 看一次
                 ① top ∈ expect ⇒ 完成
                 ② expect 带 "@battle" ⇒ 再查 inBattle ⇒ 也算完成
```

- 阶段 1 回答「这一下点上了吗」（靠次数）；阶段 2 回答「目标页面到了吗」（靠超时）。
- `@battle` 只用于「终点是战斗画面」；进编组页不能带。
- 多点 ≠ 缺陷：多点是容错手段，只有点到别的目标导致状态错乱才算问题。

### 扩展参数（2026-11-08，特别装备三连页要用）

| 参数 | 作用 |
|---|---|
| `readTop` | 换"页面标识"读取器（默认 `labTop`）。特别装备三连页改成读**页面签名** |
| `unknownTop` | 读取器返回这个值 = "读不到" ⇒ 视作**没离开**（不传则沿用旧语义：读失败会被当成"离开了"） |
| `xyAt` | ★**每次点击时重新取坐标**（不传就用固定 x,y）。EX 三连页用：切页动画期间坐标在动 ⇒ 第一次读到的是中间值 ⇒ 点空；重读一次就接近稳定值 |
| `reached` | ★阶段 1 的**目标达成判据**（可选）。传了它 ⇒ 阶段 1 只认它：「离开了 `from` 但**没到目标**」⇒ 这次没生效 ⇒ **重试同一个动作**（同一按钮实例），而不是重新决策去点别的按钮。EX 三连页用（判"到达目标页了没"） |
| `probe` / `probeMs` | ★点完**连探几次**、每次隔多久（探到"变了"就停）。这是"多点"与"多探"的取舍：**探便宜（6~80ms/次）、点贵**（120ms 等待 + 可能点错位置）⇒ 宁可把窗口开大一点 |
| `skipMax` / `skipDelayMs` | `xyAt` 连续返回 null 时最多等几次、每次隔多久（默认 6 × 60ms） |

> ★**2026-11-08 已抽成 `logic/TapCheck.kt` 的 `TapCheck.run`**：`MazeLoop.tapAndCheck` 变成薄包装
> （调用点零改动），`Navigator` 直接用它。项目里**只有这一份实现**。
>
> ⚠**探针窗口必须覆盖真实切换时间**（本轮踩的坑）：实测三选一"点下去 → 顶框/状态真的变"要
> **481~613ms**，而最初只给了 `probe=2, probeMs=120`（220ms 窗口）⇒ 探不到 ⇒ 判"没生效" ⇒
> **连点 4 下才中**（坐标还都一样，纯白点）。现在统一 `probe=5, probeMs=120`（580ms 窗口）。

**页面签名**：状态机认的"当前页面标识"不一定是窗口名，也可以是更细的签名
（页身份 + 页内关键状态）。签名变了 ⇒ 这一步生效 —— **换页**和**页内勾选/选项变了**
一视同仁，不需要额外判据。

> 特别装备三连页的签名：
> `party|change=true` ｜ `setting|row=稀有度|checked=true` ｜ `sort|sel=稀有度` ｜ `""`（不在三连页）

**阶段 1 = 在 from 页上一直点**：点 → 停 `settleMs` → 读一次标识；
- 离开了 `from` ⇒ 生效，进入阶段 2；
- 没离开 ⇒ **继续点下一次**（`retries` 次内）—— 「多点是手段」，点不准就多点几下，
  别急着换策略；唯一底线是**别点到不该点的东西**（⇒ 每次点击前用 `xyAt` 重读当前页，
  当前页是权威 ⇒ 只会点当前页该点的那个按钮）。

> ⚠ 坐标"在动"**不在 agent 侧解决**（2026-11-08 教训）：曾用 `Thread.sleep` 等一帧取稳定值，
> 而 EX 页上匹配到的按钮有十几个 ⇒ 整包读变成几秒 ⇒ **agent 单通道被占满** ⇒ 整个助手卡死。
> ⇒ 改由调用方**每次点击前重读整包 + 多点几次**。

---

## 二、窗口读取的轻量接口（2026-11-07 新增）

| RPC | 内容 | 实测耗时 |
|---|---|---|
| `labTop` | 只读 `DialogArea` 顶层子节点名（归一化去 `(Clone)`） | 中位 4ms / 最大 27ms |
| `labDlgPair` | ★★「通用确认框**出现**」：当前顶层 GameObject 指针 + `top` + `TwoButtonLeft/Right`（字段直取，不遍历子树） | **2~4ms** |
| `labDlgClosing` | ★★「通用弹窗**开始消失**」：`DialogBase.outMainTweenAlpha`（`mStarted` 0x68 / `mFactor` 0x78） | **3~6ms** |
| `labView` | 只读 ViewManager→当前 View 类名 + `inBattle` | ~5~20ms |
| `labPartyIdx` | `labTop` + 编组页 `nowSelectParty` | 切队判据在用 |
| `labPartyLite` | 三队人数 `partyDic` + 当前队 + 槽位（不带 40 个候选） | ~ms |
| `labPartyTabs` | 编组页顶部页签按钮（切队用）的坐标 | direct：几 ms |
| `labBattleResult` | `inBattle` / `resultEnable` / `gameState` | 🔍 待实测 |
| `labExEquipTop` | 只读"特别装备三连页的**页身份**"（party/setting/sort），不遍历按钮子树 | 比 `labExEquip` 便宜 |
| `labExEquipSig` | ★**极轻**：只回三连页的**页面签名**（一个字符串）—— 不算坐标 / 不读按钮 / 不回传整包 JSON | 状态机轮询专用，**7ms** |
| `labExBtn` | ★★**按需读按钮**：EX 三连页**只要那一个**按钮 + 当前签名（不读整包） | **30ms**（原整包 360ms） |
| `labStatusLite` | ★★**分派读**：`area/blockId/enterId/difficulty/panelType/statusType/next/viewId/viewClass/pageTop` + 战斗四项 | **1~4ms** |
| `labExtra` | ★★**非分派字段**：`eventId/questId/eventResultId/effectNum/curChoice/remainChoice` + LTD `choices` | **0~1ms**（谁要谁读） |
| `labStatus` | 全量快照（地图节点、顶框、编组、遗物…） | **4ms**（首读 88ms；**不是** 150~300ms） |

> 只判某个字段时不要用 `labStatus`，用对应的轻量接口。
>
> ★**2026-11-08 读数结论**：`decOI` **不是**"同步调游戏方法"（它就是 hidden^key 的内存读），
> 所以全量快照本身只有 ~4ms；真正贵的是 `_topDialogName()`（6ms，逐个子节点调游戏方法）。
> "150~300ms"那个老数字是**误传**，别再拿它当分派读的优化依据。

---

## 三、流程总览：地图 → 战斗

```
地图（top = ""）
 └─点节点→ DialogCommonSmall（通用确认小窗）
      └─点确认→ DialogQuestDetail（关卡预览，带 questId）
           └─点「开打」→ DialogCommonPartyEdit（编组页）
                └─编好队点「战斗开始」→ 战斗中（无顶框，view=ViewBattle）
                     └─打完→ 结算页（QuestResultWin / LabyrinthExResult / BackKeyCapture）
                          └─点「下一步」→ DialogLabyrinthSelection（战后选人 / 选遗物）
```

---

## 四、各类点击现状

### A. 地图 / 导航

**进入迷宫前（`Navigator.goToLabyrinthTop`，不在主循环里）**——2026-11-08 改成探针制：

| 状态 | 动作 | from（`viewId\|pageTop`） | 说明 |
|---|---|---|---|
| ✅ | 通用对话框确认（公告等） | `5\|DialogCommonFullSize` | `⤷ … --> [5\|]（探 3 次已离开）` |
| ✅ | ButtonQuest / GUIButton_Labyrinth | `5\|` → `20\|` | `⤷ …（探 1 次已离开）`，省掉原 900ms 死等 |
| ✅ | 底部导航「冒险」（未知页兜底） | 任意 | 同上 |
| ✅ | 屏幕中心（标题/加载页） | `2\|`（TITLE） | 窗口 1.8s，进页立刻走 |
| 🔍 | `findNode` 的**遍历根** | — | ⚠仍在**全树**：`findList 755/547/523ms` × 多次 ⇒ 进迷宫这段还是慢 |

> ★判据必须是 **`viewId|pageTop` 组合签名**（`Navigator.locSig`）：
> 点「确定」关公告时 **viewId 不变**（还在首页），点「冒险」时 **顶框不变** ⇒ 只看一个会漏判。
> 读不到时统一返回 `"!read"`，`TapCheck` 会当成"还没离开"（绝不误判成生效）。

**迷宫内（主循环 `statusType==0`）**：

| 状态 | 动作 | from | 说明 |
|---|---|---|---|
| ✅ | 前进到节点 XXX | `-`（地图） | 闸门：**顶框非空 ⇒ 不点节点**（2026-11-10 日志命中 2 次） |
| ✅ | 点节点后的第二跳（"是否前往 XXX"确认框） | `DialogCommonSmall` | **已改成两步**：`labDlgPair` 抓出现（实测 `+85~148ms`、读 `7~10ms`）→ 点 `TwoButtonRight`（点空就用下一拍新坐标**补点，最多 5 次**）→ `labDlgClosing` 抓开始消失。左按钮 = 取消 |
| ⏳ | **所有通用弹窗** | `pageTop` | 见下面「⚠已知坑」：现在还是"读到什么就点什么"，没有状态驱动 |
| — | 屏幕中心（推进动画） | 任意 | 不换窗口，不适用 |

**⚠已知坑（2026-11-08 用户反馈"自定义路线没按原定路线走"）：拿**过期的弹窗状态**去点，点到了别的东西**

实测（4 区）：

```
17:28:39  现场 → 节点40401 顶框=DialogCommonBig(Clone)
17:28:39  点了 对话框「OneButtonWhite」 (960,666)        ← 关掉"事件奖励"框
17:28:40  现场 → 节点40401 顶框=DialogCommonSmall(Clone)  ← 读到的是**正在消失的框**
17:28:40  点了 对话框「TwoButtonRight」 (1179,744)        ← 按"框还在"去点 → 这一下落到别处
17:28:41  现场 → 节点40502 [NORMAL]                       ← 结果去了 40502（路线要的是 40501）
```

- 全程**没有** `前进到节点40502` 日志 ⇒ 不是"点节点"导致的，是**那一帧读到/以为框还在**。
- 实际损失：4 区 c5 该走 `B40501`（上）走了 `B40502`（中），吃的节点分变低；2 区也有同类
  （`[!] 路线下一站 B20203 当前不可点` 连打 7 次 → 回退按类型挑）。
- ★这是**读取状态过期**，不是点击穿透 —— 一次点击仍只命中一个控件。

**所以通用弹窗也必须改成状态驱动**（用户 2026-11-08 计划）：

| 要点 | 做法 |
|---|---|
| 判据 | `from = setOf(pageTop)`，`expect` = **框真的消失 / 换成下一个框**（不能只看"点过就算"） |
| 按钮坐标 | 每下**现取**（`xyAt`）—— 框在收缩/淡出时坐标会变 |
| 停止 | 探到 `pageTop` 变化就**立刻停**，别在同一语义上多点 |
| ★关键 | 读到 `pageTop` **不代表那个框还在屏上**（有消失动画）⇒ 探针要能区分"框在"和"名字还挂着"，例如配合 `activeInHierarchy` / 可见性，或"连续两帧同名才认为在" |

### B. 战斗 / 结算

| 状态 | 动作 | from | expect（阶段2） | 参数 |
|---|---|---|---|---|
| ✅ | 预览·开打（进编组） | `DialogQuestDetail` | `DialogCommonPartyEdit` | 600ms × 8，阶段2 超时 3s |
| ✅ | 战斗开始（普通） | `DialogCommonPartyEdit` | `@battle` | 800ms × 6 |
| ✅ | 决定·切队（Boss） | `DialogCommonPartyEdit` | 窗口不变、`nowSelectParty` 递增 | 只点 1 次 + 每 120ms 轮询，≤2s |
| ✅ | 结算·下一步 | `{BackKeyCapture, LabyrinthExResult, QuestResultWin}` | `{DialogLabyrinthSelection, DialogLabyrinthResult}` | 200ms × 25 |
| ⏳ | 结算·关闭 | 同上 | — | 低频 |
| — | 结算后加速·点中心 | 任意 | — | 不换窗口 |

> 「下一步」实测常打满 **4~5 次点击** —— 这是**已知例外**（胜利结算本来就有两页：
> 先关分数页、再关奖励页，中间还有 count-up 动画）。不算缺陷，别按"多点"去优化它。

**Boss 三队（3 队）**：三队挤在同一个编组页，点「决定」多数是切到下一队（顶框不变）。

| 状态 | 事项 |
|---|---|
| ✅ | 切到队伍 2/3 且这一队要编 ⇒ 就地算 + 编（约 6s 编完三队） |
| ✅ | 编组框消失 = 已经开打 ⇒ 残留的预览页空壳不再点「挑战」 |
| ✅ | 日志打「Boss 决定前：队伍1/2/3 各多少人」 |
| ✅ | 两个分支（`bossAdv` / `isBoss`）**已合并**成同一个"点决定 → 轮询等切队"循环 |

**结算页终点分叉**：普通战 / 非最终 Boss → `DialogLabyrinthSelection`；**50801 最终 Boss → `DialogLabyrinthResult`**，之后 `DialogAnimationViewer → DialogCommonFullSize → DialogCommonBig`。

### C. 三选一 / 遗物 / 门票（同一窗口名，三种用途）

| 状态 | 动作 | from | 读卡接口 | 参数 |
|---|---|---|---|---|
| 🔍 | 战后选人 | `DialogLabyrinthSelection` | **`labSelection`** | `retries 2, settle 100, probe 5, probe 120` + `xyAt` |
| 🔍 | 门票：选 N | `DialogLabyrinthSelection` | **`labChoices`** | 同上 |
| 🔍 | 选择遗物 | `statusType\|pageTop`（**不是**纯顶框名） | `labChoices` | 同上 |

**★★每个页面认哪个读卡接口是硬约束（2026-11-08 事故）：**

| 页面 | 唯一可用的读法 | 另一个读法会怎样 |
|---|---|---|
| 选人（`SUMMON_UNIT` + `DialogLabyrinthSelection`） | `labSelection` | `labChoices` 恒返回 `{"cards":[], "why":"结构列表=n=3 找到DecideButton=0"}` |
| 遗物 / 门票 / 道具 | `labChoices` | `labSelection` 恒空（门票页注释里早就写了） |

⇒ 所以"重读坐标"必须用**决策时同一个接口**（`selDecideXY(reread = {...})` 由调用方传）；
用错了就取不到坐标 ⇒ 一次都不点（那次空转了 20 秒）。

**每次点击前现取坐标**（`xyAt`）：按钮从屏外竖直滑入，实测同一张卡三帧读到
`(405,1187) → (405,944) → (405,887)`（x 不变、只有 y 动）⇒ 用一次算好的旧坐标**必然点空**。
`xyAt` 返回 null（还没落位/卡片换了）⇒ 这一下不点，60ms 后再试，**不消耗** `retries`。

**遗物为什么不能用"顶框名"当判据**（实测 15:41:47）：

```
15:41:47 点了 选择遗物：21001
15:41:48 statusType 已变 SUMMON_UNIT，但顶框还是 DialogLabyrinthSelection
15:41:52 顶框才变 DialogCommonBig          ← 3.9 秒
```
⇒ 判据必须带 `statusType`（内存读，点击后立刻变），否则探 580ms 也判不出"已离开"。

**遗物 → 下一步是确定的**（日志 7 次全印证）：
- 遗物节点 → 确认框（`OneButtonWhite`）/ 直接回地图
- 战斗节点 → **必是 `DialogCommonBig` 确认框** → 点掉 → 才是「战后选人」

**外层重试**：`xyAt` 真拿到坐标才 +=1（"真点了几次"）；**一次都没拿到坐标时不清 `lastSelSig`**
（否则递归回来会走"卡片签名变了"分支把计数清零 ⇒ 永远到不了上限 ⇒ 空转）。
Boss 两轮选人：卡片 `sig` 变了 = 新一轮，`sig` 没变 = 上次没点上。

### D. 商店

| 状态 | 动作 | from | expect |
|---|---|---|---|
| ⏳ | 商店·买#N | `DialogLabyrinthShop` | 离开 → `DialogCommonMiddle` |
| ⏳ | 商店·更新 / 关闭 | `DialogLabyrinthShop` | 离开 |

> 点击仍是 `tap + 固定等待`，**还没换成探针制**（⏳）。
>
> **读取侧已修（✅ 2026-11-10）**：
> - 不再用跨轮实例缓存 —— 原来刷新后会读到**刷新前**的货架（`来源=cache` 命中旧实例）；
>   现在每拍从**当前最上层对话框**现取（`src=top`），顶层是商店但 lineup 未填就直接返回"未就绪"，不回退旧实例；
> - 刷新后的 `avail==0` 当"未就绪"重读（原来只覆盖"进店第一帧"）；
> - 清单↔货架一致性校验（清单要 3★、货架没有 3★ ⇒ 丢弃重算）；
> - 清单整体买不起时按当前金币重算；每局开始清空商店状态；
> - 实测：`来源=top 69 次 / cache 0`，刷新后清单重算正常。

### E. 编组 / 特别装备

| 状态 | 动作 | from（页面标识） | 参数 |
|---|---|---|---|
| — | 编组 add/remove | `DialogCommonPartyEdit` | 不换窗口，不适用 |
| ✅ | 编组页页签直读 | `DialogCommonPartyEdit` | `labPartyTabs` direct，0.75s → 几 ms |
| ✅ | 编组页去掉固定等待 | — | 判据：读数连续两次相同；编一队 11s → 3s |
| ✅ | 点「开打」前并行算队伍 | — | `preTeamJob`，省一次算法（~1s） |
| ✅ | 打开特别装备 | `""`（不在三连页） | settle 0 × 1，读一次签名 |
| ✅ | 特别装备·ThreeButtonCenter（自动装备） | `party\|change=false` | 同上 |
| ✅ | 特别装备·TwoButtonRight（确认） | `sort\|sel=稀有度` | 同上 |
| ✅ | 特别装备·TwoButtonRight（确认） | `setting\|row=稀有度\|checked=true` | 同上 |
| ✅ | 特别装备·ThreeButtonRight（装备确定） | `party\|change=true` | 同上 |
| ✅ | 特别装备·ThreeButtonLeft（取消·自动装备无改动） | `party\|change=false` | 同上 |
| ✅ | 特别装备·复选框（补勾） | `setting\|row=稀有度\|checked=false` | 同上 |
| ✅ | 特别装备·「稀有度」选项 | `sort\|sel=全部` | 同上 |

**特别装备三连页 = 显式转移表（2026-11-08 方案 B）**

一条规则 = **（当前页, 条件）→（动作, 目标页）**：

| 当前页 | 条件（签名里的页内状态） | 动作 | 目标页 |
|---|---|---|---|
| ① party | `change=true` | 装备确定（ThreeButtonRight） | 编组页（离开 EX） |
| ① party | `change=false` 且 已点过自动装备 且 没进过② | 自动装备（ThreeButtonCenter；等切②·超时重试） | ② setting |
| ① party | `change=false` 且 已点过自动装备 且 进过② | 取消（ThreeButtonLeft） | 编组页 |
| ① party | `change=false`（还没点过自动装备） | 自动装备（ThreeButtonCenter） | ② setting |
| ② setting | `row≠稀有度` | 「全部」行（prioBtn[0]） | ③ sort |
| ② setting | `row=稀有度` 且 `checked=false` 且 共用其他队装备 | 复选框（checkBtn） | ② setting（同页，状态变） |
| ② setting | `row=稀有度` 且 `checked=true` 且 不共用 | 复选框（checkBtn） | ② setting（同页，状态变） |
| ② setting | `row=稀有度` 且 `checked∈{true,false}` | 确认（TwoButtonRight） | ① party |
| ③ sort | `sel=稀有度` | 确认（TwoButtonRight） | ② setting |
| ③ sort | 其它 | 「稀有度」选项（toggle） | ③ sort（同页，状态变） |

- 条件只看**轻量签名**
  （`party@容器|change=…` / `setting@容器|row=…|checked=…` / `sort@容器|sel=…`）
  ⇒ 几十 ms 就知道"这一步要去哪"，**不用等整包读**；整包只在**取按钮坐标**时读。
- ★**"到达目标页"才算这次转移完成**（`tapAndCheck` 的 `reached` 判据）：
  - 目标页 ≠ 当前页 ⇒ 必须**页身份**变成目标页；
  - 目标页 == 当前页 ⇒ 必须**页内状态**真的变了（签名变了）。
  ⇒ 没到目标 ⇒ **重试同一个动作**（同一按钮实例），不重新决策去点别的按钮。
- `checked=null`（读不到）**不算**"已确定" ⇒ 不点，等一下再读（避免误点「确认」）。
- ②页是**最容易点错**的一页（「确认」和①页的按钮挨着）⇒ `settleMs` 200 → 300ms
  （探针制下"切走立刻停"，多等这 100ms 只在**没切走**时才付）。

**★2026-11-08 修：EX 三连页"卡住不动"（实测 11:44 那轮）**

| 现象 | 根因 | 修法 |
|---|---|---|
| 停在③页 15 秒**一声不吭**，整包读累计不再增长 | 整包读 RPC **挂住不返回**（agent 卡住不读 socket ⇒ `out.write` 阻塞在锁里，而它在 `withTimeoutOrNull` **之外** ⇒ 永久挂死） | ① `AgentBridge` 的**写**加超时（3s）⇒ 超时就断连让 agent 重连；② EX 流程**连续 3 次点不动就放弃这次装备**（EX 只是锦上添花，不该把整局卡死） |
| 每次"这一轮不点"毫无日志 | `xyAt` 返回 null 的三个分支都不打日志 | 加 `exeqSay()`：**每次不点都说清原因**（整包读失败 / 签名不一致 / 按钮读不到），同一句最多打 3 遍 |

**EX 读的耗时演进（实测，同一 agent 实例）**

| 读 | ①页 party | ②页 setting | ③页 sort | 状态 |
|---|---|---|---|---|
| 轻量签名读 `labExEquipSig` | **7ms** | 7ms | 7ms | ✅ |
| 整包读（不要坐标） | 7ms | 7ms | 8ms | ✅ |
| 整包读（要坐标，全量投影） | **360ms** | 53ms | 58ms | 已废弃 |
| 整包读（只要点的那一个，仍走遍历） | 360ms | 53ms | 58ms | 已废弃 |
| ★**`labExBtn`（按字段直取）** | **30ms** | ~30ms | ~30ms | ✅ 现行 |

> ★**为什么"只投影一个"没提速**：耗时大头不是坐标投影，而是 `_rawWalk` **遍历整棵子树**
>   （①页的 `DialogCommonFullSize` = 编组页大容器 ⇒ 350ms+）。
>   ⇒ 于是去逆向**按钮字段**，最终找到：
>
> ```
> DialogCommonAbstract +0x118 → DialogFundationObject
>   oneButtonWhite 0x28  oneButtonBlue 0x38
>   twoLeftButton  0x48  twoRightButton 0x58
>   threeLeftButton 0x70 threeCenterButton 0x80 threeRightButton 0x98   (CustomUIButton)
> ```
>
> ⚠**陷阱**：这些字段**不管当前页显示哪种按钮行都全都在**（不存在 = null，但存在的不一定显示）
>   ⇒ 直取后必须**核对 GameObject 名字 + `get_activeInHierarchy`**，否则会拿到"这页根本没显示"的按钮。
>
> ⇒ 现在 `labExBtn(key)` = **字段直取 + 名字/active 校验 + 只投影这一个** = **30ms**，
>   遍历那条路只作兜底（字段读不到时才走）。三次三连页实测 **1.3s/次**（原 3~4s）。

**特别装备三连页（2026-11-08 改成"打包自己跑完"，不进主循环）**

- 流程是**确定的线性序列**：编组页 →（点「特别装备」）→ ①特别装备设定 → ②自动特别装备设定
  → ③优先属性值 → ② → ① →（点「装备确定」）→ 编组页。
- ★**打包**（`handleExequipPacked`）：编组页点开「特别装备」后**当场**把三连页走完，
  主循环**根本不会看到 EX 页** ⇒ 主循环也就**不会在 EX 页上读 `labStatus`**（全量 150~300ms，
  EX 页上根本用不上）。内部循环 = 「读签名（状态检测）→ 决策 → 点一下 → 再读签名看变了没」。
- 每一轮 = **读签名（状态检测）→ 查转移表（这一步做什么 / 去哪）→ 点一下**，**不轮询**。
- ★**判据是"到达目标页了没"**（不是"签名变了没"）：没到目标 ⇒ 这次没生效 ⇒
  **重试同一个动作**（同一按钮实例），不重新决策去点别的按钮 ⇒ 多点也点不到别的目标上。
- 状态检测走 **`labExEquipSig`**（极轻：只回一个签名字符串，不算坐标/不回传整包 JSON）；
  决策那一次才用 `labExEquip(true)`（要按钮坐标）。签名的算法**只在 agent 侧有一份**
  （`_exeqSigOf`），两个 RPC 共用 ⇒ 口径永远一致。
- "上一次点的还没生效"（签名没变）⇒ **在同一次调用里接着点**（重读整包拿最新坐标再点），
  不空等、不跨轮；同一页面签名下最多点 `EXEQ_TAP_MAX` 下，超了才收手。
- 页面标识用**签名**（页身份 + 页内关键状态），不用容器窗口名 ——
  三个页可能都挂在 `DialogCommonFullSize/Big` 下，容器名分不开；
  **页内**变化（勾选框、「稀有度」）也是"签名变了" ⇒ 同一套判据，不用额外判据。
- **兜底**：打包流程异常收手（步数/预算用尽）⇒ 放弃这次装备（不卡流程）；
  只有"EX 流程还没走完"（`ExeqSess.pending`）时，主循环才做一次轻量探测（`labTop` + `labExEquipSig`）
  —— 平时（编组页正常编队）**一次轻量读都不用付**（不在 EX 页时签名读要在编组页大子树里找组件，很贵）。

### E2. 事件选项卡（`DialogLabyrinthEvent`）—— 2026-11-08 实测定案

**★★ 坑：事件页有 3 个槽位，但只有 2 张卡。**

实测截图确认是 2 张卡，但 UI 树里有 **3 颗** `labyrinth_btn_main_square`：

```
(683,893)   左卡「选择」    ← 真
(1238,893)  右卡「选择」    ← 真
(960,893)   屏幕正中        ← **空槽**（activeInHierarchy=true，但内容全空）

三颗的父链完全相同：
  PartsLabyrinthEventSelectInfo(Clone) > root > trans_root > alpha_root > BottomObjectPanel > labyrinth_btn_main_square
三个根都能找到这 3 颗：top 157ms ｜ dialog 199ms ｜ layer 250ms
```

⇒ **两个致命后果**：
1. 按 x 排序取第 2 项 ⇒ 点到 **960（空槽）** ⇒ 什么都不是；
2. 若按钮还在逐个 Instantiate（框刚出现那一帧只 1 颗）⇒ 数量不足 ⇒ 被 `pick` 的 `coerceIn` 压成第 1 项。

**正解 = 用 `labEvent`**（字段读，首读 143ms、之后 2~3ms 有实例缓存），一次给全：

```json
{"eventId":1102, "currentSelection":0, "cards":[
  {"i":0,"btnX":683, "btnY":893,"comment":"想要强化型 成为伙伴！","result":"…"},
  {"i":1,"btnX":1238,"btnY":893,"comment":"想要增益型 成为伙伴！","result":"…"},
  {"i":2,"btnX":960, "btnY":893,"comment":"",                    "result":""}     ← 空槽
]}
```

- **空槽的 `comment`/`result` 是空串** ⇒ 一次筛掉；
- 顺带给出**真实 `eventId`**（`DialogLabyrinthEvent+0x170`；`labStatus.eventId` 那个字段实测恒 0）；
- 卡片顺序按 `btnX` 升序 = `choice_id_1/2/3`。

UI 树那条路保留兜底（旧版 `labEvent` 有过返回空卡的历史），且仍带 `event_choice.json` 的选项数校验。

### F. 通用弹窗（同壳不同内容，只能靠 `(窗口, 按钮名)` 区分）

| 状态 | 动作 | from | to |
|---|---|---|---|
| ⏳ | TwoButtonRight | `DialogCommonSmall` | `-`（关闭）×43 |
| ⏳ | OneButtonWhite | `DialogCommonMiddle` | `-`×29 |
| ⏳ | OneButtonBlue | `DialogCommonSmall` | `DialogLabyrinthShop`×22 |
| ⏳ | TwoButtonRight | `DialogCommonMiddle` | `DialogCommonSmall`×22 |
| ⏳ | OneButtonWhite | `DialogCommonBig` | `DialogCommonMiddle`×10 |

> 这 5 类是日志里频次最高的弹窗转移，目前靠"名字白名单 + 熔断（同颗按钮连点 N 次没推进就不点了）"，
> **没有** from/to 期望。上探针之前要先解决"`DialogCommonMiddle` 里按名字一个都找不到"的问题
> （实测一次：宽名单在 `DialogArea` 子树里全空）。

---

## 五、下一步

### ✅ 2026-11-10 已完成

| 事项 | 实测/状态 |
|---|---|
| 点节点第二跳：确认框「出现」`labDlgPair` | 60 次命中，`+85~148ms`，读 `7~10ms`，`comp=DialogCommonSmall` |
| 点节点第二跳：确认框「消失」`labDlgClosing` | 判据已有；旧版「出现即点」59/60 点空（读到的是入场动画中的坐标，与落位差 (44,41)px）⇒ 已改「出现即点 + 最多 5 次补点」，待复测 |
| 地图节点闸门：顶框非空不点 | 日志 2 次 `地图上还盖着「…」→ 先不点节点` |
| 商店读取不再用实例缓存 | `来源=top 69 次 / cache 0` |
| 商店：刷新后未就绪 / 清单↔货架一致 / 买不起重算 / 每局清状态 | 代码已做；本次两局兜底未触发（读取本身已对） |

### 🔍 已改、等下一次实测确认（本轮遗留）

| 事项 | 期望看到的日志 |
|---|---|
| 选人 / 门票：`probe 5`（580ms 窗口）够不够 | `点了 战后选人：选 xxx` **只有 1 条**（不再 4 条） |
| 遗物：`statusType\|pageTop` 判据 | `⤷ [9\|DialogLabyrinthSelection] --选择遗物：xxx--> [1\|DialogLabyrinthSelection]`，**点 1 下**就结束 |
| 事件页：改用 `labEvent` 取卡 + 筛空槽 | ① `事件(1102)：选第 1 项（共 2 项）` 里的 **M = 真实卡片数**（**不是** 3）；② 不该再出现 `… 退 DialogArea/父层` 那条（只有 `labEvent` 空时才走）；③ `[!] 事件x：实际落在卡N，配置要的是卡M` **不应出现** |

### ⏳ 还没做

| 事项 | 说明 |
|---|---|
| **Navigator `findNode` 的遍历根** | 现在还是**全树**：`findList 755/547/523ms` 各一次 ⇒ 进迷宫那 25 秒里 4~6 秒花在这。★最该做的下一个优化 |
| 地图节点**第二跳**的**按类型收紧** | 通用「两按钮确认框」已做（§五 2026-11-10）；**还没做**的是按节点类型区分去向（NORMAL/HARD→`QuestDetail`、TICKET/RELIC→`Selection`、EVENT→`LabyrinthEvent`、SHOP→`LabyrinthShop`、BOSS→`QuestDetail`） |
| 商店·买/刷新/关闭的**点击** | 读取已修（§四 D）；点击仍是 `tap + 固定等待` |
| 通用弹窗转移（§四 F） | 5 类高频弹窗还没进转移表 |
| 结算·关闭 | 低频 |
| `DialogCommonMiddle` 按钮读不到 | 实测一次：宽名单 `Btn\|Button\|Decide\|Confirm\|OK\|Close\|Cancel\|Next` 在 `DialogArea` 子树里全空 ⇒ 需要 dump 那个弹窗的真实节点名 |
| `gameState` 胜负枚举 | 跑一胜一负，对比 `◧ 战斗结果原始值：inBattle=… resultEnable=… gameState=…` |
| `labBattleResult` 耗时 | 接入时顺带采样 |

### ✅ 已验证通过（从"待测"移出）

| 事项 | 实测 |
|---|---|
| 特别装备的**签名**判得准不准 | 三连页 3 次全通；`party\|change=false/true`、`setting\|row=稀有度\|checked=true`、`sort\|sel=稀有度` 全部判对 |
| EX 三连页"到达目标页"判据（`reached`） | 6 步全一次生效，无重试 |
| 主循环分派读（`labStatusLite`） | 18 节点跑通，Boss/EX/normal 判定全对 |
| 进迷宫前探针制（`tapUntilChange`） | `[5\|] --ButtonQuest--> [20\|]（探 1 次已离开）` |

---

## 六、观测开关与排障日志

- `MazeLoop.TRANS_VERBOSE`：命中时打 `⤷ [from] --动作--> [to] （第 i 次点击，探 N 次已离开）`；
- 转移观测 `⟳`：点击后主循环下一轮比对 **`pageTop` 变没变**（`checkTransition`，只记录不改行为）。
  ⚠**它不是"这段操作花了多久"** —— 只反映"顶框名什么时候变"。遗物那次 `3879ms` 就是这样，
  实际 `statusType` 1 秒内就变了（所以判据不能只看顶框名）。
- `labTop` 耗时采样：每进程一次（`labTopProbed`）。

本轮新加的三条排障日志（都只在**出问题**时才打，正常路径零开销）：

| 日志 | 含义 |
|---|---|
| `[!] xx 页一次都没点出去：取不到「决定」坐标（<原因>）` | `selXYWhy`：读不到卡片 / rid 对不上且张数不足 / 没有 decideXY / 坐标在屏外 |
| `… 地图上还盖着「xxx」→ 先不点节点` | `handleMove` 的顶框闸门（同值只报一次） |
| `确认框：出现 +Xms（…）` / `点「确认」#N (x,y)` / `开始消失 +Xms` | 点节点第二跳的两步；`#N > 1` = 动画期补点（**正常**，多点当手段） |
| `商店读取：来源=top` | 正常；出现 `cache` 说明设备跑的是旧脚本 |
| `… 事件页还没铺开（顶框=无）→ 等下一轮再看` | 事件页未就绪早退（不再白扫一次树 + 假告警） |
| `… 事件选项卡还没铺好 → 等下一轮（先不点）` | 卡片在铺开途中（连续 3 轮才升级成 `[!]`） |
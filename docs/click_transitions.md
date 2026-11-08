# 点击 → 窗口转移表（状态机驱动点击）

> 标记：✅ 已做 ｜ ⏳ 待做 ｜ 🔍 待测

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
| `labView` | 只读 ViewManager→当前 View 类名 + `inBattle` | ~5~20ms |
| `labPartyIdx` | `labTop` + 编组页 `nowSelectParty` | 切队判据在用 |
| `labPartyLite` | 三队人数 `partyDic` + 当前队 + 槽位（不带 40 个候选） | ~ms |
| `labPartyTabs` | 编组页顶部页签按钮（切队用）的坐标 | direct：几 ms |
| `labBattleResult` | `inBattle` / `resultEnable` / `gameState` | 🔍 待实测 |
| `labExEquipTop` | 只读"特别装备三连页的**页身份**"（party/setting/sort），不遍历按钮子树 | 比 `labExEquip` 便宜 |
| `labExEquipSig` | ★**极轻**：只回三连页的**页面签名**（一个字符串）—— 不算坐标 / 不读按钮 / 不回传整包 JSON | 状态机轮询专用 |
| `labStatus` | 全量快照（地图节点、顶框、编组、遗物…） | 150~300ms |

> 只判某个字段时不要用 `labStatus`，用对应的轻量接口。

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

| 状态 | 动作 | from | 说明 |
|---|---|---|---|
| ⏳ | 前进到节点 XXX | `-`（地图） | 第一跳都是 `DialogCommonSmall`；第二跳按节点类型收紧 |
| ⏳ | ButtonQuest / 出发 | `-` | 待观测 |
| — | 屏幕中心（推进动画） | 任意 | 不换窗口，不适用 |

### B. 战斗 / 结算

| 状态 | 动作 | from | expect（阶段2） | 参数 |
|---|---|---|---|---|
| ✅ | 预览·开打（进编组） | `DialogQuestDetail` | `DialogCommonPartyEdit` | 600ms × 8，阶段2 超时 3s |
| ✅ | 战斗开始（普通） | `DialogCommonPartyEdit` | `@battle` | 800ms × 6 |
| ✅ | 决定·切队（Boss） | `DialogCommonPartyEdit` | 窗口不变、`nowSelectParty` 递增 | 只点 1 次 + 每 120ms 轮询，≤2s |
| ✅ | 结算·下一步 | `{BackKeyCapture, LabyrinthExResult, QuestResultWin}` | `{DialogLabyrinthSelection, DialogLabyrinthResult}` | 200ms × 25 |
| ⏳ | 结算·关闭 | 同上 | — | 低频 |
| — | 结算后加速·点中心 | 任意 | — | 不换窗口 |

**Boss 三队（3 队）**：三队挤在同一个编组页，点「决定」多数是切到下一队（顶框不变）。

| 状态 | 事项 |
|---|---|
| ✅ | 切到队伍 2/3 且这一队要编 ⇒ 就地算 + 编（约 6s 编完三队） |
| ✅ | 编组框消失 = 已经开打 ⇒ 残留的预览页空壳不再点「挑战」 |
| ✅ | 日志打「Boss 决定前：队伍1/2/3 各多少人」 |
| ✅ | 两个分支（`bossAdv` / `isBoss`）**已合并**成同一个"点决定 → 轮询等切队"循环 |

**结算页终点分叉**：普通战 / 非最终 Boss → `DialogLabyrinthSelection`；**50801 最终 Boss → `DialogLabyrinthResult`**，之后 `DialogAnimationViewer → DialogCommonFullSize → DialogCommonBig`。

### C. 三选一 / 遗物 / 门票（同一窗口名，三种用途）

| 状态 | 动作 | from | expect | 参数 |
|---|---|---|---|---|
| ✅ | 战后选人 | `DialogLabyrinthSelection` | `DialogCommonMiddle` | 150ms × 1 + 外层 10 次重读重算 |
| ✅ | 选择遗物 | `DialogLabyrinthSelection` | `DialogCommonBig` | 同上 |
| ✅ | 门票：选 N | `DialogLabyrinthSelection` | `DialogCommonBig` | 同上 |

外层重试：点一次停 150ms，没离开就清 `lastSelSig` → 120ms → 重读卡片位置/属性 → 重新决策 → 再点（最多 10 次）。
Boss 两轮选人：卡片 `sig` 变了 = 新一轮，`sig` 没变 = 上次没点上。

### D. 商店

| 状态 | 动作 | from | expect |
|---|---|---|---|
| ⏳ | 商店·买#N | `DialogLabyrinthShop` | 离开 → `DialogCommonMiddle` |
| ⏳ | 商店·更新 / 关闭 | `DialogLabyrinthShop` | 离开 |

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

### F. 通用弹窗（同壳不同内容，只能靠 `(窗口, 按钮名)` 区分）

| 状态 | 动作 | from | to |
|---|---|---|---|
| ⏳ | TwoButtonRight | `DialogCommonSmall` | `-`（关闭）×43 |
| ⏳ | OneButtonWhite | `DialogCommonMiddle` | `-`×29 |
| ⏳ | OneButtonBlue | `DialogCommonSmall` | `DialogLabyrinthShop`×22 |
| ⏳ | TwoButtonRight | `DialogCommonMiddle` | `DialogCommonSmall`×22 |
| ⏳ | OneButtonWhite | `DialogCommonBig` | `DialogCommonMiddle`×10 |

---

## 五、待测

| 事项 | 怎么测 |
|---|---|
| `gameState` 胜负枚举 | 跑一胜一负，对比日志 `◧ 战斗结果原始值：inBattle=… resultEnable=… gameState=…` |
| 地图节点第二跳期望 | NORMAL/HARD→QuestDetail、TICKET/RELIC→Selection、EVENT→LabyrinthEvent、SHOP→LabyrinthShop、BOSS→QuestDetail |
| 特别装备的**签名**判得准不准 | 真机跑一次编组，看日志 `⤷ [party\|change=false] --特别装备·ThreeButtonCenter--> [setting\|…]`，以及"读 N 次共 Yms"里的 Y（读一次多贵） |
| `labBattleResult` 耗时 | 接入时顺带采样 |

---

## 六、观测开关

- `MazeLoop.TRANS_VERBOSE`：命中时打 `⤷ [from] --动作--> [to] Nms（第 i 次点击已离开）`；
- 转移观测 `⟳`：点击后主循环下一轮比对（只记录，不改行为）；
- `labTop` 耗时采样：每进程一次（`labTopProbed`）。
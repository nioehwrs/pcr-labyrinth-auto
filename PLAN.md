# `labyrinth_auto` → 独立安卓 APK —— 小计划（只规划，尚未动工）

> 目标一句话：**做一个全新的、独立的 APK**，装在 MuMu（x86_64 Android）里自给自足地跑迷宫自动化——
> 通过 **Frida 读游戏内存** 得到"现在是什么页面/队伍/商店"，用**我们自己的决策逻辑**（`labyrinth_auto` 那套）
> 决定下一步，再用 **root 的 `input tap`** 点下去（与 PC 版同一条路；无障碍降为兜底）。不依赖 PC、不依赖 ADB。

- 状态：**规划中**（本文件只描述怎么做，代码未开始）
- 日期：2026-10-13
- 本工程：`D:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android`（**独立工程**，不放进任何现有仓库）
- 参照物：`D:\Users\zhouyi\Documents\LMJ\labyrinth_auto`（PC 端 Python + Frida JS，**继续开发，不冻结**）
- 参考代码来源：`D:\Users\zhouyi\Documents\LMJ\limingjie-assistant`（只**借用**其中的通用件，见 §2）

---

## 0. 已定的边界（用户 2026-10-13 拍板）

1. **决策逻辑 100% 用我们自己的**（`labyrinth_auto`：页面状态机 + 编队策略 + 商店/遗物/事件策略）。
2. ~~**点击留两条路**：先做**无障碍**；root 后端只留空接口~~ → **改为 root 优先**（`su -c input tap`，与 PC 版同一条路），无障碍作为没有 root 时的兜底（2026-10-14 反转，理由见 §8.1 与 `RootInputBackend` 注释）。
3. **独立 APK**：新工程、新包名，**不改 `limingjie-assistant`**（那边只当"代码来源"借用几个通用类）。
4. **PC 端 Python 版继续存在、继续开发/优化**（还没测完）。新 APK 是**并行的第二条腿**；
   PC 版长期充当**对拍 / 回归基准**：同一输入，两边输出必须一致。
5. **不做网页调试面板**。理由（你指出的）：网页在前台会把游戏切到后台 ⇒ 游戏暂停/掉线，
   所以观测必须在 **app 内**（见 §7）。
6. ~~本阶段**不碰**：原生登录协议、视觉识别（截图+OCR）路线、脚本管理/定时任务~~ → **原生登录协议已实现（2026-10-15 用户要求：直接在 Android 上实现刷开局）**：从 `limingjie-assistant` 借用整套 `protocol/bilibili`（B 站 SDK 登录的 RSA 加密 + MD5 签名 + geetest 验证码 + msgpack/AES + `tool/sdk_login`/`labyrinth/*` 协议）与 `protocol/labyrinth`，改包名落进 `com.lmj.labyrinthauto.net.bilibili` / `net.labyrinth`（见 §0.7）；视觉识别、脚本管理/定时任务仍不在范围内。

---

## 0.7 刷开局（reroll）实现说明（2026-10-15 新增）

- **动机**：PC 端 `reroll.py` 能在开局前从服务器读出 3/5 区 Boss 与完整地图并刷到合格开局；安卓端原本只靠 Frida 在游戏内现读，存在"开局读不到 3 区 Boss"的残差差异。用户要求直接在 Android 上实现刷开局，一并消除该差异。
- **登录**：采用 **B 站账密登录**（`BilibiliLoginCoordinator` + `BilibiliSdkHttpGateway`：RSA 加密密码 + MD5 签名 + 可能触发 geetest 验证码）→ 拿到 uid/access_key → `tool/sdk_login` 拿游戏服会话。验证码通过 `GeetestPageBuilder` + `AndroidView`(WebView) 内联极验组件，由用户手动完成。
- **协议层来源**：整套 `protocol/bilibili`（17 文件）与 `protocol/labyrinth`（2 文件）从 `limingjie-assistant` 复制、改包名到 `com.lmj.labyrinthauto.net.*`（遵守 §0.3 只借用不修改源工程）。msgpack/AES/RSA/签名除 okhttp 外均为 JDK 自实现；已在 `build.gradle` 加 `okhttp` 依赖。
- **判定复用安卓既有能力**：`RerollJudge` 直接调用 `Route.checkValuePerfect`（价值偏差）与 `PartyPlan.bossOf`（Boss 白名单），与 PC `reroll.py` 的 boss_area3/area5 + max_value_gap 口径一致，**不另建模板**。
- **循环**：`RerollController` 复刻 `ensure_opening`——已有开局先判定沿用/撤退，再 enter/retire 循环到合格（max_count≤0 不限次），含 `stuck_same` 同因卡死保护。
- **产物消费**：成功后写入内存态 `RerollState`（enter_id / 3·5 区 Boss unitId / 每区最优路线），`MazeLoop.run` 开局即注入 `bossByArea[3]/[5]` 并给 `Route.refreshRoute(savedRoute=…)` 喂每区路线。
- **UI**：`HomeScreen` 新增刷开局区块（账号/密码、高级设置、登录并刷、进度、极验弹窗）；状态经 `AppState.reroll*`。

---

## 1. 现状（为什么是"嫁接"）

| | `labyrinth_auto`（PC 版） | `limingjie-assistant`（现有 App） |
|---|---|---|
| 感知 | **Frida 读游戏内存** | MediaProjection 截图 + ML Kit OCR |
| 点击 | ADB `input tap` | **无障碍 `dispatchGesture`** |
| 注入 / root / native | 全靠 Frida + root | **零**（无 Frida、无 root、无 `.so`） |

新工程要的是：**左列的能力 + 右列里那几件通用安卓件**。所以是嫁接，不是移植。

---

## 2. 从 `limingjie-assistant` 借用的件（复制源码进来改，不做依赖）

| 用途 | 来源文件 | 借用方式 |
|---|---|---|
| 无障碍手势（Tap/Swipe/Back） | `automation/accessibility/LandosolAccessibilityService.kt` | 抄结构，改成我们自己的服务名 |
| 坐标映射（1920×1080 ↔ 设备坐标） | `automation/CoordinateMapper.kt` | 直接搬（纯计算，几乎不用改） |
| 前台服务 + 通知栏"暂停/继续/停止" | `automation/overlay/AndroidAutomationNotificationHost.kt` | 抄结构 |
| 渠道包名表（9 个包） | `protocol/bilibili/GameServer.kt` | 只取包名常量 |
| B 站登录 + 迷宫协议（刷开局用） | `protocol/bilibili/*`（17 文件：SDK 登录 RSA/签名/geetest + msgpack/AES + `tool/sdk_login`）、`protocol/labyrinth/*`（2 文件） | 整包复制改包名到 `com.lmj.labyrinthauto.net.*`；本工程新增 `reroll/`（判定/控制器/登录客户端/编排用例）与 `ui` 的刷开局区块 |
| 权限中心页 | `ui/permissions/PermissionCenterScreen.kt` | 抄结构（可选） |
| **悬浮窗**（运行时同屏显示状态） | `automation/overlay/AutomationOverlayService.kt`（该文件在那边**遗留未注册**，可当写法参考） | 抄结构（§7 要用） |
| 构建脚手架参考 | `android/gradlew*`、`libs.versions.toml`、CI | 新工程自己起一套，版本对齐 |

> ⚠ 借用时保留来源注释，并遵守 `limingjie-assistant/参考来源与洁净实现边界.md` 的规矩；
> 本工程自己的"哪些是读 dump.cs/反向得来的知识、哪些是自研"也要单独写一份边界说明。

### 必须新写

1. **Frida 读内存层**（本仓库/本工程都没有，唯一需要引入外部二进制或 `.so` 的地方）；
2. **决策逻辑的 Kotlin 版**（把 Python 那套按模块搬过来，见 §5）。

---

## 3. 工程形态（命名已定，P0 前随时可改）

- 新工程：标准 Gradle 单模块 `:app`（Kotlin + Jetpack Compose），`minSdk 26`、`targetSdk 35`、ABI **x86_64**（MuMu）。
- **包名 / 应用名（暂定）**：`namespace = applicationId = com.lmj.labyrinthauto`，桌面显示名 = **黎明界自动化**。
  （现在工程还不存在，改个名就是几处文本，成本极低。）
- 签名：debug 先跑；要长期用就配 release keystore（新工程自己生成）。
- 目录草稿：
  ```
  labyrinth_auto_android/
    PLAN.md                      ← 本文件
    参考来源与洁净实现边界.md      ← 开工时补
    android/                     ← Gradle 工程（结构对齐现有 App）
      app/src/main/java/<pkg>/
        ui/                      Compose 页面（含 §7 的调试页）
        service/                 前台服务、无障碍服务
        input/                   InputBackend：root 实现（默认）/ 无障碍实现（兜底）/ 前台安全闸门
        frida/                   Frida 链路：FridaCore(注入客户端) / AgentBridge(socket)
                                 / FridaServerInstaller(自带 server) / GameProcess / RootShell
        logic/                   决策层（搬 pcr_auto.py / team_build.py）
        data/                    数据表加载（assets 里的 JSON）
      app/src/main/assets/       agent.js + 各类 JSON
  ```

---

## 4. 通信方案

> **★定案（2026-10-14 实测）：用"frida-server 注入 + agent 自开 socket"，下面两节只作历史记录。**
> 实测 `frida-inject`（方案 A 的注入方式）**不可用**：只要它把脚本塞进游戏，游戏进程立刻自己退出
> （`exit-info = reason 1 EXIT_SELF`，frida-inject 侧打印 `Process terminated`）；同一个 agent 交给
> **设备上的 frida-server** attach 就毫发无损 —— 反作弊认的是"谁注入"，不是 agent 内容。
> 所以实际采用的是**混合式**：
> - **注入**：app 内嵌 `libfrida-core`（静态链进 `libfrida_bridge.so`）**当 frida-server 的客户端**，
>   请设备上的 frida-server 去注入（= 方案 B 的机制，agent 一行不改）；
> - **一问一答**：仍走 **agent 自己开 socket 回连 app**（= 方案 A 的转发层 `agent_bridge.js`）；
> - **frida-server 不依赖外部环境**：app 自带一份（assets → root 释放/拷贝/拉起，模拟器重启后自愈）。
> 代码落点：`frida/FridaCore.kt`、`frida/AgentBridge.kt`、`assets/agent_bridge.js`、
> `frida/FridaServerInstaller.kt`、`frida/GameProcess.kt`。

### 方案 A（历史，实测不可用）：`frida-inject` + agent 自开 Socket
- App 用 root 起 `frida-inject -n <游戏包名> -s agent.js`（frida 官方 release 有 android-x86_64 的 `frida-inject`）；
- `frida-inject` 没有外部 RPC 接口 ⇒ 由 **agent 自己**用 Frida 的 `Socket` API 回连 App 的前台服务；
- 协议（一行一条 JSON，UTF-8）：
  ```
  → {"id":7,"fn":"labStatus","args":{}}
  ← {"id":7,"ok":true,"data":{"pageTop":"DialogCommonPartyEdit", ...}}
  ```
- agent 改造：现有 `perception_patched.js` 的 `rpc.exports.<fn>` **函数体不动**，外面加一层
  "收命令 → 调同名内部函数 → 回包"的转发。**这是本工程的技术风险点**。

### 方案 B（**已采用**）：APK 内嵌 `libfrida-core.so`
- JNI 绑定 `frida-core`，app **当 frida-server 的客户端**请它去注入（不做自注入，见上面的定案）；
- 代价：引入 NDK/CMake + 打包 `.so`（已落地：`tools/build_frida_bridge.ps1` → `libfrida_bridge.so`）。
- ★ 2026-10-14 更新：原计划"先用 A 验链路、以后再说 B"作废 —— **A 被实测排除，直接用的 B**。

### 两种方案到底差在哪（白话）

现在 PC 版是这样跑的：**Python 是"打电话的人"**（`frida.attach(...)` → `script.exports_sync.labStatus()`），
Frida 的 JS agent 是"接电话的"，两边靠 Frida 自带的 RPC 通道一问一答。

APK 里没有 Python 了，于是"谁打电话"这件事有两条做法：

| | 方案 A（先做） | 方案 B（备用） |
|---|---|---|
| 谁负责打电话 | **agent 自己**：它用 Frida 的 `Socket` 功能主动连 App，App 只是"接电话的" | **App 自己**：在 APK 里带上 Frida 的库，像 Python 一样直接调 |
| 要改 agent 吗 | **要**：加一层"收命令→调用同名函数→回包"（函数体不动） | **不用**：agent 一行不改 |
| 要写 native/JNI 吗 | **不用**，App 纯 Kotlin | **要**：编 `libfrida-core.so` + JNI 绑定 |
| 谁先能跑通 | 快（1~2 天能看到画面） | 慢（一周级） |
| 风险 | agent 那层转发要写对 | native 编译/打包/符号对不齐 |
| 适合 | **先把整条链路验出来** | 以后想"agent 永久零改动"、长期维护更省 |

一句话：**方案 A 改 JS、App 简单；方案 B 不改 JS、APK 复杂。** 先用 A 验通，值不值得上 B 等跑过再说。

---

## 5. 分阶段计划（每阶段都有验收标准）

| 阶段 | 内容 | 验收标准 | 预估 |
|---|---|---|---|
| **P0 脚手架** | 新工程能编译出 APK；前台服务 + 无障碍服务 + 通知；`InputBackend` 接口（无障碍实现 + root 空实现）；借用的两三个类搬进来 | 装上后能打开页面、服务能起、无障碍能授权 | 1 天 |
| **P1 纵切** ✅ | App 起 frida-server → 注入 agent → agent 连回 socket → 读"当前页面名/最上层对话框名"并展示 | **屏幕上看到实时页面名**（注入/回传/展示全链路通）**— 2026-10-14 达成** | 1 天 |
| **P2 agent 转发** ✅ | `rpc.exports` 全量接到 socket（`AgentBridge` 按名转发）；App 侧统一封装 `frida/AgentApi.kt`（已覆盖全部导出，含 `labUnits`/`labOwnRelics`/`labStatus` 等迷宫内部 RPC） | 转发链路全通、能调任意导出函数拿 JSON；PC/APK 两张探针列表（22 项，含全部迷宫 RPC）**逐条对齐**。标题页对拍 **22/22**（2026-10-14）。**2026-10-15 进展**：① **证明 APK 内置 `agent_base.js` 与 PC 版 `perception_patched.js` 是同一份代码**（MD5 `6F3C500053FEF520DADC9822E21775D9`、364696 字节；★2026-10-16 重新核对仍**逐字节一致** —— 当天修了"遍历缓存时间戳取自扫描前 ⇒ 每轮 3 次真扫描"（两侧同改）、加了 `labQuestDetail`（两侧同一份），并把 Android 侧独有的 `labSelection/labChoices` 的 `force` 参数同步回 PC；曾有过的分歧已消除）⇒ 两侧结果差异**只可能来自游戏状态**，不可能是代码分歧；② 一键串联器 `tools/probe_maze.py`（PC 探针 → `adb root` 提权拉起前台服务 → **触发前删远端旧 `probe_app.txt`** → 触发 `ACTION_PROBE` → 拉取 → 比对）+ HomeScreen「PROBE 对拍」按钮；`probe_diff.py` 加**状态指纹守卫**（`viewId` 不一致即告警"本次无效"）+ 指针(`0x…`)/堆扫描计数归一化。<br>⏸ **首跑实况（无效对拍）**：`一致 10 ｜ 不一致 12`，但**状态指纹不一致**（APK viewId=259 vs PC=264）：PC 在迷宫内取样（有 `labStatus/labMap/labNodes/labUnits/labOwnRelics/labParty` 数据，`findList`=撤退/返回），APK 在黎明界 top 取样（迷宫数据全空，`findList`=挑戦中/商店）⇒ **12 个"不一致"全是状态差、非 bug**。**已收尾（2026-10-15）**：同一页面（viewId 264 编组页）连跑两侧拿到**干净对拍 22/22**（`probe_diff` 状态指纹一致、0 调用失败）；顺手修三处拦路：`probe_maze.py` 误把 `adb` 前缀拼到本机 `pc_probe.py` 调用前（`pc_probe` 此前根本没在跑）、PC 连 frida 需 `adb forward tcp:1234 tcp:1234`（模拟器重启即失效→`ServerNotRunningError`）、`labParty.found` 是依赖 agent 内部缓存的诊断字段已加进 `probe_diff.py` 的 `IGNORE_KEYS`（与 `scanned` 同机制）。⇒ P2 验收达成：**转发链路全通、22 项逐条对齐** | 1~2 天 |
| **P3 点击** ✅ | 点击后端接上（**root `input tap` 优先**，无障碍兜底）+ **"游戏必须在前台"安全闸门**（`service/A11yGuard.kt`）；`tap/swipe/back` | 已在 P5 真机全流程生效（选人/移动/门票等真实点击、点后回读校验）—— 2026-10-15 随 P5 验证 **✅** | 1~2 天 |
| **P4 纯函数层** ✅ | 搬 `team_build.py`（评分/权重/关卡档/属性限定/坦克规则）+ 商店刷新判据 + 遗物/掉落选择；**用 PC 版输出当基准对拍** | **本项清单全部落地（2026-10-14）**，三轮对拍全绿（`./gradlew test`）：<br>① **组队引擎**：常量/纯函数/关卡档逐项一致 + 500 条全链路用例 **500/500 逐字一致**（`plan/team/picked/reasons/types/attrs/attr_bonus/score/fail/log/warn`）；<br>② **遗物选择**（`pick_card` 等）：**220/220**（`pick/note/desc/ctx/stars/own_dot/dots`）；<br>③ **商店**：购买计划 **160/160**（`picked/desc/kinds/rates/slots`）、刷新判据 **1440/1440**。<br>工具：`tools/gen_team_fixtures.py` + `tools/gen_relic_shop_fixtures.py`（PC 侧生成，带 md5 基准）→ `tests/fixtures/`；Kotlin 侧 `logic/team/*`、`logic/relic/RelicPick.kt`、`logic/shop/ShopPlan.kt`（每个函数标了 PC 版行号）。<br>⚠**一处口径要说清**：刷新判据 PC 版**没有独立函数**（内联在 `shop_step` 4655~4714），基准是**照抄那几行**生成 ⇒ 只验"Kotlin 抄得对不对"，验不了"判据本身是否合理"（那要靠真机跑一轮商店）。<br>⚠另：`relic_rank.json` 真数据走不到的分支（如"吸血总星数 ≥20"）由基准里的 `extra_rank` 合成条目补上，**两边用同一份**。<br>④ **选人决策（战后三选一 + 门票）**：底座(tier/attr/卡序) + **L5~L1 全量特殊优先**（dot 最优先 / 第3层后无坦克→首个坦克 / 首个强击退 / 首个 S 级抽蓝坦克 / 对策）+ dot 软优先，已于 **2026-10-15** 补进 `CharaPick.pickLabReward`/`pickTicket`（原先只有底座）；依赖的 own 数据（当前队伍/已持有遗物/层数）由 `MazeLoop` 经 `labUnits`/`labOwnRelics`/`labStatus.area` 折算成 `LabSelCtx` 传入，handler 只取 PLAN 点击。<br>⑤ **事件选择决策缺口（2026-10-15 审计发现，待补）**：PC `STATUS_HANDLERS[4]=handle_event_choice` 按 `EVENT_PICK[eventId]`（默认第 1 项）选事件选项卡，Kotlin 侧**既无 handler（statusType=4 落 `unknownState` 卡死）、也无 `EVENT_PICK` 表、也无 `event_choice.json`**——与三选一同级的"漏搬决策"。**根因**：P4 的 `./gradlew test` 只验"有 fixture 的函数"，函数没搬就永远没用例、对拍永远绿，故三选一与事件选择都曾漏掉；对拍只能证明"搬了的函数没走样"，证明不了"该搬的都搬了"。事件选择决策 + 状态机 handler 已于 2026-10-15 补（`EventPick.kt` 决策 + `MazeLoop.handleEventChoice/Effect` 接 `when(stt)` 的 4/8）：决策按 `EVENT_PICK[eventId]`（默认第 1 项），handler 已接；待真机跑一轮事件验证。 | 3~5 天 |
| **P5 主状态机** 🔶 | 搬 `pcr_auto.py` 的页面分派与各 handler（编队 → 特别装备 → 战斗 → 选人 → 商店 → 遗物 → 事件 → 结算），一页一页搬、一页一页对照 | **骨架 + 链路全通（2026-10-15，真机）**：外层 HOME→冒险→黎明界→（挑战中才）出发→进入地图；迷宫内 `MazeLoop` 按 statusType/pageTop 分派。**已搬并真机验证**：① `SUMMON_UNIT` 选人；② 通用弹窗白名单点掉；③ 路线层（对拍 66/93/140 全绿）；④ 节点前进；⑤ 战斗节点 `handle_battle_node`；⑥ 编组页 `handle_party_edit`（PC 3228 核心，P4 决策编队）；⑦ **战后选人页 `DialogLabyrinthSelection`**（3 选 1，**P4 全量 L5~L1 特殊优先 + 门票属性次序**，见 P4④）；⑧ **门票页 `SUMMON_TICKET_CHOICE`**（同 `DialogLabyrinthSelection`，按属性次序选).<br>⑨ **事件页**（2026-10-15 补）：`EVENT_CHOICE` 按 `EventPick.pick(eventId,n)` 选第 N 张选项卡（默认第 1 项，卡片按 x 左→右排序，点 `labEvent` 返回的按钮坐标）+ `EVENT_EFFECT` 优先点「跳过」按钮、退点屏幕中心；决策表 `EVENT_PICK` 见 P4⑤（默认空→选第 1 项）.<br>⑩ **商店页**（2026-10-15 补）：`7=SHOP` 接 `handleShop`——读 `labShop` 出购买/刷新计划并真点「购买/更新/关闭」，被裁商品上滑拖列表（≤`SHOP_DRAG_MAX=3` 次）；`SHOP_AUTO=false` 可切只读.<br>**🟢 游戏重启已排除**：用户疑心"进战斗后游戏重启"，本回合冷启动重测——完整走完 `进迷宫→编队→开打→战斗胜利→点下一步→战后选人→按路线移动→门票选属性` 全程**没重启**（之前那次是上一会话残留状态导致的偶发）。<br>✅ **特别装备三连页已补（2026-10-15）**：`MazeLoop` 主循环在通用弹窗白名单**之前**拦截（`statusType∈{5,6}` 且 `labExEquipTop().top` 非空）→ `handleExequip` 单步驱动 `①特别装备设定→②自动特别装备设定→③优先属性值`，照搬 PC 的 `_exeq_step`：自动装备→改稀有度→补/取消勾选→确认→按 `isChangeExtraEquip` 点装备确定/取消；同队优化（`LAB_EXEQUIP_SKIP_SAME`）、第2/3队不共用装备（`exShare`）、读不到按钮超时放行都齐；agent 探针 `labExEquip`/`labExEquipTop` 已就绪。**注：PC 是 `LAB_EXEQUIP_AUTO=True` 会自动走完**，之前"停在确认框"只是 Kotlin 侧这一 handler 还没搬（并非 PC 设计）。待真机跑一轮验证。<br>⏸ **事件 handler 已补（2026-10-15）**：`MazeLoop` 现接 `4=EVENT_CHOICE`（`handleEventChoice`）+ `8=EVENT_EFFECT`（`handleEventEffect`），决策表 `EVENT_PICK` 见 P4⑤（默认空→选第 1 项）；待真机跑一轮事件验证。<br>⏸ **商店 handler 已补（2026-10-15）**：`when(stt)` 接 `7=SHOP`（`handleShop`：读 `labShop` → `ShopPlan.shopPlan` 出购买计划 + `refreshDecision` 出刷新判据 → 真点「购买/更新/关闭」；被裁商品先上滑拖列表 ≤`SHOP_DRAG_MAX=3` 次；`SHOP_AUTO=false` 可切只读。决策复用 P4③ `ShopPlan`）。待真机跑一轮商店验证。 | 无人值守从"已进入黎明界"跑到"本局结束" | 1~2 周 |
| **P6 常驻与恢复** | 通知栏控制；卡住时的现场 dump；Frida 掉线重连、重开局等恢复路径 | 连续跑 3 局不掉线、异常能自愈 | 2~3 天 |

> P4/P5 可以**不等 APK 链路**就开工：把 PC 版跑出来的输入/输出存成 JSON 用例，Kotlin 侧读同一份用例做对拍。

---

## 6. 需要搬运的资产（来自 `labyrinth_auto`）

| 类别 | 文件 | 处理方式 |
|---|---|---|
| 感知 agent | `perception_patched.js` | 函数体保留，加 socket 转发层 |
| 主状态机 | `pcr_auto.py`（约 2 万行） | 逐 handler 移植；判据/阈值先照抄 |
| 编队策略 | `team_build.py`（约 1.7 千行） | 优先做（纯函数、好对拍） |
| 数据表 | `relic_rank.json`、`shop_items.json`、`event_choice.json`、`labyrinth_boss.json`、`units.json`、`unit_tags.json`、`tag_defs.json`（外加 `stages.json`、`lab_guild_picks.json`，共 **9 张静态表**） | 原样进 `assets/`，加载逻辑照搬；`event_choice.json` 由 `EventPick.load` 在 `MazeLoop.run` 启动时加载（2026-10-15 补齐，此前缺失——见下） |
| 昵称/术语表 | `lab_guild_picks.json` 等 | 原样 |
| 日志字段 | `team_runs.jsonl` / `party_edit_log.jsonl` | 保持字段名，两边好对拍 |

> **静态数据表对账（2026-10-15）**：迷宫决策逻辑真正读取的静态表只有上面 9 张（其余 `config.json` / `labyrinth_state.json` / `reroll_token.json` / `*.jsonl` / `stage_tags.json` 是刷开局服务或 GUI 打标工具的运行时产物/工具文件，**不是**迷宫 runner 的配置，不搬）。
> 已对 PC 与安卓 `assets/` 逐张 MD5 比对：**`units / unit_tags / tag_defs / relic_rank / shop_items / labyrinth_boss / stages / lab_guild_picks` 8 张全部一致（OK，无漂移）**；`event_choice.json` 原本缺失，已复制补齐并由 `EventPick.load` 加载（复刻 PC `load_event_choice`：缺 `pick` 字段 / 读不到 ⇒ 退化为选第 1 项）。
> ★ 以后任何静态表改动都先在 PC 改、**手动同步**进安卓 `assets/`，并重新跑 MD5 比对确认一致（目前两边是同一份源）。
> **刷开局（reroll）已实现（2026-10-15）**：`config.json` 的 reroll 相关字段（guild_id / difficulty / boss_area3 / boss_area5 / event_value / max_value_gap / max_gap_areas / sleep_between / stuck_same）已作为 `RerollConfig` 默认值；账号走 **B 站账密登录**（RSA + 签名 + geetest 验证码），不再用 `uid/access_key` 直登。`labyrinth_state.json` 的等价物改为内存态 `RerollState`（含 3/5 区 Boss 与每区最优路线），开局时由 `MazeLoop` 直接消费（见 §0.7）。
> **遗物三选一已接（2026-10-15）**：`statusType 9`(RELIC_CHOICE) 现由 `MazeLoop.handleRelicChoice` 接住，逻辑 1:1 复刻 PC `maze_loop` 6297~6362（`RelicPick.pickCard` + `labChoices` 探针），与 Python **决策一致**；`statusType 3`(USE_RELIC) 标为演出等待（PC `MAZE_WAIT_ONLY={3}`）。**残差差异已消除（2026-10-15）**：原先 `special`（3 区 Boss=毒液沙罗曼蛇）只能"遇 BOSS 节点时记进 `bossByArea`"，故"打到 3 区 Boss 前就领遗物、且 3 区恰是毒液"会误判 false；刷开局上线后 `MazeLoop` 开局即从 `RerollState` 注入 `bossByArea[3]/[5]`，3 区 Boss 在领遗物前已知，`special` 不再误判。

---

## 7. 调试与观测（**app 内**，不用网页）

- **app 内 Compose 调试页**：完整的 页面名 / 关键状态 / 决策日志 / 暂停·继续·停止。
- **模式已定（用户 2026-10-13）**：**运行时用悬浮窗**叠在游戏上看（最简单，也顺手能看到"现在在干什么"）；
  **跑完一局 / 停手之后**再切回 app 看完整日志。悬浮窗需要 `SYSTEM_ALERT_WINDOW` 权限，
  在权限页里引导开启即可（借用来的那个遗留 overlay 文件有现成写法）。
  （"切回 app 看日志、游戏在后台继续跑"这条路不采用：既要点屏幕、后台行为也没把握。）
- **日志落文件**（app 私有目录），可导出/复制到 PC 与 Python 版日志并排比对。
- **通知栏**：进度 + 暂停/继续/停止（复用借用来的通知 host 结构）。
- 明确**不用**网页面板：手机浏览器在前台会把游戏切到后台 ⇒ 游戏暂停/掉线。
  （PC 版自己的 HTTP 面板保持原样，那是 PC 上的事。）

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| ~~`frida-inject` 与设备上 `frida-server` 版本/ABI 不匹配~~（该路线已废弃）| 实测 `frida-inject` 注入即被反作弊杀（见 §8.1 第 2 条）→ 改为"frida-server 注入 + agent 回连" |
| 无障碍 `dispatchGesture` 在模拟器偶发丢手势 | 已不是主路：**点击默认走 root `input tap`**；仍保留"点后回读校验 + 重试"（PC 版已有这套，照搬）|
| 游戏更新导致内存偏移失效 | agent 保留"自检 + 兜底读取 + 现场 dump"；偏移集中在少数常量 |
| 两边逻辑走样（Kotlin 与 Python 结果不一致）| P4/P5 强制对拍：同输入必须同输出，差异必须解释清楚 |
| 借用代码的许可/洁净边界 | 保留来源注释；本工程另写一份边界说明 |
| x86_64 只覆盖 MuMu | 本阶段只保证 MuMu；真机（arm64）另行评估 |
| 两套并行维护（PC + APK）不一致 | 数据表用**同一份 JSON 为源**，改动先改 JSON 再两边同步；对拍用例由 PC 生成 |

### 8.1 实测踩到的坑（2026-10-14，MuMu + PCR 11.7.2）

| 坑 | 对策 |
|---|---|
| **app 看不到别人的 `/proc`**：普通 uid 读 `/proc/<游戏pid>/maps` 是 `No such file or directory`（实测 `run-as … cat /proc/<pid>/stat` 同样）| 就绪判据**必须 root 读**：`su -c grep -m1 libil2cpp.so /proc/<pid>/maps`（PC 版 `so_mapped()` 注释里也记过这条）|
| **"启动期注入必崩"是错判**：当年冷启动 15s/45s 注入就原生崩溃，真凶是 **frida-inject 自注入**（反作弊必杀，与时间无关；90s 后注入照样死）| 注入只走 **frida-server**（方案 B = app 内嵌 frida-core 当客户端）；就绪判据用 PC 版同款"`libil2cpp.so` 已映射 + 静置 2s"——实测冷启动 **3.6s** 即可安全注入 |
| **后台启动 Activity 会被静默掐掉**：Android 10+ 的 BAL 限制里**前台服务不算豁免**；`startActivity` 不抛异常但系统 `Abort background activity start` | 调起游戏**优先 root `am start`**（= PC 版 `step_launch()` 那条路），常规 Intent 只当兜底（我们界面在前台时才可能成功）|
| **socket 编解码必须真 UTF-8**：转发层原先按 latin1（`charCodeAt & 0xff`）写，中文全废——不只日志，`labStatus` 的中文串、将来 `find("确认")` 这类参数都会坏 | `enc()` / `toText()` 都写真 UTF-8（含 4 字节/代理对）；对拍用例里先放一条含中文的参数各测一遍 |
| **无障碍授权会被系统摘掉**：`force-stop` / 模拟器重启 / 从最近任务划掉 app 之后，服务就不在 `enabled_accessibility_services` 里了（表现：点击全失败，界面上还不一定看得出来）| **点击已改走 root**（`input tap`），所以被摘掉**不再影响点击**；它现在只影响"前台包名"那道安全闸门，而闸门有 root `dumpsys` 兜底。**2026-10-14 落地 `service/A11yGuard.kt`**：①自检**能分辨两种原因**（"授权被摘" vs "授权在但没绑上"，处置办法不同）；②界面**置顶红色横幅**（原因 + 「一键恢复」+「去设置里打开」）；③**一键恢复用 root 把自己追加回启用列表**（`原值:我们`，**不动别人那条**），实测 <1s 绑上；④通知栏展开里也带原因。真机验证：启动自检判"授权已被系统摘掉" ✓ → 一键恢复后设置变为 `landosol…:labyrinthauto…` ✓、`dumpsys` 里回到 Enabled/Bound ✓|
| **frida-server 不是常驻**：模拟器一重启就没了 | app 自带一份（assets → root 释放/拷贝/拉起，`FridaServerInstaller`）：实测 `frida-server 没在听…走自带的那一份` → **0.7s** 起来 |
| **注入链路整体依赖 root**：起 frida-server、读 maps、am start、force-stop 都要 root | MuMu 的 SuperUser 授权框选**「永久记住选择」**一次即可；真拿不到 root 时自动降级为"时间闸门 + Intent 兜底"并明确报错（不静默失败）|
| **模拟器媒体栈自己会崩**：CRI 片头视频线程在 `MediaCodec::init` 拿到空 binder → CFI trap → SIGILL，表现为"游戏启动几秒就退"，与本工程无关 | 重启模拟器自愈（实测有效）。排查入口：`dumpsys activity exit-info <pkg>` + `/data/tombstones/tombstone_*` —— **先看 tombstone 再怀疑自己** |
| **frida 的进程枚举看不到游戏主进程**：`enumerate_processes()` 里只有 `com.bilibili.priconne:pushservice`，主进程被 MuMu 隔离（pid 明明在，`ps` 也看得到）| 一律**先用 `pidof` 拿 pid、再按 pid attach**（PC 版一直这么干）。app 侧 `gamePid()` 的三级兜底里那级 root `pidof` 是**必需的**，不是冗余 |
| **PC 连设备 frida-server 需要端口转发**：`adb forward tcp:1234 tcp:1234`（模拟器重启后会被清空，表现为 `frida.ServerNotRunningError`）| 对拍 / PC attach 前先 `adb forward --list` 看一眼；app 走设备内回环，不受影响 |
| **通用弹窗白名单可能"点错"**（2026-10-15 实测）：冷启动 HOME→「冒险」途中冒出一个 `TwoButtonRight` 弹窗，白名单把它当"确定"点掉 → 落到**剧情页(view 47)**（不是我们想去的 QUEST_TOP）| 与 PC 同款白名单（它也只按名单点），所以这条**两边同源**。三层防线：① 白名单只在"有 pageTop / 兜底轮数"时查（已如此）；② **未知页面一律停手**（`goToLabyrinthTop` 的"不在计划内的页面不猜着点"，本轮就是它兜住的 ✓）；③ **已知页面登记**（2026-10-15 已做，**PC 与 Android 同步改**）：`47 = 剧情首页`，处理方式＝点底部「冒险」（`ButtonQuest`，与 HOME 同一个按钮）⇒ 从剧情页能一路续到迷宫（真机验证 ✓）；④ 待办：继续把新遇到的"死路页"照此登记；给"我们自己驱动的页"补 PC 的 `_dlg_no3` 安全阀（`_own_skip` 迷宫循环里已有） |

---

## 9. 已定 / 仍待定

**已定（2026-10-13）**
1. 包名 `com.lmj.labyrinthauto`、应用名"黎明界自动化"（§3）。
2. 观测模式：**运行时悬浮窗**、跑完再切回 app 看日志（§7）。
3. PC 端 Python 版**继续开发、不冻结**，并长期充当对拍基准（§0.4）。
4. ~~点击：先做无障碍；root 只留空接口~~ → **改为 root 优先（`su -c input tap`，与 PC 版同一条路），无障碍降为兜底**（2026-10-14：frida 链路本来就要 root，a11y 反而脆弱——授权会被 force-stop/重启摘掉、模拟器偶发丢手势）。无障碍的"前台包名跟踪"仍保留，用作**点击安全闸门**。

**已定（同日追加）**

5. Frida 路线：~~先做方案 A~~ → **方案 A（frida-inject 自注入）实测不可用，改用"frida-server 注入 + agent 自开 socket"**（§4 定案，2026-10-14）。
6. 对拍：用例放 `labyrinth_auto_android/tests/fixtures/`，**由 PC 版脚本一键生成**，APK 版拿它自测（P4 才需要）。

**已定（2026-10-30）**

7. **ARM（arm64 真机）支持：暂不做**，方案与调研结论见 §11。当前行为是"明确告知不支持"（`AbiRepair.deviceSupportsX86_64()` 前置，不再白试重装/注入）。
8. `huabanxie-server` **不指望 pcrtool 作者出 arm64 版**（用户只是普通使用者，问不到）；将来要支持 ARM 就**自己按同一套去特征改造重编一个 arm64 frida**（见 §11.4）。

**渠道服验证状态（2026-10-30，0.4.0 发布前）**

| 服务器 | 状态 |
|---|---|
| MuMu 渠道服（`com.bilibili.priconne.yofun.mumu`） | ✅ **实机跑通**：启动渠道客户端 → 抓凭证 → 登录 → 到黎明界 top → 进地图 → 选人编队 → 战斗 → 结算 → 战后选人 → 下一节点 |
| 小米 / 华为 / vivo / OPPO / 九游 / 4399 / 应用宝 | ⚠ **理论可用、未实测**（同一套渠道服登录协议 + 同一套 `BilibiliGameGatewayFactory` 适配，只差实测） |
| 国服 B 服（`com.bilibili.priconne`） | 0.3.x 系列一直在用（账密登录） |

要补齐其余渠道，需要：对应渠道的账号 + 已装该渠道客户端（并预置 `x86_64` 架构）+ 开服时段；验收口径与 MuMu 那次相同（能进迷宫、能打、能结算）。

**暂无需你定的** —— P0/P1 可以直接按本文件开跑。

---

## 10. 名词解释（给非安卓/非 Frida 的人看）

- **Frida**：能钻进别的 App 进程里、读它内存、调它内部函数的工具。PC 版就是用它读游戏页面的。
- **agent / JS agent**：注入进游戏进程的那段 JavaScript（PC 版是 `perception_patched.js`），
  负责"读内存 → 整理成 JSON"。它跟 Frida 的版本绑定，跟 Android/PC 无关。
- **frida-server / frida-inject**：前者是设备上常驻的"服务端"，后者是"一次性把 agent 塞进某个进程"的小工具。
  **本项目只用前者**：自注入（frida-inject）会被这游戏的反作弊打死（§4 定案）。
- **JNI / native / `.so`**：安卓里用 C/C++ 写的那部分代码（`.so` 是编译产物）。本项目用它把 `frida-core`
  静态链进 `libfrida_bridge.so`（app 当 frida-server 的客户端）。
- **对拍**：拿同一份输入（同一个池子/关卡/页面快照）分别喂给 Python 版和 APK 版，
  比较两边输出是否**逐字一致**；不一致就说明搬运时改坏了。**这是保证"搬得没走样"的唯一办法。**
- **用例 / fixture**：就是上面那份"输入 + 期望输出"存成的 JSON 文件，PC 版跑一遍自动生成，APK 版拿它自测。
- **无障碍（Accessibility）**：安卓官方给"辅助功能"用的 API，可以模拟点按/滑动，不需要 root。
- **悬浮窗**：叠在其它 App 上面显示的小窗口，需要 `SYSTEM_ALERT_WINDOW` 权限。

---

## 11. ARM（arm64 真机）支持 —— **暂不做**，能做的话这样做（2026-10-30 记录，供以后捡起来）

### 11.1 先纠正一个误解

"ARM 上读不到游戏信息"**不成立**。读不到只发生在 **MuMu/Houdini 这一种组合**：arm64 游戏跑在 x86_64 宿主进程里被翻译执行，`/proc/<pid>/maps` 里没有 `libil2cpp.so`。
真机 arm64 上游戏是**原生**的，il2cpp 模块正常存在于进程里，arm64 版 frida 注入后能正常枚举模块、读内存、调 `il2cpp_*` 导出 —— 该有的信息都在。

当前不支持，纯粹是"**我们只发了 x86_64 的工具链**"：

| 层 | 现状 | 卡在哪 |
|---|---|---|
| 助手 APK | `abiFilters = x86_64`、`jniLibs/` 只有 x86_64 | ARM 机器**装不上**（`INSTALL_FAILED_NO_MATCHING_ABIS`） |
| `libfrida_bridge.so` | 121MB，静态链 `libfrida-core.a` | 只有 x86_64 一份 |
| 设备端 `huabanxie-server` | 只有 `-x86_64.gz` 发布件 | 只有 x86_64 一份 |

### 11.2 需要的改动清单（按顺序）

1. **`libfrida_bridge.so` 出 arm64**（可控）
   - 源码 `tools/frida_bridge/bridge.c`（8.4KB JNI 壳）；构建 `tools/build_frida_bridge.ps1`
   - 需要 `frida-core-devkit-17.16.4-android-arm64`（当前用的是 `D:\Environment\frida-devkit-17.16.4`）
   - 脚本里 `--target=x86_64-linux-android` 和输出目录 `jniLibs\x86_64` 是写死的 → 加 `-Abi` 参数；产物放 `jniLibs/arm64-v8a/`
   - 放开 `abiFilters`；**注意 APK 体积**（两个 121MB）→ 建议 ABI splits 或按需下载桥接库
2. **arm64 的 `huabanxie-server`**（不可控，见 11.4）
   - 发布件命名 `huabanxie-server-17.16.4-arm64.gz`，`FridaServerInstaller.ASSETS` 加一行（解压字节数 + sha256）
   - `abiOf()` 已支持 arm64 映射，不用改
   - 版本三方必须对齐：server / 桥接库内嵌的 frida-core / PC 端 frida 客户端
3. **判定逻辑去掉写死 x86_64**
   - `AbiRepair.TARGET_ABI` → "设备首选 ABI"（`Build.SUPPORTED_ABIS.first()`）
   - `deviceSupportsX86_64()` → `supportsTarget()`
   - `ensure()` 在真机上应退化成 **no-op**（真机上游戏本来就该是 arm64，与设备一致就不需要"修"）；`report()` 里"走 ARM 翻译层"的提示只在 x86 宿主上有意义，要按宿主架构分支
4. **agent 脚本**：基本不用改
   - 65 处 `il2cpp_*` 都是 `findExportByName` + `NativeFunction`，frida 自动按目标架构处理调用约定（x86_64: rdi/rsi…+xmm0；arm64: x0–x7 + v0）
   - **已无写死 RVA**（2026-10-30 删掉了 `RVA_INIT`/`RVA_CTOR` 两个死常量，取地址一律走 `luMethodPtrByName`）
   - 字段偏移：arm64 与 x86_64 都是 LP64，类布局**基本一致**，但要在真机抽查 `LabyrinthTempData` / `ViewLabyrinthMap` / `LabyrinthPanelData` 三个类再确认
5. **真机环境**（最不确定的部分）
   - root：Magisk / KernelSU 的 `su` 语义、`/data/local/tmp` 可否执行、SELinux enforcing
   - 前台检测：走 `dumpsys input` 的 `FocusedWindows`，Android 13+ 正常，12 及以下为空段（模拟器上吃过这个亏）
   - 反作弊：真机还有 Play Integrity 一类检测，`huabanxie` 的伪装在模拟器上验过，真机要重测

### 11.3 验收标准（最小链路）

真机上：能装 → 能起 server → `attach` 成功 → 能读一次 il2cpp（打印迷宫 panel 列表）→ 再谈跑局。

### 11.4 关于 `huabanxie-server`：它不是"改名的 frida-server"

2026-10-30 对 `D:\pcrtool\assets\frida\huabanxie-server`（x86_64 ELF，111,587,168 字节，与 APK 资产同尺寸）做过字符串取证：

- 经典检测特征**全部为 0**：`frida:rpc`、`gum-js-loop`、`pool-frida`、`gmain`、`frida-agent`、`frida-helper`、`frida_memfd`、`frida-server`
- 换成了整套 `huabanxie-*`：`/data/local/tmp/huabanxie-helper-`、`huabanxie-agent-{arm,arm64,32,64,<arch>}.so`、`huabanxie_agent_main`、SELinux 标签 `huabanxie_memfd` / `huabanxie_file`、`huabanxie-agent-container` / `huabanxie-agent-emulated`
- `frida_agent_main`(15) → `huabanxie_agent_main`(20) **长度变了 ⇒ 不是等长 hex patch，是改源码重编**
- 残留（一般不用于检测）：`/frida/runtime/*.js`、Vala 符号 `_frida_thread`、构建路径 `../subprojects/frida-core/...`
- 附带发现：这份 server 内部**已包含 arm/arm64 agent 的加载逻辑**，它本身支持注入 64 位 ARM 目标

⇒ **arm64 版不能拿官方 arm64 server 改名了事**，必须用同一套去特征改造重编。用户只是 pcrtool 的普通使用者、问不到作者，所以将来真要做 ARM，就走"**自己按上面的标识清单改 frida 源码 → 编 arm64 server**"这条路（可作为 §11.2 第 2 步的替代实现）。

**路线选择（2026-10-30 用户拍板）**：只走 **A 路线（改源码 + 交叉编译重编）**。"等长二进制字符串替换"这条路评估过、**不采用**，以后不要再提。

自建时的验收：`strings` 扫上面那批特征串必须全为 0，且真机能注入不被踢。

# labyrinth-auto（Android）

公主连结（国服）迷宫自动化工具 Android 版。前台服务常驻运行，通过 frida 注入读取游戏内存中的迷宫状态，再用 root 模拟点击驱动游戏，完成自动战斗 / 编队 / 商店 / 遗物 / 撤退等一整局流程，并支持「刷开局」与「端侧推理模型组队」。

> ⚠️ 仅支持 **x86_64 模拟器（MuMu / 雷电）**，且 **必须 root**。由于注入所用的 frida 本身就依赖 root，无障碍（Accessibility）并不能独立支撑运行，因此设计上已移除无障碍后端、统一只走 root 路径。

---

## 整体架构

```
LmjApp / MainActivity（Compose 主页）
   └─ core（全局状态 AppState / 日志 / 组件预热 / 账号 / 更新）
         └─ ComponentPrep ── frida(server + 内嵌 frida-core) + labmodel(ONNX 模型)

AutoForegroundService（常驻前台服务，承载连跑主循环）
   ├─ frida：起 huabanxie-server，注入 agent.js 进游戏进程
   │     └─ AgentBridge(socket) ← agent 读 libil2cpp 内存，回报迷宫状态
   │           └─ AgentApi（类型化 RPC 封装）
   ├─ input：Foreground 前台闸门 + CoordinateMapper 参考系换算 → RootInputBackend
   ├─ reroll：接口刷开局 / 局内刷 / 渠道服凭证抓取（走 net 游戏服 API）
   └─ logic(MazeLoop)：每局状态机主循环
         ├─ AgentApi（状态） + input（点击）
         ├─ labmodel（ONNX 编队/选人）← 不可用时回落 logic/team 规则版
         ├─ data（角色/遗物/商店静态表）
         └─ OverlayStatus → ui(OverlayWindow)
```

要点：

- **frida 是命门**：没 root → server 起不来 → 注入失败 → 读不到页面 → 整个自动化无法工作。
- **两条通信通道**：游戏**内部状态**走 frida agent 的本地 socket（`:27183`）；**刷开局 / 登录**走 `net` 的游戏服私有协议（B 站 SDK）。
- **决策系统有两套**：① **端侧 ONNX 模型**（`labmodel`，编队/选人推理，对应 PC `inference.cpp`/`team.cpp`）；② **规则版**（`logic/team/` 与 `CharaPick`/`RelicPick`/`ShopPlan`/`EventPick` 等，移植自 PC 规则与评分）。策略页「计算方法」可切「旧版规则 / 推理模型 / 混合」，**默认混合＝模型优先、不可用时静默回落规则版**。刷开局优先 **net 接口刷**（快），无凭证可用 **局内刷**。

---

## 模块说明

### `core` —— 应用级基础设施
跨模块共享状态、日志落盘、崩溃兜底、运行组件预下载、账号 / 凭证 / 设置存储。
- `AppState.kt`：全局 `StateFlow` 单一真相源（`running / rootOk / pageName / fridaStatus / overlayGranted / component / model / abnormalExit / updateInfo …`）。
- `ComponentPrep.kt`：串行预下载「运行组件 server → 注入桥 .so → 模型组件」。
- `UpdateChecker.kt` / `FridaServerInstaller`：应用内自更新（从 Gitee `latest.json` 拉清单，校验 versionCode + HTTPS + sha256 + 本机签名）。
- `CrashGuard.kt` / `LogFile.kt`：全局崩溃兜底与逐行 flush 的日志落盘。
- `Accounts.kt` / `SecureStore.kt`：账号表与加密凭证；`OverlayStatus.kt`：悬浮窗快照（被主循环写、OverlayWindow 读）。

### `data` —— 静态游戏数据表
从 `assets` 惰性加载并缓存游戏数据（角色 / 标签 / 遗物 / 商店 DP 曲线），只做最薄读取，派生逻辑留在 `logic`，字段与 PC 版逐字对齐。
- `GameData.kt` / `Tables.kt` / `RelicTables.kt`：角色词表、遗物字典、`shop_curves.json` 商店精确 DP 曲线等。

### `frida` —— 注入链路与 App↔游戏通信
把一段 JS agent 注入游戏进程、维护注入后的自愈、处理 App 与游戏间的一问一答、root 探测、渠道服凭证抓取。
- `FridaServerInstaller.kt`：起 **huabanxie-server**（改名版 frida-server，规避反注入）于 `127.0.0.1:1234`，支持 Range 续传 + sha256 校验（固定 Gitee tag 下载）。
- `FridaCore.kt`：内嵌 **libfrida-core 客户端**（121MB `.so`），作为 frida-server 的客户端执行注入（自注入会被反作弊打死，必须走 server 这条路）。
- `FridaInjector.kt`：注入时序与**自愈总闸** `autoRecover`（agent 掉线先同 pid 原地重注入，多次失败才杀游戏重开，达上限暂停交还用户）。
- `AgentBridge.kt`：本地 socket 服务端（`:27183`），一行一条 JSON，单行写 + 超时/掉线判定 + 连接代际防幽灵掉线。
- `agent_base.js`（554KB，读 `libil2cpp.so` 内存字段提取迷宫状态）+ `agent_bridge.js`（socket 转发，挂 `Interceptor` 截渠道服登录参数 `captureArm`）拼接成 `files/agent.js`。
- 关键 RPC（`AgentApi.kt` 类型化封装）：`viewId / labStatus / labTop / labPartyIdx / screen / tree / find / captureArm / battleSettings / stuckDump` 等，按 PC 版同名全量转发。
- `GameProcess.kt` / `RootShell.kt` / `RootCheck.kt` / `AbiRepair.kt`：进程启停、su 多路径探测、x86_64 架构修复。
- `AccessCapture.kt`：渠道服凭证抓取（重启客户端 → 注入 → 挂 `captureArm` → 轮询拿到 uid/access_key）。

### `input` —— 模拟点击 / 手势
把参考系坐标（1920×1080）换算成屏幕像素并发出点击 / 滑动 / 返回。**只走 root**（无障碍后端已移除）。
- `InputBackend.kt` / `RootInputBackend.kt`：`su -c "input tap/swipe/keyevent"`。
- `CoordinateMapper.kt`：参考系 ↔ 真实屏幕等比重映射（含黑边、居中、calibration），出界绝不猜。
- `Foreground.kt`：点前安全闸门，用 `dumpsys input` 的 `FocusedWindows` 判断「下一个 tap 落到哪」，切走即中止运行。

### `labmodel` —— 端侧推理模型（编队 / 选人）
黎明界编队「推理模型」，端侧 **ONNX Runtime** 推理，逐段对应 PC 决策系统 `inference.cpp` + `team.cpp`。
- `LabModel.kt`：加载 `context/decoder/critic` 三个 session + `metadata.json`（角色/关卡词表、enemies、priors）。
  - `planTeam(...)`：beam / Gumbel 束搜索生成编队候选 → critic 六分类（5 时长箱 + 失败）打分排序。
  - `pickChar(...)`：战后三选一，多目标加权（3/5 区首领 + 4 个 EX 关卡）。
  - `ready=false` 或关卡未收录 → 调用方静默回落规则版。
- `LabModelInstaller.kt`：模型组件下载 / 解压 / 校验（固定 Gitee tag，13MB zip，白名单解压）。

### `logic` —— 自动战斗 / 迷宫循环（主循环 + 状态机）
一局迷宫的完整状态机与主循环、导航、编组、遗物、商店、队、事件、Boss 三队、撤退、汇总。
- `MazeLoop.kt`（核心）：每局 `run(input, ctx)`，每 `POLL_MS=100ms` 经 `AgentApi` 拿迷宫快照，按 `statusType`(0..9) 分派到各 handler（事件 / 编队 / 商店 / 遗物 / 战斗 / 撤退）。依赖 `Foreground` 闸门、`OverlayStatus`、`LabBtnStore` 节点路径缓存。含「凹分模式」。
- `Navigator.kt`：点到黎明界各界面，按钮按**节点名**定位（分辨率无关），`VIEW_NAMES` 照抄 PC 版。
- **规则版决策系统（第二套）就在此模块**：`team/TeamBuild.kt` / `TeamScore.kt` / `Rules.kt` / `TeamTuning.kt` 等负责编队与评分（模型不可用 / 未学该关时的兜底，也可在策略页单独选「旧版规则」）；`CharaPick.kt`（战后三选一多目标加权）、`RelicPick.kt`（遗物决策）、`ShopPlan.kt`（精确 DP 走 `GameData.shopDp`）、`EventPick.kt` 等承担各场景的规则决策。
- `party/PartyPlan.kt`、`BossChain.kt` / `MazeRetreat.kt` / `MazeSummary.kt` 等：编队计划、Boss 连链、撤退、整轮汇总。

### `net` —— 后端通信
与游戏服 / 决策服务器通信（刷开局 API、B 站 SDK 登录、私有协议、验证码/自动打码）。
- `labyrinth/`：`BilibiliLabyrinthApi.kt` 以游戏服协议调 `labyrinth/top|enter|resume|retire`，`enter` 解析节点 `map_list`。
- `bilibili/`：`BilibiliLoginCoordinator.kt`（处理 `CaptchaRequired`，先 `RemoteCaptchaSolver` 自动解极验，失败回手动 WebView）、`BilibiliSdkGateway`（RSA + 签名 SDK 登录）、`GameProtocolCrypto`（MessagePack 编解码）。
- 通信目标：Gitee 公开仓库（更新 / 组件下载）与 B 站游戏服（登录 + 黎明界 API）。

### `reroll` —— 刷开局
拿到「合格开局」（指定公会 / 难度的 Boss 白名单 + 价值偏差达标），两种实现 + 凭证抓取编排。
- `RerollController.kt`：接口刷主循环（移植 PC `reroll.py`）：已有未撤离开局先 `resume` 再 `judge`，否则 `retire` 后循环 `enter/retire`。
- `RerollUseCase.kt`：编排层（登录 → 刷开局 → 存结果），验证码出现时挂起等用户解完。
- `InGameReroll.kt`：**局内刷**（不调 API、不需凭证，走游戏内正常入口开一局 → 读地图 → 不满意点撤退），节点坐标用 agent 报的 `x/y`。
- `LabyrinthLoginClient.kt`：账号 → 游戏服会话（B 服走 SDK 登录；渠道服用 `AccessCapture` 抓取直通）。

### `service` —— 前台服务承载自动化
- `AutoForegroundService.kt`：常驻前台服务，`onStartCommand` 按 action 分发（`ACTION_INJECT` / `ACTION_RUN_FULL` / 调试）。`ACTION_RUN_FULL` 流程：root 自检 → 校验游戏包名 → 装备快照 → `InputBackends.pick` → 多公会展开 → 循环 `总` 局：注入 → 接口刷 / 局内刷 → `MazeLoop.run`；结束还原装备。

### `ui` —— 界面与悬浮窗
- `HomeScreen.kt`：Compose 主页，承载账号卡、设置、连跑控制、进度/日志/验证码。
- `OverlayWindow.kt`：状态悬浮窗（胶囊条 + 三气泡：局数 / 层数 / 状态），`WindowManager` 横屏居中、不挡操作、可拖动；collect `OverlayStatus.snap` 实时更新。第 3 格需人接手时变橙红。

---

## 构建

```bash
cd android
./gradlew assembleRelease        # 产物：app/build/outputs/apk/release/app-release.apk
```

需要签名：放 `android/keystore.properties` + `android/keystore/release.keystore`（与 CI/发版一致）。运行组件、注入桥、模型组件在首次启动后由 `ComponentPrep` 从 Gitee 公开仓库按需下载，无需在仓库内带二进制。

发版走一键脚本（构建 → 生成 `latest.json` → 传 Gitee 公开仓库 Release）：

```bash
python tools/release.py --version 0.7.4 --code 52 --upload
```

---

## 许可

本软件基于 **AGPLv3** 发布。它包含 / 派生自 [`PCR_Labyrinth_Decision_System`](https://github.com/Lanly109/PCR_Labyrinth_Decision_System)（AGPLv3）的代码与模型，因此整体以 **GNU Affero General Public License v3 或更高版本** 许可。

- 源码：见仓库根 `LICENSE`。
- 自动更新与运行组件发布在 Gitee 公开仓库 `labyrinth-auto-release`。

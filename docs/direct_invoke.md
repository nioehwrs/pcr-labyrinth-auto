# 直调按钮（跳过 root 点击）

> 分支 `feat/direct-trigger` · 2026-11-15 ~ 11-16 · 状态：PC 侧已验证 5 类控件，**尚未接入 `MazeLoop`**

目标：**不点屏幕**，直接在游戏内执行按钮自己的点击回调，替代 `su -c input tap`。

---

## 1. 实现（agent 侧）

| 组件 | 位置 | 说明 |
|---|---|---|
| 常驻派发钩子 | `agent_base.js:7727 _dispInstall` / `:7718 _dispDrain` | 注入时**只挂一次**（`UICamera.Update`，失败换 `ViewManager.Update`），每帧从队列取 1 件任务在游戏线程执行。范式照抄 pcrtool（它挂 `BattleHeaderController.SetRestTime`），但不写死 RVA，类/方法名全走 il2cpp 反射 |
| 节点选取 | `:7813 _findNodeP` | 名字正则 + **可选屏幕坐标 `xy`**（取屏内最近）。同名节点（地图格子 / 三选一）只能靠坐标区分 |
| 组件与回调 | `:7876 _pressNow` | ① `onClick`(List\<EventDelegate\>) → `EventDelegate.Execute` ② 组件 `OnClick()`（仅无参） ③ 都没有则报诊断 |
| 反射辅助 | `:7751 _fieldOff` / `:7790 _findMethodNP` | 字段偏移（含父类、记忆化）；按「方法名 + 参数个数」精确选重载（`Execute` 有多个重载，拿错必崩） |
| RPC | `:8268 pressNodeStart` / `:8287 pressNodePoll` / `:8297 pressState` | **两段式**：`runtime_invoke` 必须在游戏线程，而 RPC 里同步等主线程会死锁（同 `battleSettings`） |

App 侧：`AgentApi.pressNode/PressState`、`AutoForegroundService.ACTION_PRESS`（自检用）。
PC 侧调试器：`tools/press_test.py`。

`JS_BUILD = cs100`。

---

## 2. 四个坑（都踩过，别再踩）

### ① 运行时必须是 QJS，不能用 V8
`tools/frida_bridge/bridge.c:197` 明确设置 `FRIDA_SCRIPT_RUNTIME_QJS`；frida-python 的 `create_script` 默认 `runtime=None` → frida-core 默认 **V8**。
V8 的 agent 会 JIT、建大量 RWX 段，这游戏的反作弊线程 **`crivpd_server`** 会直接
`SIGSEGV / SEGV_ACCERR / Cause: trying to execute non-executable memory` 自毁（fault 地址恒定 `0x743210c43610`，是确定性触发）。
⇒ `session.create_script(src, name='lmj-agent', runtime='qjs')`。**改成 qjs 后一次就通。**

### ② `eternalize` 之后 RPC 不可用
`script.eternalize()` 会让脚本脱离 session，之后任何 `exports_sync.*` 都报
`frida.InvalidOperationError: script has been destroyed`。
app 无所谓（它走 socket，不用 RPC）；PC 侧调试**不要加** `eternalize`。

### ③ 本构建没有 `GetComponent(Type)`，`_compSelf` 是废的
`agent_base.js:4479-4482` 早有记录：必须用 `GetComponentInChildren(Type, true)`（Unity 语义：先自己、再子节点）⇒ 用 **`_compSelfTry`**。
用 `_compSelf` 的后果是「节点上没有 CustomUIButton/UIButton」，而 `_dumpComps` 却在同一节点上找到了 —— 两处矛盾就是这个问题。

### ④ il2cpp 初始化挂在 250ms 定时器上
`agent_base.js:2564` 的 `setInterval(... initIl2Cpp())`；PC 侧只发 `agent_base.js`（不带 `agent_bridge.js`）时不可靠，`ready()` 恒 false。
调试脚本里追加了一个 `rpc.exports.bootInit` 直接同步调用 `initIl2Cpp()` 绕开定时器。

---

## 3. 实测记录（真机，x86_64 MuMu）

| 场景 | 节点 | 选点 | 结果 |
|---|---|---|---|
| 底部导航 | `ButtonQuest` / `ButtonHome` | 名字 | `viewId` 5 ⇄ 20 ✓ |
| 通用对话框 | `TwoButtonLeft`(取消) / `TwoButtonRight`(确认) | 名字 | 关框成功 ✓ |
| **地图格子** | `PartsLabyrinthPanel(Clone)` ×13 | **xy** | 弹「移动目标确认」→ 确认 → 角色移动 ✓ |
| **三选一** | `DecideButton` ×3 | **xy** | 选中「火属性随机印记×3」→ 获得道具 ✓ |
| 奖励/角色加入框 | `OneButtonWhite` | 名字 | 逐层关闭 ✓ |

共同特征：

- 全部 `via = "EventDelegate.Execute"`，`onClick` 字段偏移 **`0xC8`(200)**，各 1 条 `EventDelegate`
- 组件实际是 `UIButton`（`CustomUIButton` 是它的子类，`GetComponentInChildren` 会认派生类，所以 `btnCls` 常报 `CustomUIButton`）
- 触发后**游戏进程稳定**，无崩溃

---

## 4. 判据：`viewId` 不能当唯一依据

- **关对话框**：`viewId` 不变
- **地图内移动**：`viewId` 也不变（一直是 `264` 迷宫视图）

⇒ 现有 `tapUntilChange`（靠 `viewId` 变化判断"点成功了没"）在**对话框和地图**两类场景失效。替代：

- 对话框：看「某节点是否还在」（如 `DialogCommonSmall(Clone)` / `PartsDialogCommonConfirmSmall` 是否从 dialog 根消失）
- 地图：用 `labStatus` / 格子状态的内存读数（本来就有）
- 三选一/奖励框：看下一层框是否出现

---

## 5. 调试工具 `tools/press_test.py`

前置（PC 侧，**不需要 adb root 反复切换**，但起 server 需要一次 root）：

```bash
adb root
adb shell "/data/local/tmp/huabanxie-base -l 0.0.0.0:1234 -D >/dev/null 2>&1 </dev/null"   # ★必须重定向，否则 adb shell 不返回
adb forward tcp:1234 tcp:1234
```

用法：

```bash
python tools/press_test.py --pid <pid> --status
python tools/press_test.py --pid <pid> --list "Btn|Button" --root footer
python tools/press_test.py --pid <pid> --node "^TwoButton" --root dialog --dump     # 组件 + 按钮文字
python tools/press_test.py --pid <pid> --node "^PartsLabyrinthPanel" --root view --xy "640,200" --probe
python tools/press_test.py --pid <pid> --node "^DecideButton" --root dialog --xy "640,591"
```

要点：`--no-eternalize` 必须给；每次 attach 都会往游戏里注入一份 agent，**一次会话里跑完所有动作**，别分多次连。
`--status` 里的 `pressState.frames` 涨说明派发钩子在跑。

---

## 6. 待验证 / 未覆盖

- 商店货架 `PurchaseButton`（可能带二次确认）
- 编队页 `selectButton`（`PartsSelectionItemInfo+0x60`）
- 结算页 `nextButton`（`DialogLabyrinthResult+0x108`）
- 战斗内 UB（大概率不是 UIButton，得走别的方法调用）

## 7. 接入 Kotlin 的计划（未做）

1. 新增 `DirectInvokeBackend : InputBackend`：`tap(x,y)` → `pressNode(节点名, xy)`
2. `Navigator` 把「刚找到的节点名 + 坐标」一起传下去（坐标从"点哪里"变成"选哪一个"）
3. 判据按 §4 改造，`tapUntilChange` 只保留在 `viewId` 真会变的场景
4. 按用户要求「改到哪删哪段 tap」，全部切完后 `InputBackend` 的 root 点击路径即可删除

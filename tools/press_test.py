"""开发小工具：PC 侧直连 frida-server 测「直调按钮」（pressNodeStart/Poll）。

为什么要单独一个脚本（不能复用 agent_call.py）：
  `pressNode` 是**两段式** RPC（投递 → 轮询取结果），而 agent_call.py 每次运行都新建一个
  script 实例、调完就 detach ⇒ 投递和轮询落在两个进程里，永远拿不到结果。

用法：
    # 看当前页面 + 列出名字像按钮的节点（先找目标）
    python press_test.py --status
    python press_test.py --list "Btn|Button"

    # 只探不点：这颗按钮的 onClick 里到底挂了什么
    python press_test.py --node "^btn_back$" --root dialog --probe

    # 真按（不点屏幕，直接触发回调）
    python press_test.py --node "^ButtonQuest$" --root footer

前置：
    1. 设备侧 frida-server 在跑、端口转发好：
         adb shell "nohup /data/local/tmp/huabanxie-base -l 0.0.0.0:1234 >/dev/null 2>&1 &"
         adb forward tcp:1234 tcp:1234
    2. PC 侧 frida 版本与设备 server 一致（当前 17.16.4）。
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

import frida

BASE = Path(__file__).resolve().parent.parent / 'android' / 'app' / 'src' / 'main' / 'assets' / 'agent_base.js'
REMOTE = '127.0.0.1:1234'
GAME = 'com.bilibili.priconne'


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')   # 中文 Windows 控制台是 GBK
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument('--pid', type=int, default=0, help='目标进程 pid（默认按包名从 adb 里取）')
    ap.add_argument('--remote', default=REMOTE)
    ap.add_argument('--status', action='store_true', help='打印 viewId / pressState')
    ap.add_argument('--list', metavar='RE', help='列出名字匹配的节点（正则）')
    ap.add_argument('--root', metavar='ROOT', help='遍历根 view/footer/layer/dialog/top')
    ap.add_argument('--node', metavar='RE', help='pressNode 的节点名正则')
    ap.add_argument('--probe', action='store_true', help='只探不点')
    ap.add_argument('--dump', action='store_true', help='列出目标节点上的全部组件（诊断用）')
    ap.add_argument('--xy', metavar='X,Y', help='同名节点多份时按屏幕坐标最近挑一个')
    ap.add_argument('--block', type=int, metavar='ID', help='★地图节点按 BlockId 直调（推荐）')
    ap.add_argument('--path', metavar='ROOT:i,j,k',
                    help='★路径轻读/直调：rootKind:索引链（如 canvas:0,4,1,0），配合 --btn/--probe')
    ap.add_argument('--btn', metavar='NAME', help='配合 --path：目标节点名（名字校验）')
    ap.add_argument('--call', metavar='FN', help='直接调一个 agent RPC 并打印结果（诊断）')
    ap.add_argument('--poll', action='store_true', help='--call 之后轮询 pressNodePoll（两段式 RPC 用）')
    ap.add_argument('--args', metavar='JSON', help='配合 --call：位置参数数组，如 "[\\"Btn|Button\\",\\"footer\\"]"')
    ap.add_argument('--timeout', type=float, default=6.0, help='投递后等游戏线程执行的秒数')
    ap.add_argument('--no-eternalize', dest='eternalize', action='store_false',
                    help='关掉 eternalize（默认开，和 app 一致）')
    args = ap.parse_args()

    pid = args.pid or _pidof()
    if not pid:
        print('找不到游戏进程（先启动游戏）')
        return 2

    # agent 的 il2cpp 初始化挂在 `setInterval(...,250)` 上（`agent_base.js:2564`），
    # 而 app 是 **base + bridge 一起加载**、bridge 会主动拉起来。PC 侧只发 base 时
    # QJS 下这个定时器不定时 ⇒ `ready()` 永远 false。这里追加一个同步入口，绕开定时器。
    BOOT = '''
;(function () {
  try {
    rpc.exports.bootInit = function () {
      try { return initIl2Cpp() ? "ok" : "not-ready"; }
      catch (e) { return "err:" + e; }
    };
    // 临时诊断：列出**所有**匹配节点上的组件 + 按钮文字（findList 两者都不带）
    rpc.exports.nodeProbe = function (nameRe, rootName) {
      const out = { items: [], err: null };
      try {
        const re = new RegExp(String(nameRe), "i");
        const root = rootName ? _subtreeRoot(rootName) : null;
        const list = _walkForRoot(rootName, root);
        if (!list) { out.err = "root missing"; return out; }
        for (let i = 0; i < list.length && out.items.length < 6; i++) {
          const nd = list[i];
          if (nd.active !== true || !re.test(nd.name || "")) continue;
          const rec = { node: nd.name, comps: _dumpComps(_inv(nd.tr, "get_gameObject")), text: null };
          try {                                   // 按钮上的字（CustomUILabel，可能在子节点）
            const go = _inv(nd.tr, "get_gameObject");
            const uk = findClass("CustomUILabel");
            const lb = (uk && !uk.isNull()) ? _compSelfTry(go, uk) : ptr(0);
            if (ptrOk(lb)) rec.text = _str1(_inv(lb, "get_text"));
          } catch (e) {}
          out.items.push(rec);
        }
      } catch (e) { out.err = String(e); }
      return out;
    };
  } catch (e) {}
})();
'''
    src = Path(BASE).read_text(encoding='utf-8') + BOOT
    dev = frida.get_device_manager().add_remote_device(args.remote)
    sess = dev.attach(pid)
    # ★必须用 QJS，不能用默认的 V8：
    #   app 那边 `tools/frida_bridge/bridge.c:197` 是 `FRIDA_SCRIPT_RUNTIME_QJS`；
    #   V8 的 agent 会做 JIT / 建大量 RWX 段，这游戏的反作弊（进程里的 crivpd_server）
    #   会直接 `SEGV_ACCERR / execute non-executable memory` 自毁 —— 实测 4 次全崩。
    script = sess.create_script(src, name='lmj-agent', runtime='qjs')
    script.on('message', lambda m, d: print('MSG:', str(m)[:400]))
    script.load()
    # ★和 app 完全一致：load 之后 eternalize（`bridge.c:212-218`）。
    #   不做这一步，detach 时脚本被卸载 —— 实测第 2/3 次 attach 就被 crivpd_server 打死。
    if args.eternalize:
        script.eternalize()
        print('eternalize = on')

    try:
        for i in range(60):                      # 等 il2cpp 就绪（最多 30s）
            try:
                if script.exports_sync.ready():
                    break
                r = script.exports_sync.bootInit()      # 主动初始化一次（不等定时器）
                if i % 6 == 0:
                    print('bootInit →', r)
            except Exception as e:
                print('bootInit 调用失败:', e)
                break
            time.sleep(0.5)
        else:
            print('il2cpp 一直没就绪（agent 注入失败？）')
            return 3

        print('build =', script.exports_sync.jsBuild())

        if args.status:
            print('viewId =', script.exports_sync.viewId())
            print('pressState =', _dump(script.exports_sync.pressState()))

        if args.list:
            nodes = script.exports_sync.findList(args.list, args.root)
            print('findList(%r, %r) → %d 个' % (args.list, args.root, len(nodes or [])))
            for nd in (nodes or [])[:40]:
                print('   ', _dump(nd))

        if args.dump and args.node:
            print('nodeProbe →', json.dumps(script.exports_sync.nodeProbe(args.node, args.root),
                                            ensure_ascii=False, indent=2))

        if args.call:
            fn = getattr(script.exports_sync, args.call)
            fargs = json.loads(args.args) if args.args else []
            r = fn(*fargs) if isinstance(fargs, list) else fn(**fargs)
            print('%s →' % args.call, json.dumps(r, ensure_ascii=False, indent=2)[:6000])
            if args.poll:
                if not (r or {}).get('queued'):
                    print('（没有 queued=true，不轮询）')
                    return 0
                pr = _poll(script, args.timeout)
                if pr is None:
                    print('超时：%ss 内游戏线程没执行' % args.timeout)
                    return 6
                print('轮询结果 →', json.dumps(pr, ensure_ascii=False, indent=2)[:6000])
                return 0 if not pr.get('pending', True) else 6
            return 0

        if args.path:
            # ★路径轻读/直调（2026-11-16）：root:索引链 → 稳态 6~12ms；非 probe 走游戏线程投递
            rk, _, pt = args.path.partition(':')
            path = [int(v) for v in pt.split(',') if v.strip() != '']
            name = args.btn or ''
            if args.probe:
                print('labPathPress(probe) →',
                      _dump(script.exports_sync.labPathPress(rk, path, name, True, True)))
                return 0
            st = script.exports_sync.labPathPress(rk, path, name, False)
            print('labPathPress →', _dump(st))
            if not (st or {}).get('queued'):
                return 4
            r = _poll(script, args.timeout)
            if r is None:
                print('超时：%ss 内游戏线程没执行' % args.timeout)
                return 6
            print('结果 →', json.dumps(r, ensure_ascii=False))
            if (r or {}).get('ok'):
                time.sleep(0.8)
                print('viewId =', script.exports_sync.viewId())
            return 0 if (r or {}).get('ok') else 5

        if args.block is not None:
            # ★地图节点按 BlockId 直调（定位走现有 _moveLocate，名字全靠不住）
            print('panelLocate →', _dump(script.exports_sync.panelLocate(args.block)))
            vid0 = script.exports_sync.viewId()
            print('viewId（触发前）=', vid0)
            st = script.exports_sync.pressPanelStart(args.block, {'probe': args.probe})
            print('pressPanelStart →', _dump(st))
            if not (st or {}).get('queued'):
                return 4
            r = _poll(script, args.timeout)
            if r is None:
                print('超时：%ss 内游戏线程没执行（看 pressState.frames）' % args.timeout)
                print('pressState =', _dump(script.exports_sync.pressState()))
                return 6
            print('结果 →', json.dumps(r, ensure_ascii=False, indent=2))
            if not args.probe and r.get('ok'):
                time.sleep(0.8)
                print('viewId（触发后）=', script.exports_sync.viewId())
            return 0 if r.get('ok') else 5

        if args.node and not args.dump:
            # ★一次会话里做完：前 viewId → 触发 → 后 viewId。
            #   这个游戏对"反复 attach"很敏感（实测第 3 次就崩），所以别分多次跑。
            vid0 = script.exports_sync.viewId()
            print('viewId（触发前）=', vid0)
            opt = {'probe': args.probe}
            if args.xy:
                opt['xy'] = [int(v) for v in args.xy.split(',')]
            st = script.exports_sync.pressNodeStart(args.node, args.root, opt)
            print('pressNodeStart →', _dump(st))
            if not (st or {}).get('queued'):
                return 4
            t0 = time.time()
            while time.time() - t0 < args.timeout:
                time.sleep(0.12)
                r = script.exports_sync.pressNodePoll()
                if not (r or {}).get('pending', True):
                    print('结果 →', json.dumps(r, ensure_ascii=False, indent=2))
                    if not args.probe and (r or {}).get('ok'):
                        time.sleep(0.8)                    # 让界面切完
                        vid1 = script.exports_sync.viewId()
                        print('viewId（触发后）=', vid1,
                              '→ 页面已切换 [OK]' if vid1 != vid0 else '→ 页面没变（回调可能没生效）')
                    return 0 if (r or {}).get('ok') else 5
            print('超时：%ss 内游戏线程没执行（钩子没在跑？看 pressState.frames）' % args.timeout)
            print('pressState =', _dump(script.exports_sync.pressState()))
            return 6
        return 0
    finally:
        sess.detach()


def _poll(script, timeout):
    """轮询 pressNodePoll（pressNode / pressPanel 共用同一个结果槽）"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        time.sleep(0.12)
        r = script.exports_sync.pressNodePoll()
        if not (r or {}).get('pending', True):
            return r
    return None


def _dump(v):
    try:
        return json.dumps(v, ensure_ascii=False)
    except Exception:
        return str(v)


def _pidof() -> int:
    """从 adb 取游戏主进程 pid（adb 走 PATH 或 ADB 环境变量）；取不到就让调用方传 --pid"""
    import shutil
    import subprocess
    adb = os.environ.get('ADB') or shutil.which('adb')
    if not adb:
        return 0
    try:
        out = subprocess.run([adb, 'shell', 'pidof', GAME], capture_output=True, text=True, timeout=15)
        return int((out.stdout or '').split()[0])
    except Exception:
        return 0


if __name__ == '__main__':
    sys.exit(main())

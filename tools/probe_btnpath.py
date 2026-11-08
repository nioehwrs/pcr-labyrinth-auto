# -*- coding: utf-8 -*-
"""看某个按钮在 UI 树里**挂在哪一层**（父链）—— 用来决定"抽哪一层当遍历根"。

背景：事件选项卡 `labyrinth_btn_main_square` 用 `top`（最上层对话框子树）只找到 1/2 颗，
用 `dialog`（整个 DialogArea）能找全但慢。需要看清另一颗的父链，才能取中间那一层。

用法（在**事件选择页**上跑）：python tools/probe_btnpath.py <pid> [正则]
"""
import json
import os
import sys
import time

import frida

REMOTE = os.environ.get('LMJ_FIDA_REMOTE', '127.0.0.1:1237')
BASE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    'android', 'app', 'src', 'main', 'assets', 'agent_base.js')

PROBE = r'''
rpc.exports.zz = function (fn, args) {
  try { return JSON.stringify(rpc.exports[fn].apply(null, args || [])); }
  catch (e) { return 'ERR:' + e; }
};
'''


def call(script, fn, args):
    raw = script.exports_sync.zz(fn, args)
    try:
        return json.loads(raw)
    except Exception:
        return raw


def main() -> int:
    pid = int(sys.argv[1])
    re_str = sys.argv[2] if len(sys.argv) > 2 else '^labyrinth_btn_main_square'
    src = open(BASE, encoding='utf-8').read() + '\n' + PROBE
    dev = frida.get_device_manager().add_remote_device(REMOTE)
    sess = dev.attach(pid)
    script = sess.create_script(src)
    script.on('message', lambda m, d: None)
    script.load()
    for _ in range(60):
        try:
            if script.exports_sync.ready():
                break
        except Exception:
            pass
        time.sleep(0.5)

    print('top    =', call(script, 'labTop', []))
    for root in ('top', 'dialog', 'layer'):
        t0 = time.time()
        hits = call(script, 'findListPath', [re_str, root, 30])
        ms = int((time.time() - t0) * 1000)
        if not isinstance(hits, list):
            print('%-7s ERR %s' % (root, hits))
            continue
        print('\n=== root=%s  %d 颗  %dms ===' % (root, len(hits), ms))
        for h in hits:
            if not isinstance(h, dict):
                print('   ', h)
                continue
            print('   (%s,%s) depth=%s' % (h.get('x'), h.get('y'), h.get('depth')))
            print('      %s' % ' > '.join(str(p) for p in (h.get('path') or [])))
    sess.detach()
    return 0


if __name__ == '__main__':
    sys.exit(main())

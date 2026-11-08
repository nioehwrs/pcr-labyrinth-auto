"""开发探针：端到端测 RPC（和 app 实际调用路径一致）。

用法：
    python tools/status_timing2.py <pid> [轮数]
"""
import json
import os
import sys
import time

import frida

REMOTE = os.environ.get('LMJ_FIDA_REMOTE', '127.0.0.1:1237')
BASE = r'd:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android\android\app\src\main\assets\agent_base.js'

PROBE = r'''
rpc.exports.zzrpc = function (fn, args, rounds) {
  var ts = [];
  var last = null;
  for (var i = 0; i < rounds; i++) {
    var t0 = Date.now();
    try { last = rpc.exports[fn].apply(null, args || []); } catch (e) { last = 'ERR:' + e; }
    ts.push(Date.now() - t0);
  }
  return { ms: ts, val: (last === null || last === undefined) ? null : String(last).slice(0, 120) };
};
'''


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    pid = int(sys.argv[1])
    rounds = int(sys.argv[2]) if len(sys.argv) > 2 else 5
    src = open(BASE, encoding='utf-8').read() + '\n' + PROBE

    dev = frida.get_device_manager().add_remote_device(REMOTE)
    sess = dev.attach(pid)
    script = sess.create_script(src)
    script.on('message', lambda m, d: print('MSG:', str(m)[:300]))
    script.load()
    for _ in range(60):
        try:
            if script.exports_sync.ready():
                break
        except Exception:
            pass
        time.sleep(0.5)
    cases = [('labStatus', 'labStatus', []),
             ('labStatusLite', 'labStatusLite', []),
             ('labExtra', 'labExtra', []),
             ('labTop', 'labTop', []),
             ('labView', 'labView', []),
             ('gameTick', 'gameTick', []),
             ('labChoices', 'labChoices', []),
             ('labNodes', 'labNodes', []),
             ('labExEquip', 'labExEquip', [True]),
             ('labExBtn', 'labExBtn', ['ThreeButtonRight']),
             ('findList_top', 'findList', ['^labyrinth_btn_main_square', 'top']),
             ('findList_dlg', 'findList', ['^labyrinth_btn_main_square', 'dialog'])]
    try:
        for name, fn, args in cases:
            r = script.exports_sync.zzrpc(fn, args, rounds)
            print('%-14s %-30s %s' % (name, r['ms'], r['val']))
    except Exception as e:
        print('调用出错:', type(e).__name__, e)
    finally:
        sess.detach()
    return 0


if __name__ == '__main__':
    sys.exit(main())

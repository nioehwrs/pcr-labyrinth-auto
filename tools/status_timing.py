"""开发探针：把 `labStatus` 拆成几块，分别计时（找出"整包读"真正的开销在哪）。

用法：
    python tools/status_timing.py <pid> [轮数]

注意：注入的脚本里能直接访问 agent_base.js 的内部函数（同一脚本作用域）。
"""
import json
import os
import sys
import time

import frida

REMOTE = os.environ.get('LMJ_FIDA_REMOTE', '127.0.0.1:1237')
BASE = r'd:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android\android\app\src\main\assets\agent_base.js'

PROBE = r'''
rpc.exports.zzparts = function (rounds) {
  function t(label, fn) {
    var ts = [];
    var last = null;
    for (var i = 0; i < rounds; i++) {
      var t0 = Date.now();
      try { last = fn(); } catch (e) { last = 'ERR:' + e; }
      ts.push(Date.now() - t0);
    }
    return { label: label, ms: ts, val: (last === null || last === undefined) ? null : String(last).slice(0, 80) };
  }
  var out = [];
  out.push(t('_findLTD', function () { return String(_findLTD()); }));
  out.push(t('getViewId', function () { return String(getViewId()); }));
  out.push(t('_viewManagerInst', function () { return String(_viewManagerInst()); }));
  out.push(t('battleStateSnapshot', function () { var b = battleStateSnapshot(); return JSON.stringify(b); }));
  out.push(t('_topDialogName', function () { return String(_topDialogName()); }));
  out.push(t('labSnapshot', function () { var s = labSnapshot(); return s ? 'ok' : 'null'; }));
  out.push(t('labStatusLite', function () { var s = labStatusLite(); return s ? 'ok' : 'null'; }));
  out.push(t('labStatus', function () { var s = labSnapshot(); return s ? 'ok' : 'null'; }));
  return out;
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
    try:
        rows = script.exports_sync.zzparts(rounds)
        for r in rows:
            print('%-22s %-28s %s' % (r['label'], r['ms'], r['val']))
    except Exception as e:
        print('调用出错:', type(e).__name__, e)
    finally:
        sess.detach()
    return 0


if __name__ == '__main__':
    sys.exit(main())

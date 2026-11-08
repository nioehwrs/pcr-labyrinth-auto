"""EX 三连页切页瞬间的读一致性复现（开发工具）。

用法：python tools/exeq_repro.py <pid> <tap_x> <tap_y> [第几轮之后点]
"""
import json
import os
import subprocess
import sys
import time

import frida

REMOTE = os.environ.get('LMJ_FIDA_REMOTE', '127.0.0.1:1237')
BASE = r'd:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android\android\app\src\main\assets\agent_base.js'


def main():
    pid = int(sys.argv[1])
    tx, ty = int(sys.argv[2]), int(sys.argv[3])
    tap_at = int(sys.argv[4]) if len(sys.argv) > 4 else 4
    src = open(BASE, encoding='utf-8').read()
    src += '\nrpc.exports.zzprobe = function (fn, args) { return rpc.exports[fn].apply(null, args || []); };\n'
    dev = frida.get_device_manager().add_remote_device(REMOTE)
    sess = dev.attach(pid)
    script = sess.create_script(src)
    script.on('message', lambda m, d: print('MSG:', str(m)[:200]))
    script.load()
    for _ in range(60):
        try:
            if script.exports_sync.ready():
                break
        except Exception:
            pass
        time.sleep(0.5)
    rows = []
    try:
        for i in range(14):
            t0 = time.time()
            try:
                sig = script.exports_sync.zzprobe('labExEquipSig', [])
                s_sig = (sig or {}).get('sig')
            except Exception as e:
                s_sig = 'EXC:' + type(e).__name__
            t_sig = int((time.time() - t0) * 1000)
            t0 = time.time()
            try:
                full = script.exports_sync.zzprobe('labExEquip', [True])
                s_full = (full or {}).get('sig')
                page = (full or {}).get('page') or {}
                labels = page.get('labels')
                toggles = page.get('toggles')
            except Exception as e:
                s_full = 'EXC:' + type(e).__name__
                labels = toggles = None
            t_full = int((time.time() - t0) * 1000)
            rows.append({'i': i, 'sig_ms': t_sig, 'full_ms': t_full, 'sig': s_sig,
                         'full_sig': s_full, 'same': s_sig == s_full, 'labels': labels,
                         'toggles': ([t.get('label') for t in toggles] if toggles else None)})
            if i == tap_at:
                subprocess.run(['adb', 'shell', 'input', 'tap', str(tx), str(ty)], check=False)
                print('--- tapped ---')
            time.sleep(0.05)
    finally:
        sess.detach()
    out = r'd:\Users\zhouyi\Documents\LMJ\labyrinth_auto_android\tools\lmj_exeq_repro.json'
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
    print('-> wrote', out, len(rows))
    prev = object()
    for r in rows:
        if r['sig'] != prev:
            print(('  ' if r['same'] else '**'), r['i'], 'sig=%s full=%s same=%s' % (
                r['sig_ms'], r['full_ms'], r['same']), '|', r['sig'], '||', r['full_sig'],
                '| labels=%s' % r['labels'])
            prev = r['sig']
    return 0


if __name__ == '__main__':
    sys.exit(main())

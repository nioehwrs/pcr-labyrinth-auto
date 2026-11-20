"""常驻调试 daemon：**只 attach 一次**，之后所有命令走本地 TCP socket。

为什么要有它（2026-11-18 实测）：
    短探针（attach → 几个 RPC → 退出）会导致游戏进程在 ~1 分钟内死掉，
    日志固定是 `com.android.webview:sandboxed_process0` 先死、主进程跟着死。
    而同一个脚本连续跑 1 分钟（一直保持连接）没问题。
    ⇒ 结论：**别反复 attach/detach**，用一个长连接喂命令。

用法：
    # 起 daemon（会一直挂着，日志写文件）
    python tools/frida_daemon.py --pid <pid> --log daemon.log

    # 另一个终端发命令
    python tools/frida_cli.py labPartySlots
    python tools/frida_cli.py labExEquipSig
    python tools/frida_cli.py labPartyPress '["remove",[102501]]' --poll
    python tools/frida_cli.py --quit          # 客户端正常退出

协议（一行一个 JSON）：
    请求  {"fn": "<rpc名>", "args": [...], "poll": false, "timeout": 30}
    应答  该 RPC 的返回值（原样 JSON），出错时 {"error": "..."}
"""
import argparse
import json
import queue
import socket
import sys
import threading
import time
from pathlib import Path

import frida

BASE = Path(__file__).resolve().parent.parent / 'android/app/src/main/assets/agent_base.js'
REMOTE = '127.0.0.1:1234'
PORT = 12777

BOOT = r'''
;(function () {
  rpc.exports.bootInit = function () {
    try { return initIl2Cpp() ? "ok" : "not-ready"; } catch (e) { return "err:" + e; }
  };
})();
'''


def log(msg):
    print(time.strftime('[%H:%M:%S] ') + msg, flush=True)


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument('--pid', type=int, required=True)
    ap.add_argument('--remote', default=REMOTE)
    ap.add_argument('--port', type=int, default=PORT)
    ap.add_argument('--log', default='')
    a = ap.parse_args()

    if a.log:
        try:
            fh = open(a.log, 'a', encoding='utf-8')
            sys.stdout = fh
            sys.stderr = fh
        except Exception:
            pass

    def load_script(sess):
        """把 agent_base.js 装进已 attach 的 session，并等到 il2cpp 就绪。

        ★2026-11-18：抽成函数是为了**同 session 内热重载**（见主循环的 `__reload`）——
          实测这个游戏 detach/attach 一轮往往就把进程送走了（webview 沙箱先死），
          改完 agent 想生效只能靠 unload + 重新 create_script，不能重连。
        """
        src = Path(BASE).read_text(encoding='utf-8') + BOOT
        sc = sess.create_script(src, name='lmj-daemon', runtime='qjs')
        sc.on('message', lambda m, d: None)      # 别刷屏；要诊断就临时打开
        sc.load()
        ok = False
        for _ in range(120):
            try:
                if sc.exports_sync.ready():
                    ok = True
                    break
            except Exception:
                pass
            try:
                sc.exports_sync.bootInit()
            except Exception:
                pass
            time.sleep(0.5)
        return sc, ok

    dev = frida.get_device_manager().add_remote_device(a.remote)
    sess = dev.attach(a.pid)
    script, ready = load_script(sess)
    log('pid=%d ready=%s build=%s' % (a.pid, ready, script.exports_sync.jsBuild()))

    q: "queue.Queue" = queue.Queue()

    def serve():
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(('127.0.0.1', a.port))
        srv.listen(16)
        log('LISTEN 127.0.0.1:%d' % a.port)
        while True:
            try:
                c, _ = srv.accept()
            except Exception as e:
                log('accept 出错: %s' % e)
                continue
            try:
                buf = b''
                while b'\n' not in buf:
                    chunk = c.recv(65536)
                    if not chunk:
                        break
                    buf += chunk
                line = buf.split(b'\n')[0].decode('utf-8', 'replace').strip()
                if not line:
                    c.close()
                    continue
                req = json.loads(line)
                box, ev = {}, threading.Event()
                q.put((req, box, ev))
                if not ev.wait(timeout=float(req.get('timeout', 30))):
                    box['r'] = {'error': '超时（命令没在 %.0fs 内跑完）' % float(req.get('timeout', 30))}
                c.sendall((json.dumps(box.get('r'), ensure_ascii=False) + '\n').encode('utf-8'))
            except Exception as e:
                try:
                    c.sendall((json.dumps({'error': str(e)}, ensure_ascii=False) + '\n').encode('utf-8'))
                except Exception:
                    pass
            finally:
                try:
                    c.close()
                except Exception:
                    pass

    threading.Thread(target=serve, daemon=True).start()

    # ★RPC 只在这一个线程里调（frida 的 exports_sync 不适合多线程并发）
    while True:
        req, box, ev = q.get()
        fn = req.get('fn') or ''
        args = req.get('args') or []
        if fn == '__reload':
            # 同 session 内热重载：不 detach，进程就不会被这一轮"送走"
            try:
                try:
                    script.unload()
                except Exception:
                    pass
                script, ok = load_script(sess)
                box['r'] = {'reloaded': True, 'ready': ok, 'build': script.exports_sync.jsBuild()}
                log('reload ready=%s' % ok)
            except Exception as e:
                box['r'] = {'error': 'reload 失败: %s' % e}
            ev.set()
            continue
        try:
            f = getattr(script.exports_sync, fn)
            r = f(*args) if isinstance(args, list) else f(**args)
            if req.get('poll') and isinstance(r, dict) and r.get('queued'):
                t0 = time.time()
                limit = float(req.get('timeout', 30))
                while time.time() - t0 < limit:
                    time.sleep(0.1)
                    pr = script.exports_sync.pressNodePoll()
                    if not (pr or {}).get('pending', True):
                        r = pr
                        break
                else:
                    r = {'error': 'poll 超时', 'last': r}
            box['r'] = r
        except Exception as e:
            box['r'] = {'error': str(e)}
        ev.set()


if __name__ == '__main__':
    sys.exit(main())

/* ============================================================================
 * P1：agent 侧的 socket 回连转发层
 *   —— app 在运行时把这段**接在 agent_base.js 后面**再交给外部注入器，
 *      所以 agent_base.js（PC 版原样）一行都不用改，两边能保持逐字一致。
 *
 * 协议：一行一条 JSON，UTF-8，\n 结尾
 *   收到: {"id":7,"fn":"labStatus","args":[]}
 *   回发: {"id":7,"ok":true,"data":{...}}
 *         {"id":7,"ok":false,"err":"..."}
 *   连上后先发一条: {"event":"hello","pid":123,"frida":"17.15.3"}
 *
 * ★实测（frida 17.15.3 / android-x86_64，2026-10-14）三个坑，全踩过：
 *   1. Socket.connect **只接受一个对象参数**：Socket.connect({host, port})。
 *      写成 Socket.connect(host, port) 永远不会连你给的地址，报的是
 *      "could not connect to localhost: Connection refused"（端口/主机都被忽略）。
 *   2. 它返回 **Promise**：要 await / return await。
 *   3. output.write **只吃 buffer-like**（Uint8Array），给字符串会报
 *      "expected a buffer-like object"。
 *   另外：base 脚本里有一句 `console.log = function () {...}`（第 4224 行）会把
 *   console.log 整个改写掉，所以本文件所有日志都走 send()。
 * ========================================================================== */
(function () {
  var HOST = '127.0.0.1';
  var PORT = 27183;
  var RECONNECT_MS = 1000;
  var POLL_MS = 20;
  var READ_SIZE = 65536;
  // ★静默看门狗：app 侧每 ~1.5s 会发一条命令（viewId），所以"超过这么久一个字节都没收到"
  //   就说明这条连接已经死了。对端进程被重启/杀死时，read() 只会一直返回"没数据"（不报错），
  //   不靠这个判断就会永远挂在僵尸连接上（实测踩过：app 重装后 agent 再也不回连）。
  var IDLE_RECONNECT_MS = 6000;

  // 日志走 send()：客户端（PC 注入器）能看到；eternalize 之后没人听就自然丢弃
  function log(s) {
    try { send({ bridge: String(s) }); } catch (e) { }
    try { console.log('[bridge] ' + s); } catch (e) { }
  }

  // ── console 转发：把 agent 的日志经 socket 发回 app ───────────────────────────
  //   为什么要有：base 脚本第 4223-4231 行把 console.log 换成了**带过滤的版本**
  //   （默认只放行含"错误/失败/异常/[!]"的行），而这些日志原本只发给 frida 客户端
  //   （PC 版直接打在终端上）。注入到手机里之后没人听 → 全丢，app 侧就成了瞎子
  //   （2026-10-14 排查注入卡死时就吃了这个亏）。
  //   这里**再包一层**，攒起来走同一个 socket 发回 app。
  //   ★注意：我们接的是 `console.log` 本身（也就是在 base 那个过滤闸**之前**），所以**过程日志
  //     也会一起回来**（PC 版终端默认看不到的那些）—— 好处是排查时不用改 JS_VERBOSE；
  //     嫌吵就把下面的限流门槛（50ms）调大。
  var outQueue = [];
  var lastLogAt = 0;
  function pushLog(text) {
    var now = Date.now();
    if (now - lastLogAt < 50) return;              // 限流 ~20 行/秒，别把 app 日志刷爆
    lastLogAt = now;
    var s = String(text);
    if (s.length > 300) s = s.slice(0, 300) + '…';
    if (outQueue.length < 200) outQueue.push(s);   // 连不上时也别无限积压
  }
  var _outerConsoleLog = (typeof console !== 'undefined' && console.log) ? console.log : null;
  if (_outerConsoleLog) {
    console.log = function () {
      try { pushLog(Array.prototype.join.call(arguments, ' ')); } catch (e) { }
      try { return _outerConsoleLog.apply(null, arguments); } catch (e) { }
    };
  }

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  // 字符串 → Uint8Array（output.write 只吃 buffer-like）
  // ★必须是**真 UTF-8**：原来写成 `charCodeAt(i) & 0xff`（等于 latin1），中文会被截成乱码
  //   （2026-10-14 实测：`[bridge] 已连上 app` 变成 `bridge ?}???`）。socket 上不只有日志 ——
  //   labStatus 的中文串、`find("确认")` 这类参数都要靠它，所以这里必须写对。
  function enc(s) {
    var out = [];
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c < 0x80) {
        out.push(c);
      } else if (c < 0x800) {
        out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
        var c2 = s.charCodeAt(++i);                       // 代理对（emoji 等 4 字节字符）
        var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
        out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f),
                 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
      } else {
        out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
      }
    }
    return new Uint8Array(out);
  }

  // Uint8Array → 字符串（按 UTF-8 解；和 enc 成对，别用 fromCharCode 逐字节糊 —— 那是 latin1）
  function toText(chunk) {
    if (chunk === null || chunk === undefined) return '';
    if (typeof chunk === 'string') return chunk;
    try {
      if (chunk.byteLength !== undefined) {
        var u8 = new Uint8Array(chunk);
        var s = '';
        var i = 0;
        while (i < u8.length) {
          var b = u8[i++];
          if (b < 0x80) {
            s += String.fromCharCode(b);
          } else if (b >= 0xc0 && b < 0xe0 && i < u8.length) {
            s += String.fromCharCode(((b & 0x1f) << 6) | (u8[i++] & 0x3f));
          } else if (b >= 0xe0 && b < 0xf0 && i + 1 < u8.length) {
            s += String.fromCharCode(((b & 0x0f) << 12) | ((u8[i++] & 0x3f) << 6) | (u8[i++] & 0x3f));
          } else if (b >= 0xf0 && i + 2 < u8.length) {
            var cp = ((b & 0x07) << 18) | ((u8[i++] & 0x3f) << 12)
                   | ((u8[i++] & 0x3f) << 6) | (u8[i++] & 0x3f);
            cp -= 0x10000;
            s += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
          }
        }
        return s;
      }
    } catch (e) { }
    return String(chunk);
  }

  async function writeText(conn, text) {
    await conn.output.write(enc(text));
  }

  async function writeJson(conn, obj) {
    await writeText(conn, JSON.stringify(obj) + '\n');
  }

  // 一行命令 → 一行回包；异常也要回包（不然 app 侧会一直等）
  function handle(line) {
    var msg;
    try { msg = JSON.parse(line); } catch (e) { return null; }
    var out = { id: msg.id, ok: true };
    try {
      var ex = (typeof rpc !== 'undefined') ? rpc.exports : null;
      if (!ex) throw new Error('rpc.exports 不存在');
      var fn = ex[msg.fn];
      if (typeof fn !== 'function') throw new Error('没有这个导出函数: ' + msg.fn);
      out.data = fn.apply(null, msg.args || []);
    } catch (e) {
      out.ok = false;
      out.err = String(e);
    }
    return out;
  }

  async function connect() {
    var n = 0;
    for (;;) {
      try {
        // ★必须是对象参数形态，见文件头注释坑 1
        return await Socket.connect({ host: HOST, port: PORT });
      } catch (e) {
        n++;
        if (n === 1 || n % 30 === 0) log('连接失败(' + n + ' 次): ' + e);
        await sleep(RECONNECT_MS);
      }
    }
  }

  async function main() {
    var conn = await connect();
    log('已连上 app ' + HOST + ':' + PORT + '（pid=' + Process.id + '）');
    try {
      await writeJson(conn, { event: 'hello', pid: Process.id, frida: Frida.version });
    } catch (e) { log('hello 发送失败: ' + e); }

    var buf = '';
    var lastRxAt = Date.now();
    for (;;) {
      var chunk = null;
      try {
        chunk = await conn.input.read(READ_SIZE);
      } catch (e) {
        log('连接断了，重连：' + e);
        conn = await connect();
        buf = '';
        lastRxAt = Date.now();
        continue;
      }
      if (!chunk && Date.now() - lastRxAt > IDLE_RECONNECT_MS) {
        log('静默 ' + IDLE_RECONNECT_MS + 'ms，判定对端没了 → 重连');
        try { conn.close(); } catch (e) { }
        conn = await connect();
        buf = '';
        lastRxAt = Date.now();
        continue;
      }
      if (chunk) {
        lastRxAt = Date.now();
        buf += toText(chunk);
        var idx;
        while ((idx = buf.indexOf('\n')) >= 0) {
          var line = buf.slice(0, idx);
          buf = buf.slice(idx + 1);
          if (!line.trim()) continue;
          var res = handle(line);
          if (res) {
            try { await writeJson(conn, res); }
            catch (e) { log('回包失败：' + e); }
          }
        }
      }
      // 把攒下的 agent 日志发回 app（同一个 socket，一行一条 event）
      if (outQueue.length) {
        var batch = outQueue.splice(0, outQueue.length);
        for (var bi = 0; bi < batch.length; bi++) {
          try { await writeJson(conn, { event: 'log', line: batch[bi] }); } catch (e) { }
        }
      }
      await sleep(POLL_MS);
    }
  }

  log('bridge 已加载，准备回连 ' + HOST + ':' + PORT);
  main();
})();

// perception_probe.js v7 —— 黎明界「只读」探针（内存读取，替代视觉）
//
// 设计目标（读层）：
//   1) 拿到 DungeonTempData 实例（单例，泛型 Singleton<DungeonTempData>）。
//   2) 按 dump.cs 偏移稳定 dump 全部字段（含字典/列表计数，IDs 列表）。
//   3) （黎明界语境下）"已有角色" = DungeonTempData.UnitsDic / DispatchUnitList /
//      UsedUnitList / DungeonClearedAreaIdList，全部在本实例内，无需另找 Manager。
//
// 实例获取（按优先级，全部安全、不主动改内存）：
//   A) 静态字段 API：il2cpp_class_get_static_field_data(parent Singleton<>) + Instance 字段偏移
//   B) 运行调用：il2cpp_runtime_invoke(get_Instance)
//   C) 钩 Init()/.ctor（按 dump RVA 直接挂），游戏(重)进黎明界时抓 this —— 最稳，零侵入
//   D) 手动：frida REPL 里输入 callInit()（会触发游戏 Init，可能重置本局，谨慎用）
//
// 运行（用改名 server + TCP，避免游戏反 frida；详看对话）：
//   adb shell pidof com.bilibili.priconne
//   frida -H 127.0.0.1:1234 -p <PID> -l d:\Users\zhouyi\Documents\LMJ\perception_probe.js
// 进入黎明界后字段会变化并自动打印；也可 REPL 输入 dump() / callInit()。
//
// ===== 黎明界「已有角色 / 本节点获取」数据模型（本次调试结论，2026-10-01）=====
// 实例载体：LabyrinthTempData（单例；本局实时状态；REPL 用 perceive(addr) 一键快照）
//   +0x28  OwnUnitInfoSet           : HashSet<LabyrinthUnitType>  —— 本局已持有角色
//          元素 LabyrinthUnitType.UnitId @+0x10 为 int【明文，即模板id，如 110301】
//          → unitIds() 读出全部（当前 11 个），是「已有角色」的可靠来源
//   +0x180 UnitAcquisitionDataList  : List<LabyrinthUnitAcquisitionData> —— 本节点三选一获取
//          元素 → UnitParam @+0x10 → UniqueData @+0x10 / MasterData @+0x30
//          · 名字 = MasterData._UnitName @+0x28，类型 ObscuredString，须调 ToString() 解密
//          · id   = UniqueData.<Id> @+0x10 或 MasterData._UnitId，类型 ObscuredInt：
//                  调 get_Id()/get_UnitId() 返回【装箱 ObscuredInt】，对其 +0x10/+0x14
//                  两个 int 异或得明文（127101 等，落进 unitIds() 的 11 个之中）
//          · 坑：裸读字段会得到加密低 32 位（如 444444），那是密文不是 id，切勿直接 readS32
//   +0xd0  CurrentPanel             : LabyrinthPanelData —— 当前所在节点
//          +0x10 mapInfo        : LabyrinthMapInfo
//          +0x18 NextBlockIdList : List<?> —— 可前往的下一节点（元素类型待探明）
//
// 反作弊坑：本游戏大量字段用 CodeStage Obscured* 加密（ObscuredString / ObscuredInt），
//   不能按「裸指针+偏移」直接读，必须走 getter（ToString / get_X）或对其内联两 int 异或解密。

const TARGET_CLASS = "DungeonTempData";

// 字段名 + 偏移（来自 Il2CppDumper dump.cs，x86_64 / ARM64 统一对象内偏移）
// t: i=int32, l=int64, b=bool(u8), p=pointer（sub: list / dict / obj）
const FIELDS = [
  { k: "CurrentQuestId",  off: 0x10,  t: "i" },
  { k: "CurrentMode",     off: 0x14,  t: "i" },
  { k: "BattleStartMode", off: 0x18,  t: "i" },
  { k: "IsPlayNextMode",  off: 0x1C,  t: "b" },
  { k: "CurrentCourseId", off: 0x20,  t: "i" },
  { k: "IsCompleted",     off: 0x24,  t: "b" },
  { k: "EnterAreaId",     off: 0x28,  t: "i" },
  { k: "DispatchUnitId",  off: 0x2C,  t: "i" },
  { k: "DispatchBattleRarity", off: 0x30, t: "i" },
  { k: "DispatchOwnerId", off: 0x38,  t: "l" },
  { k: "VersusViewerId",  off: 0x40,  t: "l" },
  { k: "ChallengeCountInfoDic", off: 0x48, t: "p", sub: "dict" },
  { k: "UnitsDic",        off: 0x50,  t: "p", sub: "dict" },   // 可派遣单位
  { k: "UsedUnitList",    off: 0x58,  t: "p", sub: "list" },   // List<int>
  { k: "UnitHpPreviousBattle", off: 0x60, t: "p", sub: "dict" },
  { k: "QuestDic",        off: 0x68,  t: "p", sub: "dict" },   // 地图节点
  { k: "DungeonBattle",   off: 0x70,  t: "p", sub: "obj" },
  { k: "RewardInfoList",  off: 0x78,  t: "p", sub: "list" },
  { k: "EnterSecretDungeonSkipResultList", off: 0x80, t: "p", sub: "list" },
  { k: "DispatchUnitList", off: 0x88, t: "p", sub: "list" },   // 已派遣单位
  { k: "DungeonEnemyUnitList", off: 0x90, t: "p", sub: "list" },
  { k: "DungeonClearedAreaIdList", off: 0x98, t: "p", sub: "list" }, // List<int>
  { k: "UserDungeonBattleMissionData", off: 0xA0, t: "p", sub: "dict" },
  { k: "SpecialBattleStartParamData", off: 0xA8, t: "p", sub: "obj" },
  { k: "DungeonEnterResetTime", off: 0xB0, t: "l" },
  { k: "FinishQuestId",   off: 0xB8,  t: "i" },
  { k: "FirstAreaClearFlag", off: 0xBC, t: "i" },
  { k: "SeasonPackRate",  off: 0xC0,  t: "i" },
  { k: "BossStartHp",     off: 0xC8,  t: "p", sub: "dict" },
  { k: "SpecialBattleHpInfo", off: 0xD0, t: "p", sub: "list" },
  { k: "SpecialBattleGaugeDic", off: 0xD8, t: "p", sub: "dict" },
  { k: "SpecialBattleDamageInfo", off: 0xE0, t: "p", sub: "list" },
  { k: "IsSkipGoing",     off: 0xE8,  t: "b" },
  { k: "UserMissionInfoList", off: 0xF0, t: "p", sub: "list" },
  { k: "BossHpInfos",     off: 0xF8,  t: "p", sub: "list" },
  { k: "Difficulty",     off: 0x100, t: "i" },
  { k: "TotalFloor",     off: 0x104, t: "i" },
  { k: "TotalFloorBeforeSkip", off: 0x108, t: "i" },
  { k: "ClearNum",       off: 0x10C, t: "i" },
  { k: "ShouldPlaySecretDungeonFirstStory", off: 0x110, t: "b" },
  { k: "SortCacheParam", off: 0x118, t: "p", sub: "obj" },
  { k: "CurrentPattern", off: 0x120, t: "i" },
];

// ★禁止在这里写死 dump RVA（2026-10-30）：RVA 只对"某一次 dump 出来的某个 so"成立 ——
//   换游戏版本、换架构（x86_64 ↔ arm64）立刻失效，而且失效时是**静默挂空**，极难查。
//   取方法地址一律用 luMethodPtrByName(klass, 名字)：它从运行时 MethodInfo 拿 methodPointer，
//   与架构和游戏版本都无关（相对偏移 / jmp 跳板两种坑见下文 848、2835 处的注释）。
//   （原来这里有两行 RVA_INIT / RVA_CTOR 常量，全仓已无引用，属于历史残留，已删除。）

let g_inst = null;
let g_il2cpp = null;
let g_klass = null;
let g_logged = 0;
const g_new = {};   // 钩 .ctor 抓到的活实例: g_new[类名]
const g_args = {};  // 钩方法时最近一次参数: g_args["类.方法"] = args
let g_lab = null;   // 最近一次见到的 LabyrinthTempData 实例

function initIl2Cpp() {
  const m = Process.findModuleByName("libil2cpp.so");
  if (!m) return false;
  const base = m.base;
  const e = (n) => m.findExportByName(n);
  const has = (n) => !!(e(n));
  const need = [
    "il2cpp_domain_get", "il2cpp_domain_get_assemblies", "il2cpp_assembly_get_image",
    "il2cpp_image_get_name", "il2cpp_class_from_name",
    "il2cpp_image_get_class_count", "il2cpp_image_get_class",
    "il2cpp_class_get_name", "il2cpp_class_get_parent",
    "il2cpp_class_get_fields", "il2cpp_class_get_methods",
    "il2cpp_field_get_name", "il2cpp_field_get_offset", "il2cpp_method_get_name",
    "il2cpp_object_get_class", "il2cpp_method_is_generic",
    "il2cpp_thread_attach",
  ];
  const miss = need.filter((n) => !has(n));
  if (miss.length) {
    if (!initIl2Cpp._w) { console.log("[probe] 缺少必要 il2cpp 导出: " + miss.join(", ")); initIl2Cpp._w = true; }
    return false;
  }
  g_il2cpp = {
    base,
    domain_get:          new NativeFunction(e("il2cpp_domain_get"), "pointer", []),
    get_assemblies:      new NativeFunction(e("il2cpp_domain_get_assemblies"), "pointer", ["pointer", "pointer"]),
    assembly_get_image:  new NativeFunction(e("il2cpp_assembly_get_image"), "pointer", ["pointer"]),
    image_get_name:      new NativeFunction(e("il2cpp_image_get_name"), "pointer", ["pointer"]),
    class_from_name:     new NativeFunction(e("il2cpp_class_from_name"), "pointer", ["pointer", "pointer", "pointer"]),
    image_class_count:   new NativeFunction(e("il2cpp_image_get_class_count"), "int", ["pointer"]),
    image_get_class:     new NativeFunction(e("il2cpp_image_get_class"), "pointer", ["pointer", "int"]),
    class_get_name:      new NativeFunction(e("il2cpp_class_get_name"), "pointer", ["pointer"]),
    class_get_parent:    new NativeFunction(e("il2cpp_class_get_parent"), "pointer", ["pointer"]),
    class_get_fields:    new NativeFunction(e("il2cpp_class_get_fields"), "pointer", ["pointer", "pointer"]),
    class_get_methods:   new NativeFunction(e("il2cpp_class_get_methods"), "pointer", ["pointer", "pointer"]),
    field_get_name:      new NativeFunction(e("il2cpp_field_get_name"), "pointer", ["pointer"]),
    field_get_offset:    new NativeFunction(e("il2cpp_field_get_offset"), "ulong", ["pointer"]),
    method_get_name:     new NativeFunction(e("il2cpp_method_get_name"), "pointer", ["pointer"]),
    object_get_class:    new NativeFunction(e("il2cpp_object_get_class"), "pointer", ["pointer"]),
    method_is_generic:   new NativeFunction(e("il2cpp_method_is_generic"), "bool", ["pointer"]),
    thread_attach:       new NativeFunction(e("il2cpp_thread_attach"), "pointer", ["pointer"]),
    // 可选导出（存在才能走 A / B 路径）
    get_static_field_data: has("il2cpp_class_get_static_field_data")
      ? new NativeFunction(e("il2cpp_class_get_static_field_data"), "pointer", ["pointer"]) : null,
    runtime_invoke: has("il2cpp_runtime_invoke")
      ? new NativeFunction(e("il2cpp_runtime_invoke"), "pointer", ["pointer", "pointer", "pointer", "pointer"]) : null,
    method_get_function_pointer: has("il2cpp_method_get_function_pointer")
      ? new NativeFunction(e("il2cpp_method_get_function_pointer"), "pointer", ["pointer"]) : null,
    method_get_class:   has("il2cpp_method_get_class")
      ? new NativeFunction(e("il2cpp_method_get_class"), "pointer", ["pointer"]) : null,
    // 以下为可选导出（用于构造合法回调委托，缺失则退回 null 回调提交）
    object_new:         has("il2cpp_object_new")
      ? new NativeFunction(e("il2cpp_object_new"), "pointer", ["pointer"]) : null,
    method_get_parameter_type: has("il2cpp_method_get_parameter_type")
      ? new NativeFunction(e("il2cpp_method_get_parameter_type"), "pointer", ["pointer", "uint32"]) : null,
    class_from_type:    has("il2cpp_class_from_type")
      ? new NativeFunction(e("il2cpp_class_from_type"), "pointer", ["pointer"]) : null,
    // 以下用于从 <>9__1 字段类型取 Action<...> 委托类（优先于反射/按名查找）
    field_get_type:     has("il2cpp_field_get_type")
      ? new NativeFunction(e("il2cpp_field_get_type"), "pointer", ["pointer"]) : null,
    type_get_class:     has("il2cpp_type_get_class")
      ? new NativeFunction(e("il2cpp_type_get_class"), "pointer", ["pointer"]) : null,
  };
  globalThis.g_il2cpp = g_il2cpp;   // 暴露给 REPL，方便粘贴代码里直接引用
  const avail = [];
  if (g_il2cpp.get_static_field_data) avail.push("static_field_data");
  if (g_il2cpp.runtime_invoke) avail.push("runtime_invoke");
  console.log("[probe] il2cpp 就绪；可选路径: " + (avail.length ? avail.join(", ") : "无（仅用钩子/手动）"));
  return true;
}

function getImageByName(target) {
  const domain = g_il2cpp.domain_get();
  const cnt = Memory.alloc(8);
  const asms = g_il2cpp.get_assemblies(domain, cnt);
  const nAsm = cnt.readU64().toNumber();
  for (let i = 0; i < nAsm; i++) {
    const asm = asms.add(i * Process.pointerSize).readPointer();
    const image = g_il2cpp.assembly_get_image(asm);
    if (image.isNull()) continue;
    const iname = g_il2cpp.image_get_name(image);
    if (!iname.isNull() && iname.readUtf8String() === target) return image;
  }
  return null;
}

// ★类指针在进程内是稳定的 → findClass 做**记忆化**（2026-10-12 性能修复）：
//   快路只查 Assembly-CSharp.dll，像 RectTransformUtility / UnityEngine.Time 这类每次都会掉到
//   "全程序集 × 逐类 class_get_name"的慢路（几万次调用），而它们是**每轮**都在查的
//   （Time 之前被单独特判缓存，见 g_timeKlass）→ 统一在这里缓存，所有 RPC 都受益。
var g_classCache = null;
function findClass(name) {
  if (!g_classCache) g_classCache = Object.create(null);
  const _c = g_classCache[name];
  if (_c && !_c.isNull()) return _c;
  const _k = _findClassRaw(name);
  if (_k && !_k.isNull()) g_classCache[name] = _k;
  return _k;
}
function _findClassRaw(name) {
  const img = getImageByName("Assembly-CSharp.dll");
  if (!img.isNull()) {
    for (const ns of ["", "Elements"]) {
      const klass = g_il2cpp.class_from_name(img, Memory.allocUtf8String(ns), Memory.allocUtf8String(name));
      if (!klass.isNull()) return klass;
    }
  }
  const domain = g_il2cpp.domain_get();
  const cnt = Memory.alloc(8);
  const asms = g_il2cpp.get_assemblies(domain, cnt);
  const nAsm = cnt.readU64().toNumber();
  for (let i = 0; i < nAsm; i++) {
    const asm = asms.add(i * Process.pointerSize).readPointer();
    const image = g_il2cpp.assembly_get_image(asm);
    if (image.isNull()) continue;
    const nCls = g_il2cpp.image_class_count(image);
    for (let j = 0; j < nCls; j++) {
      const klass = g_il2cpp.image_get_class(image, j);
      if (klass.isNull()) continue;
      const nm = g_il2cpp.class_get_name(klass);
      if (!nm.isNull() && nm.readUtf8String() === name) return klass;
    }
  }
  return null;
}

// 按 命名空间+类名 精确查找（findClass 只比简单名，会误命中别的程序集同名类）
function findClassNS(name, ns) {
  const domain = g_il2cpp.domain_get();
  const cnt = Memory.alloc(8);
  const asms = g_il2cpp.get_assemblies(domain, cnt);
  const nAsm = cnt.readU64().toNumber();
  for (let i = 0; i < nAsm; i++) {
    const asm = asms.add(i * Process.pointerSize).readPointer();
    const image = g_il2cpp.assembly_get_image(asm);
    if (image.isNull()) continue;
    const nCls = g_il2cpp.image_class_count(image);
    for (let j = 0; j < nCls; j++) {
      const klass = g_il2cpp.image_get_class(image, j);
      if (klass.isNull()) continue;
      const nm = g_il2cpp.class_get_name(klass);
      if (nm.isNull()) continue;
      let nsn = "";
      try { const np = g_il2cpp.class_get_namespace(klass); if (!np.isNull()) nsn = np.readUtf8String(); } catch (e) {}
      if (nm.readUtf8String() === name && nsn === ns) return klass;
    }
  }
  return null;
}

function readName(k) {  try { const p = g_il2cpp.class_get_name(k); return p.isNull() ? "" : p.readUtf8String(); } catch (e) { return ""; }
}

function objIsClass(obj, klass) {
  try {
    const k = g_il2cpp.object_get_class(obj);
    return !k.isNull() && (k.equals(klass) || isSubclassOf(k, klass));
  } catch (e) { return false; }
}

function isSubclassOf(k, base) {
  let cur = k;
  for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
    if (cur.equals(base)) return true;
    cur = g_il2cpp.class_get_parent(cur);
  }
  return false;
}

function klassNameOf(obj) {
  try {
    if (!obj || obj.isNull()) return "null";
    const k = g_il2cpp.object_get_class(obj);
    if (k.isNull()) return "no-class";
    const p = g_il2cpp.class_get_name(k);
    return p.isNull() ? "?" : p.readUtf8String();
  } catch (e) { return "err"; }
}

// ---- 读取辅助 ----
function rdScalar(off, t) {
  try {
    if (t === "i") return g_inst.add(off).readS32();
    if (t === "l") return g_inst.add(off).readS64();
    if (t === "b") return g_inst.add(off).readU8() !== 0;
  } catch (e) { return "?"; }
  return "?";
}

// 调游戏自带的 get_Count() 取 List / Dictionary 真实计数（比硬读偏移稳）
function invokeGetCount(obj) {
  try {
    const k = g_il2cpp.object_get_class(obj);
    let kk = k, mi = null;
    for (let d = 0; d < 8 && kk && !kk.isNull() && !mi; d++) {
      const iter = Memory.alloc(Process.pointerSize);
      iter.writePointer(ptr(0));
      while (true) {
        const m = g_il2cpp.class_get_methods(kk, iter);
        if (m.isNull()) break;
        const mn = g_il2cpp.method_get_name(m);
        if (!mn.isNull() && mn.readUtf8String() === "get_Count") { mi = m; break; }
      }
      if (!mi) kk = g_il2cpp.class_get_parent(kk);
    }
    if (!mi) return -1;
    if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
    const exc = Memory.alloc(8); exc.writePointer(ptr(0));
    // runtime_invoke 接收的是 MethodInfo* 本身（不是首字段 methodPointer，虚拟/泛型方法该字段为 0）；
    // 值类型返回值会被装箱，真实数值在对象头(16B)之后。
    const ret = g_il2cpp.runtime_invoke(mi, obj, ptr(0), exc);
    if (ret.isNull()) return -1;
    return ret.add(0x10).readS32();
  } catch (e) { return -1; }
}

function listCount(p) {
  const c = invokeGetCount(p);
  if (c >= 0) return c;
  try { return p.add(0x18).readS32(); } catch (e) { return -1; } // List._size 兜底
}

// List<int> 读出前 maxItems 个整数（用于 UsedUnitList / DungeonClearedAreaIdList）
// ★注意下面 for 里的 +0x20：托管数组 = +0x10 对象头 / +0x18 length / **+0x20 数据**
//   （2026-10-11 实测踩过：写成 +0x18 会整体错位一格 —— acquireIdList 读成 [4,0,110301]，4 其实是容量）
function readIntList(p, maxItems) {
  try {
    const size = listCount(p);
    if (size <= 0) return "[]";
    const items = p.add(0x10).readPointer();
    if (items.isNull()) return "[]";
    const n = Math.min(size, maxItems);
    const arr = [];
    for (let i = 0; i < n; i++) arr.push(items.add(0x20 + i * 4).readS32());   // ★+0x20：数组数据起点
    return "[" + arr.join(",") + (size > maxItems ? "…(" + size + ")" : "") + "]";
  } catch (e) { return "?"; }
}

function rdPointer(off, sub) {
  try {
    const p = g_inst.add(off).readPointer();
    if (p.isNull()) return "null";
    if (sub === "list") {
      const n = listCount(p);
      // 若是 List<int> 顺手展开（UsedUnitList / DungeonClearedAreaIdList）
      if (off === 0x58 || off === 0x98) return "List n=" + n + " " + readIntList(p, 12);
      return "List n=" + n;
    }
    if (sub === "dict") {
      let n = invokeGetCount(p);
      if (n < 0) { try { n = p.add(0x20).readS32(); } catch (e) { n = -1; } } // Dictionary.count 兜底
      return "Dict n=" + (n >= 0 ? n : "?");
    }
    return "obj@" + p;
  } catch (e) { return "?"; }
}

function buildDumpString() {
  if (!g_inst) return "";
  return FIELDS.map((f) =>
    f.t === "p" ? `${f.k}=${rdPointer(f.off, f.sub)}` : `${f.k}=${rdScalar(f.off, f.t)}`
  ).join("  ");
}
function dumpState() {
  if (!g_inst) { console.log("[probe] 等待 DungeonTempData 实例…（实例尚未捕获）"); return; }
  console.log("[probe] " + buildDumpString());
}

// ---- 实例获取 ----
// A) 静态字段路径
function tryStaticInstance(klass) {
  if (!g_il2cpp.get_static_field_data) return null;
  let k = klass;
  for (let d = 0; d < 8 && k && !k.isNull(); d++) {
    const nm = readName(k);
    if (nm && nm.indexOf("Singleton") >= 0) {
      let data;
      try { data = g_il2cpp.get_static_field_data(k); } catch (e) { data = null; }
      if (data && !data.isNull()) {
        const iter = Memory.alloc(Process.pointerSize);
        iter.writePointer(ptr(0));
        while (true) {
          const f = g_il2cpp.class_get_fields(k, iter);
          if (f.isNull()) break;
          const fn = g_il2cpp.field_get_name(f);
          if (!fn.isNull() && /instance/i.test(fn.readUtf8String())) {
            const off = g_il2cpp.field_get_offset(f).toNumber();
            try {
              const inst = data.add(off).readPointer();
              if (!inst.isNull() && objIsClass(inst, klass)) {
                return inst;
              }
            } catch (e) {}
          }
        }
      }
    }
    k = g_il2cpp.class_get_parent(k);
  }
  return null;
}

// B) runtime_invoke(get_Instance)
function tryInvokeInstance(klass) {
  if (!g_il2cpp.runtime_invoke) return null;
  // 找 get_Instance（在 Singleton<> 基类上）
  let k = klass, mi = null;
  for (let d = 0; d < 8 && k && !k.isNull() && !mi; d++) {
    const iter = Memory.alloc(Process.pointerSize);
    iter.writePointer(ptr(0));
    while (true) {
      const m = g_il2cpp.class_get_methods(k, iter);
      if (m.isNull()) break;
      const mn = g_il2cpp.method_get_name(m);
      if (!mn.isNull() && mn.readUtf8String() === "get_Instance") { mi = m; break; }
    }
    if (!mi) k = g_il2cpp.class_get_parent(k);
  }
  if (!mi) return null;
  try {
    if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
    const exc = Memory.alloc(8); exc.writePointer(ptr(0));
    const ret = g_il2cpp.runtime_invoke(mi, ptr(0), ptr(0), exc);
    if (!ret.isNull() && objIsClass(ret, klass)) {
      console.log("[probe] runtime_invoke(get_Instance) 命中实例 " + ret);
      return ret;
    }
  } catch (e) { console.log("[probe] runtime_invoke 失败: " + e); }
  return null;
}

// C) 钩 Init() / .ctor 抓 this
// 按方法名在 klass（含父类）中查找并取当前真实地址（ASLR 安全，绝不硬编码）
function luMethodPtrByName(klass, name) {
  if (!klass || klass.isNull()) return null;
  let c = klass, d = 0;
  while (c && !c.isNull() && d < 8) {
    const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while (true) {
      const m = g_il2cpp.class_get_methods(c, iter);
      if (m.isNull()) break;
      const mn = g_il2cpp.method_get_name(m);
      if (!mn.isNull() && mn.readUtf8String() === name) {
        const fp = methodPtr(m);
        if (fp && !fp.isNull()) return fp;
      }
    }
    c = g_il2cpp.class_get_parent(c); d++;
  }
  return null;
}

function hookCapture(klass) {
  // Init() 静态方法：onLeave 取返回值（按名字解析，避免硬编码 RVA 踩坏错误函数）
  try {
    const initPtr = luMethodPtrByName(klass, "Init");
    if (!initPtr) { console.log("[probe] 未找到 Init() 方法，跳过（依赖 hookLab 堆扫描兜底）"); }
    else {
      Interceptor.attach(initPtr, {
        onLeave(ret) {
          try {
            if (!g_inst && ret && !ret.isNull() && objIsClass(ret, klass)) {
              g_inst = ret;
              console.log("[probe] 通过 Init() 返回值捕获实例 " + ret);
              dumpState();
            }
          } catch (e) {}
        },
      });
      console.log("[probe] 已 hook Init() @ " + initPtr);
    }
  } catch (e) { console.log("[probe] hook Init() 失败: " + e); }

  // .ctor：a[0] 即 this
  try {
    const ctorPtr = luMethodPtrByName(klass, ".ctor");
    if (!ctorPtr) { console.log("[probe] 未找到 .ctor 方法，跳过（依赖 hookLab 堆扫描兜底）"); }
    else {
      Interceptor.attach(ctorPtr, {
        onEnter(a) {
          try {
            const self = safeArg(a, 0);
            if (!g_inst && self && !self.isNull() && objIsClass(self, klass)) {
              g_inst = self;
              console.log("[probe] 通过 .ctor(this) 捕获实例 " + self);
              dumpState();
            }
          } catch (e) {}
        },
      });
      console.log("[probe] 已 hook .ctor @ " + ctorPtr);
    }
  } catch (e) { console.log("[probe] hook .ctor 失败: " + e); }
}

// D) 手动 callInit（REPL 输入 callInit()）
function callInit() {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  try {
    if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
    const k = g_klass || findClass("LabyrinthTempData");
    if (!k || k.isNull()) { console.log("[probe] callInit() 未找到类"); return; }
    const fp = luMethodPtrByName(k, "Init");
    if (!fp) { console.log("[probe] callInit() 未找到 Init 方法"); return; }
    const fn = new NativeFunction(fp, "pointer", []);
    const ret = fn();
    if (!ret.isNull() && objIsClass(ret, k)) {
      g_inst = ret;
      console.log("[probe] callInit() 已捕获实例 " + ret);
      dumpState();
    } else {
      console.log("[probe] callInit() 返回非实例: " + ret);
    }
  } catch (e) { console.log("[probe] callInit() 异常: " + e); }
}
// 通用对象探查：打印某对象（指针 / 地址字符串 / NativePointer）的类名与全部字段（含父类）
// 列出某类的全部字段（名+偏移），用于定位真实字段偏移与判断类型（不依赖实例）
function lsClassFields(name){
  const k=findClass(name);
  if(!k||k.isNull()){ console.log("[ls] 未找到类: "+name); return; }
  console.log("══ [class fields] "+name+" @"+k);
  let cur=k;
  for(let d=0; d<14 && cur && !cur.isNull(); d++){
    const iter=Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while(true){
      const f=g_il2cpp.class_get_fields(cur,iter); if(f.isNull())break;
      const fn=g_il2cpp.field_get_name(f); if(fn.isNull())continue;
      const off=g_il2cpp.field_get_offset(f).toNumber();
      console.log("  +0x"+off.toString(16).padStart(3,"0")+" "+fn.readUtf8String());
    }
    cur=g_il2cpp.class_get_parent(cur);
  }
}
globalThis.lsClassFields = lsClassFields;
// 一次性打印地图相关三个类的字段布局
function lsMap(){ lsClassFields("LabyrinthTempData"); lsClassFields("LabyrinthSectorData"); lsClassFields("LabyrinthPanelData"); lsClassFields("LabyrinthMapInfo"); }
globalThis.lsMap = lsMap;

function inspect(target) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  let p;
  if (target && typeof target === "object" && typeof target.readPointer === "function") p = target;
  else { try { p = ptr(target); } catch (e) { console.log("[probe] inspect: 无法解析 " + target); return; } }
  if (!p || p.isNull()) { console.log("[probe] inspect: null"); return; }
  console.log("[probe] inspect " + p + "  class=" + klassNameOf(p));
  let cur = g_il2cpp.object_get_class(p);
  for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
    const iter = Memory.alloc(Process.pointerSize);
    iter.writePointer(ptr(0));
    while (true) {
      const f = g_il2cpp.class_get_fields(cur, iter);
      if (f.isNull()) break;
      const fn = g_il2cpp.field_get_name(f);
      const off = g_il2cpp.field_get_offset(f).toNumber();
      let ps = "?", i32 = "?";
      try { ps = p.add(off).readPointer().toString(); } catch (e) {}
      try { i32 = p.add(off).readS32().toString(); } catch (e) {}
      console.log("  +0x" + off.toString(16).padStart(3, "0") + " " +
        (fn.isNull() ? "?" : fn.readUtf8String()) + " : ptr=" + ps + " i32=" + i32);
    }
    cur = g_il2cpp.class_get_parent(cur);
  }
}

// 探查 DungeonTempData.DungeonBattle（0x70 字段）当前内容
function dumpBattle() {
  if (!g_inst) { console.log("[probe] 无实例"); return; }
  const p = g_inst.add(0x70).readPointer();
  if (p.isNull()) { console.log("[probe] DungeonBattle=null"); return; }
  inspect(p);
}

// 按名字模式搜索全部类（正则/子串，大小写不敏感），用于发现“已有角色/地图”所在类
function findClasses(pat) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  let re = null;
  try { re = new RegExp(pat, "i"); } catch (e) { re = null; }
  const key = String(pat).toLowerCase();
  const out = [];
  const domain = g_il2cpp.domain_get();
  const cnt = Memory.alloc(8);
  const asms = g_il2cpp.get_assemblies(domain, cnt);
  const nAsm = cnt.readU64().toNumber();
  for (let i = 0; i < nAsm; i++) {
    const asm = asms.add(i * Process.pointerSize).readPointer();
    const image = g_il2cpp.assembly_get_image(asm);
    if (image.isNull()) continue;
    const nCls = g_il2cpp.image_class_count(image);
    for (let j = 0; j < nCls; j++) {
      const klass = g_il2cpp.image_get_class(image, j);
      if (klass.isNull()) continue;
      const nm = g_il2cpp.class_get_name(klass);
      if (nm.isNull()) continue;
      const s = nm.readUtf8String();
      if ((re && re.test(s)) || (!re && s.toLowerCase().indexOf(key) >= 0)) out.push(s);
    }
  }
  console.log("[probe] 匹配类 " + out.length + " 个（显示前 300）:");
  out.slice(0, 300).forEach((n) => console.log("  " + n));
}

// 通用：从类（含整条父类链）的静态 Instance 字段取单例实例
// 不限定父类名必须含 "Singleton"：Instance 字段可能在任意基类或本类上
function getSingletonInstance(klass) {
  let k = klass;
  for (let d = 0; d < 12 && k && !k.isNull(); d++) {
    if (g_il2cpp.get_static_field_data) {
      let data;
      try { data = g_il2cpp.get_static_field_data(k); } catch (e) { data = null; }
      if (data && !data.isNull()) {
        const iter = Memory.alloc(Process.pointerSize);
        iter.writePointer(ptr(0));
        while (true) {
          const f = g_il2cpp.class_get_fields(k, iter);
          if (f.isNull()) break;
          const fn = g_il2cpp.field_get_name(f);
          if (!fn.isNull() && /instance/i.test(fn.readUtf8String())) {
            const off = g_il2cpp.field_get_offset(f).toNumber();
            try {
              const inst = data.add(off).readPointer();
              if (!inst.isNull() && objIsClass(inst, klass)) return inst;
            } catch (e) {}
          }
        }
      }
    }
    k = g_il2cpp.class_get_parent(k);
  }
  return null;
}

// 按名找类 → 取单例实例 → 展开字段
function findInstance(name) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const klass = findClass(name);
  if (!klass || klass.isNull()) { console.log("[probe] 未找到类 " + name); return; }
  const inst = getSingletonInstance(klass);
  if (!inst || inst.isNull()) { console.log("[probe] " + name + " 无静态实例（可能非单例）"); return; }
  console.log("[probe] " + name + " 实例 " + inst);
  inspect(inst);
}

// 只列类的字段名+偏移（含父类），不取实例，适合快速侦察结构
function fieldsOf(name) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const klass = findClass(name);
  if (!klass || klass.isNull()) { console.log("[probe] 未找到类 " + name); return; }
  console.log("[probe] " + name + " 字段（含父类）:");
  let cur = klass;
  for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
    const iter = Memory.alloc(Process.pointerSize);
    iter.writePointer(ptr(0));
    while (true) {
      const f = g_il2cpp.class_get_fields(cur, iter);
      if (f.isNull()) break;
      const fn = g_il2cpp.field_get_name(f);
      const off = g_il2cpp.field_get_offset(f).toNumber();
      console.log("  +0x" + off.toString(16).padStart(3, "0") + " " + (fn.isNull() ? "?" : fn.readUtf8String()));
    }
    cur = g_il2cpp.class_get_parent(cur);
  }
}

// 列出类的方法名（含父类），用于找访问器/单例入口（如 GetXxx / Instance）
function methodsOf(name) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const klass = findClass(name);
  if (!klass || klass.isNull()) { console.log("[probe] 未找到类 " + name); return; }
  console.log("[probe] " + name + " 方法（含父类）:");
  let cur = klass;
  for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
    const iter = Memory.alloc(Process.pointerSize);
    iter.writePointer(ptr(0));
    while (true) {
      const m = g_il2cpp.class_get_methods(cur, iter);
      if (m.isNull()) break;
      const mn = g_il2cpp.method_get_name(m);
      let s = (mn.isNull() ? "?" : mn.readUtf8String());
      try { if (g_il2cpp.method_is_generic(m)) s += " <generic>"; } catch (e) {}
      console.log("  " + s);
    }
    cur = g_il2cpp.class_get_parent(cur);
  }
}

// ---- 通用方法调用 + 集合枚举 ----
function dumpFields(obj) {
  if (!g_il2cpp) { console.log("[dump] il2cpp 未就绪"); return; }
  const o = ptr(obj);
  if (!ptrOk(o)) { console.log("[dump] 无效指针 " + o); return; }
  console.log("[dump] " + o + " (" + klassNameOf(o) + "):");
  let c = g_il2cpp.object_get_class(o), d = 0;
  while (c && !c.isNull() && d < 10) {
    const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while (true) {
      const f = g_il2cpp.class_get_fields(c, iter);
      if (f.isNull()) break;
      const fn = g_il2cpp.field_get_name(f);
      if (fn.isNull()) continue;
      const off = g_il2cpp.field_get_offset(f).toNumber();
      let v = "?";
      try {
        const p = o.add(off);
        try { const r = p.readPointer(); v = "ptr=" + r + "(" + klassNameOf(r) + ")"; }
        catch (e) { try { v = "i32=" + p.readS32(); } catch (e2) { try { v = "i64=" + p.readS64(); } catch (e3) {} } }
      } catch (e) {}
      console.log("  [" + off.toString(16) + "] " + fn.readUtf8String() + " = " + v);
    }
    c = g_il2cpp.class_get_parent(c); d++;
  }
}
globalThis.dumpFields = dumpFields;
function declaringClassName(m) {
  if (!g_il2cpp.method_get_class) return "";
  try { return g_il2cpp.class_get_name(g_il2cpp.method_get_class(m)).readUtf8String() || ""; } catch (e) { return ""; }
}
function isGeneratedMethod(m, s) {
  // 编译器生成的方法（lambda 等）：方法名含 <> / b__，或声明类含 < / DisplayClass / c__
  if (s.indexOf('<') >= 0 || s.indexOf('>') >= 0 || s.indexOf('b__') >= 0) return true;
  const dc = declaringClassName(m);
  if (dc && (dc.indexOf('<') >= 0 || dc.indexOf('DisplayClass') >= 0 || dc.indexOf('c__') >= 0)) return true;
  return false;
}
function findMethodLike(klass, sub) {
  let cur = klass, exact = null, best = null, bestName = "";
  for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
    const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while (true) {
      const m = g_il2cpp.class_get_methods(cur, iter);
      if (m.isNull()) break;
      const mn = g_il2cpp.method_get_name(m);
      if (mn.isNull()) continue;
      const s = mn.readUtf8String();
      if (isGeneratedMethod(m, s)) continue;             // 跳过 lambda / 编译器生成方法
      if (s === sub) { exact = m; break; }              // 精确名优先
      if (s.indexOf(sub) >= 0 && !best) { best = m; bestName = s; }
    }
    if (exact) break;
    cur = g_il2cpp.class_get_parent(cur);
  }
  if (exact) return { m: exact, name: sub };
  return best ? { m: best, name: bestName } : null;
}
// 诊断：列出某类的全部方法（可按名字过滤），并显示声明类，确认 findMethodLike 是否选错
function listMethods(klassPtr, filter) {
  console.log("[listMethods] " + klassNameOf(ptr(klassPtr)) + (filter ? ("  过滤='" + filter + "'") : ""));
  const k = ptr(klassPtr);
  let cur = k;
  for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
    const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while (true) {
      const m = g_il2cpp.class_get_methods(cur, iter);
      if (m.isNull()) break;
      const mn = g_il2cpp.method_get_name(m);
      if (mn.isNull()) continue;
      const s = mn.readUtf8String();
      let dcName = "?";
      try { dcName = g_il2cpp.class_get_name(g_il2cpp.method_get_class(m)).readUtf8String(); } catch (e) {}
      if (!filter || s.indexOf(filter) >= 0) {
        console.log("  " + (isGeneratedMethod(m, s) ? "[gen] " : "      ") + s + "   <- decl:" + dcName);
      }
    }
    cur = g_il2cpp.class_get_parent(cur);
  }
}
globalThis.listMethods = listMethods;
// 不过滤"声明类含 <"的查找（用于取泛型基类单例的 get_Instance 等；findMethodLike 会误跳过这类方法）
function findMethodAny(klass, sub) {
  let cur = klass;
  for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
    const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while (true) {
      const m = g_il2cpp.class_get_methods(cur, iter);
      if (m.isNull()) break;
      const mn = g_il2cpp.method_get_name(m);
      if (mn.isNull()) continue;
      const s = mn.readUtf8String();
      if (s === sub) return m;
    }
    cur = g_il2cpp.class_get_parent(cur);
  }
  return null;
}
// 取 ApiManager 单例实例：ManagerSingleton<ApiManager>.get_Instance()（静态方法，无参）
function getApiMgr() {
  const k = findClass("ApiManager");
  if (!k || k.isNull()) { console.log("[getApiMgr] 未找到 ApiManager 类"); return null; }
  const m = findMethodAny(k, "get_Instance") || findMethodAny(k, "GetInstance");
  if (!m) { console.log("[getApiMgr] 未找到 get_Instance / GetInstance"); return null; }
  if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  const params = Memory.alloc(8); params.writePointer(ptr(0)); // 静态方法无参数
  const r = g_il2cpp.runtime_invoke(m, ptr(0), params, ex);
  if (!ex.readPointer().isNull()) { console.log("[getApiMgr] get_Instance 异常: " + ex.readPointer()); return null; }
  if (!r || r.isNull()) { console.log("[getApiMgr] get_Instance 返回 null"); return null; }
  try {
    const rk = g_il2cpp.object_get_class(r);
    const rn = g_il2cpp.class_get_name(rk).readUtf8String();
    if (rn !== "ApiManager") { console.log("[getApiMgr] 返回对象类型异常: " + rn + " (非 ApiManager)"); return null; }
  } catch (e) {}
  return r;
}
globalThis.getApiMgr = getApiMgr;
function callMethod(obj, name, argPtr) {
  if (!g_il2cpp || !g_il2cpp.runtime_invoke) return null;
  const k = g_il2cpp.object_get_class(obj);
  const r = findMethodLike(k, name);
  if (!r) return null;
  if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  return g_il2cpp.runtime_invoke(r.m, obj, argPtr ? argPtr : ptr(0), ex);
}
// 通用枚举：List / Dictionary / HashSet 等 IEnumerable 统一展开（类型无关，走枚举器）
function dumpEnum(pt) {
  if (!g_il2cpp || !g_il2cpp.runtime_invoke) { console.log("[probe] 需要 runtime_invoke"); return; }
  const obj = ptr(pt);
  let n = invokeGetCount(obj);
  if (n < 0) n = 512;
  const en = callMethod(obj, "GetEnumerator");
  if (!en || en.isNull()) { console.log("[probe] 无 GetEnumerator（可能不是集合）"); return; }
  console.log("[probe] 枚举 n≈" + n + "（类 " + klassNameOf(obj) + "）");
  for (let i = 0; i < n; i++) {
    callMethod(en, "MoveNext");
    const cur = callMethod(en, "get_Current");
    if (!cur || cur.isNull()) { console.log("  [" + i + "] null"); continue; }
    const cn = klassNameOf(cur);
    let extra = "";
    if (cn && cn.indexOf("KeyValuePair") >= 0) {
      const kk = g_il2cpp.object_get_class(cur);
      let keyOff = -1, valOff = -1, c2 = kk;
      for (let d = 0; d < 4 && c2 && !c2.isNull(); d++) {
        const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
        while (true) {
          const f = g_il2cpp.class_get_fields(c2, iter);
          if (f.isNull()) break;
          const fn = g_il2cpp.field_get_name(f); if (fn.isNull()) continue;
          const nm = fn.readUtf8String(); const off = g_il2cpp.field_get_offset(f).toNumber();
          if (nm === "key") keyOff = off; else if (nm === "value") valOff = off;
        }
        c2 = g_il2cpp.class_get_parent(c2);
      }
      if (keyOff >= 0) { try { const kv = cur.add(keyOff); extra += " key=int=" + kv.readS32() + " ptr=" + kv.readPointer() + "(" + klassNameOf(kv.readPointer()) + ")"; } catch (e) {} }
      if (valOff >= 0) { try { const vv = cur.add(valOff); extra += " val=int=" + vv.readS32() + " ptr=" + vv.readPointer() + "(" + klassNameOf(vv.readPointer()) + ")"; } catch (e) {} }
    }
    console.log("  [" + i + "] " + cur + " (" + cn + ")" + extra);
  }
}

// 取方法原生地址：本构建未导出 il2cpp_method_get_function_pointer，
// 直接读 MethodInfo 结构体首字段 methodPointer（现代 il2cpp 通用布局）。
// 若该值不在 libil2cpp.so 内，则视为相对偏移（某些构建 Il2CppMethodPointer 为相对指针）。
function methodPtr(m) {
  try {
    const raw = m.readPointer();
    const mod = Process.findModuleByName("libil2cpp.so");
    if (mod && raw.compare(mod.base) >= 0 && raw.compare(mod.base.add(mod.size)) < 0) return raw;
    if (mod) {
      const rel = raw.toInt32();
      const abs = mod.base.add(rel);
      if (abs.compare(mod.base) >= 0 && abs.compare(mod.base.add(mod.size)) < 0) return abs;
    }
    return raw;
  } catch (e) { return ptr(0); }
}

// 钩住某类的 .ctor，对象被 new 出来时抓 this 存进 g_new[类名]（无需它是单例）
function hookNew(name) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const klass = findClass(name);
  if (!klass || klass.isNull()) { console.log("[probe] 未找到类 " + name); return; }
  const iter = Memory.alloc(Process.pointerSize);
  let ctor = null;
  iter.writePointer(ptr(0));
  while (true) {
    const m = g_il2cpp.class_get_methods(klass, iter);
    if (m.isNull()) break;
    const mn = g_il2cpp.method_get_name(m);
    if (!mn.isNull() && mn.readUtf8String() === ".ctor") { ctor = m; break; }
  }
  if (!ctor) { console.log("[probe] " + name + " 无 .ctor"); return; }
  const fp = methodPtr(ctor);
  if (fp.isNull()) { console.log("[probe] " + name + " .ctor 指针为空"); return; }
  Interceptor.attach(fp, {
    onEnter(args) {
      const self = safeArg(args, 0);
      g_new[name] = self;
      console.log("[probe] 新建 " + name + " @ " + self);
    }
  });
  console.log("[probe] 已 hook " + name + " .ctor @ " + fp);
}

// 钩住任意方法：实例方法 args[0]=this，静态方法 args[0] 起为参数；存最近一次参数
function hookMethod(className, methodName) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const klass = findClass(className);
  if (!klass || klass.isNull()) { console.log("[probe] 未找到类 " + className); return; }
  const iter = Memory.alloc(Process.pointerSize);
  let m = null;
  iter.writePointer(ptr(0));
  while (true) {
    const mm = g_il2cpp.class_get_methods(klass, iter);
    if (mm.isNull()) break;
    const mn = g_il2cpp.method_get_name(mm);
    if (!mn.isNull() && mn.readUtf8String() === methodName) { m = mm; break; }
  }
  if (!m) { console.log("[probe] " + className + "." + methodName + " 未找到"); return; }
  const fp = methodPtr(m);
  if (fp.isNull()) { console.log("[probe] " + className + "." + methodName + " 指针为空"); return; }
  const key = className + "." + methodName;
  Interceptor.attach(fp, {
    onEnter(args) {
      const snap = [];
      let s = "[probe] 调用 " + key;
      let n = 12;
      try { const L = args.length | 0; if (L >= 0 && L < n) n = L; } catch (e) {}
      for (let i = 0; i < n; i++) {
        let p;
        try { p = ptr(args[i]); } catch (e) { break; }
        snap.push(p);
        let cn = "?";
        try { cn = klassNameOf(p); } catch (e) {}
        s += "  arg" + i + "=" + p + "(" + cn + ")";
      }
      g_args[key] = snap;
      for (const p of snap) {
        try { if (klassNameOf(p) === "LabyrinthTempData") g_lab = p; } catch (e) {}
      }
      console.log(s);
    }
  });
  console.log("[probe] 已 hook " + key + " @ " + fp);
}

// ===== 迷宫 UI 驱动（目标：不改动内存，改为调用游戏自身的“点击节点/选择移动”handler）=====
// 注意：hookMoves / labCall / hookMovesAll 已移除——它们是“找操作”阶段引入的实验代码，
// 会挂大量 inline hook（本游戏有反篡改，且会刷屏）。当前脚本刻意保持与读地图/角色名时
// 一致的只读稳定形态。后续若要做 UI 驱动，将改用：按名字枚举方法 + NativeFunction 直接调用
// （全程零 inline hook），不破坏此稳定状态。
let g_lastHook = {};
// 安全读取 Interceptor 参数：部分方法 0 参数，直接 args[i] 会抛 "invalid array index"，必须包 try
function safeArg(a,i){ try{ return ptr(a[i]); }catch(e){ return null; } }


// 一次性读出本局已持有角色 UnitId（替代视觉识别“我有哪些角色”）
// addr 可传 LabyrinthTempData 实例地址；不传则自动定位（g_lab / hookNew / 静态单例）
// ===== 黎明界角色名解析（复用 acqIds 的 MasterData._UnitName 路径；按字段名定位，兼容偏移差异）=====
function luFindFieldPtr(obj, name){
  if(!ptrOk(obj)) return null;
  let c=g_il2cpp.object_get_class(obj), d=0;
  while(c && !c.isNull() && d<6){
    const iter=Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while(true){
      const f=g_il2cpp.class_get_fields(c, iter); if(f.isNull()) break;
      const fn=g_il2cpp.field_get_name(f); if(fn.isNull()) continue;
      if(fn.readUtf8String()===name){
        const off=g_il2cpp.field_get_offset(f).toNumber();
        try{ const p=obj.add(off).readPointer(); return ptrOk(p)?p:null; }catch(e){ return null; }
      }
    }
    c=g_il2cpp.class_get_parent(c); d++;
  }
  return null;
}
function luUnitParamInfoOne(o){
  try{
    if(!ptrOk(o)) return null;
    const md=luFindFieldSub(o,"masterdata")||luFindFieldPtr(o,"MasterData")||luFindFieldPtr(o,"_MasterData");
    if(!md) return null;
    let tmpl=null;
    const g=callIntGetter(md,"get_UnitId");
    if(g && g.ok) tmpl=g.v; else tmpl=readIdField(md,"UnitId");
    const np=luFindFieldSub(md,"unitname")||luFindFieldPtr(md,"_UnitName")||luFindFieldPtr(md,"UnitName");
    let name="?";
    if(np){
      const cn=klassNameOf(np);
      if(cn==="String"||cn==="System.String") name=readStr(np)||"?";
      else { const s=callMethod(np,"ToString"); name=(s&&!s.isNull())?(readStr(s)||"(空)"):"(解密失败:"+cn+")"; }
    }
    if(tmpl==null && name==="?") return null;
    return {tmpl, name};
  }catch(e){ return null; }
}
// 容错：直接解析失败则尝试 o 的某个指针字段（一层间接包装）
function luUnitParamInfo(up){
  if(!ptrOk(up)) return null;
  let info=luUnitParamInfoOne(up);
  if(info) return info;
  let c=g_il2cpp.object_get_class(up), d=0;
  while(c && !c.isNull() && d<6){
    const iter=Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while(true){
      const f=g_il2cpp.class_get_fields(c, iter); if(f.isNull()) break;
      const fn=g_il2cpp.field_get_name(f); if(fn.isNull()) continue;
      const off=g_il2cpp.field_get_offset(f).toNumber();
      if(off<0x8||off>0x400) continue;
      try{ const p=up.add(off).readPointer(); if(ptrOk(p)){ const i2=luUnitParamInfoOne(p); if(i2) return i2; } }catch(e){}
    }
    c=g_il2cpp.class_get_parent(c); d++;
  }
  return null;
}
// 从 LabyrinthTempData.UnitParameterDictionary(+0x30) 建 {模板id:名字} 映射
function luBuildNameMap(lab){
  const map={};
  const dict=lab.add(0x30).readPointer();
  if(!ptrOk(dict)) return map;
  const rows=enumDict(dict);
  for(const r of rows){
    const info=luUnitParamInfo(r.val);
    if(info && info.tmpl!=null) map[info.tmpl]=info.name;
  }
  return map;
}

// =====（续）全局 Master 表兜底：UnitParameterDictionary 未填充时，按模板id 从 Master 类实例解析名字 =====
function _hasFieldLike(klass, sub){
  let c=klass, d=0;
  while(c && !c.isNull() && d<6){
    const iter=Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while(true){
      const f=g_il2cpp.class_get_fields(c, iter); if(f.isNull()) break;
      const fn=g_il2cpp.field_get_name(f); if(!fn.isNull() && fn.readUtf8String().toLowerCase().includes(sub)) return true;
    }
    c=g_il2cpp.class_get_parent(c); d++;
  }
  return false;
}
function _hasMethodLike(klass, sub){
  let c=klass, d=0;
  while(c && !c.isNull() && d<6){
    const iter=Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while(true){
      const m=g_il2cpp.class_get_methods(c, iter); if(m.isNull()) break;
      const mn=g_il2cpp.method_get_name(m); if(!mn.isNull() && mn.readUtf8String().toLowerCase().includes(sub)) return true;
    }
    c=g_il2cpp.class_get_parent(c); d++;
  }
  return false;
}
// 按字段名子串定位字段并返回指针值（容错命名差异：_UnitName / unitName / <UnitName>k__BackingField 等）
function luFindFieldSub(obj, sub){
  if(!ptrOk(obj)) return null;
  let c=g_il2cpp.object_get_class(obj), d=0;
  while(c && !c.isNull() && d<6){
    const iter=Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while(true){
      const f=g_il2cpp.class_get_fields(c, iter); if(f.isNull()) break;
      const fn=g_il2cpp.field_get_name(f);
      if(!fn.isNull() && fn.readUtf8String().toLowerCase().includes(sub)){
        const off=g_il2cpp.field_get_offset(f).toNumber();
        try{ const p=obj.add(off).readPointer(); return ptrOk(p)?p:null; }catch(e){ return null; }
      }
    }
    c=g_il2cpp.class_get_parent(c); d++;
  }
  return null;
}
function score(n){ let s=0; if(/unit/i.test(n))s+=2; if(/master/i.test(n))s+=2; if(/data/i.test(n))s+=1; if(/info|param/i.test(n))s+=1; return s; }
let g_masterCands=null;
function luFindMasterCandidates(){
  if(g_masterCands) return g_masterCands;
  const names=[];
  if(g_il2cpp){
    const domain=g_il2cpp.domain_get();
    const cnt=Memory.alloc(8);
    const asms=g_il2cpp.get_assemblies(domain,cnt);
    const nAsm=cnt.readU64().toNumber();
    for(let i=0;i<nAsm;i++){
      const asm=asms.add(i*Process.pointerSize).readPointer();
      const image=g_il2cpp.assembly_get_image(asm);
      if(image.isNull()) continue;
      const nCls=g_il2cpp.image_class_count(image);
      for(let j=0;j<nCls;j++){
        const klass=g_il2cpp.image_get_class(image,j);
        if(klass.isNull()) continue;
        const nm=g_il2cpp.class_get_name(klass);
        if(nm.isNull()) continue;
        const s=nm.readUtf8String();
        // 放宽：命中 unit/master/chara/hero/player/data/info/param 任一即可
        if(/unit|master|chara|hero|player|data|info|param/i.test(s)) names.push(s);
      }
    }
  }
  const cands=[];
  for(const n of names){
    const k=findClass(n);
    if(!k||k.isNull()) continue;
    // 必须有"名称"字段 且 有"id"相关字段/方法（容忍命名差异）
    if(!_hasFieldLike(k,"name")) continue;
    if(!(_hasFieldLike(k,"id")||_hasMethodLike(k,"id"))) continue;
    cands.push({klass:k, name:n});
  }
  cands.sort((a,b)=>score(b.name)-score(a.name));
  g_masterCands=cands;
  console.log("[probe] 候选 Master 类 "+cands.length+" 个: "+(cands.map(c=>c.name).join(", ")||"(无)"));
  return cands;
}
// 读 Master 行实例的模板id：优先明文 int，否则按 ObscuredInt(hidden^key) 解密
function luReadUnitId(o){
  const p=luFindFieldSub(o,"unitid")||luFindFieldPtr(o,"_UnitId")||luFindFieldPtr(o,"UnitId");
  if(!p) return null;
  let v=p.readS32();
  if(v>=10000&&v<=99999999) return v;
  try{ const k=p.add(4).readS32(); const dec=(v^k)|0; if(dec>=10000&&dec<=99999999) return dec; }catch(e){}
  return null;
}
function luScanMasterInstances(klass, wanted){
  const map={}; let seen=0;
  let h=klass.toString(16).padStart(16,'0');
  let pat=[]; for(let i=0;i<8;i++) pat.push(h.substr((7-i)*2,2)); pat=pat.join(' ');
  const ranges=Process.enumerateRanges('r--');
  for(const r of ranges){
    if(r.file||r.size<0x10000) continue;
    try{
      const ms=Memory.scanSync(r.base,r.size,pat);
      for(const m of ms){
        const addr=m.address;
        try{ if(!objIsClass(addr,klass)) continue; }catch(e){ continue; }
        seen++;
        const tmpl=luReadUnitId(addr);
        if(tmpl==null||!(tmpl in wanted)) continue;
        const np=luFindFieldSub(addr,"unitname")||luFindFieldPtr(addr,"_UnitName")||luFindFieldPtr(addr,"UnitName");
        if(!np) continue;
        let name="?";
        const cn=klassNameOf(np);
        if(cn==="String"||cn==="System.String") name=readStr(np)||"?";
        else { const s=callMethod(np,"ToString"); name=(s&&!s.isNull())?(readStr(s)||"(空)"):"(解密失败:"+cn+")"; }
        map[tmpl]=name;
        if(Object.keys(map).length>=Object.keys(wanted).length) return {map, seen};
      }
    }catch(e){}
  }
  return {map, seen};
}
// 字段读法失败（如 _UnitId 是加密 getter）时的兜底：逐实例调 get_UnitId()
function luScanMasterInstancesGetter(klass, wanted){
  const map={};
  let h=klass.toString(16).padStart(16,'0');
  let pat=[]; for(let i=0;i<8;i++) pat.push(h.substr((7-i)*2,2)); pat=pat.join(' ');
  const ranges=Process.enumerateRanges('r--');
  for(const r of ranges){
    if(r.file||r.size<0x10000) continue;
    try{
      const ms=Memory.scanSync(r.base,r.size,pat);
      for(const m of ms){
        const addr=m.address;
        try{ if(!objIsClass(addr,klass)) continue; }catch(e){ continue; }
        const g=callIntGetter(addr,"get_UnitId");
        let tmpl=null; if(g&&g.ok) tmpl=g.v; else tmpl=readIdField(addr,"UnitId");
        if(tmpl==null||!(tmpl in wanted)) continue;
        const np=luFindFieldSub(addr,"unitname")||luFindFieldPtr(addr,"_UnitName")||luFindFieldPtr(addr,"UnitName");
        if(!np) continue;
        let name="?";
        const cn=klassNameOf(np);
        if(cn==="String"||cn==="System.String") name=readStr(np)||"?";
        else { const s=callMethod(np,"ToString"); name=(s&&!s.isNull())?(readStr(s)||"(空)"):"(解密失败:"+cn+")"; }
        map[tmpl]=name;
      }
    }catch(e){}
  }
  return map;
}
// 统一入口：先试 UnitParameterDictionary，缺失再退化为 Master 表
function luResolveNames(lab, ids){
  const wanted={}; ids.forEach(id=>{ if(id>=0) wanted[id]=true; });
  const result={};
  const dmap=luBuildNameMap(lab);
  for(const id in dmap){ if(id in wanted){ result[id]=dmap[id]; delete wanted[id]; } }
  if(Object.keys(wanted).length===0) return result;
  const cands=luFindMasterCandidates();
  for(const c of cands){
    const {map, seen}=luScanMasterInstances(c.klass, wanted);
    for(const id in map){ result[id]=map[id]; delete wanted[id]; }
    if(Object.keys(wanted).length===0) return result;
    if(seen>0 && Object.keys(map).length===0){   // 该类有实例但未命中 → 字段可能是加密 getter，换 get_UnitId 再扫
      const m2=luScanMasterInstancesGetter(c.klass, wanted);
      for(const id in m2){ result[id]=m2[id]; delete wanted[id]; }
      if(Object.keys(wanted).length===0) return result;
    }
  }
  return result;
}

function unitIds(addr) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  let lab = addr ? ptr(addr) : null;
  if (!lab || lab.isNull()) {
    if (g_lab && !g_lab.isNull()) lab = g_lab;
    else if (g_new["LabyrinthTempData"] && !g_new["LabyrinthTempData"].isNull()) lab = g_new["LabyrinthTempData"];
    else { const s = findLab(); if (s && !s.isNull()) lab = s; }
  }
  if (!lab || lab.isNull()) { console.log("[probe] 未定位 LabyrinthTempData；请传入地址，或先 hookNew(\"LabyrinthTempData\") 后重进黎明界"); return; }
  g_lab = lab;
  console.log("[probe] LabyrinthTempData=" + lab);
  const set = lab.add(0x28).readPointer();      // OwnUnitInfoSet (HashSet<LabyrinthUnitType>)
  if (set.isNull()) { console.log("[probe] OwnUnitInfoSet 空"); return; }
  const entries = set.add(0x18).readPointer();   // _entries
  if (entries.isNull()) { console.log("[probe] _entries 空"); return; }
  const cap = entries.add(0x18).readS32();
  const ids = [];
  for (let i = 0; i < cap; i++) {
    const slot = entries.add(0x20 + i * 16);
    let hc = -2; try { hc = slot.readS32(); } catch (e) {}
    if (hc === -1) continue;
    const v = slot.add(8).readPointer();         // LabyrinthUnitType
    if (v.isNull()) continue;
    let uid = -1; try { uid = v.add(0x10).readS32(); } catch (e) {}  // UnitId @+0x10 (明文模板id)
    if (uid >= 0) ids.push(uid);
  }
  console.log("[probe] 已有角色 模板id (n=" + ids.length + "):");
  const nameMap = luResolveNames(lab, ids);
  for (const uid of ids) {
    const name = (uid in nameMap) ? nameMap[uid] : "(名字未解析)";
    console.log("    " + uid + "  " + name);
  }
}

// 读 il2cpp 托管字符串（Il2CppString：头16B + int32 len@0x10 + UTF-16 chars@0x14）
function readStr(p) {
  try {
    if (!p || p.isNull()) return null;
    const cn = klassNameOf(p);
    if (cn !== "String" && cn !== "System.String") return "(非字符串:" + cn + ")";
    const len = p.add(0x10).readS32();
    if (len < 0 || len > 65536) return "(len=" + len + ")";
    return p.add(0x14).readUtf16String(len);
  } catch (e) { return "(err:" + e + ")"; }
}

// 读一个字段值：可能是内联 int，也可能是引用(装箱int / ObscuredInt)，后者需解引用/解密。
function readNumField(obj, off) {
  try {
    if (!obj || obj.isNull()) return null;
    const rp = obj.add(off).readPointer();
    if (!rp.isNull() && rp.compare(ptr(0x10000)) > 0 && (rp.toInt32() & 7) === 0) {
      const cn = klassNameOf(rp);
      if (cn === "Int32" || cn === "System.Int32") return rp.add(0x10).readS32();
      if (cn === "Int64" || cn === "System.Int64") return rp.add(0x10).readS64();
      if (cn.indexOf("Obscured") >= 0) { const s = callMethod(rp, "ToString"); if (s && !s.isNull()) { const t = readStr(s); if (t) { const v = parseInt(t, 10); if (!isNaN(v)) return v; } } }
      if (cn === "String" || cn === "System.String") { const t = readStr(rp); if (t) { const v = parseInt(t, 10); if (!isNaN(v)) return v; } }
    }
    return obj.add(off).readS32();
  } catch (e) { return "(err:" + e + ")"; }
}
// 按字段名子串在类层级里定位字段并读数字（用于 _UnitId 等）
function readNumFieldByName(obj, sub) {
  try {
    if (!obj || obj.isNull()) return null;
    const k = g_il2cpp.object_get_class(obj);
    let cur = k;
    for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
      const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
      while (true) {
        const f = g_il2cpp.class_get_fields(cur, iter);
        if (f.isNull()) break;
        const fn = g_il2cpp.field_get_name(f); if (fn.isNull()) continue;
        const nm = fn.readUtf8String();
        if (nm && nm.indexOf(sub) >= 0) {
          const off = g_il2cpp.field_get_offset(f).toNumber();
          return readNumField(obj, off);
        }
      }
      cur = g_il2cpp.class_get_parent(cur);
    }
  } catch (e) {}
  return null;
}
// 调属性 getter（如 get_Id / get_UnitId）：返回 {ok, v, cn}。
// 若返回 Int32/Int64 直接取数；若返回 ObscuredInt(装箱结构体) 则对其两 int 异或解密。
function callIntGetter(obj, name) {
  try {
    const r = callMethod(obj, name);
    if (!r || r.isNull()) return null;
    const cn = klassNameOf(r);
    if (cn === "Int32" || cn === "System.Int32") return { ok: true, v: r.add(0x10).readS32(), cn };
    if (cn === "Int64" || cn === "System.Int64") return { ok: true, v: r.add(0x10).readS64(), cn };
    if (cn.indexOf("Obscured") >= 0) {
      const a = r.add(0x10).readS32(), b = r.add(0x14).readS32();
      return { ok: true, v: a ^ b, cn };
    }
    return { ok: false, v: null, cn };
  } catch (e) { return { ok: false, v: null, cn: "(err:" + e + ")" }; }
}
// 退回方案：按字段名定位 ObscuredInt 字段，读两个 int 异或（启发式诊断）
function readIdField(obj, sub) {
  try {
    if (!obj || obj.isNull()) return "(无对象)";
    const k = g_il2cpp.object_get_class(obj);
    let cur = k;
    for (let d = 0; d < 12 && cur && !cur.isNull(); d++) {
      const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
      while (true) {
        const f = g_il2cpp.class_get_fields(cur, iter);
        if (f.isNull()) break;
        const fn = g_il2cpp.field_get_name(f); if (fn.isNull()) continue;
        const nm = fn.readUtf8String();
        if (nm && nm.indexOf(sub) >= 0) {
          const off = g_il2cpp.field_get_offset(f).toNumber();
          const a = obj.add(off).readS32(), b = obj.add(off + 4).readS32();
          return "(字段@0x" + off.toString(16) + " a=" + a + " b=" + b + " XOR=" + (a ^ b) + ")";
        }
      }
      cur = g_il2cpp.class_get_parent(cur);
    }
  } catch (e) { return "(err:" + e + ")"; }
  return "(无" + sub + "字段)";
}

// 读出本节点三选一刚获取的角色（UnitAcquisitionDataList）：名字 + 全局实例 id
function acqIds(addr) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  let lab = addr ? ptr(addr) : null;
  if (!lab || lab.isNull()) {
    if (g_lab && !g_lab.isNull()) lab = g_lab;
    else if (g_new["LabyrinthTempData"] && !g_new["LabyrinthTempData"].isNull()) lab = g_new["LabyrinthTempData"];
    else { const s = findLab(); if (s && !s.isNull()) lab = s; }
  }
  if (!lab || lab.isNull()) { console.log("[probe] 未定位 LabyrinthTempData"); return; }
  g_lab = lab;
  const list = lab.add(0x180).readPointer();
  if (list.isNull()) { console.log("[probe] UnitAcquisitionDataList 空"); return; }
  let n = -1;
  try {
    const k = g_il2cpp.object_get_class(list);
    let cur = k, cm = null;
    for (let d = 0; d < 8 && cur && !cur.isNull() && !cm; d++) {
      const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
      while (true) {
        const m = g_il2cpp.class_get_methods(cur, iter);
        if (m.isNull()) break;
        const mn = g_il2cpp.method_get_name(m);
        if (!mn.isNull() && mn.readUtf8String() === "get_Count") { cm = m; break; }
      }
      if (!cm) cur = g_il2cpp.class_get_parent(cur);
    }
    if (cm) {
      if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
      const ex = Memory.alloc(8); ex.writePointer(ptr(0));
      const r = g_il2cpp.runtime_invoke(cm, list, ptr(0), ex);
      if (!r.isNull()) n = r.add(0x10).readS32();
    }
  } catch (e) {}
  if (n < 0) { try { n = list.add(0x18).readS32(); } catch (e) {} }
  console.log("[probe] UnitAcquisitionDataList n=" + n);
  const items = list.add(0x10).readPointer();
  if (items.isNull()) return;
  for (let i = 0; i < n; i++) {
    const elem = items.add(0x20 + i * 8).readPointer();
    if (elem.isNull()) continue;
    const up = elem.add(0x10).readPointer();           // UnitParam
    if (up.isNull()) { console.log("  [" + i + "] UnitParam=null"); continue; }
    const ud = up.add(0x10).readPointer();             // UniqueData (UnitData)
    let uid = "(无)";
    if (!ud.isNull()) {
      const g = callIntGetter(ud, "get_Id");
      if (g && g.ok) uid = "" + g.v + (g.cn.indexOf("Obscured") >= 0 ? "[XOR解密]" : "");
      else uid = readIdField(ud, "Id");
    }
    const md = up.add(0x30).readPointer();             // MasterData (静态定义类, 含 _UnitName / _UnitId)
    let tmpl = "(无)";
    if (!md.isNull()) {
      const g = callIntGetter(md, "get_UnitId");
      if (g && g.ok) tmpl = "" + g.v + (g.cn.indexOf("Obscured") >= 0 ? "[XOR解密]" : "");
      else tmpl = readIdField(md, "UnitId");
    }
    let name = "?";
    try {
      if (!md.isNull()) {
        const np = md.add(0x28).readPointer();          // _UnitName : ObscuredString
        if (!np.isNull()) {
          const cn = klassNameOf(np);
          if (cn === "String" || cn === "System.String") name = readStr(np) || "?";
          else { const s = callMethod(np, "ToString"); name = (s && !s.isNull()) ? (readStr(s) || "(空)") : "(解密失败:" + cn + ")"; }
        }
      }
    } catch (e) { name = "(err:" + e + ")"; }
    console.log("  [" + i + "] 实例id=" + uid + " 模板id=" + tmpl + " 名字=" + name);
  }
}

// 整合快照：当前节点 + 已有角色 + 本节点获取（替代屏幕视觉）
function perceive(addr) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  let lab = addr ? ptr(addr) : null;
  if (!lab || lab.isNull()) {
    if (g_lab && !g_lab.isNull()) lab = g_lab;
    else if (g_new["LabyrinthTempData"] && !g_new["LabyrinthTempData"].isNull()) lab = g_new["LabyrinthTempData"];
    else { const s = findLab(); if (s && !s.isNull()) lab = s; }
  }
  if (!lab || lab.isNull()) { console.log("[probe] 未定位 LabyrinthTempData；传入地址或先 hookNew(\"LabyrinthTempData\")"); return; }
  g_lab = lab;
  console.log("══════════ perceive 快照 ══════════");
  const panel = lab.add(0xd0).readPointer();   // CurrentPanel (LabyrinthPanelData)
  if (!panel.isNull()) {
    const cn = klassNameOf(panel);
    const mapInfo = panel.add(0x10).readPointer();
    const next = panel.add(0x18).readPointer();
    let nNext = -1;
    if (!next.isNull()) { try { nNext = next.add(0x18).readS32(); } catch (e) {} }
    console.log("[节点] class=" + cn + " ptr=" + panel);
    console.log("  mapInfo=" + mapInfo + " (" + klassNameOf(mapInfo) + ")");
    console.log("  NextBlockIdList=" + next + " (n=" + nNext + ")");
    if (!next.isNull()) {
      const items = next.add(0x10).readPointer();   // _items : int[]（List<int> 元素 4B）
      const nids = [];
      if (!items.isNull()) {
        for (let i = 0; i < nNext; i++) {
          try { nids.push(items.add(0x20 + i * 4).readS32()); } catch (e) {}
        }
      }
      console.log("  └ 下一节点区块id: " + (nids.length ? nids.join(", ") : "(空)"));
    }
  } else console.log("[节点] CurrentPanel 空");
  console.log("── 已有角色（unitIds）──");
  unitIds(lab);
  console.log("── 本节点获取（acqIds）──");
  acqIds(lab);
  console.log("═════════════════════════════════");
}

// ===== 全地图 dumpMap：从 LabyrinthTempData.MapDataDictionary 读出完整 5 层地图 =====
const BLOCK_TYPE = {1:"NONE",2:"NORMAL",3:"HARD",4:"TICKET",5:"EVENT",6:"RELIC",7:"SHOP",8:"BOSS",[-1]:"INVALID"};
// 粗略判断 p 是否像合法用户态托管指针（过滤 0 / 极小 / 内核地址）
function ptrOk(p){ try{ return !!p && !p.isNull() && p.compare(ptr(0x1000))>0 && p.compare(ptr(0x7fffffffffff))<0; }catch(e){ return false; } }
function safeCls(p){ try{ return ptrOk(p)?klassNameOf(p):"?"; }catch(e){ return "?"; } }
function safeEnumListRef(lp){ try{ const o=ptr(lp); return ptrOk(o)?enumListRef(o):[]; }catch(e){ return []; } }
function safeEnumListOI(lp){ try{ const o=ptr(lp); return ptrOk(o)?enumListOI(o):[]; }catch(e){ return []; } }
// ObscuredInt 结构体：hidden@0, key@4，明文 = hidden ^ key
function decOI(p){ try{ const h=p.readS32(); const k=p.add(4).readS32(); return (h^k)|0; }catch(e){return "?"; } }


// 直接读 il2cpp Dictionary_2 内部数组（_entries / _values + _count），不依赖 GetEnumerator/KeyValuePair
// 判断一个指针是否是“引用元素数组”（如 LabyrinthSectorData[] / List`1[]），而非值类型数组（Int32[] 等）
function arrayIsRefVals(v){
  let cn=""; try{ cn=klassNameOf(v); }catch(e){ return false; }
  if(!/\[\]$/.test(cn)) return false;                                  // 必须是数组
  if(/Int32|Int64|IntPtr|UInt32|UInt64|Single|Double|Boolean|Byte|Char|SByte|UInt16|Int16/.test(cn)) return false; // 值类型数组排除
  return true;
}
// 自动定位 Dictionary 的 _values 数组（.NET Framework 风格：真实 TValue[] 在 KeyCollection/ValueCollection 包装器之后）
function findValuesArray(d){
  for(const vo of [0x58,0x50,0x60,0x48,0x40,0x38,0x68,0x70]){
    let v; try{ v=d.add(vo).readPointer(); }catch(e){ continue; }
    if(ptrOk(v) && arrayIsRefVals(v)) return v;
  }
  return null;
}
function enumDict(dictPtr){
  const out=[]; const d=ptr(dictPtr);
  if(!ptrOk(d))return out;
  let entries, count;
  try{
    entries=d.add(0x18).readPointer();   // _entries : Entry[]
    count  =d.add(0x20).readS32();        // _count
  }catch(e){ return out; }
  if(!ptrOk(entries)||count<=0||count>100000)return out;
  let baseV=null;
  try{ const va=findValuesArray(d); if(ptrOk(va)) baseV=va.add(0x20); }catch(e){ baseV=null; }
  try{
    const baseE=entries.add(0x20);       // Entry[] 数据起始（IL2CPP 数组：klass8+monitor8+bounds8+len4+pad4=0x20）
    for(let i=0;i<count;i++){
      const ent=baseE.add(i*24);          // Entry: hashCode(4)+next(4)+key(4)+value(8)
      const hc=ent.readS32();
      if(hc<0)continue;                    // 空闲/分发项跳过
      const key=ent.add(8).readS32();      // TKey=int
      let val=null;
      try{ val=ent.add(16).readPointer(); }catch(e){ val=null; }   // IL2CPP: value 内联在 Entry+16
      if((!val||val.isNull()) && baseV){ try{ val=baseV.add(i*8).readPointer(); }catch(e){} }
      out.push({key,val});
    }
  }catch(e){}
  return out;
}
// 直接读 il2cpp List<T> 内部 _items 数组；引用元素（8B）回传指针
function enumListRef(listPtr){
  const out=[]; const o=ptr(listPtr);
  if(!ptrOk(o))return out;
  let items,size;
  try{ items=o.add(0x10).readPointer(); size=o.add(0x18).readS32(); }catch(e){ return out; }
  if(!ptrOk(items)||size<=0||size>100000)return out;
  try{
    const base=items.add(0x20);   // IL2CPP 数组数据基址 0x20
    for(let i=0;i<size;i++){ const e=base.add(i*8).readPointer(); if(ptrOk(e)) out.push(e); }
  }catch(e){}
  return out;
}
// 直接读 List<ObscuredInt>，内部数组每元素 12B 结构体，逐元素解密
function enumListOI(listPtr){
  const out=[]; const o=ptr(listPtr);
  if(!ptrOk(o))return out;
  let items,size;
  try{ items=o.add(0x10).readPointer(); size=o.add(0x18).readS32(); }catch(e){ return out; }
  if(!ptrOk(items)||size<=0||size>100000)return out;
  try{
    const base=items.add(0x20);   // IL2CPP 数组数据基址 0x20
    for(let i=0;i<size;i++){ out.push(decOI(base.add(i*12))); }
  }catch(e){}
  return out;
}
// 通用：打印一个 panelDataDictionary（Dictionary<int row, List<LabyrinthPanelData>>）
function dumpPanelDict(pdd, curArea, curBlock, label){
  if(!ptrOk(pdd)){ console.log(label+" 空"); return 0; }
  const rows=enumDict(pdd);
  console.log(label+" 行(row)数="+rows.length);
  let n=0;
  for(const rw of rows){
    const panels=safeEnumListRef(rw.val);
    for(const panel of panels){
      try{
        const mi=panel.add(0x10).readPointer();  // LabyrinthMapInfo
        if(!ptrOk(mi))continue;
        const area=decOI(mi.add(0x10));
        const col =decOI(mi.add(0x1C));
        const row =decOI(mi.add(0x28));
        const bid =decOI(mi.add(0x34));
        const btype=mi.add(0x40).readS32();
        const visited=decOI(mi.add(0x44));
        const quest=decOI(mi.add(0x50));
        const nbl=mi.add(0x60).readPointer();
        const nexts=safeEnumListOI(nbl);
        const tname=BLOCK_TYPE[btype]||("?"+btype);
        const mark=(bid===curBlock)?" ★当前":"";
        console.log("    ("+area+","+row+","+col+") B"+bid+" ["+tname+"] v"+visited+" q"+quest+" →["+nexts.join(",")+"]"+mark);
        n++;
      }catch(e){}
    }
  }
  return n;
}
function dumpMap(addr){
  if(!g_il2cpp){console.log("[probe] il2cpp 未就绪");return;}
  const k = findClass("LabyrinthTempData");
  // 校验某 LabyrinthTempData 实例是否“活着”：MapDataDictionary(@+0xF0) 非空且能枚举出区域
  const labAlive = (l)=>{ try{ const mdd=l.add(0xf0).readPointer(); return ptrOk(mdd) && enumDict(mdd).length>0; }catch(e){ return false; } };
  let lab=addr?ptr(addr):null;
  // 优先用 probeMap 已校验的实例，但必须确认其地图仍有效（撤退/重开后旧指针会变 stale）
  if(!lab && globalThis.LTD && !globalThis.LTD.isNull() && objIsClass(globalThis.LTD, k) && labAlive(globalThis.LTD)) lab=globalThis.LTD;
  if(!lab && g_lab && !g_lab.isNull() && objIsClass(g_lab, k) && labAlive(g_lab)) lab=g_lab;
  if(!lab||lab.isNull()){
    // LabyrinthTempData 非静态单例，每次重新堆扫描拿当前存活实例
    try{ const s=findLab(); if(s&&!s.isNull()) lab=s; }catch(e){}
  }
  // 兜底：最终选定实例地图为空（stale），强制重扫
  if(lab && !labAlive(lab)){ try{ const s=findLab(); if(s&&!s.isNull()) lab=s; }catch(e){} }
  if(!lab||lab.isNull()){console.log("[probe] 传入 LabyrinthTempData 地址（或先 perceive）");return;}
  g_lab=lab;
  const curArea=lab.add(0x40).readS32();
  const curBlock=lab.add(0x44).readS32();
  console.log("══════════ 全地图 dumpMap ══════════");
  console.log("[当前] CurrentArea="+curArea+" BlockId="+curBlock+"  区块类型: 1NONE 2NORMAL 3HARD 4TICKET 5EVENT 6RELIC 7SHOP 8BOSS");
  console.log("  坐标格式=(Area,Row,Column)；BlockId=A+列(2)+行(2) 故二者 Row/Column 顺序相反；列表按 BlockId 升序(列优先)");

  // 当前区域 SectorData 指针（与 MapDataDictionary[curArea] 指向同一对象，仅作提示，不重复展开）
  const sd=lab.add(0xD8).readPointer();
  console.log("[当前区域 SectorData @+0xD8] "+(ptrOk(sd)?(sd+" cls="+safeCls(sd)):"空"));

  // 全区域：MapDataDictionary @+0xF0（含全部 5 层区域，含当前区域，已在下方按层展开）
  const mdd=lab.add(0xF0).readPointer();
  if(mdd.isNull()){console.log("[probe] MapDataDictionary 空");console.log("═════════════════════════════════");return;}
  console.log("[地图] MapDataDictionary="+mdd+" ("+klassNameOf(mdd)+")");
  const areas=enumDict(mdd);
  console.log("  区域(area)数="+areas.length);
  let total=0;
  for(const ar of areas){
    const obj=ar.val;
    const oc=safeCls(obj);
    console.log("  [area "+ar.key+"] val="+obj+" cls="+oc);
    if(!ptrOk(obj)){ continue; }
    if(/SectorData/.test(oc)){
      total+=dumpPanelDict(obj.add(0x10).readPointer(),curArea,curBlock,"  ── [area "+ar.key+"]"+(ar.key===curArea?" ←当前区域":""));
    } else {
      // 值含 mapInfo 的面板对象：打印入口区块信息，并 inspect 学习结构
      try{
        const mi=obj.add(0x10).readPointer();  // LabyrinthPanelData.mapInfo
        if(ptrOk(mi)){
          const a=decOI(mi.add(0x10)), c=decOI(mi.add(0x1C)), r=decOI(mi.add(0x28)), b=decOI(mi.add(0x34));
          const t=mi.add(0x40).readS32(), nbl=mi.add(0x60).readPointer();
          const nx=safeEnumListOI(nbl);
          const mark=(b===curBlock)?" ★当前":"";
          console.log("    → 入口面板 ("+a+","+r+","+c+") B"+b+" ["+(BLOCK_TYPE[t]||("?"+t))+"] →["+nx.join(",")+"]"+mark);
        }
      }catch(e2){ console.log("    inspect 失败: "+e2); }
    }
  }
  console.log("[统计] MapDataDictionary 解析区块总数="+total);
  console.log("═════════════════════════════════");
}
globalThis.dumpMap = dumpMap;

// 整张地图的**数据版**（供 Python 算路线）。与 dumpMap 同一读取链，只是返回对象：
//   { curArea, curBlockId, panels:[{area,column,row,blockId,blockType,isVisited,questId,next:[...]}] }
// 链路（dump.cs 偏移已核对 / 与 dumpMap 一致）：
//   LTD +0xF0 MapDataDictionary: Dictionary<int, LabyrinthSectorData>
//     SectorData +0x10 panelDataDictionary: Dictionary<int, List<LabyrinthPanelData>>
//       LabyrinthPanelData +0x10 mapInfo(LabyrinthMapInfo)
//         Area@0x10 Column@0x1C Row@0x28 BlockId@0x34 BlockType@0x40 IsVisited@0x44 QuestId@0x50
//         NextBlockIdList: List<ObscuredInt> @0x60
function labMapSnapshot(diag) {
  const res = { curArea: null, curBlockId: null, panels: [] };
  try {
    let lab = null;
    try { const s = findLab(); if (s && !s.isNull()) lab = s; } catch (e) {}
    if (!lab) { if (diag) diag.err = "未找到 LabyrinthTempData"; return res; }
    res.curArea = lab.add(0x40).readS32();
    res.curBlockId = lab.add(0x44).readS32();
    const mdd = lab.add(0xF0).readPointer();
    if (!ptrOk(mdd)) { if (diag) diag.err = "MapDataDictionary 为空"; return res; }
    const seen = {};
    const addPanel = (panel) => {
      try {
        const mi = panel.add(0x10).readPointer();      // LabyrinthPanelData.mapInfo
        if (!ptrOk(mi)) return;
        const bid = decOI(mi.add(0x34));
        if (typeof bid !== "number" || bid <= 0 || seen[bid]) return;
        seen[bid] = 1;
        res.panels.push({
          area: decOI(mi.add(0x10)), column: decOI(mi.add(0x1C)), row: decOI(mi.add(0x28)),
          blockId: bid, blockType: mi.add(0x40).readS32(),
          isVisited: decOI(mi.add(0x44)), questId: decOI(mi.add(0x50)),
          next: safeEnumListOI(mi.add(0x60).readPointer()),
        });
      } catch (e) {}
    };
    for (const ar of enumDict(mdd)) {
      const obj = ar.val;
      if (!ptrOk(obj)) continue;
      if (/SectorData/.test(safeCls(obj))) {
        for (const rw of enumDict(obj.add(0x10).readPointer())) {
          for (const panel of safeEnumListRef(rw.val)) addPanel(panel);
        }
      } else {
        addPanel(obj);                                  // 个别区域直接放面板对象
      }
    }
    res.panels.sort((a, b) => a.blockId - b.blockId);
    if (diag) diag.count = res.panels.length;
  } catch (e) { if (diag) diag.err = "" + e; }
  return res;
}

globalThis.labMap = function () {
  if (!g_il2cpp) { console.log("[map] il2cpp 未就绪"); return null; }
  const diag = {};
  const r = labMapSnapshot(diag);
  console.log("[map] curArea=" + r.curArea + " curBlockId=" + r.curBlockId
    + "  区域数=" + new Set(r.panels.map(p => p.area)).size + "  节点数=" + r.panels.length
    + (diag.err ? ("  错误=" + diag.err) : ""));
  for (const p of r.panels) {
    console.log("[map]   (area" + p.area + ",col" + p.column + ",row" + p.row + ") B" + p.blockId
      + " [" + (BLOCK_TYPE[p.blockType] || ("?" + p.blockType)) + "] v" + p.isVisited
      + " q" + p.questId + " →[" + p.next.join(",") + "]"
      + (p.blockId === r.curBlockId ? "  ★当前" : ""));
  }
  return r;
};

// 当前已有角色（模板id + 角色名）：与 dumpMap 同健壮性——不依赖固定地址，自动定位活动 LabyrinthTempData 实例
// 数据来源：LTD.OwnUnitInfoSet(@+0x28, HashSet<LabyrinthUnitType>)，每个元素 @+0x10 是明文模板id
function dumpUnits() {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const k = findClass("LabyrinthTempData");
  const labAlive = (l)=>{ try{ const mdd=l.add(0xf0).readPointer(); return ptrOk(mdd) && enumDict(mdd).length>0; }catch(e){ return false; } };
  let lab=null;
  if(globalThis.LTD && !globalThis.LTD.isNull() && objIsClass(globalThis.LTD,k) && labAlive(globalThis.LTD)) lab=globalThis.LTD;
  if(!lab && g_lab && !g_lab.isNull() && objIsClass(g_lab,k) && labAlive(g_lab)) lab=g_lab;
  if(!lab||lab.isNull()){ try{ const s=findLab(); if(s&&!s.isNull()) lab=s; }catch(e){} }
  if(!lab||lab.isNull()){ console.log("[probe] 未定位 LabyrinthTempData（请进入黎明界，或先 probeMap()）"); return; }
  g_lab=lab;
  const set=lab.add(0x28).readPointer();
  if(!ptrOk(set)){ console.log("[probe] OwnUnitInfoSet 空"); return; }
  const entries=set.add(0x18).readPointer();
  if(!ptrOk(entries)){ console.log("[probe] _entries 空"); return; }
  const cap=entries.add(0x18).readS32();
  const ids=[];
  for(let i=0;i<cap;i++){
    const slot=entries.add(0x20 + i*16);
    let hc=-2; try{ hc=slot.readS32(); }catch(e){}
    if(hc===-1) continue;
    const v=slot.add(8).readPointer();
    if(v.isNull()) continue;
    let uid=-1; try{ uid=v.add(0x10).readS32(); }catch(e){}
    if(uid>=0) ids.push(uid);
  }
  ids.sort((a,b)=>a-b);
  const nameMap=luResolveNames(lab, ids);   // 优先 UnitParameterDictionary 内解析，缺的退回 Master 扫描
  console.log("══════════ 已有角色 (n="+ids.length+") ══════════");
  for(const uid of ids){
    const name=(uid in nameMap)?nameMap[uid]:"(名字未解析)";
    console.log("  "+uid+"  "+name);
  }
  console.log("═════════════════════════════════");
}
globalThis.dumpUnits = dumpUnits;

globalThis.callInit = callInit;
globalThis.perceive = perceive;
globalThis.dump = dumpState;
globalThis.inspect = inspect;
globalThis.dumpBattle = dumpBattle;
globalThis.findClasses = findClasses;
globalThis.findInstance = findInstance;
globalThis.fieldsOf = fieldsOf;
globalThis.methodsOf = methodsOf;
globalThis.dumpEnum = dumpEnum;
globalThis.dumpList = dumpList;
globalThis.dumpSet = dumpSet;
globalThis.unitIds = unitIds;
globalThis.acqIds = acqIds;
globalThis.readStr = readStr;
globalThis.hookNew = hookNew;
globalThis.hookMethod = hookMethod;
globalThis.hookAll = hookAll;
globalThis.dumpList = dumpList;

// 直接读 il2cpp List<T>（引用元素）：_items@0x10；T[] 数组头 0x20，元素 8B，elem[i]=0x20+i*8
function dumpList(ptrArg) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const obj = ptr(ptrArg);
  let n = -1;
  try {
    const k = g_il2cpp.object_get_class(obj);
    let cur = k, cm = null;
    for (let d = 0; d < 8 && cur && !cur.isNull() && !cm; d++) {
      const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
      while (true) {
        const m = g_il2cpp.class_get_methods(cur, iter);
        if (m.isNull()) break;
        const mn = g_il2cpp.method_get_name(m);
        if (!mn.isNull() && mn.readUtf8String() === "get_Count") { cm = m; break; }
      }
      if (!cm) cur = g_il2cpp.class_get_parent(cur);
    }
    if (cm) {
      if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
      const ex = Memory.alloc(8); ex.writePointer(ptr(0));
      const r = g_il2cpp.runtime_invoke(cm, obj, ptr(0), ex);
      if (!r.isNull()) n = r.add(0x10).readS32();
    }
  } catch (e) {}
  let items = null;
  try { items = obj.add(0x10).readPointer(); } catch (e) {}
  if (!items || items.isNull()) { console.log("[probe] List _items 为空"); return; }
  const cap = items.add(0x18).readS32();
  if (n < 0) n = cap;
  console.log("[probe] List n=" + n + " cap=" + cap);
  for (let i = 0; i < n; i++) {
    const e = items.add(0x20 + i * 8).readPointer();
    let cn = "?";
    try { cn = klassNameOf(e); } catch (err) {}
    console.log("  [" + i + "] " + e + " (" + cn + ")");
  }
}

// 直接读 il2cpp HashSet<T>（引用元素）：_entries@0x18；Entry{int hc;int next;T val}=16B，val@+8
function dumpSet(ptrArg) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const obj = ptr(ptrArg);
  let count = -1;
  try { count = obj.add(0x20).readS32(); } catch (e) {}
  const entries = obj.add(0x18).readPointer();
  if (entries.isNull()) { console.log("[probe] HashSet _entries 为空"); return; }
  const cap = entries.add(0x18).readS32();
  console.log("[probe] HashSet _count=" + count + " cap=" + cap);
  for (let i = 0; i < cap; i++) {
    const slot = entries.add(0x20 + i * 16);
    let hc = -2;
    try { hc = slot.readS32(); } catch (e) {}
    if (hc === -1) continue; // 空槽
    const val = slot.add(8).readPointer();
    let cn = "?";
    try { cn = klassNameOf(val); } catch (e) {}
    console.log("  [" + i + "] hc=" + hc + " " + val + " (" + cn + ")");
  }
}

// 挂上某类的全部方法（跳过 Lambda/Object 基类），每次调用打印方法名。
// 用于诊断：在游戏里做操作时看哪些方法真被调到，验证 methodPtr 并暴露调用流程。
function hookAll(name) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const klass = findClass(name);
  if (!klass || klass.isNull()) { console.log("[probe] 未找到类 " + name); return; }
  const skip = ["Equals", "ToString", "GetHashCode", "GetType", "MemberwiseClone",
    "Finalize", ".ctor", "FieldGetter", "FieldSetter", "ReferenceEquals", "InternalGetHashCode"];
  let cur = klass, n = 0;
  for (let d = 0; d < 6 && cur && !cur.isNull(); d++) {
    const iter = Memory.alloc(Process.pointerSize);
    iter.writePointer(ptr(0));
    while (true) {
      const m = g_il2cpp.class_get_methods(cur, iter);
      if (m.isNull()) break;
      const mn = g_il2cpp.method_get_name(m);
      if (mn.isNull()) continue;
      const ms = mn.readUtf8String();
      if (ms[0] === "<" || skip.indexOf(ms) >= 0) continue;
      const fp = methodPtr(m);
      if (fp.isNull()) continue;
      try {
        Interceptor.attach(fp, {
          onEnter() { console.log("[probe] ▶ " + name + "." + ms); }
        });
        n++;
      } catch (e) {}
    }
    cur = g_il2cpp.class_get_parent(cur);
  }
  console.log("[probe] hookAll " + name + " 完成，挂 " + n + " 个方法");
}

// 自动捕获 LabyrinthTempData 实例：它是泛型单例，attach 前已构造、重进黎明界是复用（不重建），
// 故不靠构造钩子抓；且 IL2CPP 里 Instance 多为直接静态字段（无 get_Instance 方法）。
// 主路径：每 2s 从单例静态 Instance 字段读取（进黎明界后实例就绪即捕获）。
// 兜底：.ctor 触发时抓 this（万一有新建实例）。
// 自动捕获 LabyrinthTempData 实例。
// 模式与已验证可用的 DungeonTempData 捕获一致：hook 其 Init()（进迷宫时被调用，返回/持有实例）
// 与 .ctor（兜底），任意一处命中即抓 this/返回值。只挂这几个方法，安全不崩。
// 另加只读 2s 轮询从单例静态字段兜底。
function hookLab(){
  if(!g_il2cpp){ console.log("[probe] il2cpp 未就绪"); return; }
  const k = findClass("LabyrinthTempData");
  if(!k || k.isNull()){ console.log("[probe] 未找到 LabyrinthTempData 类"); return; }
  let nHook=0;
  let cur = k;
  while(cur && !cur.isNull()){
    const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    while(true){
      const m = g_il2cpp.class_get_methods(cur, iter);
      if(m.isNull()) break;
      const mn = g_il2cpp.method_get_name(m);
      if(mn.isNull()) continue;
      const nm = mn.readUtf8String();
      if(nm === ".ctor" || nm === "Init"){
        try{
          Interceptor.attach(m, {
            onEnter(a){
              if(g_lab && !g_lab.isNull()) return;
              try{ if(a && !a[0].isNull() && objIsClass(a[0], k)){ g_lab = a[0]; console.log("[cap] LabyrinthTempData 实例(this) = "+a[0]); } }catch(e){}
            },
            onLeave(ret){
              if(g_lab && !g_lab.isNull()) return;
              try{ if(ret && !ret.isNull() && objIsClass(ret, k)){ g_lab = ret; console.log("[cap] LabyrinthTempData 实例(ret) = "+ret); } }catch(e){}
            }
          });
          nHook++;
        }catch(e){}
      }
    }
    cur = g_il2cpp.class_get_parent(cur);
  }
  console.log("[probe] 已 hook "+nHook+" 个 Init/.ctor 用于捕获（进黎明界即触发）");
  // 只读轮询兜底（实例已存在但未触发钩子时）
  setInterval(() => {
    if(g_lab && !g_lab.isNull()) return;
    try{
      const s = getSingletonInstance(k);
      if(s && !s.isNull()){ g_lab = s; console.log("[cap] LabyrinthTempData 实例 = "+s); }
    }catch(e){}
  }, 2000);
  console.log("[probe] hookLab：每 2s 自动从单例静态字段取 LabyrinthTempData（兜底）");
  // 兜底C：加载后延迟一次堆扫描，抓取 attach 前已构造好的实例
  setTimeout(() => {
    if(g_lab && !g_lab.isNull()) return;
    try{ const f=findLab(); if(f && !f.isNull()){ g_lab=f; console.log("[cap] LabyrinthTempData 实例(堆扫描) = "+f); } }catch(e){}
  }, 4000);
}
globalThis.hookLab = hookLab;

// 调试：打印 LabyrinthTempData 继承链上每个类的静态字段数据及 Instance 字段取值，
// 用于定位"静态单例读取失败"的根因（仅在需要时 REPL 输入 probeLab()）
function probeLab(){
  if(!g_il2cpp){ console.log("[probe] il2cpp 未就绪"); return; }
  const k0 = findClass("LabyrinthTempData");
  if(!k0||k0.isNull()){ console.log("[probe] 未找到 LabyrinthTempData"); return; }
  let cur=k0;
  for(let d=0; d<12 && cur && !cur.isNull(); d++){
    const cn=g_il2cpp.class_get_name(cur);
    const cns=cn.isNull()?"?":cn.readUtf8String();
    let data=null;
    if(g_il2cpp.get_static_field_data){ try{data=g_il2cpp.get_static_field_data(cur);}catch(e){data=null;} }
    let info="[cls] "+cns+" staticData="+(data&&!data.isNull()?data:"null");
    const iter=Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
    let flds=[];
    while(true){
      const f=g_il2cpp.class_get_fields(cur,iter);
      if(f.isNull())break;
      const fn=g_il2cpp.field_get_name(f);
      const fns=fn.isNull()?"?":fn.readUtf8String();
      const off=g_il2cpp.field_get_offset(f).toNumber();
      let val="?";
      if(data&&!data.isNull()){ try{ const p=data.add(off).readPointer(); val=p.isNull()?"null":p.toString(); }catch(e){val="err";} }
      flds.push(fns+"@"+off+"="+val);
    }
    console.log(info);
    if(flds.length) console.log("   fields: "+flds.join("  "));
    cur=g_il2cpp.class_get_parent(cur);
  }
  const s=getSingletonInstance(k0);
  console.log("[probe] getSingletonInstance => "+(s&&!s.isNull()?s:"null"));
}
globalThis.probeLab = probeLab;

// 兜底C：扫描托管堆，找 klass == LabyrinthTempData 的存活对象。
// IL2CPP 对象首 8 字节即 Il2CppClass*；只扫匿名大块内存（GC 堆），避免代码段误命中。
// ★零扫描取 LabyrinthTempData（2026-10-11 加）：
//   ViewManager.Instance（runtime_invoke get_Instance，µs 级）→ currentView(+0x110)
//   → 类是 ViewLabyrinthMap 时，**+0xF8 就是 labyrinthTempData**（dump.cs 确认；旧代码也是这么读的）
//   进迷宫后主循环一直在 ViewLabyrinthMap → 这条路随时可用；取不到才退回堆扫描。
function labLtdFast() {
  const out = { ok: false, ltd: null, view: null, how: null, err: null };
  try {
    // ★**视图优先**（2026-10-16 加）：先读"当前视图挂着的 LTD" —— `ViewManager.Instance → currentView`，
    //   类为 ViewLabyrinthMap 时 `+0xF8` 就是 labyrinthTempData，这**才是当前这一局**的权威值。
    //   这段本来就每次都读（几只内存读，µs 级），原来却**读到了不采信**：缓存"类名还对"就直接 return。
    //   ⚠实机踩到的坑：旧局的 LTD 只要还没被回收，类名校验照样通过 ⇒ 缓存永远指着旧局 ⇒ `labUnits`
    //   （持有池）读到上一局的人（同一局里页面"可编入" 7→14→15、而"持有"一直是 7 ⇒ 组队算法拿残缺
    //   池子做选择，选出了单体破甲而不是 134301）。
    //   代价：几次内存读 + 一次指针比较，**不触发任何堆扫描**（不一致就直接覆盖缓存）。
    let vmLtd = null;
    try {
      const vm0 = _viewManagerInst();
      if (vm0 && !vm0.isNull()) {
        const cv0 = vm0.add(0x110).readPointer();
        if (ptrOk(cv0)) {
          out.view = klassNameOf(cv0);
          const l0 = cv0.add(0xF8).readPointer();
          if (ptrOk(l0) && klassNameOf(l0) === "LabyrinthTempData") vmLtd = l0;
        }
      }
    } catch (e) {}
    if (vmLtd) {
      try {
        if (g_lab && !g_lab.isNull() && String(g_lab) !== String(vmLtd)) {
          console.log("[findMap] ★换局：视图 LTD=" + vmLtd + " ≠ 缓存 " + g_lab + " → 以视图为准");
        }
      } catch (e) {}
      g_lab = vmLtd;
      out.ok = true; out.ltd = vmLtd; out.how = (out.view || "视图") + "+0xF8";
      return out;
    }
    // 不在地图视图（登录页/结算页等）⇒ 才退回缓存兜底；下面那段是原来的诊断路径（错误信息照旧）
    if (g_lab && !g_lab.isNull()) {                       // ① 缓存（校验类名，防换局后失效）
      try {
        if (klassNameOf(g_lab) === "LabyrinthTempData") { out.ok = true; out.ltd = g_lab; out.how = "缓存"; return out; }
      } catch (e) {}
      g_lab = null;
    }
    const vm = _viewManagerInst();                        // ② ViewManager.Instance → currentView
    if (!vm || vm.isNull()) { out.err = "ViewManager 实例取不到"; return out; }
    const cv = vm.add(0x110).readPointer();
    if (!ptrOk(cv)) { out.err = "currentView 为空"; return out; }
    out.view = klassNameOf(cv);
    const ltd = cv.add(0xF8).readPointer();               // ViewLabyrinthMap.labyrinthTempData @0xF8
    if (!ptrOk(ltd)) { out.err = (out.view || "?") + " 的 +0xF8 为空"; return out; }
    const kn = klassNameOf(ltd);
    if (kn !== "LabyrinthTempData") {
      out.err = "+0xF8 指向 " + kn + "（视图=" + out.view + "，不是地图视图？）";
      return out;
    }
    g_lab = ltd;                                          // ★填全局：后面所有功能都用它，不再扫
    out.ok = true; out.ltd = ltd; out.how = out.view + "+0xF8";
  } catch (e) { out.err = String(e); }
  return out;
}

function findLab(){
  if(!g_il2cpp){ console.log("[probe] il2cpp 未就绪"); return null; }
  const k = findClass("LabyrinthTempData");
  if(!k || k.isNull()){ console.log("[probe] 未找到 LabyrinthTempData 类"); return null; }
  const fast = labLtdFast();                              // ★先走零扫描（µs 级）
  if (fast.ok) {
    let info = "(未知)";
    try { const mdd = fast.ltd.add(0xf0).readPointer(); if (ptrOk(mdd)) info = "区域数=" + enumDict(mdd).length; } catch (e) {}
    console.log("[probe] LTD 直接取到（" + fast.how + "）= " + fast.ltd + " " + info + "　★跳过堆扫描");
    return fast.ltd;
  }
  console.log("[probe] 零扫描取 LTD 未成（" + (fast.err || "?") + "）→ 退回堆扫描");
  console.log("[probe] 堆扫描查找 LabyrinthTempData 实例（klass="+k+"）…");
  let h = k.toString(16).padStart(16,'0');
  let pat = [];
  for(let i=0;i<8;i++) pat.push(h.substr((7-i)*2,2));
  pat = pat.join(' ');
  let hits=[];
  const ranges = Process.enumerateRanges('r--');
  for(const r of ranges){
    if(r.file || r.size < 0x10000) continue; // 只扫匿名大块（GC 堆），排除文件映射（代码/库）
    try{
      const ms = Memory.scanSync(r.base, r.size, pat);
      for(const m of ms){
        const addr = m.address;
        try{ if(objIsClass(addr, k)) hits.push(addr); }catch(e){}
      }
    }catch(e){}
  }
  if(hits.length===0){ console.log("[probe] 堆扫描未找到 LabyrinthTempData 对象"); return null; }
  // 选 MapDataDictionary(@+0xF0) 非空且区域数最多的实例（排除 stale/未初始化副本）
  let best=null, bestScore=-1, bestInfo="(无有效地图)";
  for(const addr of hits){
    let score=0, info="(空)";
    try{
      const mdd = addr.add(0xf0).readPointer();
      if(ptrOk(mdd)){
        const areas = enumDict(mdd);
        score = areas.length;
        info = "区域数="+score;
      }
    }catch(e){}
    if(score>bestScore){ bestScore=score; best=addr; bestInfo=info; }
  }
  console.log("[probe] 堆扫描命中 "+hits.length+" 个，选取实例 = "+best+" ("+bestInfo+")");
  return best;
}
globalThis.findLab = findLab;

// 堆扫描查找【当前激活】的 ViewLabyrinthMap 实例（非单例 + 多实例消歧）
// 先严格校验 labyrinthTempData / partsLabyrinthMap 是真实对象（消除 klass 指针误命中），
// 再按 optional 的 expectedBid 反查 CurrentPanel.BlockId 做消歧。
// 不传 expectedBid 时完全不依赖坐标，直接取唯一有效实例（通常只有一个）。
function findView(expectedBid) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return null; }
  const vk  = findClass("ViewLabyrinthMap");
  const ltk = findClass("LabyrinthTempData");
  const pmk = findClass("PartsLabyrinthPanel");
  const cpk = findClass("LabyrinthPanelData");
  if (!vk || vk.isNull()) { console.log("[probe] 未找到 ViewLabyrinthMap 类"); return null; }
  const wantBid = (typeof expectedBid === "number") ? expectedBid : null;   // 仅作可选消歧，绝不硬编码坐标
  const h = vk.toString(16).padStart(16, '0');
  const pat = [];
  for (let i = 0; i < 8; i++) pat.push(h.substr((7 - i) * 2, 2));
  const kpat = pat.join(' ');
  // 排除 libil2cpp.so 内的 class/vtable 元数据误命中（真实实例在 GC 堆，不在 so 内）
  let libBase = ptr(0), libSize = 0;
  try { const mod = Process.findModuleByName('libil2cpp.so'); if (mod) { libBase = mod.base; libSize = mod.size; } } catch (e) {}
  const inLib = (a) => libSize > 0 && a.compare(libBase) >= 0 && a.compare(libBase.add(libSize)) < 0;
  const cands = [];
  for (const r of Process.enumerateRanges('r--').filter(x => !x.file && x.size >= 0x10000).sort((a,b)=>b.size-a.size).slice(0,3)) {
    if (r.file || r.size < 0x10000) continue;   // 只扫匿名大块（GC 堆），排除文件映射
    try {
      const ms = Memory.scanSync(r.base, r.size, kpat);
      for (const m of ms) {
        const a = m.address;
        if (inLib(a)) continue;
        try {
          if (!objIsClass(a, vk)) continue;
          const ltd = a.add(0xf8).readPointer();   // labyrinthTempData
          const pm  = a.add(0xd0).readPointer();   // partsLabyrinthMap
          if (ltd.isNull() || pm.isNull()) continue;
          // 真实实例判定：ltd 必须是 LabyrinthTempData 且 pm 必须是 PartsLabyrinthMap（dump.cs 确认的精确类名）
          const ltdName = klassNameOf(ltd);
          const pmName  = klassNameOf(pm);
          const good = (ltdName === "LabyrinthTempData" && pmName === "PartsLabyrinthMap");
          if (!good && globalThis.VERBOSE)
            console.log("  [warn] 候选 " + a + " 的 ltd/pm 类名异常: " + ltdName + " / " + pmName);
          let curBid = -1;
          try {
            const cp = ltd.add(0xd0).readPointer();   // CurrentPanel @ LTD+0xd0
            if (!cp.isNull() && klassNameOf(cp) === "LabyrinthPanelData") {
              const b = callMethod(cp, 'get_BlockId');
              if (b && !b.isNull()) curBid = b.add(0x10).readS32();
            }
          } catch (e) {}
          cands.push({ a: a, ltd: ltd, curBid: curBid, good: good, ltdName: ltdName, pmName: pmName });
        } catch (e) {}
      }
    } catch (e) {}
  }
  const goodOnes = cands.filter(c => c.good);
  if (cands.length === 0) { console.log("[probe] 未找到有效 ViewLabyrinthMap"); return null; }
  const tag = (wantBid === null) ? "任意节点" : ("节点 " + wantBid);
  console.log("[probe] 候选 " + cands.length + " 个（真实实例 " + goodOnes.length + " 个，BlockId 消歧=" + tag + "）:");
  goodOnes.forEach((c, i) => console.log("  [good " + i + "] " + c.a + "  LTD=" + c.ltd + "  CurrentPanel.BlockId=" + c.curBid));
  cands.slice(0, 15).forEach((c, i) => console.log("  [#" + i + "] " + c.a + "  good=" + c.good + "  ltdName=" + c.ltdName + "  pmName=" + c.pmName));
  // 选取优先级：真实实例∩指定BlockId > 真实实例 > 指定BlockId > 首个候选
  let best = goodOnes.find(c => c.curBid === wantBid)
          || goodOnes[0]
          || cands.find(c => c.curBid === wantBid)
          || cands[0];
  console.log("[probe] 选取实例 = " + best.a + " (good=" + best.good + ", BlockId=" + best.curBid + ")");
  return best.a;
}
globalThis.findView = findView;

// 一键导出当前迷宫地图上下文（无 hook），偏移均来自 dump.cs 确认：
//   VIEW+0xf8 -> LTD(labyrinthTempData)；VIEW+0xd0 -> PM(partsLabyrinthMap)
//   PM+0x68   -> SECTOR(sectorData / LabyrinthSectorData)；LTD+0xd0 -> CUR(CurrentPanel)
function probeMap(expectedBid) {
  const VIEW = findView(expectedBid);
  if (!VIEW) { console.log("[probe] 未找到 ViewLabyrinthMap，确认处于迷宫地图界面"); return null; }
  const LTD    = VIEW.add(0xf8).readPointer();
  const PM     = VIEW.add(0xd0).readPointer();
  const SECTOR = PM.add(0x68).readPointer();
  const CUR    = LTD.add(0xd0).readPointer();
  globalThis.VIEW = VIEW; globalThis.LTD = LTD; globalThis.PM = PM;
  globalThis.SECTOR = SECTOR; globalThis.CUR = CUR;
  console.log("[probe] VIEW   = " + VIEW);
  console.log("        LTD    = " + LTD + " (" + klassNameOf(LTD) + ")");
  console.log("        PM     = " + PM + " (" + klassNameOf(PM) + ")");
  console.log("        SECTOR = " + SECTOR + " (" + klassNameOf(SECTOR) + ")");
  console.log("        CUR    = " + CUR + " (" + klassNameOf(CUR) + ")");
  return { VIEW, LTD, PM, SECTOR, CUR };
}
globalThis.probeMap = probeMap;

// ===== 无 hook 读状态 / 打印地图 的便捷函数 =====

// 带异常检查的 int 参数方法调用：runtime_invoke 抛异常则返回 null（不再崩溃）
function safeCallInt(obj, name, v) {
  if (!g_il2cpp || !g_il2cpp.runtime_invoke) return null;
  const k = g_il2cpp.object_get_class(obj);
  const r = findMethodLike(k, name);
  if (!r) return null;
  if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
  const a = Memory.alloc(Process.pointerSize); a.writePointer(ptr(v));   // ARM64 上 runtime_invoke 按 8 字节槽读参，必须 8 字节缓冲区，否则高位垃圾把 int 当指针
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  const ret = g_il2cpp.runtime_invoke(r.m, obj, a, ex);
  if (!ex.readPointer().isNull()) return null;   // 抛出异常（如 KeyNotFoundException）→ 视为无此节点
  return ret;
}

// 读取 List<int> 元素，返回 "1, 2, 3"。blockId 一般为 10000+，优先选含合理值的偏移（0x10 / 0xc 自动判定）
function enumIntList(listPtr) {
  try {
    const lp = ptr(listPtr);
    const arr = lp.add(0x10).readPointer();   // _items (int[])
    const size = lp.add(0x18).readS32();       // _size
    if (arr.isNull() || size <= 0 || size > 4096) return "[]";
    const readAt = (baseOff) => {
      const out = [];
      for (let i = 0; i < size && i < 256; i++) out.push(arr.add(baseOff + i * 4).readS32());
      return out;
    };
    // il2cpp int[] 元素起点 = arr + 0x20（对象头0x10 + 数组头0x10）；兜底试 0x10 / 0xc
    const a20 = readAt(0x20), a10 = readAt(0x10), a0c = readAt(0xc);
    const hasBlock = (a) => a.some(v => v >= 1000 && v < 100000);
    if (hasBlock(a20)) return "[" + a20.join(", ") + "]";
    if (hasBlock(a0c)) return "[" + a0c.join(", ") + "]";
    if (hasBlock(a10)) return "[" + a10.join(", ") + "]";
    return "[" + a20.join(", ") + "]";   // 都无合理值，返回 0x20（多半就是真实 0）
  } catch (e) { return "ERR:" + e; }
}

// 读取 Dictionary<int, T> 的 key 列表：遍历 _slots，自动挑选「有效正整数最多」的 Slot 布局。
// key 偏移候选含 0x0（key 在 slot 首部）；并排除「看起来像对象指针低32位」的超大值（读到了 value 字段）。
function enumDictIntKeys(dictPtr) {
  try {
    const d = ptr(dictPtr);
    const slots = d.add(0x18).readPointer();   // _slots (Slot[])
    const count = d.add(0x20).readS32();        // _count
    if (slots.isNull() || count <= 0 || count > 16384) return "[]";
    const layouts = [
      [0x10,0x18,0x0],[0xc,0x18,0x0],[0x10,0x18,0x8],[0x10,0x20,0x0],[0xc,0x14,0x0],
      [0x10,0x18,0xc],[0x10,0x18,0x10],[0x10,0x10,0x0],[0xc,0x18,0xc],[0x10,0x18,0x14]
    ];
    let bestOut = [], bestScore = -1;
    for (const [hb, ss, ko] of layouts) {
      const out = [];
      let score = 0;
      for (let i = 0; i < count; i++) {
        const slot = slots.add(hb + i * ss);
        let key;
        try { key = slot.add(ko).readS32(); } catch (e) { key = -1; }
        if (key > 0 && key < 0x70000000) score++;   // 有效正整数（排除指针截断值）
        out.push(key);
      }
      if (score > bestScore) { bestScore = score; bestOut = out; }
    }
    return "[" + bestOut.join(", ") + "]";
  } catch (e) { return "ERR:" + e; }
}

// 取真实面板字典：LabyrinthSectorData.<panelDataDictionary>（Dictionary<int, LabyrinthPanelData>）
// 注意：LTD.<MapDataDictionary>（LTD+0xf0）是另一个字典（keys 1~5、value 为 null），不是面板表
function getMapDict() {
  try { const p = globalThis.SECTOR.add(0x10).readPointer(); return p.isNull() ? null : p; } catch (e) { return null; }
}
// 诊断：裸 dump 面板字典的内部数组，定位 key/value 真实偏移
function dumpDict() {
  const d = getMapDict();
  if (!d) { console.log("[probe] getMapDict 失败"); return; }
  console.log("[dumpDict] dict=" + d);
  console.log("  @0x10(_buckets)=" + d.add(0x10).readPointer());
  console.log("  @0x18(_entries)=" + d.add(0x18).readPointer());
  console.log("  @0x20(_count)  =" + d.add(0x20).readS32());
  console.log("  @0x24(_version)=" + d.add(0x24).readS32());
  const arr = d.add(0x18).readPointer();
  if (arr.isNull()) { console.log("  _entries 为 null"); return; }
  console.log("[dumpDict] _entries=" + arr + "  klass=" + klassNameOf(arr));
  console.log("=== _entries 原始 qword 扫描（仅标注 Panel/Labyrinth/Map/Dictionary 类指针）===");
  for (let base = 0x0; base <= 0x1f0; base += 0x10) {
    const parts = [];
    for (let j = 0; j < 2; j++) {
      const off = base + j * 8;
      try {
        const q = arr.add(off).readPointer();
        let tag = "";
        try { const cn = klassNameOf(q); if (cn.match(/Panel|Labyrinth|Map|Dictionary|List|Int32|Array/i)) tag = " [" + cn + "]"; } catch (e) {}
        parts.push(off.toString(16) + ":" + q + tag);
      } catch (e) { parts.push(off.toString(16) + ":?"); }
    }
    console.log("  " + parts.join("   "));
  }
}
globalThis.dumpDict = dumpDict;
// 诊断：raw dump 一个 List<int> 的底层数组，确认元素偏移（排查 next=[0,0]）
function rawIntList(listPtr, n) {
  n = n || 16;
  try {
    const lp = ptr(listPtr);
    const arr = lp.add(0x10).readPointer();   // _items (int[])
    const size = lp.add(0x18).readS32();       // _size
    console.log("[raw] _items=" + arr + "  _size=" + size);
    if (arr.isNull()) return;
    const a10 = [], a0c = [];
    for (let i = 0; i < n; i++) {
      try { a10.push(arr.add(0x10 + i * 4).readS32()); } catch (e) { a10.push("?"); }
      try { a0c.push(arr.add(0xc + i * 4).readS32()); } catch (e) { a0c.push("?"); }
    }
    console.log("  @0x10: " + a10.join(", "));
    console.log("  @0xc : " + a0c.join(", "));
  } catch (e) { console.log("ERR:" + e); }
}
globalThis.rawIntList = rawIntList;
// int 参数包：runtime_invoke 的 params 是 void**，值参数需指向独立存储（避免把 int 当指针解引用）
function intArg(v) {
  const params = Memory.alloc(Process.pointerSize);
  const buf = Memory.alloc(Process.pointerSize); buf.writePointer(ptr(v));
  params.writePointer(buf);
  return params;
}
// 按 blockId 从面板字典取面板（用 Dictionary.get_Item，绕开同名 lambda 的 GetLabyrinthPanelDataFromBlockId）
let _getItemMethod = null;
function getPanel(bid) {
  const d = getMapDict();
  if (!d) return null;
  if (!_getItemMethod) {
    const r = findMethodLike(g_il2cpp.object_get_class(d), "get_Item");
    if (!r) { console.log("[probe] 未在 MapDataDictionary 上找到 get_Item"); return null; }
    _getItemMethod = r.m;
  }
  if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  const ret = g_il2cpp.runtime_invoke(_getItemMethod, d, intArg(bid), ex);
  if (!ex.readPointer().isNull()) return null;
  return ret;
}
globalThis.getPanel = getPanel;
// 诊断：确认 MapDataDictionary 结构与 get_Item 返回类型
function dumpMapDict() {
  const d = getMapDict();
  if (!d) { console.log("[probe] MapDataDictionary 为空或读取失败"); return; }
  console.log("[probe] MapDataDictionary @ " + d + "  类=" + klassNameOf(d));
  console.log("[probe] get_Count = " + invokeGetCount(d));
  console.log("[probe] keys = " + enumDictIntKeys(d));
  const p = getPanel(10403);
  console.log("[probe] get_Item(10403) -> " + (p && !p.isNull() ? klassNameOf(p) : "null"));
}
globalThis.dumpMapDict = dumpMapDict;

// 探查面板到底存在哪：SECTOR / PM 的字段 + MapDataDictionary 每个 value 的真实类型
function explorePanels() {
  const LTD = globalThis.LTD, SECTOR = globalThis.SECTOR, PM = globalThis.PM, CUR = globalThis.CUR;
  if (!LTD) { console.log("[probe] 先跑 probeMap()"); return; }
  console.log("===== SECTOR (LabyrinthSectorData) 字段 =====");
  dumpFields(SECTOR);
  console.log("===== PM (PartsLabyrinthMap) 字段 =====");
  dumpFields(PM);
  console.log("===== MapDataDictionary 每个 value =====");
  const d = LTD.add(0xf0).readPointer();
  const count = invokeGetCount(d);
  console.log("  count=" + count);
  for (let k = 1; k <= count; k++) {
    const p = callMethod(d, "get_Item", intArg(k));
    if (!p || p.isNull()) { console.log("  key " + k + " -> null"); continue; }
    const cn = klassNameOf(p);
    let extra = "";
    if (cn.indexOf("Panel") >= 0 || cn.indexOf("Labyrinth") >= 0 || cn.indexOf("Map") >= 0) {
      let bid = "?"; try { bid = unboxInt(callMethod(p, "get_BlockId")); } catch (e) {}
      let nx = "?"; try { const lst = callMethod(p, "get_NextBlockIdList"); if (lst && !lst.isNull()) nx = enumIntList(lst); } catch (e) {}
      extra = "  BlockId=" + bid + "  next=" + nx;
    }
    console.log("  key " + k + " -> " + cn + extra);
  }
  console.log("===== 当前节点 CUR (BlockId=" + unboxInt(callMethod(CUR, "get_BlockId")) + ") 的 NextBlockIdList =====");
  try { const lst = callMethod(CUR, "get_NextBlockIdList"); console.log("  next=" + (lst && !lst.isNull() ? enumIntList(lst) : "null")); } catch (e) { console.log("  ERR:" + e); }
}
globalThis.explorePanels = explorePanels;

// 从面板字典直接裸读 key+value：value 可能是 LabyrinthPanelData，也可能是 List<int>（邻接表）。
// 经验布局：_entries 数组元素数据起点 hb=0x28，条目步长 0x18，key@+0x0，value@+0x8。
function readPanelDict() {
  const d = getMapDict();
  if (!d) return null;
  const slots = d.add(0x18).readPointer();   // _entries (Entry[])，指向数组对象
  const count = d.add(0x20).readS32();        // _count
  if (slots.isNull() || count <= 0 || count > 16384) return [];
  const hbs = [0x28, 0x10, 0x20, 0x0, 0x18, 0x30];
  const strides = [0x18, 0x14, 0x10];
  const kos = [0x0, 0x8, 0xc];
  const vos = [0x8, 0xc, 0x10, 0x14, 0x0];
  for (const hb of hbs) for (const st of strides) for (const ko of kos) for (const vo of vos) {
    let ok = true;
    for (let i = 0; i < count; i++) {
      const e = slots.add(hb + i * st);
      const val = e.add(vo).readPointer();
      let cn = "?"; try { cn = klassNameOf(val); } catch (e) { cn = "?"; }
      if (val.isNull() || !cn.match(/Panel|Labyrinth|Map|List|Dictionary|Int32|Array/i)) { ok = false; break; }
    }
    if (ok) {
      const out = [];
      for (let i = 0; i < count; i++) {
        const e = slots.add(hb + i * st);
        let key = -1; try { key = e.add(ko).readS32(); } catch (e) {}
        const val = e.add(vo).readPointer();
        out.push({ key, val, valClass: (()=>{try{return klassNameOf(val);}catch(e){return "?";}})() });
      }
      return out;
    }
  }
  return [];
}
globalThis.readPanelDict = readPanelDict;

// 把字典条目 value 描述成可读字符串：List 直接展开整数，面板则取 BlockId + next
function describeVal(it) {
  const cn = it.valClass || "";
  if (cn.indexOf("List") >= 0) {
    let s = "?"; try { s = enumIntList(it.val); } catch (e) { s = "ERR:" + e; }
    return cn + " " + s;
  }
  if (cn.indexOf("Panel") >= 0 || cn.indexOf("Labyrinth") >= 0 || cn.indexOf("Map") >= 0) {
    let b = "?"; try { b = unboxInt(callMethod(it.val, "get_BlockId")); } catch (e) {}
    let nx = "?"; try { const nl = callMethod(it.val, "get_NextBlockIdList"); if (nl && !nl.isNull()) nx = enumIntList(nl); } catch (e) {}
    return cn + " BlockId=" + b + " next=" + nx;
  }
  return cn;
}
globalThis.describeVal = describeVal;

// 详细打印每个字典条目：key / value 类 / 内容（需先 probeMap()）
function dumpPanels() {
  if (!globalThis.LTD) { console.log("[probe] 先跑 probeMap()"); return; }
  const list = readPanelDict();
  if (!list) { console.log("[probe] panelDataDictionary 读取失败"); return; }
  console.log("===== 面板字典（共 " + list.length + " 条）=====");
  for (const it of list) {
    console.log("  key=" + it.key + "  " + describeVal(it));
  }
}
globalThis.dumpPanels = dumpPanels;

// 打印整张迷宫地图（邻接关系）
function printMap() {
  if (!globalThis.LTD) { console.log("[probe] 先跑 probeMap()"); return; }
  const d = getMapDict();
  if (!d) { console.log("[probe] panelDataDictionary 读取失败"); return; }
  const curBid = unboxInt(callMethod(globalThis.CUR, "get_BlockId"));
  console.log("===== 迷宫地图（当前节点 BlockId=" + curBid + "）=====");
  console.log("[probe] panelDataDictionary Count = " + invokeGetCount(d));
  const list = readPanelDict();
  console.log("[probe] 共 " + list.length + " 条（来源：panelDataDictionary 直接裸读）");
  for (const it of list) {
    console.log("  key=" + it.key + " -> " + describeVal(it));
  }
}
globalThis.printMap = printMap;

// 读取 List<引用类型> 的元素指针数组（object[] 元素起点尝试多种 hb）
function readRefList(listPtr) {
  const lp = ptr(listPtr);
  const arr = lp.add(0x10).readPointer();   // _items
  const size = lp.add(0x18).readS32();       // _size
  if (arr.isNull() || size <= 0 || size > 4096) return [];
  for (const hb of [0x10, 0x20, 0x18, 0x8]) {
    try {
      const cand = [];
      for (let i = 0; i < size; i++) cand.push(arr.add(hb + i * 8).readPointer());
      let objCount = 0;
      for (const p of cand) {
        if (!p.isNull()) { let cn = "?"; try { cn = klassNameOf(p); } catch (e) { cn = "?"; } if (cn !== "?" && cn.indexOf("err") < 0) objCount++; }
      }
      if (objCount >= size * 0.6) return cand;
    } catch (e) { /* 试下一个 hb */ }
  }
  return [];
}
globalThis.readRefList = readRefList;

// 打印屏幕上可见面板（PM.visualPanelList）：每个的 BlockId 与 NextBlockIdList
function dumpVisualPanels() {
  if (!globalThis.PM) { console.log("[probe] 先跑 probeMap()"); return; }
  const listPtr = globalThis.PM.add(0x70).readPointer();
  if (listPtr.isNull()) { console.log("[probe] visualPanelList 为空"); return; }
  const size = listPtr.add(0x18).readS32();
  console.log("===== visualPanelList (count=" + size + ") =====");
  const elems = readRefList(listPtr);
  let idx = 0;
  for (const p of elems) {
    if (p.isNull()) { console.log("  [" + (idx++) + "] null"); continue; }
    let cn = "?"; try { cn = klassNameOf(p); } catch (e) {}
    let b = "?"; try { b = unboxInt(callMethod(p, "get_BlockId")); } catch (e) {}
    let nx = "?"; try { const nl = callMethod(p, "get_NextBlockIdList"); if (nl && !nl.isNull()) nx = enumIntList(nl); } catch (e) {}
    console.log("  [" + (idx++) + "] " + cn + "  BlockId=" + b + "  next=" + nx);
  }
}
globalThis.dumpVisualPanels = dumpVisualPanels;

// dump 第 idx 个可视面板（PartsLabyrinthPanel）的字段，找它包着的 LabyrinthPanelData / 标号字段
function dumpVisualPanelFields(idx) {
  if (!globalThis.PM) { console.log("[probe] 先跑 probeMap()"); return; }
  const elems = readRefList(globalThis.PM.add(0x70).readPointer());
  if (!elems.length || idx < 0 || idx >= elems.length) { console.log("[probe] 无该面板（count=" + elems.length + "）"); return; }
  const p = elems[idx];
  console.log("===== visualPanel[" + idx + "] = " + p + " (" + klassNameOf(p) + ") =====");
  dumpFields(p);
}
globalThis.dumpVisualPanelFields = dumpVisualPanelFields;

// 读当前节点的真实邻接：CUR.mapInfo.<NextBlockIdList>（LabyrinthMapInfo @ CUR+0x10，邻接 List @ +0x60）
// 这给出的是 blockId 形式的"可前往节点"，比 CUR.NextBlockIdList(= [0,0]) 更可靠
function getCurNeighbors() {
  if (!globalThis.CUR) { console.log("[probe] 先跑 probeMap()"); return; }
  const curBid = unboxInt(callMethod(globalThis.CUR, "get_BlockId"));
  let nlStr = "?";
  try {
    const nl = globalThis.CUR.add(0x18).readPointer();   // LabyrinthPanelData.<NextBlockIdList>
    nlStr = enumIntList(nl);
  } catch (e) { nlStr = "ERR:" + e; }
  console.log("[probe] 当前节点 BlockId=" + curBid + "  可前往=" + nlStr);
}
globalThis.getCurNeighbors = getCurNeighbors;

// 按关键词（数组或单字符串）过滤某实例的全部方法名（含父类，跳过 lambda/编译器生成方法）
// 用于快速定位“移动/确认/选节点”相关 API。已做全量异常保护，避免遍历到坏父类指针时 fault。
function grepMethods(inst, kws) {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const kw = (typeof kws === "string") ? [kws] : kws;
  const k = ptr(inst);
  console.log("[grepMethods] " + klassNameOf(k) + "  过滤=" + JSON.stringify(kw));
  let cur = k, shown = 0, depth = 0;
  while (cur && !cur.isNull() && depth < 10) {
    depth++;
    try {
      const iter = Memory.alloc(Process.pointerSize); iter.writePointer(ptr(0));
      while (true) {
        let m;
        try { m = g_il2cpp.class_get_methods(cur, iter); } catch (e) { m = ptr(0); }
        if (!m || m.isNull()) break;
        let s = "?";
        try { const mn = g_il2cpp.method_get_name(m); if (mn && !mn.isNull()) s = mn.readUtf8String(); } catch (e) {}
        if (s === "?" || isGeneratedMethod(m, s)) continue;
        let dc = "?";
        try { const mc = g_il2cpp.method_get_class(m); if (mc && !mc.isNull()) dc = g_il2cpp.class_get_name(mc).readUtf8String(); } catch (e) {}
        if (kw.some(function (x) { return s.indexOf(x) >= 0; })) { console.log("  " + s + "   <- decl:" + dc); shown++; }
      }
    } catch (e) {}
    try { cur = g_il2cpp.class_get_parent(cur); } catch (e) { cur = ptr(0); }
  }
  console.log("  (共 " + shown + " 个匹配)");
}
globalThis.grepMethods = grepMethods;

// （注：完整的 5 区域 + 节点类型 + 邻接 全地图由上方 dumpMap()（line ~1442）提供，这里不再重复定义）

// 显示当前地图状态 + 角色状态（需先 probeMap()）
function showState() {
  if (!globalThis.CUR) { console.log("[probe] 先跑 probeMap()"); return; }
  const curBid = unboxInt(callMethod(globalThis.CUR, "get_BlockId"));
  console.log("===== 地图状态（当前 BlockId=" + curBid + "）=====");
  dumpFields(globalThis.CUR);
  const next = callMethod(globalThis.CUR, "get_NextBlockIdList");
  console.log("可前往节点: " + (next && !next.isNull() ? enumIntList(next) : "(无)"));
  console.log("===== 角色状态 =====");
  dumpFields(globalThis.LTD);
  console.log("--- 参战角色 UnitParameterDictionary 的 key（unitId 列表）---");
  console.log(enumDictIntKeys(globalThis.LTD.add(0x30).readPointer()));
  console.log("--- OwnUnitInfoSet @0x28 ---");
  dumpFields(globalThis.LTD.add(0x28).readPointer());
  console.log("--- UnitAcquisitionDataList @0x180 ---");
  dumpFields(globalThis.LTD.add(0x180).readPointer());
}
globalThis.showState = showState;

// 诊断：列出每个真实 ViewLabyrinthMap 候选的 labyrinthTempData / partsLabyrinthMap 实际类名与当前节点，
// 用于确认字段偏移是否正确（不过滤，纯观察）。找到 ltd=LabyrinthTempData、pm=PartsLabyrinthPanel、
// BlockId=当前节点的那一行即为激活视图。
function diagView(limit) {
  const vk = findClass("ViewLabyrinthMap");
  if (!vk || vk.isNull()) { console.log("[probe] 未找到 ViewLabyrinthMap 类"); return; }
  const h = vk.toString(16).padStart(16, '0');
  const pat = [];
  for (let i = 0; i < 8; i++) pat.push(h.substr((7 - i) * 2, 2));
  const kpat = pat.join(' ');
  const lim = (typeof limit === "number") ? limit : 30;
  let n = 0;
  for (const r of Process.enumerateRanges('r--').filter(x => !x.file && x.size >= 0x10000).sort((a,b)=>b.size-a.size).slice(0,3)) {
    if (r.file || r.size < 0x10000) continue;
    try {
      const ms = Memory.scanSync(r.base, r.size, kpat);
      for (const m of ms) {
        const a = m.address;
        if (inLib(a)) continue;
        try {
          if (!objIsClass(a, vk)) continue;
          const ltd = a.add(0xf8).readPointer();
          const pm  = a.add(0xd0).readPointer();
          let cp = ptr(0), bid = "?";
          try { cp = ltd.add(0xd0).readPointer(); } catch (e) {}
          try { if (!cp.isNull()) { const b = callMethod(cp, "get_BlockId"); if (b && !b.isNull()) bid = b.add(0x10).readS32(); } } catch (e) {}
          console.log("[cand] " + a + "  ltd=" + ltd + "(" + klassNameOf(ltd) + ")  pm=" + pm + "(" + klassNameOf(pm) + ")  BlockId=" + bid);
          n++; if (n >= lim) return;
        } catch (e) {}
      }
    } catch (e) {}
  }
  console.log("[probe] 共 " + n + " 个候选（已显示前 " + lim + "）");
}
globalThis.diagView = diagView;

// 便捷封装：以 int 为参数的方法调用 / 拆箱 int 返回值（runtime_invoke 的 int 返回是装箱对象）
globalThis.callInt = function (o, n, v) { return safeCallInt(o, n, v); };
globalThis.unboxInt = function (p) { try { if (!p || p.isNull()) return null; return p.add(0x10).readS32(); } catch (e) { return null; } };

function main() {
  const klass = findClass(TARGET_CLASS);
  if (klass.isNull()) { console.log("[probe] 未找到类 " + TARGET_CLASS); return; }
  g_klass = klass;
  console.log("[probe] 已找到 " + TARGET_CLASS + "，开始抓取实例…");

  // 先试自动路径（静态字段；runtime_invoke 路径会崩，已弃用）
  let inst = tryStaticInstance(klass);
  if (inst) { g_inst = inst; dumpState(); }

  // 钩子兜底（重进黎明界即触发）
  hookCapture(klass);

  if (!g_inst) {
    console.log("[probe] 自动路径未命中：请退出并重进黎明界一次，或 REPL 输入 callInit()");
  }

  let lastDump = buildDumpString(); // 基线，避免启动后重复打印
  setInterval(() => {
    // 每周期重新确认静态字段里的单例实例（进黎明界可能新建/替换实例），跟踪最新
    const live = tryStaticInstance(klass);
    if (live && (!g_inst || !live.equals(g_inst))) {
      g_inst = live;
      console.log("[probe] 实例已刷新为 " + live);
    }
    if (!g_inst) return;
    // 仅在字段变化时打印，避免无变化时刷屏
    const s = buildDumpString();
    if (s !== lastDump) {
      lastDump = s;
      console.log("[probe] " + s);
    }
  }, 3000);
}

const timer = setInterval(() => {
  if (initIl2Cpp()) { clearInterval(timer); console.log("[probe] il2cpp 就绪（交互模式：已跳过 main()/hookLab()，无自动钩子/扫描）"); }
}, 250);
//



globalThis.diagKlass = function() {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return; }
  const ltk = findClass("LabyrinthTempData");
  const pmk = findClass("PartsLabyrinthMap");
  console.log("[diag] LTD klass=" + ltk + "  PM klass=" + pmk);
  if (!ltk || ltk.isNull()) { console.log("[diag] 未找到 LabyrinthTempData 类，findClass 可能有问题"); return; }
  function scanKlass(kls) {
    const out = [];
    for (const r of Process.enumerateRanges("r--").filter(x => !x.file && x.size >= 0x1000).sort((a,b)=>b.size-a.size)) {
      if (r.file || r.size < 0x1000) continue;
      try {
        const kb = kls.toString(16).padStart(16, "0").match(/../g).reverse().join(" ");
        const ms = Memory.scanSync(r.base, r.size, kb);
        for (const m of ms) {
          const a = m.address;
          try { if (objIsClass(a, kls)) out.push(a); } catch (e) {}
        }
      } catch (e) {}
    }
    return out;
  }
  const lts = scanKlass(ltk);
  console.log("[diag] LabyrinthTempData 实例数=" + lts.length + " -> " + lts.join(", "));
  if (lts.length > 0) {
    const o = lts[0];
    console.log("[diag] LTD[0]=" + o);
    try { console.log("  BlockId(int@0x44)=" + o.add(0x44).readS32()); } catch(e){ console.log("  BlockId err"); }
    try { const cp = o.add(0xD0).readPointer(); console.log("  CurrentPanel@0xD0=" + cp + "  null?" + cp.isNull()); } catch(e){ console.log("  CurrentPanel err"); }
    try { const sd = o.add(0xD8).readPointer(); console.log("  SectorData@0xD8=" + sd + "  null?" + sd.isNull()); } catch(e){ console.log("  SectorData err"); }
  }
  const pms = scanKlass(pmk);
  console.log("[diag] PartsLabyrinthMap 实例数=" + pms.length + " -> " + pms.join(", "));
};

globalThis.findMap = function() {
  if (!g_il2cpp) { console.log("[probe] il2cpp 未就绪"); return null; }
  const ltk = findClass("LabyrinthTempData");
  const cpk = findClass("LabyrinthPanelData");
  if (!ltk || ltk.isNull()) { console.log("[findMap] LabyrinthTempData 类未找到"); return null; }
  if (!cpk || cpk.isNull()) { console.log("[findMap] LabyrinthPanelData 类未找到"); return null; }
  let insts = [];
  const fast = labLtdFast();                              // ★先走零扫描（缓存 → ViewLabyrinthMap+0xF8）
  if (fast.ok) {
    insts = [fast.ltd];
    if (JS_VERBOSE) console.log("[findMap] LTD 直接取到（" + fast.how + "）= " + fast.ltd + "　★跳过堆扫描");
  } else {
    console.log("[findMap] 零扫描取 LTD 未成（" + (fast.err || "?") + "）→ 退回堆扫描");
    for (const r of Process.enumerateRanges("r--").filter(x => !x.file && x.size >= 0x1000).sort((a,b)=>b.size-a.size)) {
      if (r.file || r.size < 0x1000) continue;
      try {
        const kb = ltk.toString(16).padStart(16, "0").match(/../g).reverse().join(" ");
        const ms = Memory.scanSync(r.base, r.size, kb);
        for (const m of ms) {
          const a = m.address;
          try { if (objIsClass(a, ltk)) insts.push(a); } catch(e){}
        }
      } catch(e){}
    }
    console.log("[findMap] LabyrinthTempData 实例数=" + insts.length);
  }
  const cand = [];
  for (const o of insts) {
    let cp = null, cpNull = true;
    try { cp = o.add(0xD0).readPointer(); cpNull = cp.isNull(); } catch(e){}
    if (cpNull) continue;
    let isPanel = false;
    try { isPanel = objIsClass(cp, cpk); } catch(e){}
    if (!isPanel) continue;
    let bid = 0;
    try { bid = o.add(0x44).readS32(); } catch(e){}
    let next = [];
    try {
      const list = cp.add(0x18).readPointer();
      const size = list.add(0x18).readS32();
      const items = list.add(0x10).readPointer();
      if (size > 0 && size < 64) { for (let i=0;i<size;i++) next.push(items.add(0x20 + i*4).readS32()); }
    } catch(e){}
    cand.push({o:o, cp:cp, bid:bid, next:next});
  }
  // ★这一行在"换区途中读空"时会**每次调用**都打（一秒钟好几行）→ 默认关；
  //   真要查为什么读不到，把 JS_VERBOSE 改 true。
  if (JS_VERBOSE) console.log("[findMap] 有效活跃实例(CP 为 LabyrinthPanelData) 数=" + cand.length);
  for (const c of cand) {
    console.log("[findMap] LTD=" + c.o + "  BlockId=" + c.bid + "  CurrentPanel=" + c.cp + "  NextBlockIdList=[" + c.next.join(", ") + "]");
  }
  return cand;
};



function readListInt(listObj) {
  const out = [];
  if (!listObj || listObj.isNull()) return out;
  try {
    const items = listObj.add(0x10).readPointer();
    if (items.isNull()) return out;
    const len = items.add(0x18).readS32();      // ★数组 length 在 +0x18（原来读 +0x10 是 bounds 指针）
    if (len < 0 || len > 100000) return out;
    for (let i = 0; i < len; i++) out.push(items.add(0x20 + i*4).readS32());
  } catch(e){}
  return out;
}
// （已移除"直接改内存版" moveTo：直接写 LTD 的 BlockId/CurrentPanel 不合规范、游戏不认）
// （已清理）点面板版 moveTo（onClickPanel + b__0 直调）会撞 ART 断言，已被 moveThread/clickNodeThread 取代。

// （已清理）diagCrash —— 旧的一次性崩溃诊断（含 Process.setExceptionHandler），已移除。
function _listCount(l) {
  const exc = Memory.alloc(8); exc.writePointer(ptr(0));
  const c = g_il2cpp.object_get_class(l);
  const m = findMethodLike(c, "get_Count");
  if (!m) return -1;
  const r = g_il2cpp.runtime_invoke(m.m, l, ptr(0), exc);
  if (!exc.readPointer().isNull()) return -1;
  return r.add(0x10).readS32();
}
function _listItem(l, i) {
  const exc = Memory.alloc(8); exc.writePointer(ptr(0));
  const c = g_il2cpp.object_get_class(l);
  const m = findMethodLike(c, "get_Item");
  if (!m) return ptr(0);
  const ib = Memory.alloc(4); ib.writeInt(i);
  const pa = Memory.alloc(8); pa.writePointer(ib);
  const r = g_il2cpp.runtime_invoke(m.m, l, pa, exc);
  if (!exc.readPointer().isNull()) return ptr(0);
  return r;
}
function _getBlockId(pd) {
  const exc = Memory.alloc(8); exc.writePointer(ptr(0));
  const c = g_il2cpp.object_get_class(pd);
  const m = findMethodLike(c, "get_BlockId");
  if (!m) return -1;
  const r = g_il2cpp.runtime_invoke(m.m, pd, ptr(0), exc);
  if (!exc.readPointer().isNull()) return -1;
  return r.add(0x10).readS32();
}
// （已清理）_callMove —— 旧的"直接调 AddLabyrinthMovePostParam 发包"路径（绕过 UI 事件链），已移除。

// ---------- 走真实"确认"路径（触发 OK 处理器 b__0，等价点确认按钮） ----------
// 嵌套类全名分隔符可能是 . / / +，用后缀粗筛；但 <>c__DisplayClass26_0 在多个外层类里都可能存在，
// 所以进一步要求该类自身有方法名含 "openSelectConfirm"——只有 ViewLabyrinthMap 的那个闭包类才满足
function findClassBySuffix(suffix) {
  if (!g_il2cpp) return null;
  const domain = g_il2cpp.domain_get();
  const cnt = Memory.alloc(8);
  const asms = g_il2cpp.get_assemblies(domain, cnt);
  const nAsm = cnt.readU64().toNumber();
  for (let i = 0; i < nAsm; i++) {
    const asm = asms.add(i * Process.pointerSize).readPointer();
    const image = g_il2cpp.assembly_get_image(asm);
    if (image.isNull()) continue;
    const nCls = g_il2cpp.image_class_count(image);
    for (let j = 0; j < nCls; j++) {
      const klass = g_il2cpp.image_get_class(image, j);
      if (klass.isNull()) continue;
      const nmPtr = g_il2cpp.class_get_name(klass);
      if (nmPtr.isNull()) continue;
      const nm = nmPtr.readUtf8String();
      if (!nm || !nm.endsWith(suffix)) continue;
      // class_get_methods 是迭代器：每次返回单个方法、把遍历状态写入 iter，返回 null 结束
      const iter = Memory.alloc(Process.pointerSize);
      iter.writePointer(ptr(0));
      let ok = false;
      while (true) {
        const mm = g_il2cpp.class_get_methods(klass, iter);
        if (mm.isNull()) break;
        const sPtr = g_il2cpp.method_get_name(mm);
        if (!sPtr.isNull()) {
          const s = sPtr.readUtf8String();
          if (s && s.indexOf("openSelectConfirm") >= 0) { ok = true; break; }
        }
      }
      if (ok) return klass;  // 只有自含 openSelectConfirm 方法的闭包类（即 ViewLabyrinthMap 的）才认
    }
  }
  return null;
}
// 确认 OK 处理器：ViewLabyrinthMap.<>c__DisplayClass26_0.<openSelectConfirm>b__0
function getB0() {
  const cls = findClassBySuffix("<>c__DisplayClass26_0");
  if (!cls || cls.isNull()) { console.log("[getB0] 未找到 ViewLabyrinthMap 的 <>c__DisplayClass26_0"); return null; }
  console.log("[getB0] cls=" + cls + " name=" + g_il2cpp.class_get_name(cls).readUtf8String());
  let m = findMethodAny(cls, "<openSelectConfirm>b__0"); // 优先用 findMethodAny（b__0 是编译器生成方法）
  if (!m) {
    // 兜底：用迭代器在该类里按子串定位（防方法名编码差异）
    const iter = Memory.alloc(Process.pointerSize);
    iter.writePointer(ptr(0));
    while (true) {
      const mm = g_il2cpp.class_get_methods(cls, iter);
      if (mm.isNull()) break;
      const sPtr = g_il2cpp.method_get_name(mm);
      if (!sPtr.isNull()) {
        const s = sPtr.readUtf8String();
        if (s && s.indexOf("openSelectConfirm") >= 0) { m = mm; break; }
      }
    }
    if (!m) {
      console.log("[getB0] 未找到 <openSelectConfirm>b__0，该类方法列表:");
      const iter2 = Memory.alloc(Process.pointerSize); iter2.writePointer(ptr(0));
      while (true) {
        const mm = g_il2cpp.class_get_methods(cls, iter2);
        if (mm.isNull()) break;
        const sp = g_il2cpp.method_get_name(mm);
        if (!sp.isNull()) console.log("   " + sp.readUtf8String());
      }
      return null;
    }
  }
  return { cls, m };
}
// 在正确 attach 到 ART 运行时的上下文中执行 fn。
// 本构建实测：il2cpp_thread_attach 只登记 il2cpp 线程表、未把 art::Thread* 写进 gs:[0xe0]，
// 故托管代码里的 monitor 锁会 SEGV(libart access-violation)。优先走 Java.perform
// （JNI AttachCurrentThread，真正建立 ART 线程上下文），失败则退回 il2cpp_thread_attach，最后兜底直接执行。
function withArt(fn) {
  try {
    if (typeof Java !== 'undefined' && Java.available) return Java.perform(fn);
  } catch (e) {
    // debug 版 ART 的 suspend 检查点 brk 会被 Java 桥报成 "breakpoint triggered"，但操作已完成，属良性，吞掉
    if (String(e).indexOf('breakpoint triggered') >= 0) { console.log("[withArt] 忽略良性 breakpoint triggered（ART 检查点；操作已完成）"); return; }
    console.log("[withArt] Java.perform 失败（退回 il2cpp_thread_attach）: " + e);
  }
  if (g_il2cpp && g_il2cpp.thread_attach) {
    try { g_il2cpp.thread_attach(g_il2cpp.domain_get()); } catch (e) {}
  }
  return fn();
}
globalThis.withArt = withArt;

// 在"游戏自己的主线程"上执行 fn：主线程是完整 ART 线程，suspend 检查点 brk 会被 ART 正常接管，
// 不会像 Java.perform 那样把我们调用的游戏逻辑中断在半途。fn 异步执行（投递到主线程 looper）。
function withMain(fn) {
  try {
    if (typeof Java !== 'undefined' && Java.available && Java.scheduleOnMainThread) {
      Java.scheduleOnMainThread(fn);
      return true;
    }
  } catch (e) { console.log("[withMain] scheduleOnMainThread 失败: " + e); }
  return false;
}
globalThis.withMain = withMain;

// 把指针按小端拆成 8 字节扫描模板（Memory.scanSync 用空格分隔的 hex 字节）
function _ptrPattern(p) {
  return p.toString(16).padStart(16, "0").match(/../g).reverse().join(" ");
}
// 在堆里定位游戏真实创建的 <>c__DisplayClass26_0 实例：
// 其字段 <>4__this@0x10 == vm，_nextPanelData@0x18 == foundData（由 openSelectConfirm new 出来）。
// 注意：该实例的 <>9__1@0x20（Action<LabyrinthMoveReceiveParam> 回调）往往为 null（游戏只在确认按钮真正被点时才填），
// 故命中后需在 Step2 里补填合法委托，否则服务器回包会崩。
function _findDisplayClass26(vm, foundData, cls) {
  const pat = _ptrPattern(foundData);
  const ranges = Process.enumerateRanges("rw-").concat(Process.enumerateRanges("r--"))
    .filter(x => !x.file && x.size >= 0x1000).sort((a, b) => b.size - a.size);
  for (const r of ranges) {
    try {
      const hits = Memory.scanSync(r.base, r.size, pat);
      for (const h of hits) {
        const obj = h.address.sub(0x18); // _nextPanelData 在 +0x18
        try {
          if (!obj.readPointer().equals(cls)) continue;
          if (!obj.add(0x10).readPointer().equals(vm)) continue;
          if (!obj.add(0x18).readPointer().equals(foundData)) continue;
          return obj;
        } catch (e) {}
      }
    } catch (e) {}
  }
  return null;
}
// （已清理）旧的"手搓委托 + 直调 b__0"整条路径，已被 confirmThread（在游戏线程触发 b__0）取代，故移除：
//   _callOpenSelectConfirm / _delegateKlass（依赖被裁剪的反射 API：field_get_type/type_get_class 等）
//   _makeMoveCallback / _invokeConfirmB0 / confirmNow / _callMove

// 解 il2cpp 跳板：methodPointer 常是一段 `jmp rel32`(直接) 或 `jmp [rip+disp]`(间接) 跳板，Frida 无法在 jmp 上插桩。
// 追到真实函数体（同模块内的 jmp 目标）再返回；支持直接/间接两种跳板，最多追 8 级防止死循环。
function _resolveThunk(fp) {
  try {
    let p = fp;
    for (let i = 0; i < 8; i++) {
      const ins = Instruction.parse(p);
      if (!ins) break;
      const s = ins.toString();
      const op = ins.operands[0];
      if (op && s.indexOf('jmp') === 0) {
        let t = null;
        if (op.type === 'imm') {
          t = ptr(op.value);
        } else if (op.type === 'mem') {
          try { t = ptr(op.value).readPointer(); } catch (e) {}
        }
        if (t && !t.isNull()) {
          const cm = Process.findModuleByAddress(p);
          const tm = Process.findModuleByAddress(t);
          if (cm && tm && cm.path === tm.path) { p = t; continue; }
        }
      }
      break;
    }
    return p;
  } catch (e) { return fp; }
}

let g_freshClosure = null;
let g_dispCtorHooked = false;
let g_lastLoc = null;            // 上次 step1 的定位结果（供 step2 兜底扫描）
let g_lastEnterId = null;        // hookMove 观测到的真实移动 enterId（诊断用）
let g_lastCallback = null;       // hookMove 观测到的真实回调委托（诊断用）
let g_vm = null;                 // 缓存的活跃 ViewLabyrinthMap 实例（labNodes 用）

// （已清理）hookDisplayClassCtor —— 钩闭包构造器的旧方案（会撞跳板 unable-to-intercept），已移除。

// 定位当前活跃 VM 与目标 PanelData
function _moveLocate(targetBlockId) {
  if (!g_il2cpp || !g_il2cpp.runtime_invoke) { console.log("[move] 需要 runtime_invoke 路径"); return null; }
  const ltdArr = findMap();
  if (!ltdArr || !ltdArr.length) { console.log("[move] 未找到活跃 LTD"); return null; }
  const ltd = ltdArr[0];
  const bidBefore = ltd.o.add(0x44).readS32();
  const vmk = findClass("ViewLabyrinthMap");
  if (!vmk || vmk.isNull()) { console.log("[move] ViewLabyrinthMap 类未找到"); return null; }
  const ppk = findClass("PartsLabyrinthPanel");
  if (!ppk || ppk.isNull()) { console.log("[move] PartsLabyrinthPanel 类未找到"); return null; }
  let vm = null;
  for (const r of Process.enumerateRanges("r--").filter(x=>!x.file&&x.size>=0x1000).sort((a,b)=>b.size-a.size)) {
    if (r.file||r.size<0x1000) continue;
    try {
      const kb = vmk.toString(16).padStart(16,"0").match(/../g).reverse().join(" ");
      const ms = Memory.scanSync(r.base,r.size,kb);
      for (const m of ms) { const a=m.address; try {
        if (!objIsClass(a,vmk)) continue;
        const ld=a.add(0xF8).readPointer();
        const parts=a.add(0xD0).readPointer();
        if (!ld.isNull()&&ld.equals(ltd.o)&&!parts.isNull()) vm=a;
      } catch(e){} }
    } catch(e){}
    if (vm) break;
  }
  if (!vm) { console.log("[move] 未找到已接好 parts 的活跃 VM"); return null; }
  console.log("[move] 活跃 VM=" + vm);
  const partsMap = vm.add(0xD0).readPointer();
  const listObj = partsMap.add(0x70).readPointer();
  if (listObj.isNull()) { console.log("[move] visualPanelList 为 null"); return null; }
  const count = _listCount(listObj);
  if (count < 0) { console.log("[move] 无法读取 visualPanelList 长度"); return null; }
  console.log("[move] 可视面板数=" + count);
  let found = null, foundData = null;
  for (let i=0;i<count;i++) {
    const panel = _listItem(listObj, i);
    if (panel.isNull()) continue;
    const pd = panel.add(0x70).readPointer();
    if (pd.isNull()) continue;
    const bid = _getBlockId(pd);
    if (bid === targetBlockId) { found = panel; foundData = pd; break; }
  }
  if (!found) { console.log("[move] 未在可视面板列表中找到 blockId=" + targetBlockId + " (可能不在可走范围/尚未刷新)"); return null; }
  console.log("[move] 命中可视面板=" + found + "  PanelData=" + foundData);
  return { vm, foundData, bidBefore, panel: found };
}

// （已清理）diagNode / clickNode(raw) —— 探查与"原始线程"点击的临时工具，已移除；保留 clickNodeThread。

// ============================================================================
// 【旧方案 · 手动工具】runOnGameThread / clickNodeThread / confirmThread / moveThread
// 走的是"钩 Update → 在游戏线程里 runtime_invoke 调游戏内部方法"的路子。
// 它能用（游戏线程 ART/il2cpp 上下文完整，不会野指针、不会 breakpoint triggered），但：
//   1) 会临时挂 Interceptor 钩子 → 有被反 Frida 检测的风险（弱检测，最坏闪退，不封号）；
//   2) 本质是"替游戏调用内部方法"，不如真实输入干净。
// ★ 自动流程 pcr_auto.py 已改为【真实输入注入 adb shell input tap】，全程零钩子，不使用本段。
//   本段仅保留作手动调试 / 离线工具。
// ============================================================================

// 通用：在"游戏自己的主线程"上执行 fn。挂一个每帧调用的方法(Update)，在其 onEnter(此刻正运行在游戏线程、
// ART/il2cpp 上下文完整)里调用 fn，执行后自动卸载钩子。既不用 Java.perform(躲开 Frida Java 桥)，ART 状态又正确(躲开野指针)。
function runOnGameThread(fn) {
  // 借"每帧执行的主线程方法"运行 fn。候选按"是否在所有界面都跑"排序；
  // 挂上后 1s 内没触发就自动换下一个候选，避免"挂在迷宫专用 Update 上、在其它界面永不触发"。
  const cands = [["UICamera", "Update"], ["ViewManager", "Update"], ["PartsLabyrinthMap", "Update"], ["ViewLabyrinthMap", "Update"]];
  let idx = 0;
  function tryNext() {
    if (idx >= cands.length) { console.log("[gameThread] 候选 Update 均未触发"); return; }
    const c = cands[idx++];
    const ck = findClass(c[0]);
    if (!ck || ck.isNull()) return tryNext();
    const u = findMethodAny(ck, c[1]);
    if (!u || u.isNull()) return tryNext();
    const rawM = (u && u.m) ? u.m : u;
    let fp; try { fp = _resolveThunk(methodPtr(rawM)); } catch (e) { return tryNext(); }
    if (!fp || fp.isNull()) return tryNext();
    let done = false, timer = null, listener = null;
    try {
      listener = Interceptor.attach(fp, {
        onEnter() {
          if (done) return;
          done = true;
          if (timer) { clearTimeout(timer); timer = null; }
          console.log("[gameThread][游戏线程] 命中 " + c[0] + "." + c[1] + "，执行任务");
          try { fn(); } catch (e) { console.log("[gameThread] 错误: " + e); }
          setTimeout(function () { try { listener.detach(); } catch (e) {} }, 0);
        }
      });
    } catch (e) { console.log("[gameThread] 挂钩 " + c[0] + "." + c[1] + " 失败: " + e); return tryNext(); }
    console.log("[gameThread] 已在 " + c[0] + "." + c[1] + " 挂钩，等它触发…");
    timer = setTimeout(function () {
      if (done) return;
      console.log("[gameThread] " + c[0] + "." + c[1] + " 1s 未触发，换下一个候选");
      try { listener.detach(); } catch (e) {}
      tryNext();
    }, 1000);
  }
  tryNext();
  return true;
}

// 第一步：点节点（真人路径 onPanelButtonClick），弹出"移动目标确认"框。
globalThis.clickNodeThread = function(targetBlockId) {
  const loc = _moveLocate(targetBlockId);
  if (!loc || !loc.panel || loc.panel.isNull()) { console.log("[clickNodeThread] 未定位到节点面板"); return; }
  g_lastLoc = loc;
  const k = g_il2cpp.object_get_class(loc.panel);
  const mAny = findMethodAny(k, "onPanelButtonClick");
  const m = (mAny && mAny.m) ? mAny.m : mAny;
  if (!m || m.isNull()) { console.log("[clickNodeThread] 未找到 onPanelButtonClick"); return; }
  runOnGameThread(function () {
    console.log("[clickNodeThread] 触发 onPanelButtonClick(panel=" + loc.panel + ")");
    const ex = Memory.alloc(8); ex.writePointer(ptr(0));
    g_il2cpp.runtime_invoke(m, loc.panel, ptr(0), ex);
    console.log("[clickNodeThread][游戏线程] 已执行，异常=" + ex.readPointer());
  });
};

// 第二步：点"确认"按钮 —— 调用本次确认框闭包 <>c__DisplayClass26_0 的 b__0（openSelectConfirm 里挂在确认按钮上的回调）。
globalThis.confirmThread = function() {
  const loc = g_lastLoc;
  if (!loc) { console.log("[confirmThread] 请先 clickNodeThread(目标)"); return; }
  const b0 = getB0();
  if (!b0) { console.log("[confirmThread] getB0 失败"); return; }
  let t = (g_freshClosure && !g_freshClosure.isNull()) ? g_freshClosure : _findDisplayClass26(loc.vm, loc.foundData, b0.cls);
  if (!t || t.isNull()) { console.log("[confirmThread] 找不到本次确认框闭包（先 clickNodeThread 弹出确认框）"); return; }
  g_freshClosure = t;
  const m = (b0 && b0.m) ? b0.m : b0;
  console.log("[confirmThread] 将调用 b__0(this=" + t + ") —— 等价真人点“确认”");
  runOnGameThread(function () {
    const ex = Memory.alloc(8); ex.writePointer(ptr(0));
    g_il2cpp.runtime_invoke(m, t, ptr(0), ex);
    console.log("[confirmThread][游戏线程] b__0 已执行，异常=" + ex.readPointer());
  });
};

// 一步到位：点节点 -> 等确认框出现 -> 点确认（均在游戏线程执行）。
globalThis.moveThread = function(targetBlockId) {
  clickNodeThread(targetBlockId);
  setTimeout(function () { console.log("[moveThread] 800ms 后触发确认"); confirmThread(); }, 800);
};

// ===================== 真实触摸注入：第一步·求屏幕坐标 =====================
// 取方法指针（兼容 findMethodAny 返回原始指针 / findMethodLike 返回 {m}）
function _mInfo(klass, name) { const m = findMethodAny(klass, name); return m ? ((m && m.m) ? m.m : m) : null; }
// 打印疑似 boxed Vector3 的返回值，尝试 +0x00 / +0x10 两种布局
function _dumpVec3(label, p) {
  if (!p || p.isNull()) { console.log("[pos] " + label + " = null"); return; }
  const f = (off) => { try { return p.add(off).readFloat(); } catch (e) { return NaN; } };
  console.log("[pos] " + label + "  @+0x00=(" + f(0) + "," + f(4) + "," + f(8) + ")  @+0x10=(" + f(0x10) + "," + f(0x14) + "," + f(0x18) + ")");
}

// 诊断：把目标节点面板的世界坐标换算成屏幕坐标（全部在游戏线程执行引擎调用）
globalThis.nodeScreenPos = function(targetBlockId) {
  const loc = _moveLocate(targetBlockId);
  if (!loc || !loc.panel) { console.log("[pos] 未定位到节点"); return; }
  runOnGameThread(function () {
    try {
      const ex = Memory.alloc(8); ex.writePointer(ptr(0));
      // 1) panel.get_transform()
      const mTr = _mInfo(g_il2cpp.object_get_class(loc.panel), "get_transform");
      if (!mTr) { console.log("[pos] 未找到 get_transform"); return; }
      const tr = g_il2cpp.runtime_invoke(mTr, loc.panel, ptr(0), ex);
      console.log("[pos] transform=" + tr + "  exc=" + ex.readPointer());
      if (tr.isNull()) return;
      // 2) transform.get_position()  (Vector3)
      ex.writePointer(ptr(0));
      const mPos = _mInfo(g_il2cpp.object_get_class(tr), "get_position");
      if (!mPos) { console.log("[pos] 未找到 get_position"); return; }
      const wp = g_il2cpp.runtime_invoke(mPos, tr, ptr(0), ex);
      console.log("[pos] get_position 返回=" + wp + "  exc=" + ex.readPointer());
      _dumpVec3("world(local?)", wp);
      // 3) UICamera.mainCamera（NGUI UI 相机）
      ex.writePointer(ptr(0));
      const ucK = findClass("UICamera");
      const mCam = (ucK && !ucK.isNull()) ? _mInfo(ucK, "get_mainCamera") : null;
      const cam = mCam ? g_il2cpp.runtime_invoke(mCam, ptr(0), ptr(0), ex) : ptr(0);
      console.log("[pos] UICamera.mainCamera=" + cam + "  exc=" + ex.readPointer());
      if (cam.isNull()) { console.log("[pos] 无 UI 相机（需另找相机），暂停"); return; }
      // 相机像素尺寸（用于把统一屏幕坐标换算成像素/Android 顶点）
      try {
        const ck = g_il2cpp.object_get_class(cam);
        const mPH = _mInfo(ck, "get_pixelHeight"), mPW = _mInfo(ck, "get_pixelWidth");
        ex.writePointer(ptr(0)); const ph = mPH ? g_il2cpp.runtime_invoke(mPH, cam, ptr(0), ex) : ptr(0);
        ex.writePointer(ptr(0)); const pw = mPW ? g_il2cpp.runtime_invoke(mPW, cam, ptr(0), ex) : ptr(0);
        console.log("[pos] camera pixel = " + (pw.isNull() ? "?" : pw.add(0x10).readS32()) + " x " + (ph.isNull() ? "?" : ph.add(0x10).readS32()));
      } catch (e) {}
      // 4) RectTransformUtility.WorldToScreenPoint(Camera, Vector3) -> Vector2（静态，无重载歧义）
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      if (!mW2S) { console.log("[pos] 未找到 RectTransformUtility.WorldToScreenPoint"); return; }
      const vec = Memory.alloc(16);
      vec.writeFloat(wp.add(0x10).readFloat()); vec.add(4).writeFloat(wp.add(0x14).readFloat()); vec.add(8).writeFloat(wp.add(0x18).readFloat());
      const params = Memory.alloc(16); params.writePointer(cam); params.add(8).writePointer(vec);
      ex.writePointer(ptr(0));
      const sp = g_il2cpp.runtime_invoke(mW2S, ptr(0), params, ex);   // 静态：obj=null
      console.log("[pos] WorldToScreenPoint 返回=" + sp + "  exc=" + ex.readPointer());
      if (!sp.isNull()) console.log("[pos] 屏幕坐标(unity, 左下原点) x=" + sp.add(0x10).readFloat() + " y=" + sp.add(0x14).readFloat());
    } catch (e) { console.log("[pos] 错误: " + e); }
  });
};

// ---- 通用小工具（均需在游戏线程内调用）----
function _inv(obj, name, params) {
  const m = _mInfo(g_il2cpp.object_get_class(obj), name);
  if (!m) return ptr(0);
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  return g_il2cpp.runtime_invoke(m, obj, params ? params : ptr(0), ex);
}
function _str(s) {
  try { if (!s || s.isNull()) return "null"; const n = s.add(0x10).readS32(); if (n < 0 || n > 400) return "?"; return s.add(0x14).readUtf16String(n); } catch (e) { return "?"; }
}
// 单行化：UI 标签常带换行（如角色名「美里\n（夏日）」）→ 折叠成空格，日志才不会断行
function _str1(s) {
  const t = _str(s);
  return (t === "null" || t === "?") ? t : t.replace(/[\r\n\t]+/g, " ").replace(/\s{2,}/g, " ").trim();
}
function _uiCamera() {
  const ucK = findClass("UICamera");
  const m = (ucK && !ucK.isNull()) ? _mInfo(ucK, "get_mainCamera") : null;
  if (!m) return ptr(0);
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  return g_il2cpp.runtime_invoke(m, ptr(0), ptr(0), ex);
}
function _screenOf(obj, cam, mW2S) {
  try {
    const tr = _inv(obj, "get_transform");
    if (tr.isNull()) return null;
    const wp = _inv(tr, "get_position");
    if (wp.isNull()) return null;
    const vec = Memory.alloc(16);
    vec.writeFloat(wp.add(0x10).readFloat()); vec.add(4).writeFloat(wp.add(0x14).readFloat()); vec.add(8).writeFloat(wp.add(0x18).readFloat());
    const params = Memory.alloc(16); params.writePointer(cam); params.add(8).writePointer(vec);
    const ex = Memory.alloc(8); ex.writePointer(ptr(0));
    const sp = g_il2cpp.runtime_invoke(mW2S, ptr(0), params, ex);
    if (sp.isNull()) return null;
    return { x: sp.add(0x10).readFloat(), y: sp.add(0x14).readFloat() };
  } catch (e) { return null; }
}

// 列出所有 CustomUIButton 的 名称 + 屏幕坐标（用于定位确认框的"确认/取消"按钮）
globalThis.listButtons = function() {
  const ck = findClass("CustomUIButton");
  if (!ck || ck.isNull()) { console.log("[btn] 未找到 CustomUIButton"); return; }
  const pat = _ptrPattern(ck);
  const found = [];
  const ranges = Process.enumerateRanges("rw-").concat(Process.enumerateRanges("r--")).filter(x=>!x.file&&x.size>=0x1000).sort((a,b)=>b.size-a.size);
  for (const r of ranges) {
    try { const hits = Memory.scanSync(r.base, r.size, pat); for (const h of hits) { try { if (objIsClass(h.address, ck)) found.push(h.address); } catch (e) {} } } catch (e) {}
  }
  console.log("[btn] CustomUIButton 实例数=" + found.length + "（下面只在游戏线程求 active 的坐标）");
  runOnGameThread(function () {
    try {
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      const cam = _uiCamera();
      if (!mW2S || cam.isNull()) { console.log("[btn] 无相机/换算方法"); return; }
      for (const b of found) {
        let name = "?", active = "?";
        try { name = _str(_inv(_inv(b, "get_gameObject"), "get_name")); } catch (e) {}
        try { active = String(_inv(b, "get_activeInHierarchy") !== ptr(0)); } catch (e) {}
        const s = _screenOf(b, cam, mW2S);
        if (!s) continue;
        console.log("[btn] " + b + "  name=" + name + "  screen(unity)=(" + s.x.toFixed(0) + "," + s.y.toFixed(0) + ")  [android y=" + (1080 - s.y).toFixed(0) + "]");
      }
    } catch (e) { console.log("[btn] 错误: " + e); }
  });
};

// 带 1 个 int / 1 个对象 参数的调用
function _inv1i(obj, name, i) { const m = _mInfo(g_il2cpp.object_get_class(obj), name); if (!m) return ptr(0); const b = Memory.alloc(4); b.writeInt(i); const p = Memory.alloc(8); p.writePointer(b); const ex = Memory.alloc(8); ex.writePointer(ptr(0)); return g_il2cpp.runtime_invoke(m, obj, p, ex); }
function _inv1o(obj, name, o) { const m = _mInfo(g_il2cpp.object_get_class(obj), name); if (!m) return ptr(0); const p = Memory.alloc(8); p.writePointer(o); const ex = Memory.alloc(8); ex.writePointer(ptr(0)); return g_il2cpp.runtime_invoke(m, obj, p, ex); }
// 带 (对象/Type, int) 两参的调用：如 GetComponentInChildren(Type, bool includeInactive)
function _inv2oi(obj, name, o, i) {
  const m = _mInfo(g_il2cpp.object_get_class(obj), name);
  if (!m) return ptr(0);
  const ib = Memory.alloc(4); ib.writeInt(i);
  const p = Memory.alloc(16); p.writePointer(o); p.add(8).writePointer(ib);
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  return g_il2cpp.runtime_invoke(m, obj, p, ex);
}

// 已捕获的"移动目标确认"对话框实例（由 hookMoveDialog 钩 InitializeParam 得到，避开堆扫描的元数据误命中）
let g_moveDialog = null;
globalThis.hookMoveDialog = function() {
  const dk = findClass("PartsDialogLabyrinthMoveConfirm");
  if (!dk || dk.isNull()) { console.log("[dlg] 未找到 PartsDialogLabyrinthMoveConfirm"); return; }
  const mAny = findMethodAny(dk, "InitializeParam") || findMethodAny(dk, "Awake");
  const m = (mAny && mAny.m) ? mAny.m : mAny;
  if (!m || m.isNull()) { console.log("[dlg] 未找到 InitializeParam/Awake"); return; }
  const fp = _resolveThunk(methodPtr(m));
  Interceptor.attach(fp, { onEnter(args) { try { g_moveDialog = args[0]; console.log("[dlg] 捕获对话框实例=" + args[0]); } catch (e) {} } });
  console.log("[dlg] 已挂钩 @ " + fp + "（请再点一次节点弹出对话框以捕获实例）");
};

// 定位"移动目标确认"对话框并遍历其子孙，找出其中的 CustomUIButton 及其屏幕坐标
globalThis.dialogInfo = function() {
  const dk = findClass("PartsDialogLabyrinthMoveConfirm");
  if (!dk || dk.isNull()) { console.log("[dlg] 未找到 PartsDialogLabyrinthMoveConfirm"); return; }
  let found = [];
  if (g_moveDialog && !g_moveDialog.isNull()) {
    found = [g_moveDialog];
    console.log("[dlg] 使用已捕获的对话框实例=" + g_moveDialog);
  } else {
    const pat = _ptrPattern(dk);
    const ranges = Process.enumerateRanges("rw-").filter(x=>!x.file&&x.size>=0x1000).sort((a,b)=>b.size-a.size);
    for (const r of ranges) {
      try { const hits = Memory.scanSync(r.base, r.size, pat); for (const h of hits) { try { if (objIsClass(h.address, dk)) found.push(h.address); } catch (e) {} } } catch (e) {}
    }
    console.log("[dlg] 未捕获实例，堆扫描命中=" + found.length + "（建议先 hookMoveDialog() 再点节点）");
  }
  runOnGameThread(function () {
    try {
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      const cam = _uiCamera();
      if (cam.isNull() || !mW2S) { console.log("[dlg] 无相机/换算方法"); return; }
      // 取 CustomUIButton 的 System.Type：优先 il2cpp_class_get_type
      let btnType = ptr(0), uibType = ptr(0);
      try {
        const gct = Process.findModuleByName("libil2cpp.so").findExportByName("il2cpp_class_get_type");
        const cbCls = findClass("CustomUIButton");
        if (gct && cbCls && !cbCls.isNull()) btnType = new NativeFunction(gct, "pointer", ["pointer"])(cbCls);
        const ubCls = findClass("UIButton");
        if (gct && ubCls && !ubCls.isNull()) uibType = new NativeFunction(gct, "pointer", ["pointer"])(ubCls);
      } catch (e) {}
      console.log("[dlg] 待遍历对话框数=" + found.length + "  (CustomUIButton Type=" + btnType + ")");
      let k = 0;
      for (const d of found) {
        if (k++ >= 4) break;
        const s = _screenOf(d, cam, mW2S);
        console.log("[dlg] dialog=" + d + "  screen(unity)=" + (s ? "(" + s.x.toFixed(0) + "," + s.y.toFixed(0) + ")" : "?"));
        const dlgb = d.add(0x20).readPointer();   // PartsDialogAbstract.dialogBase（弹窗外壳，含按钮）
        console.log("[dlg] dialogBase(" + d + "+0x20)=" + dlgb);
        const root = _inv(!dlgb.isNull() ? dlgb : d, "get_transform");
        if (root.isNull()) continue;
        const queue = [root]; let n = 0;
        while (queue.length && n < 400) {
          const tr = queue.shift(); n++;
          let cc = 0; try { const r = _inv(tr, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
          for (let i = 0; i < cc; i++) { const ch = _inv1i(tr, "GetChild", i); if (!ch.isNull()) queue.push(ch); }
          const gobj = _inv(tr, "get_gameObject");
          if (gobj.isNull()) continue;
          const nm = _str(_inv(gobj, "get_name"));
          let tag = "";
          if (!btnType.isNull()) { try { const c = _inv1o(gobj, "GetComponent", btnType); if (!c.isNull()) tag += " [CustomUIButton]"; } catch (e) {} }
          if (!uibType.isNull()) { try { const c = _inv1o(gobj, "GetComponent", uibType); if (!c.isNull()) tag += " [UIButton]"; } catch (e) {} }
          const ss = _screenOf(tr, cam, mW2S);
          console.log("[dlg]   节点 name=" + nm + tag + "  screen(unity)=" + (ss ? "(" + ss.x.toFixed(0) + "," + ss.y.toFixed(0) + ")  [android y=" + (1080 - ss.y).toFixed(0) + "]" : "?"));
        }
        console.log("[dlg]   遍历节点数=" + n);
      }
    } catch (e) { console.log("[dlg] 错误: " + e); }
  });
};

// 求"目标节点"与"确认按钮"的 adb 触摸坐标（屏幕固定，确认按钮为 TwoButtonRight）
globalThis.tapInfo = function(targetBlockId) {
  const loc = _moveLocate(targetBlockId);
  if (!loc || !loc.panel) { console.log("[tap] 未定位到节点"); return; }
  runOnGameThread(function () {
    try {
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      const cam = _uiCamera();
      if (!mW2S || cam.isNull()) { console.log("[tap] 无相机/换算方法"); return; }
      let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
      const s = _screenOf(loc.panel, cam, mW2S);
      if (!s) { console.log("[tap] 节点坐标求解失败"); return; }
      const ax = Math.round(s.x), ay = Math.round(H - s.y);
      console.log("[tap] 屏幕高 H=" + H + "（confirmY=" + (H - 336) + "）");
      console.log("[tap] ① 点节点 " + targetBlockId + "：  adb shell input tap " + ax + " " + ay);
      console.log("[tap] ② 等 [dlg] 捕获对话框实例 后，运行 confirmPos() 动态取确认按钮坐标");
    } catch (e) { console.log("[tap] 错误: " + e); }
  });
};

// 在"已捕获的确认框"里按名字特征动态找按钮，返回其 adb 坐标（默认 TwoButtonRight=确认）
globalThis.confirmPos = function(namePart) {
  if (!g_moveDialog || g_moveDialog.isNull()) { console.log("[dlg] 尚未捕获对话框：先 hookMoveDialog() 再点节点"); return; }
  const want = (namePart || "TwoButtonRight").toLowerCase();
  const d = g_moveDialog;
  runOnGameThread(function () {
    try {
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      const cam = _uiCamera();
      if (!mW2S || cam.isNull()) { console.log("[dlg] 无相机/换算方法"); return; }
      let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
      const dlgb = d.add(0x20).readPointer();
      const root = _inv(!dlgb.isNull() ? dlgb : d, "get_transform");
      if (root.isNull()) { console.log("[dlg] 无 transform"); return; }
      const queue = [root]; let n = 0, found = null, foundName = "";
      while (queue.length && n < 400) {
        const tr = queue.shift(); n++;
        let cc = 0; try { const r = _inv(tr, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
        for (let i = 0; i < cc; i++) { const ch = _inv1i(tr, "GetChild", i); if (!ch.isNull()) queue.push(ch); }
        const gobj = _inv(tr, "get_gameObject");
        if (gobj.isNull()) continue;
        const nm = _str(_inv(gobj, "get_name"));
        if (nm.toLowerCase().indexOf(want) >= 0) { found = tr; foundName = nm; break; }
      }
      if (!found) { console.log("[dlg] 未找到名字含 '" + want + "' 的节点"); return; }
      const s = _screenOf(found, cam, mW2S);
      if (!s) { console.log("[dlg] 坐标求解失败"); return; }
      console.log("[dlg] 确认按钮='" + foundName + "' → adb shell input tap " + Math.round(s.x) + " " + Math.round(H - s.y));
    } catch (e) { console.log("[dlg] 错误: " + e); }
  });
};

// （已清理）hookBrk / brkSkipOn / brkSkipOff —— 会破坏 ART 解释器或制造连锁野指针，已移除。

// （已清理）step1 / step2 / diagPanel / moveTo 及按钮辅助函数（_findButtonClass/_findUnityEventClass/findAllButtons/_buttonOnClick）
// —— 均为"主线程调度 / 找 Button"的旧失败方案，已被 clickNodeThread/confirmThread/moveThread 取代，故移除。
// （已清理）diagMove / installTrapLog —— 装异常处理器的调试工具（会替换 Frida 内部异常处理），已移除。

// （已清理）disasmAt / disasmMethod / diagList —— 一次性反汇编/列表诊断工具，已移除。
globalThis.hookMove = function() {
  if (!g_il2cpp || !g_il2cpp.runtime_invoke) { console.log("[hookMove] 需要 runtime_invoke 路径"); return; }
  const k = findClass("ApiManager");
  if (!k || k.isNull()) { console.log("[hookMove] 未找到 ApiManager"); return; }
  const m = findMethodLike(k, "AddLabyrinthMovePostParam");
  if (!m) { console.log("[hookMove] 未找到 AddLabyrinthMovePostParam"); return; }
  const fp = methodPtr(m.m);
  if (fp.isNull()) { console.log("[hookMove] 函数指针为空"); return; }
  Interceptor.attach(fp, {
    onEnter(args) {
      try {
        const enterId = args[1].toInt32();
        const blockId = args[2].toInt32();
        const cb = args[3];
        console.log("[hookMove] >>> AddLabyrinthMovePostParam enterId=" + enterId + " blockId=" + blockId + " callback=" + cb);
        g_lastEnterId = enterId;                 // 校准：后续 step2 直接复用
        if (!cb.isNull()) g_lastCallback = cb;   // 校准：捕获真实合法回调（含正确 invoke_impl）
        const bt = Thread.backtrace(this.context, Backtracer.ACCURATE);
        const mod = Process.findModuleByName("libil2cpp.so");
        console.log("[hookMove] 调用栈(内->外, VA=addr-modBase):");
        for (const a of bt.slice(0, 12)) {
          const off = mod ? a.sub(mod.base) : a;
          console.log("   " + a + "  VA=0x" + off.toString(16) + "  " + DebugSymbol.fromAddress(a));
        }
      } catch(e) { console.log("[hookMove] onEnter 错误: " + e); }
    }
  });
  console.log("[hookMove] 已挂钩 AddLabyrinthMovePostParam @ " + fp);
};
// （已清理）hookConfirm —— 钩 UnityEvent.AddListener 的旧方案（会破坏 UI 事件系统），已移除。

// ===== 状态机锚点：读取 ViewManager.Instance.CurrentViewId（eViewId）=====
// 供 Python(frida-python) 编排：杀进程 → 启动 → attach → 按"当前界面"决定下一步
function _viewManagerInst() {
  if (!g_il2cpp) return ptr(0);
  const k = findClass("ViewManager");
  if (!k || k.isNull()) return ptr(0);
  const mAny = findMethodAny(k, "get_Instance") || findMethodAny(k, "GetInstance");
  const m = (mAny && mAny.m) ? mAny.m : mAny;
  if (!m || m.isNull()) return ptr(0);
  if (g_il2cpp.thread_attach) { try { g_il2cpp.thread_attach(g_il2cpp.domain_get()); } catch (e) {} }
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  try { return g_il2cpp.runtime_invoke(m, ptr(0), ptr(0), ex); } catch (e) { return ptr(0); }
}
// ★换视图 ⇒ 让"遍历缓存"作废（2026-10-22，「战斗结束动画期卡死」修复）：
//   那两个缓存存的是**上一轮的 Transform 指针**（`_walkNodes`/`_rootWalkNodes` 1.5s TTL）。
//   战斗结束 → 结算页 Instantiate → 战斗 view 销毁，这几件事就发生在 1~2s 内 ⇒ 缓存里的指针会
//   指向**已 Destroy 的对象**，再对它们 `get_gameObject/get_childCount/get_name` 就是野指针 invoke
//   （挂死或 native 崩）。而 `viewId` 恰好是"换视图"的免费信号（状态机每轮本来就读它）⇒ 一变就清。
//
//   ⚠**故意不清 `g_dialogArea`**（2026-10-22 实机核对）：它是**长驻对象**、自带
//   `get_name() === "DialogArea"` 校验，而且上面已经加了 [MAX_CHILD_COUNT] 闸门（对象半死时
//   childCount 读成垃圾 ⇒ 直接当 0，不会展开遍历）⇒ 保留它风险可控。清掉的话每场都要重做一次
//   "从 UI 根 BFS 找 DialogArea"（实测"学习并缓存"从 215ms 涨到 847ms ✗），不划算。
let g_lastViewId = -1;
function _dropWalkCaches(why) {
  try {
    g_walkCache = null; g_walkAt = 0;
    for (const k in g_rootWalk) { try { delete g_rootWalk[k]; } catch (e) {} }
    g_resBtns.win = null; g_resBtns.fail = null;   // 结算按钮指针 ⇒ 换页必然要重学
  } catch (e) {}
}
globalThis.getViewId = function () {
  if (!g_il2cpp) return -1;                 // il2cpp 还没就绪（脚本会每 250ms 重试初始化）
  const vm = _viewManagerInst();
  if (vm.isNull()) return -2;               // 未取到 ViewManager 实例
  let vid = -3;
  try { vid = vm.add(0x120).readS32(); } catch (e) { return -3; }    // CurrentViewId BackingField @0x120
  // ★只认**正数** viewId（2026-10-22）：场景切换的那一两帧里这个字段可能读到 0/垃圾值，若拿它比较
  //   就会"264 → 0 → 264"来回清缓存 ⇒ 每次都重新遍历（**反而更慢**）。0/负数一律忽略、不更新基线。
  if (vid > 0 && vid !== g_lastViewId) {
    g_lastViewId = vid;
    _dropWalkCaches("view=" + vid);
  }
  return vid;
};

// ===== 按"节点名"定位（供 Python 同步调用；分辨率无关）=====
// 在调用线程上直接做 raw 调用（UI getter 不涉 Java，安全），DFS 遍历整个 UI 树，
// 只回调"屏内且激活"的节点。Python 据此把"按钮名"换成当前真实坐标。
// 遍历当前界面整棵 UI 树。wantCoords=true 时**给每个节点**算屏幕坐标（贵：3 次调用/节点）；
// false 时只取名字/激活/Transform（~5 次调用/节点）。★绝大多数场景用 false：
// 先用便宜的名字/激活态筛出候选，再只对候选调 _ndXY 算坐标（见 find/findList/dialog）。

// ★子节点数闸门（2026-10-22，「战斗结束动画期卡死」修复）：
//   读 `get_childCount` 是 `r.add(0x10).readS32()` —— 若 `t` 已被 Destroy（场景切换/结算页
//   Instantiate 的瞬间读到的就是这种"半死对象"），`r` 非空但内容是垃圾 ⇒ `cc` 可能是 0x7FFFFFFF
//   这类巨大正数 ⇒ 下面 `for (i < cc)` 变成几十万次 `GetChild` invoke ⇒ **agent 线程再也不回来**、
//   同时把游戏主线程的 il2cpp 调用槽全占住（23:03 那场卡死 + 游戏自己崩就是这个形状）。
//   ⇒ 任何"按 childCount 展开"的地方都先过这道闸：正常 UI 单节点子数不可能上百，512 足够宽松。
const MAX_CHILD_COUNT = 512;
function _childCountOf(t) {
  let cc = 0;
  try { const r = _inv(t, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) { return 0; }
  return (cc >= 0 && cc <= MAX_CHILD_COUNT) ? cc : 0;
}

function _rawWalk(cb, wantCoords, rootTr) {
  try {
    if (g_il2cpp && g_il2cpp.thread_attach) { try { g_il2cpp.thread_attach(g_il2cpp.domain_get()); } catch (e) {} }
    // rootTr 可选：只走某个子树（如 DialogArea）而不是整棵 UI 树 —— "只抓需要的内容"。
    // 整棵树实测 661 节点 ≈ 1.8s；DialogArea 子树只有几十个节点。
    let tr = (rootTr && !rootTr.isNull()) ? rootTr : null;
    if (!tr) {
      const vm = _viewManagerInst();
      if (vm.isNull()) return;
      const cv = vm.add(0x110).readPointer();
      if (cv.isNull()) return;
      tr = _inv(cv, "get_transform");
      if (tr.isNull()) return;
      let p = _inv(tr, "get_parent"); let up = 0;
      while (!p.isNull() && up < 8) { tr = p; p = _inv(tr, "get_parent"); up++; }
    }
    let cam = ptr(0), mW2S = null, H = 1080, W = 1920;
    if (wantCoords) {
      const rtu = findClass("RectTransformUtility");
      mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      cam = _uiCamera();
      if (cam.isNull() || !mW2S) return;
      try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
      try { const pw = _inv(cam, "get_pixelWidth"); if (!pw.isNull()) W = pw.add(0x10).readS32(); } catch (e) {}
    }
    const st = [{ t: tr, d: 0, pn: "" }]; let n = 0;
    while (st.length && n < 12000) {
      const it = st.pop(); const t = it.t, d = it.d; n++;
      const cc = _childCountOf(t);                            // ★脏数据闸门，见 [MAX_CHILD_COUNT]
      const kids = []; for (let i = 0; i < cc; i++) { const ch = _inv1i(t, "GetChild", i); if (!ch.isNull()) kids.push(ch); }
      // 先序 DFS：子节点逆序入栈 → 出栈即先序；父名随栈项带下去（供"最上层对话框"判定用）
      const gobj = _inv(t, "get_gameObject");
      if (gobj.isNull()) continue;
      const nm = _str(_inv(gobj, "get_name"));
      for (let i = kids.length - 1; i >= 0; i--) st.push({ t: kids[i], d: d + 1, pn: nm });
      const act = _goActive(gobj);
      let ss = null, ay = null, onscr = false;
      if (wantCoords) {
        ss = _screenOf(t, cam, mW2S);
        ay = ss ? Math.round(H - ss.y) : null;
        onscr = ss ? (ss.x >= 0 && ss.x <= W && ay >= 0 && ay <= H) : false;
      }
      // 回调返回 false → 立即结束遍历（find 找到目标即停，省掉剩下几百个节点的调用）
      if (cb({ name: nm, d: d, pn: it.pn, active: act, tr: t,
               x: ss ? Math.round(ss.x) : null, y: ay, onscreen: onscr }) === false) break;
    }
  } catch (e) { console.log("[rawWalk] 错误: " + e); }
}

// ★在子树里按节点名找一次 → 返回**父子索引路径**（如 [0,3,2]）。
//   用途：给 `labBtn` 学路径 —— 下次用 `_pathAt` 直接 GetChild 链下去（O(深度)），不再遍历。
function _pathFind(rootTr, name) {
  try {
    if (!ptrOk(rootTr)) return null;
    const st = [{ tr: rootTr, path: [] }];
    let guard = 0;
    while (st.length && guard++ < 16000) {
      const it = st.pop();
      const t = it.tr;
      if (!ptrOk(t)) continue;
      let go = null;
      try { go = _inv(t, "get_gameObject"); } catch (e) { go = null; }
      if (!ptrOk(go)) continue;
      let nm = "";
      try { nm = _str(_inv(go, "get_name")); } catch (e) {}
      if (nm === name && _goActive(go) === true) return it.path;
      let cc = 0;
      try { const r = _inv(t, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
      for (let i = cc - 1; i >= 0; i--) {
        const ch = _inv1i(t, "GetChild", i);
        if (!ch.isNull()) st.push({ tr: ch, path: it.path.concat([i]) });
      }
    }
  } catch (e) {}
  return null;
}

// 沿父子索引路径取回节点（GetChild 链；任一步断开返回 null）
function _pathAt(rootTr, path) {
  try {
    let t = rootTr;
    for (let k = 0; k < path.length; k++) {
      if (!ptrOk(t)) return null;
      const ch = _inv1i(t, "GetChild", path[k]);
      if (ch.isNull()) return null;
      t = ch;
    }
    return t;
  } catch (e) { return null; }
}

// 取一次相机环境（惰性算坐标时复用，别每个节点都重新查相机）
function _camEnv() {
  try {
    const rtu = findClass("RectTransformUtility");
    const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
    const cam = _uiCamera();
    if (cam.isNull() || !mW2S) return null;
    let H = 1080, W = 1920;
    try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
    try { const pw = _inv(cam, "get_pixelWidth"); if (!pw.isNull()) W = pw.add(0x10).readS32(); } catch (e) {}
    return { cam: cam, mW2S: mW2S, H: H, W: W };
  } catch (e) { return null; }
}

// 只对"已筛出的候选节点"算屏幕坐标（android 坐标，左上原点）
function _ndXY(nd, env) {
  if (!nd || !nd.tr || !env) return null;
  const ss = _screenOf(nd.tr, env.cam, env.mW2S);
  if (!ss) return null;
  const ay = Math.round(env.H - ss.y);
  return { x: Math.round(ss.x), y: ay,
           onscreen: (ss.x >= 0 && ss.x <= env.W && ay >= 0 && ay <= env.H) };
}

// 战斗状态检测（内存字段，权威判据；偏移已与 dump.cs 核对，且与 pcrtool 一致）。
//   判定"在战斗视图"：currentView 类名含 ViewBattle
//   判定"战斗中"    ：ViewBattle.battleManager(+0x100) -> BattleManager.<GameState>(+0x1DC) == PLAY(0)
//                    eBattleGameState = { PLAY:0, IDLE:1, WAIT_WAVE_END:2, NEXT_WAVE_PROCESS:3, INVALID:-1 }
//   判定"战斗结束"  ：ViewBattle.<IsResultEnable>(+0xF0) == true（结算就绪）
//   合理性校验（同 pcrtool plausibleBM）：TimeLimit(+0x1E8) ∈ [-1,10000]、startRemainTime(+0x16C) ∈ (0,10000]
// 注意：eBattleGameState **不含胜负**；胜负要看结算对话框类名（DialogLabyrinthResult /
//       DialogQuestResultWin / DialogQuestFailed …）或 eBattleResult{WIN=0,LOSE=1,TIME_OVER=2,RETIRE=3}。
const BATTLE_GS_NAME = { 0: "PLAY", 1: "IDLE", 2: "WAIT_WAVE_END", 3: "NEXT_WAVE_PROCESS", [-1]: "INVALID" };

/**
 * 极简「当前 View 类名 + 是否在战斗」（2026-11-07）。
 *
 * `labStatus` 要 150~300ms（构造全量快照），而这里只做 3 次指针读 + 一次类名解析：
 *   ViewManager+0x110 → 当前 View → klassName 里是否含 "ViewBattle"
 * 给状态机阶段 2 判"进战斗了吗"用。
 */
function labViewQuickSnapshot() {
  const t0 = Date.now();
  const out = { view: "", inBattle: false, ms: 0 };
  try {
    const vm = _viewManagerInst();
    if (vm && !vm.isNull()) {
      const cv = vm.add(0x110).readPointer();
      if (cv && !cv.isNull()) {
        out.view = klassNameOf(cv) || "";
        out.inBattle = out.view.indexOf("ViewBattle") >= 0;
      }
    }
  } catch (e) {}
  out.ms = Date.now() - t0;
  return out;
}

/**
 * 轻量「战斗结果原始值」（2026-11-07）：判胜/负不该靠"等按钮出现"，字段里就有。
 *   resultEnable = ViewBattle+0xF0（结算按钮可点）
 *   gameState    = BattleManager+0x1DC（战斗状态机枚举，胜/负不同值 —— 待标定）
 * 只读 3 次指针 + 3 次 int，比 labStatus 快一个数量级。
 */
function labBattleResultSnapshot() {
  const t0 = Date.now();
  const out = { inBattle: false, view: "", resultEnable: null, gameState: null, ms: 0 };
  try {
    const vm = _viewManagerInst();
    if (vm && !vm.isNull()) {
      const cv = vm.add(0x110).readPointer();
      if (cv && !cv.isNull()) {
        out.view = klassNameOf(cv) || "";
        out.inBattle = out.view.indexOf("ViewBattle") >= 0;
        if (out.inBattle) {
          try { out.resultEnable = cv.add(0xF0).readU8() !== 0; } catch (e) {}
          try {
            const bm = cv.add(0x100).readPointer();
            if (ptrOk(bm)) out.gameState = bm.add(0x1DC).readS32();
          } catch (e) {}
        }
      }
    }
  } catch (e) {}
  out.ms = Date.now() - t0;
  return out;
}

function battleStateSnapshot() {
  const out = { inBattle: false, viewClass: null, gameState: null, gameStateName: null, resultEnable: null, plausible: null };
  try {
    const vm = _viewManagerInst();
    if (vm.isNull()) return out;
    const cv = vm.add(0x110).readPointer();
    if (cv.isNull()) return out;
    out.viewClass = klassNameOf(cv);
    if (!out.viewClass || out.viewClass.indexOf("ViewBattle") < 0) return out;   // 不在战斗视图
    out.inBattle = true;
    out.resultEnable = (cv.add(0xF0).readU8() !== 0);                          // IsResultEnable
    const bm = cv.add(0x100).readPointer();                                    // ViewBattle.battleManager
    if (ptrOk(bm)) {
      const gs = bm.add(0x1DC).readS32();                                      // BattleManager.GameState
      const tl = bm.add(0x1E8).readFloat();                                    // TimeLimit
      const srt = bm.add(0x16C).readFloat();                                   // startRemainTime
      const okTL = (tl >= -1 && tl <= 10000), okSRT = (srt > 0 && srt <= 10000);
      out.plausible = (gs >= -1 && gs <= 3) && okTL && okSRT;
      if (gs >= -1 && gs <= 3) { out.gameState = gs; out.gameStateName = BATTLE_GS_NAME[gs] || ("?" + gs); }
    }
  } catch (e) {}
  return out;
}

// ── 共享遍历缓存（性能关键）──
// 每轮状态机要"整棵树的节点快照"好几次（dialog / find / findList / tree），
// 而一次全树遍历 ≈ 661 节点 × 数次 runtime_invoke（模拟器上几百 ms）。
// 用 TTL 把它们合并成"每 250ms 最多真正遍历一次"，其余调用读缓存 —— 直接把每轮的
// 遍历开销从 2~3 次降到 ~1 次（这是"每一步都有延迟"的主要来源之一）。
let g_walkCache = null, g_walkAt = 0;
// ★TTL 250ms → **1500ms**（2026-10-16，哨兵实锤 `[slow] findList 2455ms` / `findTextList 2459ms`）：
//   整树遍历一次 ≈1.8s，而"一轮一次"的调用 ≈2.5s 才来一次 ⇒ 250ms 的 TTL 等于**每轮都真扫** ✗。
//   1.5s 让"同一页面停留期间的多轮调用"复用同一次遍历（页面真变了最多晚 1.5s 看到 —— 我们的点击后面
//   都有 delay，且关键路径（战斗结算探测）走的是带根的 [_rootWalkNodes] 缓存，不受这里影响 ✓）。
const WALK_TTL_MS = 1500;

function _walkNodes() {
  const now = Date.now();
  if (g_walkCache && (now - g_walkAt) < WALK_TTL_MS) return g_walkCache;
  const out = [];
  _opBegin("walkNodes(全树遍历)");
  try { _rawWalk(function (nd) { out.push(nd); }, false); } catch (e) {}   // false=不算坐标（省 ~3 次调用/节点）
  finally { _opEnd(); }
  // ★TTL 时间戳必须取在**扫描结束**之后（2026-10-16 修）：
  //   整树 661 节点 ≈2s > TTL 250ms，而原来记的是"扫描**开始**时刻" ⇒ 扫描一返回缓存就已过期
  //   ⇒ **每一个 find/findList/dialog 调用都要重扫一遍**（预览页一轮里有 3 次 find ⇒ 一轮 6s+，
  //   实测"看七八秒敌人"就是这个）。改成结束后取，同一轮里连续的几次调用就能共用这一次扫描。
  g_walkCache = out; g_walkAt = Date.now();
  return out;
}

// 取"当前最上层对话框"的名字 —— 解决"多对话框同时 active、按名字找会拿到被盖住的那个"。
// 依据：DialogArea 的子节点里，**后入的子节点在 Unity 里渲染在上层**（实测：
//   战斗格子预览 = DialogQuestDetail(Clone)，队伍编组 = DialogCommonPartyEdit(Clone)，
//   两者会同时 active 且都是 DialogArea 的子节点，靠 activeInHierarchy 完全分不出来）。
// 先序 DFS 会按兄弟顺序访问，故"最后一个匹配的"就是最上层的那个。
let g_dialogArea = null;   // 缓存 DialogArea 的 Transform（长驻对象，指针对不上就重找）
let g_dlgBfs = 0;          // DialogArea 被"重扫"的次数（用于诊断：每轮都在涨 ⇒ 缓存失配 → 全树 BFS 开销）
let g_vmScans = 0;         // ★查"活跃 ViewManager"时的**全堆扫描**次数（1~2s 级的重活；Python 侧看到它涨就出声）

// ★失败退避（2026-10-12 性能修复）：找不到 DialogArea 时（换页中/不在迷宫），原来**每次调用**
//   都重做"向上 8 层 + 全树 BFS（最多 12000 节点）"；而调用方在缓存为空时每轮都会来问 ⇒ 白烧。
//   改成：找失败后 1s 内直接返回 null；找到就清零。bgs 计数仍在真扫的那层（诊断口径不变）。
var g_dlgAreaFailAt = 0;
const DLG_AREA_BACKOFF_MS = 1000;
function _findDialogAreaTr() {
  if (Date.now() - g_dlgAreaFailAt < DLG_AREA_BACKOFF_MS) return null;
  const _r = _findDialogAreaTrRaw();
  if (_r && !_r.isNull()) { g_dlgAreaFailAt = 0; return _r; }
  g_dlgAreaFailAt = Date.now();
  return null;
}
function _findDialogAreaTrRaw() {
  g_dlgBfs++;
  try {
    const vm = _viewManagerInst();
    if (vm.isNull()) return null;
    const cv = vm.add(0x110).readPointer();
    if (cv.isNull()) return null;
    let tr = _inv(cv, "get_transform");
    if (tr.isNull()) return null;
    let p = _inv(tr, "get_parent"); let up = 0;
    while (!p.isNull() && up < 8) { tr = p; p = _inv(tr, "get_parent"); up++; }
    const q = [tr]; let n = 0;
    while (q.length && n < 12000) {
      const t = q.shift(); n++;
      const gobj = _inv(t, "get_gameObject");
      if (!gobj.isNull() && _str(_inv(gobj, "get_name")) === "DialogArea") return t;
      const cc = _childCountOf(t);                          // ★脏数据闸门，见 [MAX_CHILD_COUNT]
      for (let i = 0; i < cc; i++) { const ch = _inv1i(t, "GetChild", i); if (!ch.isNull()) q.push(ch); }
    }
  } catch (e) {}
  return null;
}

// 取"当前最上层对话框"的名字。
// 依据：DialogArea 的子节点里，**后入的子节点在 Unity 里渲染在上层**（实测：
//   战斗格子预览 = DialogQuestDetail(Clone)，队伍编组 = DialogCommonPartyEdit(Clone)，
//   两者会同时 active、都是 DialogArea 的子节点，靠 activeInHierarchy 完全分不出来）。
// 性能：走"缓存 DialogArea + 只数它的子节点"（约 15 次调用），**不要**每次全树遍历
// （661 节点 × 若干 invoke ≈ 3000 次调用，每轮多花几百毫秒，是每步卡顿的来源之一）。
// DialogArea 的 Transform（带缓存 + 失配重找）。给"只遍历子树"的调用当根用。
function _dialogAreaTr() {
  try {
    if (!g_dialogArea || g_dialogArea.isNull() ||
        _str(_inv(_inv(g_dialogArea, "get_gameObject"), "get_name")) !== "DialogArea") {
      g_dialogArea = _findDialogAreaTr();
    }
    return (g_dialogArea && !g_dialogArea.isNull()) ? g_dialogArea : null;
  } catch (e) { return null; }
}

/**
 * 窗口名归一化（2026-11-06 状态机基础设施）：
 *   `DialogLabyrinthSelection(Clone)` → `DialogLabyrinthSelection`
 *   `DialogCommonBig(Instance)`      → `DialogCommonBig`
 * 只去掉 Unity 实例化后缀，**不碰业务前缀**——状态机要拿它当稳定 key。
 */
function _normWindowName(nm) {
  if (!nm) return "";
  let s = String(nm);
  // 反复剥掉末尾的 (...) 后缀（可能嵌套，如 Name(Clone)(1)）
  for (let i = 0; i < 3; i++) {
    const t = s.replace(/\s*\([^()]*\)\s*$/, "").trim();
    if (t === s) break;
    s = t;
  }
  return s;
}

/**
 * 轻量「当前窗口 + 编组页队伍索引」（2026-11-07，Boss 三队状态机）。
 *
 * Boss 战的 3 个队挤在同一个 `DialogCommonPartyEdit` 里，点「战斗开始」多数是**切到下一队**，
 * 窗口名不变 ⇒ 只看窗口判不出"点没点上"。`nowSelectParty`（DialogCommonPartyEdit+0x208 → +0x148）
 * 就是当前选中的队伍索引，把它和窗口名组合成二元判据。
 *
 * 返回 `{ top, idx, ms }`：top=归一化窗口名，idx=当前队伍（-1 = 读不到/不在编组页）。
 */
/**
 * 轻量「单个角色的图标坐标」（2026-11-07）：只对**命中 uid 的那一个** icon 做投影。
 *
 * `labParty` 慢的根源是一次给 21 个 icon 全做 world-to-screen（~700ms），
 * 而编队时每次只需要"我要点的那个人"在哪 ⇒ 1 次投影 ≈ 20ms。
 *
 * ★`via` 决定**先找哪个区域**（2026-11-07，与 Kotlin 端 `findUnitCoord` 同款，两边不能混用）：
 *   · `"槽位"` → 队伍那一排（`plate+0xC8`）优先 —— **撤人**点的是底排那张卡，
 *     点角色一览里的同 id 卡**不会撤人**；
 *   · `"一览"`/其它 → 角色一览（`scrollController+0x70 useUnitIconList`）优先 —— **加人**点的是一览里那张。
 *   一览的卡还必须落在**裁剪带**内才算可点（同 `labPartySnapshot` 的 `vis` 规则）。
 *
 * @param uid 目标角色 id
 * @param via "槽位" / "一览"（可空 = 一览优先）
 * @return `{ top, uid, via, src, found, x, y, ms }`
 */
function labPartyIconPosSnapshot(uid, via) {
  const t0 = Date.now();
  const out = { top: "", uid: uid | 0, via: (via || ""), src: "", found: false, x: 0, y: 0, ms: 0 };
  try {
    out.top = _normWindowName(_topDialogName() || "");
    if (out.top === "DialogCommonPartyEdit") {
      let d = g_partyDlg;
      if (!d || d.isNull()) {
        const f = labPartyFind();
        if (f && f.ok) { d = f.ptr; g_partyDlg = d; }
      }
      if (d && !d.isNull()) {
        const abs = d.add(0x208).readPointer();
        if (ptrOk(abs)) {
          const env = _camEnv();
          const slotIcons = [];        // 队伍那一排（plate+0xC8，5 格）
          const listIcons = [];        // 角色一览（scrollController+0x70）
          let band = null;             // 一览的裁剪带（可见性判定）
          try {
            const plate = abs.add(0x68).readPointer();
            if (ptrOk(plate)) {
              const arr = plate.add(0xC8).readPointer();
              let size = 0; try { size = arr.add(0x18).readS32(); } catch (e) {}
              if (size > 0 && size <= 30) {
                for (let i = 0; i < size; i++) {
                  let ic = null; try { ic = arr.add(0x20 + i * 8).readPointer(); } catch (e) { continue; }
                  if (ptrOk(ic)) slotIcons.push(ic);
                }
              }
            }
          } catch (e) {}
          try {
            const sc = abs.add(0x70).readPointer();
            if (ptrOk(sc)) {
              const lst = sc.add(0x70).readPointer();
              let n = ptrOk(lst) ? listCount(lst) : 0;
              if (n > 0) n = Math.min(n, 64);
              const items = ptrOk(lst) ? lst.add(0x10).readPointer() : ptr(0);
              for (let i = 0; i < n; i++) {
                const ic = ptrOk(items) ? items.add(0x20 + i * 8).readPointer() : ptr(0);
                if (ptrOk(ic)) listIcons.push(ic);
              }
              try {
                const cc = sc.add(0x20).readPointer();
                if (ptrOk(cc)) {
                  const pan = cc.add(0x28).readPointer();
                  if (ptrOk(pan)) band = (_panelClipScreen(pan, env) || {}).band || null;
                }
              } catch (e) {}
            }
          } catch (e) {}
          const wantSlot = (out.via === "槽位" || out.via === "slot");
          const order = wantSlot ? ["slot", "list"] : ["list", "slot"];
          for (const src of order) {
            const arr = src === "slot" ? slotIcons : listIcons;
            for (const icon of arr) {
              let u = 0; try { u = partyUnitIdOfIcon(icon).unitId; } catch (e) {}
              if (u !== out.uid) continue;
              if (!env) break;
              let ss = null;
              try { ss = _screenOf(icon, env.cam, env.mW2S); } catch (e) {}
              if (!ss) continue;
              const x = Math.round(ss.x), y = Math.round(env.H - ss.y);
              if (src === "list" && band) {
                const m = 10;
                if (!(x >= band.left + m && x <= band.right - m && y >= band.top + m && y <= band.bottom - m)) continue;
              }
              out.x = x; out.y = y; out.found = true; out.src = src;
              break;
            }
            if (out.found) break;
          }
        }
      }
    }
  } catch (e) {}
  out.ms = Date.now() - t0;
  return out;
}

/**
 * ★「角色一览」当前窗口的 unitId 列表（2026-11-07，**不投影** ⇒ ~1ms）。
 *
 * 来源：`abs(DialogCommonPartyEdit+0x208) +0x70` = scrollController → `+0x70 useUnitIconList`
 *   （滑走就换内容的那一屏"内容对象"）。
 *
 * ★为什么单独要这个：**滚动一览**时"队伍那一排"（`plate+0xC8`）**不会变**，只有一览会变 ⇒
 *   判断"滚到位了没"必须看**一览**的列表，不能看槽位。
 */
function _partyListIds(abs) {
  const out = [];
  try {
    if (!ptrOk(abs)) return out;
    const sc = abs.add(0x70).readPointer();
    if (!ptrOk(sc)) return out;
    const lst = sc.add(0x70).readPointer();
    let n = ptrOk(lst) ? listCount(lst) : 0;
    if (n > 0) n = Math.min(n, 64);
    const items = ptrOk(lst) ? lst.add(0x10).readPointer() : ptr(0);
    for (let i = 0; i < n; i++) {
      const ic = ptrOk(items) ? items.add(0x20 + i * 8).readPointer() : ptr(0);
      // ★空指针**跳过**（与 `labPartySnapshot` 的 `scrollIcons` 循环同款）——
      //   两边列表必须逐项一致，否则"Kotlin 侧快照签名"和"agent 侧轻量签名"对不上。
      if (!ptrOk(ic)) continue;
      let uid = 0;
      try { uid = partyUnitIdOfIcon(ic).unitId; } catch (e) {}
      out.push(uid);
    }
  } catch (e) {}
  return out;
}

/**
 * 轻量「编队决策包」（2026-11-07）：决策真正需要的三样东西，**不读坐标、不读名字**。
 *
 * `labParty` 是"全量压缩包"（21 个 icon 各做一次投影 + 3 个 label，893ms），
 * 而编队决策只用：屏上是谁（ids）、已编了谁（members）、当前第几队（idx）。
 *
 * 返回 `{ top, idx, ids[], listIds[], members[], ms }`；ids 里 0 = 空槽。
 */
function labPartyPlanSnapshot() {
  const t0 = Date.now();
  const out = { top: "", idx: -1, ids: [], listIds: [], members: [], ms: 0 };
  try {
    out.top = _normWindowName(_topDialogName() || "");
    if (out.top === "DialogCommonPartyEdit") {
      let d = g_partyDlg;
      if (!d || d.isNull()) {
        const f = labPartyFind();
        if (f && f.ok) { d = f.ptr; g_partyDlg = d; }
      }
      if (d && !d.isNull()) {
        const abs = d.add(0x208).readPointer();
        if (ptrOk(abs)) {
          try { out.idx = abs.add(0x148).readS32(); } catch (e) {}
          out.listIds = _partyListIds(abs);
          const plate = abs.add(0x68).readPointer();
          if (ptrOk(plate)) {
            try {
              const nm = plate.add(0x178).readPointer();
              out.members = _intListOf(nm) || [];
            } catch (e) {}
            const arr = plate.add(0xC8).readPointer();
            let size = -1;
            try { size = arr.add(0x18).readS32(); } catch (e) {}
            if (size > 0 && size <= 30) {
              for (let i = 0; i < size; i++) {
                let icon = null;
                try { icon = arr.add(0x20 + i * 8).readPointer(); } catch (e) { continue; }
                // ★空指针**跳过**（与 `labPartySnapshot` 的 `icons` 循环同款）——
                //   两边列表必须逐项一致，否则"Kotlin 侧快照签名"和"agent 侧轻量签名"对不上。
                if (!ptrOk(icon)) continue;
                let uid = 0;
                try { uid = partyUnitIdOfIcon(icon).unitId; } catch (e) {}
                out.ids.push(uid);
              }
            }
          }
        }
      }
    }
  } catch (e) {}
  out.ms = Date.now() - t0;
  return out;
}

/**
 * 轻量「编队页图标清单」（2026-11-07）：只读 `PartsPartyEditPlate+0xC8` 数组的
 * **长度 + 每格 unitId**，不读坐标/名字/相机投影（那些才是 `labParty` 的大头）。
 *
 * 用途：替代编队流程里的两个固定等待（进场 1.5s、滚动后 1.5s）——
 *   · 进场：轮询到 `ids` 非空且连续两次相同 ⇒ 卡片铺好了；
 *   · 滚动：轮询到 `ids` 变化 ⇒ 滚到位了。
 *
 * 返回 `{ top, idx, count, ids[], ms }`；**ids 里的 0 = 空槽/读不到**。
 */
function labPartyIconsSnapshot() {
  const t0 = Date.now();
  const out = { top: "", idx: -1, count: -1, ids: [], listIds: [], ms: 0 };
  try {
    out.top = _normWindowName(_topDialogName() || "");
    if (out.top === "DialogCommonPartyEdit") {
      let d = g_partyDlg;
      if (!d || d.isNull()) {
        const f = labPartyFind();
        if (f && f.ok) { d = f.ptr; g_partyDlg = d; }
      }
      if (d && !d.isNull()) {
        const abs = d.add(0x208).readPointer();
        if (ptrOk(abs)) {
          try { out.idx = abs.add(0x148).readS32(); } catch (e) {}
          out.listIds = _partyListIds(abs);
          const plate = abs.add(0x68).readPointer();
          if (ptrOk(plate)) {
            const arr = plate.add(0xC8).readPointer();
            let size = -1;
            try { size = arr.add(0x18).readS32(); } catch (e) {}
            out.count = size;
            if (size > 0 && size <= 30) {
              for (let i = 0; i < size; i++) {
                let icon = null;
                try { icon = arr.add(0x20 + i * 8).readPointer(); } catch (e) { continue; }
                // ★空指针**跳过**（与 `labPartySnapshot` 的 `icons` 循环同款）——
                //   两边列表必须逐项一致，否则"Kotlin 侧快照签名"和"agent 侧轻量签名"对不上。
                if (!ptrOk(icon)) continue;
                let uid = 0;
                try { uid = partyUnitIdOfIcon(icon).unitId; } catch (e) {}
                out.ids.push(uid);
              }
            }
          }
        }
      }
    }
  } catch (e) {}
  out.ms = Date.now() - t0;
  return out;
}

function labPartyIdxSnapshot() {
  const t0 = Date.now();
  const out = { top: "", idx: -1, ms: 0 };
  try {
    out.top = _normWindowName(_topDialogName() || "");
    if (out.top === "DialogCommonPartyEdit") {
      let d = g_partyDlg;
      if (!d || d.isNull()) {
        const f = labPartyFind();
        if (f && f.ok) { d = f.ptr; g_partyDlg = d; }
      }
      if (d && !d.isNull()) {
        const abs = d.add(0x208).readPointer();
        if (ptrOk(abs)) out.idx = abs.add(0x148).readS32();
      }
    }
  } catch (e) {}
  out.ms = Date.now() - t0;
  return out;
}

/**
 * 轻量「当前窗口」：只走 DialogArea 的子节点，**不构造快照、不扫全树**。
 * 给状态机做 60ms 级轮询用（`labStatus` 要 150~300ms，太慢）。
 *
 * 返回 `{ top, raw, ms }`：top = 归一化名（空串 = 没有对话框），raw = 原始名。
 */
function labTopSnapshot() {
  const t0 = Date.now();
  const out = { top: "", raw: "", ms: 0 };
  try {
    const raw = _topDialogName() || "";
    out.raw = raw;
    out.top = _normWindowName(raw);
  } catch (e) {}
  out.ms = Date.now() - t0;
  return out;
}

function _topDialogName() {
  try {
    const t = _topDialogGo();
    return t ? t.name : null;
  } catch (e) { return null; }
}

// ★★"当前最上层对话框"—— **唯一权威实现**（2026-11-08）。
//   DialogArea 的子节点里，取**索引最大的、active 的、名字以 "Dialog" 开头**的那个。
//   ★`_topDialogName`（labTop，仪表盘/状态机用）和 `_findExTopPart`（EX 三连页查找用）
//     **必须用同一个**判定 —— 否则两边认为"最上层"是不同的一层：
//     实测 2026-11-08：labTop 说最上层已是 DialogCommonBig(setting 页)，
//     而 EX 查找因为"这一层没命中就往下一层退" ⇒ 一直读到下面那层仍 active 的
//     ①特别装备设定(party) ⇒ 签名永远不变 ⇒ 误判"点击没生效" ⇒ 多点
//     ⇒ 决策跳成「取消」⇒ **点到了不该点的按钮**。
function _topDialogGo() {
  try {
    if (!g_dialogArea || g_dialogArea.isNull() ||
        _str(_inv(_inv(g_dialogArea, "get_gameObject"), "get_name")) !== "DialogArea") {
      g_dialogArea = _findDialogAreaTr();
    }
    if (!g_dialogArea || g_dialogArea.isNull()) return null;
    let cc = 0;
    try { const r = _inv(g_dialogArea, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
    let go = null, nm = "";
    for (let i = 0; i < cc; i++) {          // 正序：后者覆盖前者 = 更上层
      const ch = _inv1i(g_dialogArea, "GetChild", i);
      if (ch.isNull()) continue;
      const gobj = _inv(ch, "get_gameObject");
      if (gobj.isNull()) continue;
      const n = _str(_inv(gobj, "get_name"));
      if (!/^Dialog/.test(n)) continue;
      if (_goActive(gobj) !== true) continue;
      go = gobj; nm = n;
    }
    return go ? { go: go, name: nm } : null;
  } catch (e) { return null; }
}

// 供 Python 调用（frida-python 把 Python 的 snake_case 转为 JS 驼峰）：
//   script.exports_sync.view_id()             -> JS viewId()
//   script.exports_sync.ready()               -> JS ready()
//   script.exports_sync.find("ButtonQuest")   -> {name,x,y} 或 None（首个"屏内+激活+名字匹配"的节点）
//   script.exports_sync.tree()                -> [{name,x,y,d}, ...]（当前所有"屏内+激活"节点）
// 战斗胜利后的"选取奖励角色"页（pageTop=DialogLabyrinthSelection）—— 读出 3 张卡的真实内容。
// 结构（dump.cs，全部字段偏移已核对）：
//   DialogLabyrinthSelection(+0x140 partsItemView, +0x150 ticketId)
//     PartsLabyrinthSelectionItemView(+0x28 itemSelectionPartsList: List<PartsSelectionItemInfo>)
//       PartsSelectionItemInfo: +0x60 selectButton(CustomUIButton)=「决定」
//                              +0x98 unitChoiceIndex   +0xB8 unitInfo(LabyrinthUnitInfo)
//                              +0xB0 unitData(UnitData)  +0xA8 choiceInfo(遗物时才用)
//       LabyrinthUnitInfo(+0x10 UnitId[ObscuredInt], +0x1C UnitType, +0x20 UnitData)
// 走"堆扫描找实例 + 卡片列表校验"的老办法（同 labEventSnapshot），不依赖 activeInHierarchy。
// ★选取页读取很贵：内部是"堆扫描 512 个实例"，实测不缓存时把主循环拖到 4s/轮。
//   选取页停留期间内容不会变 → 1s 内直接复用上次结果。诊断调用（带 diag）不走缓存。
let g_selCache = null, g_selAt = 0;
const SEL_TTL_MS = 1000;

// ★★**"什么时候才允许全堆扫描"**（2026-10-16 用户方案，取代原来的"8s 失败退避"）：
//   原来一旦"精确读"（从 DialogArea 取组件）没拿到就**立刻**扫堆（实测 2150ms ✗），
//   而我又加过一道 8s 失败退避 —— 那道退避**把精确读也一起挡了** ✗ ⇒ 优化等于没生效
//   （实测 `[slow] labSelection 2260ms` 依旧）。
//   现在分级：
//     · **每轮都做精确读**（`_compInDialogs` 取组件，几十 ms ✓，代价可忽略）；
//     · 连续 **读不到 [SEL_SCAN_AFTER_MS] = 10s** 才允许**扫堆兜底**（全堆 2.2s ✗，一次就够）；
//     · 一旦读到（精确或扫堆）⇒ 计时归零，回到"只做精确读"。
//   这样"页面还没建好"的常见情况（精确读暂时拿不到）**不会**把我们拖进 2.2s 的扫描 ✗。
const SEL_SCAN_AFTER_MS = 10000;
let g_selMissAt = 0;
function _selScanDue() {
  const now = Date.now();
  if (g_selMissAt === 0) g_selMissAt = now;
  return (now - g_selMissAt) >= SEL_SCAN_AFTER_MS;
}
function _selScanDone() { g_selMissAt = 0; }
let g_selFastSaid = false;      // "DialogArea 快捷路没走通"的诊断只打一次

// 实例缓存校验：这个地址现在还"像个活的选取页"吗（只读 3 个指针，不扫描）
function _selPickValid(d) {
  try {
    const view = d.add(0x140).readPointer();
    if (!ptrOk(view)) return false;
    // ★放宽（2026-10-16）：原来还要"`+0x28` 的列表长度 ∈ [1,8]"，而实测**组件拿到了却校验不过**
    //   （`[sel] DialogArea 快捷路没走通：组件=0x… 校验=不过 ⇒ 退回堆扫描`）⇒ 那条快捷路白建 ✗。
    //   现在只要求"`+0x140` 指向一个 `Parts*` 结构"（页面本体）+ 列表指针可读；**卡片数**留给
    //   `_selDialogList`（它仍要求 1..8 ✓）与读卡流程去校验 ⇒ 校验不过也不会误判 ✓。
    const vn = _safeKlassOf(view);
    if (vn && vn.indexOf("Parts") >= 0) return true;
    const list = view.add(0x28).readPointer();
    if (!ptrOk(list)) return false;
    const n = _listCount(list);
    return (n > 0 && n <= 8);
  } catch (e) { return false; }
}

function labSelectionSnapshot(diag, force) {
  const _now = Date.now();
  // force=true：绕过 1s 读缓存（用于"卡片滑入动画期间读到屏外坐标"时的重读 —— 否则缓存会让重读
  //   在 1s 内一直返回旧的屏外坐标，白白多等 1s）。命中缓存写回不变，因此 force 之后普通调用仍快。
  if (!diag && !force && g_selCache && (_now - g_selAt) < SEL_TTL_MS) return g_selCache;
  const res = { ticketId: null, cards: [] };
  const k = findClass("DialogLabyrinthSelection");
  if (!k || k.isNull()) { if (diag) diag.err = "未找到 DialogLabyrinthSelection 类"; return res; }
  const rtu = findClass("RectTransformUtility");
  const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
  const cam = _uiCamera();
  if (cam.isNull() || !mW2S) { if (diag) diag.err = "无相机/换算方法"; return res; }
  let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
  // ★缓存优先：命中就用"上次选中的那个实例"（O(1) 校验），跳过 1~2s 的全堆扫描。
  //   页面存活期间实例地址不变；换页/销毁时校验失败 → 自动回到扫描路径。
  const _hitSel = diag ? null : _instCacheGet("labSel", "DialogLabyrinthSelection", _selPickValid);
  // ★同 [_selDialogList]：**先走 DialogArea**（零堆扫描、几十 ms ✓），堆扫描只作兜底（全堆 ≈2.2s ✗）
  let insts = _hitSel ? [_hitSel] : null;
  // ★★多候补（2026-10-17 修"选人第二轮卡死"）：一轮里会有**多次**选择（打完 Boss：遗物 + 选人 ×2），
  //   而**上一轮那个对话框实例会留在 DialogArea 里、卡片列表已清空** ✗ ⇒ 只取"第一个命中"会每轮都
  //   读到那个空实例（实测探针 `d` 40s 不变、`n=0`）⇒ 判"没卡片列表"⇒ 不敢点 ⇒ 卡到丢缓存/自愈重启 ✗。
  //   这里把 DialogArea 里**所有**同类实例都并进来（[_selDlgCandidates]，新的在前），下面按"列表 1..8"挑。
  const _many = _selDlgCandidates(k, 4);
  if (_many.length) {
    if (!insts) insts = _many.slice();
    else for (const c of _many) {
      let dup = false;
      for (let i = 0; i < insts.length; i++) if (String(insts[i]) === String(c)) { dup = true; break; }
      if (!dup) insts.push(c);
    }
  }
  if (!insts) {
    const _cd = _compInDialogs(k);
    if (ptrOk(_cd) && _selPickValid(_cd)) { _instCachePut("labSel", _cd); insts = [_cd]; }
  }
  // ★扫堆兜底：**只在连续 10s 精确读都拿不到时**才做（见 [SEL_SCAN_AFTER_MS]）。
  //   用带校验的扫描：默认上限只数"校验通过"的实例（假命中不占名额）。
  if (!insts && _selScanDue()) {
    insts = _scanKlassValid(k, diag ? 8 : 4, _selPickValid);
    _selScanDone();
  }
  if (diag) { diag.scanned = insts.length; diag.cached = !!_hitSel; diag.probe = []; }
  let pickd = null, firstList = null;
  for (const d of insts) {
    try {
      const view = d.add(0x140).readPointer();
      if (!ptrOk(view)) { if (diag) diag.probe.push({ d: String(d), view: "null" }); continue; }
      const list = view.add(0x28).readPointer();
      if (!ptrOk(list)) { if (diag) diag.probe.push({ d: String(d), view: String(view), list: "null" }); continue; }
      const n = _listCount(list);
      let units = 0;
      if (n > 0 && n <= 8) {
        for (let i = 0; i < n; i++) {
          const it = _listItem(list, i);
          if (ptrOk(it) && ptrOk(it.add(0xB8).readPointer())) units++;
        }
      }
      if (diag) diag.probe.push({ d: String(d), viewCls: klassNameOf(view), list: String(list), n: n, units: units });
      if (n <= 0 || n > 8) continue;
      if (!firstList) firstList = { d: d, list: list, n: n };
      if (!units) continue;                  // 优先"有 unitInfo"的；没有也接受（放宽，见下）
      pickd = { d: d, list: list, n: n };
      break;
    } catch (e) { if (diag) diag.probe.push({ d: String(d), err: "" + e }); }
  }
  if (!_hitSel && pickd) _instCachePut("labSel", pickd.d);   // 首次扫描选出 → 记下来给后续轮次
  if (_hitSel && !pickd) _instCacheDel("labSel");            // 缓存实例已失效 → 丢掉，下轮重扫
  if (!pickd) pickd = firstList;               // 放宽：有卡片列表就先用（unitInfo 偏移可能不同）
  if (!pickd) { if (diag) diag.err = "没有卡片列表(0<n<=8)的 DialogLabyrinthSelection"; return res; }
  if (diag) diag.picked = String(pickd.d) + " n=" + pickd.n;
  try { res.ticketId = pickd.d.add(0x150).readS32(); } catch (e) {}
  for (let i = 0; i < pickd.n; i++) {
    const it = _listItem(pickd.list, i);
    if (!ptrOk(it)) continue;
    const c = { i: i, choiceIndex: null, unitId: null, unitType: null, decideX: null, decideY: null,
                npc: null, topText: null, bottomText: null };
    try { c.choiceIndex = it.add(0x98).readS32(); } catch (e) {}
    const ui = it.add(0xB8).readPointer();
    if (ptrOk(ui)) {
      c.unitId = decOI(ui.add(0x10));
      try { c.unitType = ui.add(0x1C).readS32(); } catch (e) {}
      // ★NPC 判据就在 LabyrinthUnitInfo.UnitType：
      //   eLabyrinthUnitType { USER = 1, NPC = 2, INVALID_VALUE = -1 }
      //   实测：带 NPC 标志的卡 type=2，正常卡 type=1（与画面一致）。
      c.npc = (c.unitType === 2);
    }
    const sb = it.add(0x60).readPointer();          // selectButton =「决定」
    if (ptrOk(sb)) {
      const ss = _screenOf(sb, cam, mW2S);
      if (ss) { c.decideX = Math.round(ss.x); c.decideY = Math.round(H - ss.y); }
    }
    // 卡面内容：按类型取 unitParts(+0x48) / relicParts(+0x50) / ticketParts(+0x58)，谁在显示就用谁。
    // PartsDetail: +0x10 Object  +0x30 UnregisteredObject  +0x40 TopTextLabel  +0x48 BottomLabel
    // （NPC 与否不看这里，看 unitInfo.UnitType —— 见上面 c.npc）
    for (const off of [0x48, 0x50, 0x58]) {
      const pd = it.add(off).readPointer();
      if (!ptrOk(pd)) continue;
      const ob = pd.add(0x10).readPointer();
      if (!ptrOk(ob) || _goActive(ob) !== true) continue;
      const un = pd.add(0x30).readPointer();
      c.unreg = (ptrOk(un) && _goActive(un) === true);   // 仅供参考：实测**不是** NPC 标志
      const tl = pd.add(0x40).readPointer();
      if (ptrOk(tl)) { try { c.topText = _str1(_inv(tl, "get_text")); } catch (e) {} }
      const bl = pd.add(0x48).readPointer();
      if (ptrOk(bl)) { try { c.bottomText = _str1(_inv(bl, "get_text")); } catch (e) {} }
      break;
    }
    res.cards.push(c);
  }
  res.cards.sort((a, b) => (a.decideX == null ? 1e9 : a.decideX) - (b.decideX == null ? 1e9 : b.decideX));
  if (!diag) { g_selCache = res; g_selAt = _now; }      // 只缓存成功结果（诊断调用不污染缓存）
  if (res.cards && res.cards.length) _selScanDone();    // 读到了 ⇒ 扫堆计时归零（回到"只做精确读"）
  return res;
}

// ── 编队页（pageTop=DialogCommonPartyEdit）：读出"编队界面的组成方式"（★只读，不点）──
// 偏移来源：pcrtool/worker/js/peizhi_tools.js（readParty / unitIdFromIcon）+ dump.cs 核对字段顺序：
//   DialogCommonPartyEdit:        +0x110 btnDecide  +0x120 btnCancel  +0x158 exEquipButton
//                                 +0x208 commonPartyEdit(PartsCommonPartyEditAbstract)
//   PartsCommonPartyEditAbstract: +0x38 unitParameterDic(Dictionary<int,UnitParameter>)
//                                 +0x40 PartyEditType   +0x50 selectPlate  +0x68 editPlate
//                                 +0xB8 organizeFixedUnitId  +0xC8 limitedUnitIdList(List<int>)
//   PartsPartyEditPlate:          +0xC8 unitIconArray(IUnitIcon[])  ← "已编成"那一排（含助っ人）
//                                  size@数组+0x18，第 i 项@+0x20+i*8
//   UnitIcon:                     +0x118 unitId(明文 S32)  +0x48 unitNameLabel
//                                 +0x50 statusNameLabel  +0x60 statusValueLabel（切 POWER 排序后=战力）
//     其它子类要穿透：UnitIconGauge→+0xB0、UnitIconTravel/SupportQuest→+0x38、UnitIconSupport→+0x40→+0xB0
const PARTY_ICON_INNER = { UnitIcon: [], UnitIconGauge: [0xB0], UnitIconTravel: [0x38],
                           UnitIconSupportQuest: [0x38], UnitIconSupport: [0x40, 0xB0] };

function partyUnitIdOfIcon(icon) {
  const cls = klassNameOf(icon);
  const path = PARTY_ICON_INNER[cls];
  let p = icon, inner = false;
  if (path && path.length) {
    inner = true;
    for (const off of path) { p = p.add(off).readPointer(); if (!ptrOk(p)) return { unitId: 0, cls: cls, inner: true }; }
  }
  return { unitId: p.add(0x118).readS32(), cls: cls, inner: inner };
}

function partyLabelText(obj, off) {
  try { const l = obj.add(off).readPointer(); if (!ptrOk(l)) return null; return _str1(_inv(l, "get_text")); } catch (e) { return null; }
}

// List<int> → [ints]（复用 listCount 的两套布局兜底）
function _intListOf(p) {
  const out = [];
  try {
    if (!ptrOk(p)) return out;
    const n = listCount(p);
    if (n <= 0 || n > 100) return out;
    const items = p.add(0x10).readPointer();
    if (!ptrOk(items)) return out;
    for (let i = 0; i < n; i++) out.push(items.add(0x20 + i * 4).readS32());   // ★+0x20：数组数据起点
  } catch (e) {}
  return out;
}

// 托管 int[]（如 PartsCommonPartyEditAbstract.scrollUnitIdArray +0x178）：
//   元素布局同 List 的"内部数组" —— 长度 @+0x18、数据从 +0x20 起、每项 4 字节
function _intArrayOf(p, maxN) {
  const out = [];
  try {
    if (!ptrOk(p)) return out;
    const n = p.add(0x18).readS32();
    const lim = Math.min(Math.max(n | 0, 0), maxN || 256);
    for (let i = 0; i < lim; i++) out.push(p.add(0x20 + i * 4).readS32());
  } catch (e) {}
  return out;
}

// Dictionary<int, T> → {count, cap, keys}
// ★IL2CPP 的 Entry 是 **24 字节**（hashCode@+0 / next@+4 / key@+8 / pad@+12 / value@+16）——
//   和 enumDict 用同一套读法。**别按 .NET 的 16 字节步长读**：实测踩过，编组页
//   unitParameterDic 读出 [110301, 102501, 2137126304]（第三项起就是步长错位读到的垃圾）。
function _dictIntKeys(d) {
  const out = { count: -1, cap: -1, keys: [] };
  try {
    if (!ptrOk(d)) return out;
    const e = d.add(0x18).readPointer();
    if (ptrOk(e)) { try { out.cap = e.add(0x18).readS32(); } catch (e2) {} }
    try { out.count = d.add(0x20).readS32(); } catch (e2) {}
    for (const r of enumDict(d)) if (typeof r.key === "number") out.keys.push(r.key);
    if (!(out.count >= 0 && out.count <= 4096)) out.count = out.keys.length;
  } catch (e) {}
  return out;
}

let g_partyCache = null, g_partyAt = 0, g_partyCacheXY = true;   // ★g_partyCacheXY=true 表示缓存里**带坐标**
const PARTY_TTL_MS = 1000;

// ── ★编组页"定案版"定位（2026-10-11 加）──
// 为什么不再靠堆扫描：DialogCommonPartyEdit 是**会被复用的对话框**，堆里既有"别的对象存着这个类
//   指针的字段槽"、也有没初始化的池化 clone —— 旧的弱校验（3 个指针非空 + 数组长度 0~30，n=0 也放行）
//   正好把后者放进来，于是越读越像"页面是空的"（实机：按钮全 None / 图标 0 / 字典 -1）。
//   ★改用和选角色页同一条**已经跑通**的路：DialogArea 的**子节点名**就是 pageTop 给的那个
//   （DialogCommonPartyEdit(Clone)）→ GetComponentInChildren(类, includeInactive=true) → 真实例。
const PARTY_CLS = "DialogCommonPartyEdit";
let g_partyDlg = null, g_partyDlgAt = 0;

/** ★编队页"页签探测"的缓存（键 = 最上层对话框 Transform 指针）—— 见 [labPartySnapshot] 里那段注释：
 *  同一对话框存活期间页签坐标不变，没必要每轮重走整棵子树（那是 `[slow] labParty ~950ms` 的大头）。 */
let g_partyTabsCache = null;

// ★只在这个地址**确实可读**时才去读类名：object_get_class 会在 libil2cpp 里解引用，
//   碰到垃圾/未映射地址会把游戏直接打崩。先用 Process.findRangeByAddress 兜一道（没这个 API 就退回旧行为）。
function _safeKlassOf(p) {
  try {
    if (!ptrOk(p)) return "";
    if (typeof Process.findRangeByAddress === "function") {
      const r = Process.findRangeByAddress(p);
      if (!r || String((r && r.protection) || "").indexOf("r") < 0) return "";
    }
    const nm = klassNameOf(p);
    // ★"err"/"null"/"no-class" 是 `klassNameOf` 的**错误/空返回值**，而它们恰好也是合法标识符
    //   ⇒ 原来那句正则会让它们**通过**，于是一整排槽位打成 `0x10=err`（看着像"读不到"，其实
    //   "读到了但不是对象"）。2026-10-16 抓取结算页时踩到，这里一并挡掉。
    if (nm === "err" || nm === "null" || nm === "no-class" || nm === "?") return "";
    return /^[A-Za-z_][A-Za-z0-9_`.<>+]*$/.test(nm) ? nm : "";
  } catch (e) { return ""; }
}

// 指针槽里"像对象"的列出来（诊断用：一次跑完就知道哪个偏移挂的是什么类）
function _slotDump(o, from, to) {
  const out = [];
  try {
    for (let off = from; off <= to; off += 8) {
      const kn = _safeKlassOf(o.add(off).readPointer());
      if (kn) out.push("0x" + off.toString(16) + "=" + kn);
    }
  } catch (e) {}
  return out;
}

// ── ★战斗结算页"对象字段"抓取（2026-10-16 用户："做一个抓取试试"）────────────────
//   目的：让结算按钮**从内存字段读**（像迷宫结算页 `DialogLabyrinthResult+0x108 →ItemView+0x48` 那样
//   现算坐标），从而战斗中**彻底不扫树**（现在靠 `findTextIn` 全树遍历，一次最坏 6s）。
//   这一次只抓证据：
//   · 最上层对话框：名字 + GameObject 指针 + **字段槽（0x10..0x140）里挂的类名** + 身上的组件实例；
//   · 屏内"像按钮"的节点：GameObject 指针 + **候选组件（类名@实例指针）** + 槽位（只对屏内的 dump）。
//   ⇒ 有了"类名 + 实例指针 + 偏移"，下一版就能按类读字段、算出按钮坐标，不再遍历树。
// ★本构建的 GameObject **没有 `GetComponent(Type)`**（dump.cs 已确认，见 labCharaPageQuick 里的注释）
//   ⇒ 一律用 `GetComponentInChildren(Type, includeInactive=true)`（Unity 语义：**先查自己**、再查子节点）
//   —— 2026-10-16 抓取结算页时踩到：`_compSelf` 全部扑空、退到"子树里第一个"，于是所有节点读成同一个
//   `CustomUILabel@0x7b9e7ce21320`，等于没抓到东西。
function _compSelfTry(go, klass) {
  try {
    if (!ptrOk(go) || !klass || klass.isNull()) return ptr(0);
    const t = _typeOf(klass);
    if (t.isNull()) return ptr(0);
    let c = _inv2oi(go, "GetComponentInChildren", t, 1);
    if (!c || c.isNull()) c = _inv1o(go, "GetComponentInChildren", t);
    if (!c || c.isNull()) c = _inv1o(go, "GetComponent", t);
    return (c && !c.isNull()) ? c : ptr(0);
  } catch (e) { return ptr(0); }
}
const _DUMP_COMPS = ["CustomUILabel", "UIButton", "Button", "MyButton", "MyButtonBase",
                     "ItemView", "CustomButton", "RectTransform", "CanvasRenderer"];
// 组件所在：优先"自己身上"（`_compSelfTry`），没有再退"子树里第一个"（`_compInChildren`）——
// ★这个区分很重要：含子节点会拿到"子树里第一个"，多格页面会全读成同一个对象（2026-10-11 踩过）。
function _dumpComps(go) {
  const r = [];
  for (let i = 0; i < _DUMP_COMPS.length; i++) {
    const cn = _DUMP_COMPS[i];
    try {
      const k = findClass(cn);
      if (!k || k.isNull()) continue;
      let c = _compSelfTry(go, k), how = "self";
      if (!ptrOk(c)) { c = _compInChildren(go, k); how = "child"; }
      if (ptrOk(c)) r.push(cn + "@" + String(c) + "(" + how + ")");
    } catch (e) {}
  }
  return r;
}
// ★★"学一次，之后只校验"的**结算按钮指针缓存**（2026-10-16 用户："试一下"）：
//   背景：胜利页那颗「下一步」**不在结算对话框字段里**（`PartsQuestResultFormer` 里只有文字 ✗），
//   它在**结算 prefab 的骨架**下（`TopParts/BottomParts/ButtonAnimator/Button/NextButton`）——
//   实测这些节点**同一 session 内指针稳定**（两场战斗里 `TopParts@0x7b9f7f8f7280` / `FormerButtons@…7240`
//   完全一样 ✓）。
//   做法：① 先按缓存指针"读 + 校验（节点名 + 屏内）"⇒ 约 3 次调用、**零遍历** ✓；
//         ② 缓存没有/校验不过 ⇒ 走一次**只限 DialogArea** 的遍历去"学"（便宜 ✓，与窄档同量级），
//            并把最优候选记下（优先 `NextButton` ✓ 否则**取最右** —— 两页实测可用按钮都在右下 ✓）；
//         ③ 换版本/换页面结构 ⇒ 校验失败 ⇒ 自动丢弃重学 ✓（**只慢不误** ✓）。
// ★按页面**分槽**缓存（2026-10-16 用户："下一步/重新挑战都能学吗？"）：
//   两个都要能学 ✓，但**绝不能共用一个槽** ✗ —— 否则失败页上会命中"胜利页那颗"（标签是「下一步」）
//   ⇒ 被判成**胜利** ✗。所以按页分槽，并且**用结算对话框本体的存在来判页**（顶框名这里是
//   `DialogBackKeyCapture` 外壳 ✗ 不可用）。
const g_resBtns = {};                                      // "win"/"fail" -> {tr,name,at}
const RES_WIN_LABELS = ["下一步", "次へ", "Next"];
const RES_FAIL_LABELS = ["挑战", "Retry", "重新挑战", "撤退"];
function _resPageKind() {                                  // 便宜：两次 GetComponentInChildren
  try {
    const kf = findClass("DialogQuestFailed");
    if (kf && !kf.isNull() && ptrOk(_compInDialogs(kf))) return "fail";
    const kw = findClass("DialogQuestResultWin");
    if (kw && !kw.isNull() && ptrOk(_compInDialogs(kw))) return "win";
  } catch (e) {}
  return null;
}

// ★★"只认页"的**便宜**探测（2026-10-23，两层化的第 1 层）：
//   战斗中每 300ms 问一次，答"结算页出来了没有" —— DialogArea 在战斗中基本是空的
//   （`childCount = 0`）⇒ 整个判断就是"几次 findClass + 几次 get_childCount"，**个位数毫秒**。
//   ⇒ 认出来之前**什么都不做**（不找按钮、不扫树）；认出来之后 Kotlin 侧先静置 1s 再找按钮。
//   ⚠比 `_resPageKind` 多认一个 `DialogLabyrinthExResult`（EX 迷宫战斗的结算页）：
//     只认 win/fail 的话 EX 战永远"认不出"⇒ 不会进入找按钮流程（会卡到超时）。
function _resPageKindLite() {
  try {
    const p = _resPageKind();
    if (p) return p;
    const ke = findClass("DialogLabyrinthExResult");
    if (ke && !ke.isNull() && ptrOk(_compInDialogs(ke))) return "ex";
  } catch (e) {}
  return null;
}
function _resLabelMatch(which, label) {
  const arr = (which === "fail") ? RES_FAIL_LABELS : RES_WIN_LABELS;
  for (let i = 0; i < arr.length; i++) if (label.indexOf(arr[i]) >= 0) return true;
  return false;
}
function battleResultBtn(minY) {
  const out = { ok: false };
  try {
    const env = _camEnv();
    if (!env) return out;
    const page = _resPageKind();                              // ① 先认页（win/fail/null）
    out.page = page;
    const slot = page ? g_resBtns[page] : null;
    if (slot && ptrOk(slot.tr)) {                             // ② 缓存校验（页一致 + 节点名 + 屏内）
      let good = false;
      try {
        const go = _inv(slot.tr, "get_gameObject");
        const nm = ptrOk(go) ? _str(_inv(go, "get_name")) : "";
        const ss = _screenOf(slot.tr, env.cam, env.mW2S);
        if (nm === slot.name && ss) {
          const x = Math.round(ss.x), y = Math.round(env.H - ss.y);
          if (x >= 0 && x <= env.W && y >= 0 && y <= env.H && (minY == null || y >= minY)) {
            let label = "";
            try {
              const uk = findClass("CustomUILabel");
              const lb = (uk && !uk.isNull()) ? _compInChildren(go, uk) : ptr(0);
              label = ptrOk(lb) ? (_str1(_inv(lb, "get_text")) || "") : "";
            } catch (e) {}
            // ★标签也要**符合本页语义**（win 页必须是「下一步」类、fail 页必须是「挑战」类 ✓）——
            //   不然"结构变了但指针恰好还能读"时会误判胜负 ✗
            if (page == null || _resLabelMatch(page, label)) {
              out.ok = true; out.hit = "缓存"; out.name = nm; out.x = x; out.y = y; out.label = label;
              good = true;
            }
          }
        }
      } catch (e) {}
      if (good) return out;
      if (page) g_resBtns[page] = null;                       // 校验不过 ⇒ 作废、重学
    }
    const root = _dialogAreaTr();                             // ③ 学习（只走 DialogArea ✓ 便宜）
    // ★★2026-10-22 **卡死修复**：原来 `root` 为 null 时会退化成 `_walkNodes()`（**全树**）。而
    //   `root` 为 null 的时刻恰好就是"结算页刚 Instantiate / 场景正在换 view"那一瞬（ViewManager
    //   的 currentView 正在被替换，`_findDialogAreaTr` 的 BFS 拿不到 DialogArea）—— 此时全树遍历最贵
    //   （节点最多 + 一半对象正在 Destroy），且 `_walkNodes()` 的 1.5s TTL 缓存里存的是**上一轮**的
    //   Transform，那批对象可能已销毁 ⇒ 在野指针上 `get_gameObject/get_name` ⇒ 挂死或直接崩。
    //   实测（2026-10-22 22:58 日志）：23:03:02 顶框出现结算页 → 13s 无日志 → 帧冻 10s → 游戏进程没了。
    //   ⇒ 拿不到 DialogArea 就**直接返回"未找到"**（不猜、不扫）：Kotlin 侧还有 `findTextIn(top/dialog)`
    //     这条路（23:06:34 那次命中就是它），它同样拿不到就返回空 ⇒ 功能不受影响，只是不再白烧。
    if (!root) { out.err = "noDialogArea"; return out; }
    const list = _rootWalkNodes(root, "dialog");
    const re = /Next|Retry|End|Decide|Resume|Continue/i;
    const cands = [];
    for (let i = 0; i < list.length && cands.length < 6; i++) {
      const nd = list[i];
      if (!nd || nd.active !== true || !nd.tr) continue;
      if (!re.test(nd.name)) continue;
      const ss = _screenOf(nd.tr, env.cam, env.mW2S);
      if (!ss) continue;
      const x = Math.round(ss.x), y = Math.round(env.H - ss.y);
      if (x < 0 || x > env.W || y < 0 || y > env.H) continue;
      if (minY != null && y < minY) continue;
      let label = "";
      try {
        const go = _inv(nd.tr, "get_gameObject");
        const uk = findClass("CustomUILabel");
        const lb = (uk && !uk.isNull() && ptrOk(go)) ? _compInChildren(go, uk) : ptr(0);
        label = ptrOk(lb) ? (_str1(_inv(lb, "get_text")) || "") : "";
      } catch (e) {}
      if (!label) continue;                                   // 无标签 ⇒ 判不了胜负 ⇒ 不认（不猜 ✓）
      cands.push({ tr: nd.tr, name: nd.name, x: x, y: y, label: label });
    }
    if (!cands.length) return out;
    cands.sort(function (a, b) {                              // 本页语义标签优先 → NextButton → 最右
      const am = (page && _resLabelMatch(page, a.label)) ? 1 : 0;
      const bm = (page && _resLabelMatch(page, b.label)) ? 1 : 0;
      if (am !== bm) return bm - am;
      const an = /Next/i.test(a.name) ? 1 : 0, bn = /Next/i.test(b.name) ? 1 : 0;
      if (an !== bn) return bn - an;
      return b.x - a.x;
    });
    const best = cands[0];
    if (page) g_resBtns[page] = { tr: best.tr, name: best.name, at: Date.now() };
    out.ok = true;
    out.hit = (page ? "" : "（页未认出，不缓存）") + "学习并缓存";
    out.name = best.name; out.x = best.x; out.y = best.y; out.label = best.label;
    out.cands = cands.length;
    return out;
  } catch (e) { out.err = String(e); }
  return out;
}

// ★战斗结算按钮"字段读"（见 RPC 注册处注释）。零扫描：只用 `_compInDialogs` 拿对话框组件。
function battleResultMem() {
  const out = { ok: false, cands: [] };
  try {
    const env = _camEnv();
    if (!env) return out;
    const classes = ["DialogQuestFailed", "DialogQuestResultWin", "DialogLabyrinthExResult", "DialogLabyrinthResult"];
    for (let ci2 = 0; ci2 < classes.length; ci2++) {
      const cn = classes[ci2];
      const k = findClass(cn);
      if (!k || k.isNull()) continue;
      const d = _compInDialogs(k);
      if (!ptrOk(d)) continue;
      // 两级：① 对话框自身字段 ② 它的 `Parts*` 零件字段（胜利页的按钮在零件里 ✓）
      const holders = [{ ptr: d, src: cn }];
      for (let po = 0x10; po <= 0x280; po += 8) {
        try {
          const pp = d.add(po).readPointer();
          const pn = _safeKlassOf(pp);
          if (pn && pn.indexOf("Parts") === 0) {
            holders.push({ ptr: pp, src: cn + "+0x" + po.toString(16) });
            if (holders.length >= 4) break;
          }
        } catch (e) {}
      }
      for (let hi = 0; hi < holders.length; hi++) {
        const h = holders[hi];
        for (let off = 0x10; off <= 0x1f8; off += 8) {
          let cp = null, cl = "";
          try { cp = h.ptr.add(off).readPointer(); cl = _safeKlassOf(cp); } catch (e) { continue; }
          if (!cl || cl.indexOf("CustomUIButton") < 0) continue;
          let tr = _inv(cp, "get_transform");
          if (!tr || tr.isNull()) {
            const g = cp.add(0x20).readPointer();
            if (ptrOk(g)) { try { tr = _inv(g, "get_transform"); } catch (e) {} }
          }
          if (!tr || tr.isNull()) continue;
          const ss = _screenOf(tr, env.cam, env.mW2S);
          if (!ss) continue;
          const x = Math.round(ss.x), y = Math.round(env.H - ss.y);
          if (x < 0 || x > env.W || y < 0 || y > env.H) continue;        // 屏外 = 读歪了，丢掉
          let label = "";
          try {                                                          // ★紧邻槽位 = 配对标签（0x118→0x120）
            const lp = h.ptr.add(off + 8).readPointer();
            const ln = _safeKlassOf(lp);
            if (ln && ln.indexOf("CustomUILabel") >= 0) label = _str1(_inv(lp, "get_text")) || "";
          } catch (e) {}
          out.cands.push({ x: x, y: y, label: label, src: h.src + "+0x" + off.toString(16) });
        }
      }
      if (out.cands.length) break;
    }
    out.ok = out.cands.length > 0;
  } catch (e) { out.err = String(e); }
  return out;
}

function battleResultDump() {
  const out = { ok: false, top: null, topGo: null, topCls: null, topComp: null,
                topCompSlots: [], hits: [], dlgChildren: [], dlgSlots: [], btnCands: [],
                partSlots: [] };
  const t0 = Date.now();
  try {
    const topTr = _topDialogTr();
    if (topTr) {
      const go = _inv(topTr, "get_gameObject");
      if (ptrOk(go)) {
        out.top = _str(_inv(go, "get_name"));
        out.topGo = String(go);
        // ★关键证据：**对话组件对象**上的字段（不是 GameObject 的槽位——Unity 的 GameObject 里
        //   不是"字段→对象"结构，dump 出来只会得到一排假类名）。
        //   迷宫结算页就是这条路：`DialogLabyrinthResult+0x108 → ItemView+0x48` = 「下一步」坐标。
        out.topCls = String(out.top).replace(/\(Clone\)$/, "");
        const k = findClass(out.topCls);
        const c = (k && !k.isNull()) ? _compSelfTry(go, k) : ptr(0);
        if (ptrOk(c)) {
          out.topComp = String(c);
          out.topCompSlots = _slotDump(c, 0x10, 0x1C0);
        }
        // ★顶框常是 `DialogBackKeyCapture`（返回键外壳，没有同名组件）⇒ 顺手把 DialogArea 里
        //   **每个 `Dialog*` 子节点**都记一行（名字 + 拿到的同名组件指针），结算对话框本体就在其中，
        //   下次就知道了"该读哪个类"。
        const d = _dialogAreaTr();
        if (d) {
          let cc = 0;
          try { const rcc = _inv(d, "get_childCount"); cc = rcc.isNull() ? 0 : rcc.add(0x10).readS32(); } catch (e) {}
          const row = [];
          // ★上限 12 → 200（2026-10-16 用户："你直接调200吧，临时的"）：取证期间宁多勿漏
          //   —— 原来 12 个名额可能被遮罩/战斗中对话框占满、结算对话框连"看一眼"的机会都没有。
          for (let i = 0; i < cc && i < 200; i++) {
            const ch = _inv1i(d, "GetChild", i);
            if (ch.isNull()) continue;
            const cgo = _inv(ch, "get_gameObject");
            if (!ptrOk(cgo)) continue;
            const nm = String(_str(_inv(cgo, "get_name"))).replace(/\(Clone\)$/, "");
            const ck = findClass(nm);
            const cc2 = (ck && !ck.isNull()) ? _compSelfTry(cgo, ck) : ptr(0);
            // ★真正的结算对话框本体的**字段槽**（`DialogQuestResultWin` 这类）——这就是要找
            //   `+0x?? → ItemView/Button` 的地方（迷宫结算页是 `DialogLabyrinthResult+0x108`）。
            //   只有 `Dialog*` 且拿到组件时才 dump（其余是遮罩/外壳，没用且贵）。
            // ★上限 3 → **200（临时，2026-10-16 用户："你直接调200吧"）**：3 个名额会被
            //   `PartsDialogDarkFilter`/`DialogFlatoutPlayer` 这类先出现的对话框占满，**真正的结算对话框
            //   被挤掉** ⇒ `btnCands` 里看不到它（21:45 那次就是）。取证期间宁多勿漏。
            // ★只对**相关对话框**取证（2026-10-16 三次修）：`PartsDialogDarkFilter` ×5、
            //   `DialogFlatoutPlayer`、`DialogBackKeyCapture` 这些**不是结算本体**，逐个 dump 0x400 的槽位
            //   是纯浪费（实测 dump 一次 5~6s ⇒ 把游戏帧读冻 ⇒ 被判"卡死"并重启 ✗）。
            if (ptrOk(cc2) && /^(DialogLabyrinth|DialogQuest|DialogBattle|Dialog.*Result)/.test(nm) &&
                out.dlgSlots.length < 200) {
              // ★范围 0x200 → **0x400**（2026-10-16）：`DialogLabyrinthExResult` 的按钮字段在
              //   `0x108/0x118`（在旧范围内 ✓），但普通战的 `DialogQuestResultWin` 在 `0x10..0x1F8`
              //   里**没有任何按钮字段** ⇒ `btnCands` 只报出了 `DialogFlatoutPlayer` ✗。取证期宁可多扫。
              out.dlgSlots.push({ cls: nm, ptr: String(cc2), slots: _slotDump(cc2, 0x10, 0x400) });
              // ★★再挖一层：把该对话框里的 **`Parts*` 零件**的字段也 dump 出来（2026-10-16 六次修）。
              //   实测 `DialogQuestResultWin+0x100=PartsQuestResultFormer`（WIN 页零件）、
              //   `+0x128=PartsQuestResultLater`（金币页零件）—— **「下一步」按钮就在这些零件里**，
              //   所以对话框自己的字段里看不到 `CustomUIButton`（`btnCands` 为空就是这个原因）。
              //   拿到 `零件+0x?? = CustomUIButton` 之后，读法就是：对话框 → 零件 → 按钮组件 → transform → 坐标 ✓。
              const parts = [];
              for (let po = 0x10; po <= 0x280; po += 8) {
                try {
                  const pp = cc2.add(po).readPointer();
                  const pn = _safeKlassOf(pp);
                  if (!pn || pn.indexOf("Parts") !== 0) continue;
                  parts.push({ off: "0x" + po.toString(16), cls: pn, ptr: String(pp),
                               slots: _slotDump(pp, 0x10, 0x1c0) });
                  if (parts.length >= 3) break;
                } catch (e) {}
              }
              if (parts.length) out.partSlots.push({ cls: nm, parts: parts });
              // ★★★决定性一步（2026-10-16）：把该对话框字段里**"像按钮"的引用直接算成屏幕坐标**
              //   —— 哪个偏移算出 (1664,984)/(960,996) 这种坐标，那个偏移就是那颗按钮 ⇒ 之后
              //   `battleResultBtns` 就能"读一个指针 + 3 次调用"拿到坐标，**彻底不遍历树**。
              const cenv = _camEnv();
              if (cenv) {
                const cands2 = [];
                for (let off = 0x10; off <= 0x3f8; off += 8) {   // ★同上：0x1F8 → 0x3F8（2026-10-16）
                  let cp = null, cn2 = "";
                  try { cp = cc2.add(off).readPointer(); cn2 = _safeKlassOf(cp); } catch (e) { continue; }
                  if (!cn2 || !/Button|ItemView|Decide|Next/.test(cn2)) continue;
                  try {
                    // ★三级兜底取 Transform（2026-10-16 四次修）：只用 `组件.get_transform()` 会在
                    //   **结算本体**的按钮上取不到 ⇒ 明明 `0x3a8/0x3b0=CustomUIButton` 就摆在那儿，
                    //   `btnCands` 却是空的 ✗。依次试：组件自身 → `+0x20 GameObject` → `+0x50 Transform`
                    //   （后两个偏移是我们实测过的：`UIButton+0x20=GameObject`、`CustomUILabel+0x50=Transform`）。
                    let tr2 = _inv(cp, "get_transform");
                    if (!tr2 || tr2.isNull()) {
                      const go2 = cp.add(0x20).readPointer();
                      if (ptrOk(go2)) { try { tr2 = _inv(go2, "get_transform"); } catch (e) {} }
                    }
                    if (!tr2 || tr2.isNull()) {
                      const t2 = cp.add(0x50).readPointer();
                      if (ptrOk(t2)) { try { tr2 = _safeKlassOf(t2) ? t2 : null; } catch (e) {} }
                    }
                    if (!tr2 || tr2.isNull()) continue;
                    const ss = _screenOf(tr2, cenv.cam, cenv.mW2S);
                    if (!ss) continue;
                    cands2.push("0x" + off.toString(16) + "=" + cn2 +
                                "(" + Math.round(ss.x) + "," + Math.round(cenv.H - ss.y) + ")");
                  } catch (e) {}
                }
                if (cands2.length) out.btnCands.push({ cls: nm, cands: cands2 });
              }
            }
            row.push(nm + (ptrOk(cc2) ? "@" + String(cc2) : ""));
          }
          out.dlgChildren = row;
        }
      }
    }
    out.t1 = Date.now() - t0;
    let env = null;
    const re = /Button|Btn|Next|Retry|End|Decide/i;
    _rawWalk(function (nd) {
      if (out.hits.length >= 8) return;
      if (nd.active !== true) return;
      if (!re.test(nd.name)) return;
      const go = nd.tr ? _inv(nd.tr, "get_gameObject") : null;
      if (!ptrOk(go)) return;
      if (!env) { env = _camEnv(); if (!env) return; }        // 惰性：只对命中节点算坐标
      const p = _ndXY(nd, env);
      if (!p || !p.onscreen) return;                          // 结算按钮一定在屏内
      const rec = { name: nd.name, go: String(go), x: p.x, y: p.y };
      try {
        const uk = findClass("CustomUILabel");
        const lb = ptrOk(uk) ? _compSelfTry(go, uk) : ptr(0);
        rec.text = ptrOk(lb) ? _str1(_inv(lb, "get_text")) : "";
      } catch (e) { rec.text = "?"; }
      rec.comps = _dumpComps(go);
      // ★★把命中节点的组件**直接算成屏幕坐标**（2026-10-16 六次修，决定性一层）：实测三个「下一步」
      //   节点（`ButtonAnimator(959,984)`/`Button(959,984)`/`NextButton(1664,984)`）**共用同一个
      //   UIButton 组件** ⇒ "这颗组件的 transform 落在哪"就决定了我们该点哪 -> 直接把答案打出来。
      try {
        const cenv2 = _camEnv();
        if (cenv2) {
          rec.compXY = {};
          for (let ci = 0; ci < rec.comps.length; ci++) {
            const s = rec.comps[ci];
            if (s.indexOf("(self)") < 0) continue;
            const cp2 = ptr(parseInt(s.substring(s.lastIndexOf("@") + 1), 16));
            if (!ptrOk(cp2)) continue;
            let tr3 = _inv(cp2, "get_transform");
            if (!tr3 || tr3.isNull()) {
              const g3 = cp2.add(0x20).readPointer();
              if (ptrOk(g3)) { try { tr3 = _inv(g3, "get_transform"); } catch (e) {} }
            }
            if (!tr3 || tr3.isNull()) continue;
            const ss2 = _screenOf(tr3, cenv2.cam, cenv2.mW2S);
            if (ss2) rec.compXY[s.substring(0, s.indexOf("@"))] =
              "(" + Math.round(ss2.x) + "," + Math.round(cenv2.H - ss2.y) + ")";
          }
        }
      } catch (e) {}
      // ★前 3 个命中额外 dump **组件自己的字段槽**：将来"按字段读坐标"就看这里
      //   （迷宫结算页是 `ItemView+0x48`；战斗这边要找等价的 `UIButton/ItemView +0x??`）。
      if (out.hits.length < 3) {
        rec.compSlots = {};
        for (let ci = 0; ci < rec.comps.length; ci++) {
          const s = rec.comps[ci];
          if (s.indexOf("(self)") < 0) continue;
          const at = s.lastIndexOf("@");
          const cp = ptr(parseInt(s.substring(at + 1), 16));
          if (ptrOk(cp)) rec.compSlots[s.substring(0, s.indexOf("@"))] = _slotDump(cp, 0x10, 0x100);
        }
      }
      // ★★父链（2026-10-16 七次修）：**胜利页那颗「下一步」不在结算对话框字段里**
      //   （`PartsQuestResultFormer` 的字段只有 `CustomUILabel` 文字 ✗）⇒ 它是**全局按钮**
      //   （与 `ButtonAnimator(959,984)`/`Button(959,984)` 共用同一个 `UIButton` 组件 ✓ = 全局 prefab）
      //   ⇒ 必须知道它**挂在哪个常驻对象下**。这里从命中节点往上走 6 层，记下每层节点名 + GameObject 指针
      //   （3 次调用/层，很便宜 ✓）。拿到宿主之后，`battleResultMem` 就能照"宿主+偏移"的方式读它 ✓。
      try {
        rec.anc = [];
        let t4 = nd.tr;
        for (let up = 0; up < 6 && t4 && !t4.isNull(); up++) {
          const g4 = _inv(t4, "get_gameObject");
          if (!ptrOk(g4)) break;
          rec.anc.push(_str(_inv(g4, "get_name")) + "@" + String(g4));
          t4 = _inv(t4, "get_parent");
        }
      } catch (e) {}
      out.hits.push(rec);
    }, false, _dialogAreaTr());     // ★只走 DialogArea（2026-10-16 三次修）：全树 + 每命中算坐标/读组件
                                    //   实测 ≈1.8s，而结算按钮**实测都在 DialogArea 里**（`root=dialog` 命中 ✓）
    out.ok = true;
  } catch (e) { out.err = String(e); }
  out.t2 = Date.now() - t0;
  return out;
}

// ★强判据：PartsPartyEditLabyrinth(+0x1A0 = labyrinthTempData) 必须就是我们手里那个 LTD
//   （普通格子和 Boss 格都是这个零件；Boss 的"3 队"以后单独看）。null = 取不到，不做判定。
function _partyLtdOk(abs) {
  try {
    const ltd = abs.add(0x1A0).readPointer();
    let cur = null;
    try { cur = (g_lab && !g_lab.isNull()) ? g_lab : null; } catch (e) {}
    if (!cur) { try { const lf = labLtdFast(); if (lf && lf.ok) cur = lf.ltd; } catch (e) {} }
    if (!ptrOk(ltd) || !cur || !ptrOk(cur)) return null;
    return String(ltd) === String(cur);
  } catch (e) { return null; }
}

// 这个实例真的"像个活的编组页"吗：零件类名必须是 PartsPartyEdit* + editPlate 必须挂上；
//   如果是迷宫零件（PartsPartyEditLabyrinth）还要 LTD 对上（对不上 = 垃圾实例 → 一票否决）
function _partyDlgValid(d) {
  try {
    if (!ptrOk(d)) return false;
    const abs = d.add(0x208).readPointer();
    if (!ptrOk(abs)) return false;
    const cn = _safeKlassOf(abs);
    if (!(cn && cn.indexOf("PartsPartyEdit") === 0)) return false;
    if (!ptrOk(abs.add(0x68).readPointer())) return false;
    if (cn === "PartsPartyEditLabyrinth" && _partyLtdOk(abs) === false) return false;
    return true;
  } catch (e) { return false; }
}

// 从 DialogArea 子树拿"真实例"：倒序遍历（后加的在上层，同 pageTop 口径），
//   优先 active 的那个；同名的 inactive 池化 clone 只作为最后手段并记录下来。
function labPartyFind() {
  const out = { ok: false, ptr: null, node: null, active: null, absCls: null, tried: 0, cands: [], err: null };
  try {
    const k = findClass(PARTY_CLS);
    if (!k || k.isNull()) { out.err = "没有这个类：" + PARTY_CLS; return out; }
    const t = _typeOf(k);
    if (!t || t.isNull()) { out.err = "取 System.Type 失败"; return out; }
    if (!g_dialogArea || g_dialogArea.isNull() || !_dialogAreaOk(g_dialogArea)) {
      g_dialogArea = _findDialogAreaTr();
    }
    if (!g_dialogArea || g_dialogArea.isNull()) { out.err = "没找到 DialogArea"; return out; }
    let cc = 0;
    try { const r = _inv(g_dialogArea, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
    for (let i = cc - 1; i >= 0; i--) {
      const ch = _inv1i(g_dialogArea, "GetChild", i);
      if (ch.isNull()) continue;
      const go = _inv(ch, "get_gameObject");
      if (go.isNull()) continue;
      let c = null;
      try { c = _inv2oi(go, "GetComponentInChildren", t, 1); } catch (e) {}      // includeInactive=true
      if (!c || c.isNull()) { try { c = _inv1o(go, "GetComponentInChildren", t); } catch (e) {} }
      if (!c || c.isNull()) continue;
      out.tried++;
      const act = _goActive(go);
      const good = _partyDlgValid(c);
      let nm = null; try { nm = _str(_inv(go, "get_name")); } catch (e) {}
      const rec = { i: i, node: nm, ptr: String(c), active: act, ok: good };
      try { rec.absCls = _safeKlassOf(c.add(0x208).readPointer()); } catch (e) {}
      if (!good) { try { rec.why = "校验不过（零件类名/plate/LTD 不符）"; } catch (e) {} }
      out.cands.push(rec);
      if (good && act === true) { out.pick = c; out.hit = rec; break; }          // 活跃优先，命中即停
      if (good && !out.pick) { out.pick = c; out.hit = rec; }                    // 先记下，继续找有没有活跃的
    }
    if (out.pick) {
      out.ok = true; out.ptr = out.pick;
      out.node = out.hit ? out.hit.node : null;
      out.active = out.hit ? out.hit.active : null;
      out.absCls = out.hit ? out.hit.absCls : null;
      g_partyDlg = out.pick;
      return out;
    }
    out.err = "DialogArea 子树里没有可用的 " + PARTY_CLS
      + "（同名节点 " + out.tried + " 个，都过不了校验）";
  } catch (e) { out.err = String(e); }
  return out;
}

/**
 * @param diag   诊断模式（多打一些字段）
 * @param skipXY **跳过「队伍那一排」每个 icon 的坐标投影**（2026-11-07）：
 *               槽位坐标只在**点撤人**时要用 ⇒ 改成点击前调 `labPartyIconPos(uid, "槽位")` 现取（~20ms）。
 *               ⚠**角色一览（`scrollIcons`）的坐标仍然要算** —— 决策靠它的 `vis`（裁剪带可见性）
 *                 判断"这个人在不在屏上可点 / 要往哪边滚"，跳了就没法决策了。
 */
function labPartySnapshot(diag, skipXY) {
  const _now = Date.now();
  // ★缓存**按"带不带坐标"分叉**（2026-11-07）：带坐标（full）和 noXY 两种快照内容不同，
  //   共用一个缓存 ⇒ 1s 内先读 noXY 再读 full 会拿到**没有坐标的旧读** ⇒ 槽位坐标读成 null。
  if (!diag && g_partyCache && g_partyCacheXY === !skipXY && (_now - g_partyAt) < PARTY_TTL_MS) return g_partyCache;
  const res = { jsBuild: JS_BUILD, pageTop: null, dlg: null, dlgNode: null, abs: null, absCls: null,
                ltdOk: null, partyEditType: null, nowSelectParty: null, fixedUnitId: null,
                limited: [], limitChara: [], usable: [], special: [], partyDic: {}, scrollIds: [],
                dict: null, plate: null, plateCls: null, nowMember: [], nowMemberN: -1,
                partyDicN: -1, iconCount: -1, icons: [],
                slots: [], scroll: null, scrollCls: null, scrollN: -1, scrollDataMax: null,
                scrollIcons: [], labelTitle: null, buttons: {}, partyTabs: {}, found: null, err: null, diag: null };
  try { res.pageTop = _topDialogName() || ""; } catch (e) {}
  // ── 定位：① 缓存真实例 ② DialogArea 子树按节点名取组件（权威） ③ 兜底堆扫描（旧路）
  let d = null;
  if (!diag) {
    try { if (g_partyDlg && !g_partyDlg.isNull() && _partyDlgValid(g_partyDlg)) d = g_partyDlg; } catch (e) {}
  }
  if (!d) {
    if (!diag && (Date.now() - g_partyDlgAt) < 400) { res.err = "编队页零件未就绪（上一轮刚找过）"; return res; }
    g_partyDlgAt = Date.now();
    const f = labPartyFind();
    res.found = { ok: f.ok, node: f.node, active: f.active, absCls: f.absCls,
                  tried: f.tried, cands: f.cands, err: f.err };
    if (f.ok) d = f.ptr;
    if (!d) {
      // 兜底：老路（堆扫描）——★受 [_scanGate] 闸门约束（2026-10-17）：编队页**每场战斗都来**，
      //   页面刚建时子树里还没有对话框是正常的，此时扫堆（数秒 ✗）纯属自伤。等够 10s 才扫一次。
      if (!_scanGate("party")) {
        res.found.scanSkipped = true;
        res.err = "编组页还没建好（DialogArea 里没有）→ 下一轮再读";
        return res;
      }
      const k = findClass(PARTY_CLS);
      if (k && !k.isNull()) {
        const insts = _scanKlassValid(k, 4, _partyDlgValid);
        res.found.scan = insts.length;
        if (insts.length) d = insts[0];
      }
    }
    if (!d) { res.err = "没找到编组页真实例（" + ((res.found && res.found.err) || "?") + "）"; return res; }
    _scanGateHit("party");
    try { g_partyDlg = d; } catch (e) {}
  }
  res.dlg = String(d);
  try { res.dlgNode = _str(_inv(_inv(d, "get_transform"), "get_name")); } catch (e) {}
  try {                                  // 标题（自检：正常应写着「队伍编组」之类）
    const lt = d.add(0x100).readPointer();
    if (ptrOk(lt)) res.labelTitle = _str1(_inv(lt, "get_text"));
  } catch (e) {}
  const abs = d.add(0x208).readPointer();
  res.abs = String(abs);
  res.absCls = _safeKlassOf(abs);
  res.ltdOk = _partyLtdOk(abs);                    // ★自证：+0x1A0 必须等于我们的 LTD
  try { res.partyEditType = abs.add(0x40).readS32(); } catch (e) {}
  try { res.nowSelectParty = abs.add(0x148).readS32(); } catch (e) {}
  try { res.fixedUnitId = abs.add(0xB8).readS32(); } catch (e) {}
  try { res.limited = _intListOf(abs.add(0xC8).readPointer()); } catch (e) {}
  try { res.limitChara = _intListOf(abs.add(0xC0).readPointer()); } catch (e) {}
  try { res.usable = _intListOf(abs.add(0x150).readPointer()); } catch (e) {}          // 可编入
  try { res.special = _intListOf(abs.add(0x158).readPointer()); } catch (e) {}
  try { res.scrollIds = _intArrayOf(abs.add(0x178).readPointer(), 512); } catch (e) {} // 滚动表全量
  try { res.dict = _dictIntKeys(abs.add(0x38).readPointer()); } catch (e) {}
  try {                                            // ★已编成（权威）：Dictionary<eSelectParty, List<int>>
    const pd = abs.add(0x160).readPointer();
    if (ptrOk(pd)) {
      // ★原始 _count：用来区分"字典真是空的"和"我们读法不对"（两者表现都是"读到空"）
      try { res.partyDicN = pd.add(0x20).readS32(); } catch (e2) {}
      for (const r of enumDict(pd)) {
        if (r && r.key !== undefined && r.key !== null) res.partyDic[String(r.key)] = _intListOf(r.val);
      }
    }
  } catch (e) {}
  const env = _camEnv();
  // ── ★编组页顶部"队伍1/2/3"页签探测（2026-10-16 加）─────────────────────────────
  //   用户截图确认：顶部那三个页签**可以直接点**（"1 2 3"式分页，点谁切谁），右下角那颗只是
  //   "下一页"（显示下一队名，到最后一队才变「挑战」）。要接"切到 2/3 队把残员撤下来"就必须
  //   能定位页签，而游戏里这些节点的**名字我们还没掌握** ⇒ 先把候选（名字含 party/team/tab/
  //   select 的节点）连坐标一起报上来，实机看一眼就能定名（下一版再接真页签坐标）。
  try {
    const tr = _topDialogTr();
    if (tr) {
      // ★★页签探测结果**按对话框实例缓存**（2026-10-17）：同一对话框存活期间页签坐标不变，而原来
      //   **每轮都重走一遍整棵对话框子树**（~200 节点 × ~5 次调用 ⇒ 实测 `[slow] labParty 950~1000ms`
      //   每轮一次 ✗，一进编队页就刷十几次）。现在只在该对话框第一次读时走一遍 ✓。
      const _pk = String(tr);
      const _hit = (g_partyTabsCache && g_partyTabsCache.key === _pk) ? g_partyTabsCache : null;
      if (_hit) {
        res.tabCand = _hit.cand;
        res.partyTabs = _hit.tabs;
      } else {
        const cand = [];
        _rawWalk(function (nd) {
          if (nd.active !== true) return;
          if (!/party|team|tab|select/i.test(nd.name)) return;
          cand.push(nd);
        }, false, tr);
        if (cand.length) {
          const out = [];
          for (const nd of cand) {
            let p = null;
            try { p = env ? _ndXY(nd, env) : null; } catch (e2) {}
            out.push({ name: nd.name, x: p && p.onscreen ? p.x : null,
                       y: p && p.onscreen ? p.y : null });
          }
          res.tabCand = out.slice(0, 40);
          // ★顺带定下"队伍页签"坐标（实机 2026-10-16 探到名字，见日志"编组页页签候选"）：
          //   `PartsPartySelectButton1/2/3` = 顶部「队伍1/队伍2/队伍3」页签（y≈180，x≈195/432/669）。
          //   点它就是**直接切到第 N 队**（右下角那颗只显示"下一队名"、是"下一页"，走到最后一队还会变成
          //   「挑战」，所以撤别队残员必须用这里的页签）。
          //   ⚠别和角色筛选页签（`TabButton_All`/`TabButton_Talent_*`）搞混 —— 名字里没有 PartySelect。
          res.partyTabs = {};
          for (const o of out) {
            const m = /^PartsPartySelectButton(\d+)$/.exec(o.name || "");
            if (m && o.x != null && o.y != null) res.partyTabs[m[1]] = { x: o.x, y: o.y };
          }
        }
        g_partyTabsCache = { key: _pk, cand: res.tabCand, tabs: res.partyTabs };
      }
    }
  } catch (e) {}
  {                                                // 按钮（+ 按钮上的文字：靠文字核对，不靠猜）
    const BTN = { decide: 0x110, cancel: 0x120, exequip: 0x158 };
    const uk = findClass("CustomUILabel");
    for (const nm in BTN) {
      try {
        const b = d.add(BTN[nm]).readPointer();
        if (!ptrOk(b)) continue;
        const it = { ptr: String(b), x: null, y: null, text: null };
        if (env) {
          const ss = _screenOf(b, env.cam, env.mW2S);
          if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); }
        }
        try {
          const go = _inv(b, "get_gameObject");
          if (ptrOk(go) && uk && !uk.isNull()) {
            const lb = _compInChildren(go, uk);
            if (ptrOk(lb)) it.text = _str1(_inv(lb, "get_text"));
          }
        } catch (e) {}
        res.buttons[nm] = it;
      } catch (e) {}
    }
  }
  // ── 已编成那一排：PartsPartyEditPlate(+0x68) ──
  //   +0xC8 unitIconArray(IUnitIcon[])  ← 画面上那排图标（含助っ人/空槽）
  //   +0x178 nowMemberUnitIdList(List<int>) ← ★"现在编了谁"（短、权威，优先信它）
  const plate = abs.add(0x68).readPointer();
  res.plate = String(plate);
  if (ptrOk(plate)) {
    res.plateCls = _safeKlassOf(plate);
    try {
      const nm = plate.add(0x178).readPointer();
      res.nowMemberN = ptrOk(nm) ? listCount(nm) : -1;      // ★原始 List._size（区分真空/读法错）
      res.nowMember = _intListOf(nm);
    } catch (e) {}
    const arr = plate.add(0xC8).readPointer();
    let size = -1; try { size = arr.add(0x18).readS32(); } catch (e) {}
    res.plateArrN = size;
    res.iconCount = size;
    if (size > 0 && size <= 30) {
      for (let i = 0; i < size; i++) {
        let icon = null;
        try { icon = arr.add(0x20 + i * 8).readPointer(); } catch (e) { continue; }
        if (!ptrOk(icon)) continue;
        let uid = 0, cls = "?";
        try { const r = partyUnitIdOfIcon(icon); uid = r.unitId; cls = r.cls; } catch (e) {}
        const it = { i: i, unitId: uid, cls: cls, name: null, statusName: null, statusValue: null };
        if (cls === "UnitIcon") {                  // 只有基础 UnitIcon 保证有这三个 label 偏移
          it.name = partyLabelText(icon, 0x48);
          it.statusName = partyLabelText(icon, 0x50);
          it.statusValue = partyLabelText(icon, 0x60);
        }
        if (env && !skipXY) {
          try { const ss = _screenOf(icon, env.cam, env.mW2S);
                if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); } } catch (e) {}
        }
        res.icons.push(it);
      }
      res.slots = res.icons.filter(function (o) { return o.unitId > 0; })
        .sort(function (a, b) {
          return ((a.x == null ? 1e9 : a.x) - (b.x == null ? 1e9 : b.x)) || (a.i - b.i);
        });
    }
  }
  // ── 候选角色（没编入的，自动选人要点的就是这些）：scrollController(+0x70) ──
  //   +0x70 useUnitIconList(List<IUnitIcon>) ← 一屏 8 个"内容对象"（滑走就换内容）
  const sc = abs.add(0x70).readPointer();
  res.scroll = String(sc);
  if (ptrOk(sc)) {
    res.scrollCls = _safeKlassOf(sc);
    try { res.scrollDataMax = sc.add(0x84).readS32(); } catch (e) {}
    // ★候选列表的裁剪面板：scrollController(+0x70=useUnitIconList) → 基类 +0x20 curUIScrollContent
    //   → +0x28 curUIPanel(UIPanel)。finalClipRegion → 屏幕可见矩形（商店页已验证同款做法）。
    //   用来区分"useUnitIconList 里的缓冲/被裁行"和"真正可见可点的行"（110301 在 y=844 底行即此例）。
    try {
      const _cc = sc.add(0x20).readPointer();
      if (ptrOk(_cc)) {
        const _pan = _cc.add(0x28).readPointer();
        if (ptrOk(_pan)) res.band = (_panelClipScreen(_pan, env).band) || null;
      }
    } catch (e) { res.bandErr = "" + e; }
    const lst = sc.add(0x70).readPointer();
    res.scrollN = ptrOk(lst) ? listCount(lst) : -1;
    const n = (res.scrollN > 0) ? Math.min(res.scrollN, 64) : 0;
    const items = ptrOk(lst) ? lst.add(0x10).readPointer() : ptr(0);
    for (let i = 0; i < n; i++) {
      const ic = ptrOk(items) ? items.add(0x20 + i * 8).readPointer() : ptr(0);
      if (!ptrOk(ic)) continue;
      const it = { i: i, ptr: String(ic), cls: _safeKlassOf(ic), unitId: 0, x: null, y: null };
      try { it.unitId = partyUnitIdOfIcon(ic).unitId; } catch (e) {}
      if (env) {
        try { const ss = _screenOf(ic, env.cam, env.mW2S);
              if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); } } catch (e) {}
      }
      // ★可见性：图标中心是否落在候选列表裁剪带内。带内才算"屏上可点"；
      //   缓冲行/被裁行（y=844 那条底行等）vis=false → 交给 Python 走"滚动把它滚进带内"。
      if (it.x != null && it.y != null && res.band) {
        const _b = res.band, _m = 10;
        it.vis = (it.x >= _b.left + _m && it.x <= _b.right - _m
                  && it.y >= _b.top + _m && it.y <= _b.bottom - _m);
      } else {
        it.vis = (it.x != null);            // 没 band 兜底：有坐标就算可见（旧行为，避免回归）
      }
      res.scrollIcons.push(it);
    }
  }
  if (diag) {                                      // 现场：每个对象槽里挂的是什么类（一次跑完就能定案）
    res.diag = { dlg: _slotDump(d, 0x10, 0x268), abs: _slotDump(abs, 0x10, 0x1B0) };
    if (ptrOk(plate)) res.diag.plate = _slotDump(plate, 0x10, 0x1B0);
    if (ptrOk(sc)) res.diag.scroll = _slotDump(sc, 0x10, 0xD0);
  }
  if (!diag) { g_partyCache = res; g_partyAt = _now; g_partyCacheXY = !skipXY; }
  return res;
}

/**
 * ★★「编队决策**精简读**」（2026-11-07 用户："只取需要的内容就好了，不用都取吧"）。
 *
 * `labParty` 是**整页快照**：29 个 icon 每个带 3 个中文 label（87 次 native 读）+ 40 个页签候选
 *   + 一堆诊断字段（plate/limited/special/dict/…）⇒ 每轮编队页都要序列化/传输/解析一大坨，
 *   而**决策根本用不到**（Kotlin 侧从来没读过 name/statusName/statusValue/limited/special/…）。
 *
 * 这里只取**决策真正要用的**：
 *   · `nowMember`   权威队伍名单（谁在队里）
 *   · `icons`       槽位：unitId + **x/y**（撤人按 x 从小到大一批点完 ⇒ 坐标必须带；
 *                  只投影这 5 个，便宜）
 *   · `scrollIcons` 一览当前窗口：unitId + x/y + vis（**要投影**：vis 决定"谁能直接点、谁要滚"）
 *   · `usable` / `scrollIds` / `partyDic` / `nowSelectParty` / `band`
 *   · `buttons`     「战斗开始」「特别装备」坐标（要点 ⇒ 必须）
 *   · `err` / `ltdOk`
 * **去掉**：每个 icon 的 name/statusName/statusValue、页签候选（另走 `labPartyTabs`）、全部诊断字段。
 *
 * ⚠字段名与 `labParty` **保持一致** ⇒ Kotlin 侧 `PartyPlan` / `MazeLoop` 一行都不用改。
 */
function labPartyLiteSnapshot() {
  const res = { top: "", nowMember: [], icons: [], scrollIcons: [], usable: [], scrollIds: [],
                partyDic: {}, nowSelectParty: null, buttons: {}, band: null,
                ltdOk: null, err: null };
  try {
    res.top = _normWindowName(_topDialogName() || "");
    let d = null;
    try { if (g_partyDlg && !g_partyDlg.isNull() && _partyDlgValid(g_partyDlg)) d = g_partyDlg; } catch (e) {}
    if (!d) {
      const f = labPartyFind();
      if (f && f.ok) { d = f.ptr; try { g_partyDlg = d; } catch (e) {} }
    }
    if (!d || d.isNull()) { res.err = "没找到编组页真实例"; return res; }
    const abs = d.add(0x208).readPointer();
    if (!ptrOk(abs)) { res.err = "编组页 abs 读不到"; return res; }
    try { res.ltdOk = _partyLtdOk(abs); } catch (e) {}
    try { res.nowSelectParty = abs.add(0x148).readS32(); } catch (e) {}
    try { res.usable = _intListOf(abs.add(0x150).readPointer()); } catch (e) {}
    try { res.scrollIds = _intArrayOf(abs.add(0x178).readPointer(), 512); } catch (e) {}
    try {
      const pd = abs.add(0x160).readPointer();
      if (ptrOk(pd)) {
        for (const r of enumDict(pd)) {
          if (r && r.key !== undefined && r.key !== null) res.partyDic[String(r.key)] = _intListOf(r.val);
        }
      }
    } catch (e) {}
    const env = _camEnv();
    // ── 按钮（要点 ⇒ 坐标必须有）──
    {
      const BTN = { decide: 0x110, cancel: 0x120, exequip: 0x158 };
      for (const nm in BTN) {
        try {
          const b = d.add(BTN[nm]).readPointer();
          if (!ptrOk(b)) continue;
          const it = { x: null, y: null };
          if (env) {
            const ss = _screenOf(b, env.cam, env.mW2S);
            if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); }
          }
          res.buttons[nm] = it;
        } catch (e) {}
      }
    }
    // ── 槽位（**要坐标**：撤人按 x 从小到大一批点完，见下方说明）──
    const plate = abs.add(0x68).readPointer();
    if (ptrOk(plate)) {
      try { res.nowMember = _intListOf(plate.add(0x178).readPointer()); } catch (e) {}
      const arr = plate.add(0xC8).readPointer();
      let size = -1; try { size = arr.add(0x18).readS32(); } catch (e) {}
      if (size > 0 && size <= 30) {
        for (let i = 0; i < size; i++) {
          let ic = null;
          try { ic = arr.add(0x20 + i * 8).readPointer(); } catch (e) { continue; }
          if (!ptrOk(ic)) continue;
          let uid = 0;
          try { uid = partyUnitIdOfIcon(ic).unitId; } catch (e) {}
          const it = { i: i, unitId: uid, x: null, y: null };
          if (env) {
            try { const ss = _screenOf(ic, env.cam, env.mW2S);
                  if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); } } catch (e) {}
          }
          res.icons.push(it);
        }
      }
    }
    // ── 一览（要坐标 + vis：vis 决定"谁能直接点、谁要滚"）──
    const sc = abs.add(0x70).readPointer();
    if (ptrOk(sc)) {
      try {
        const _cc = sc.add(0x20).readPointer();
        if (ptrOk(_cc)) {
          const _pan = _cc.add(0x28).readPointer();
          if (ptrOk(_pan)) res.band = (_panelClipScreen(_pan, env).band) || null;
        }
      } catch (e) {}
      const lst = sc.add(0x70).readPointer();
      const n = ptrOk(lst) ? Math.min(listCount(lst), 64) : 0;
      const items = ptrOk(lst) ? lst.add(0x10).readPointer() : ptr(0);
      for (let i = 0; i < n; i++) {
        const ic = ptrOk(items) ? items.add(0x20 + i * 8).readPointer() : ptr(0);
        if (!ptrOk(ic)) continue;
        const it = { i: i, unitId: 0, x: null, y: null, vis: false };
        try { it.unitId = partyUnitIdOfIcon(ic).unitId; } catch (e) {}
        if (env) {
          try { const ss = _screenOf(ic, env.cam, env.mW2S);
                if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); } } catch (e) {}
        }
        if (it.x != null && it.y != null && res.band) {
          const _b = res.band, _m = 10;
          it.vis = (it.x >= _b.left + _m && it.x <= _b.right - _m
                    && it.y >= _b.top + _m && it.y <= _b.bottom - _m);
        } else {
          it.vis = (it.x != null);            // 没 band 兜底：有坐标就算可见（旧行为）
        }
        res.scrollIcons.push(it);
      }
    }
  } catch (e) { res.err = "" + e; }
  return res;
}

/**
 * ★「编组页顶部页签」（队伍1/2/3）。
 *
 * ★★**直接命中，不用遍历整棵对话框子树**（2026-11-07 用户问"boss页会不会有直接命中的方法"——有）：
 *   `abs(DialogCommonPartyEdit+0x208)` → `+0x50` = **PartsPartyEditSelectPlate**
 *     → `+0x28` = **PartsPartySelectButton[]**（dump.cs 实证）← 就是「队伍1/2/3」那排页签
 *       每个元素 `PartsPartySelectButton`：`+0x20` = CustomUIButton btn（投影取坐标），
 *       GameObject 名字里带队伍号（`PartsPartySelectButton2(Clone)`）。
 *   ⇒ 普通战数组里**只有 1 个**（没有 2/3 队）⇒ 一眼就知道"不用切队"，**连遍历都不用**。
 *
 * 兜底：直接命中失败（偏移对不上 / 类名不符）才退回老的 `_rawWalk` 整树遍历。
 */
function labPartyTabsSnapshot() {
  const res = { top: "", tabCand: [], partyTabs: {}, err: null };
  try {
    res.top = _normWindowName(_topDialogName() || "");
    let d = null;
    try { if (g_partyDlg && !g_partyDlg.isNull() && _partyDlgValid(g_partyDlg)) d = g_partyDlg; } catch (e) {}
    if (!d) {
      const f = labPartyFind();
      if (f && f.ok) { d = f.ptr; try { g_partyDlg = d; } catch (e) {} }
    }
    if (!d || d.isNull()) { res.err = "没找到编组页真实例"; return res; }
    const env = _camEnv();
    const abs = d.add(0x208).readPointer();
    if (ptrOk(abs)) {
      // ── ① 直接命中：`abs+0x50` = PartsPartyEditSelectPlate → `+0x28` = 页签数组 ──
      try {
        const plate = abs.add(0x50).readPointer();
        if (ptrOk(plate)) {
          const arr = plate.add(0x28).readPointer();
          let n = -1; try { n = arr.add(0x18).readS32(); } catch (e) {}
          if (n > 0 && n <= 8) {
            const cand = [], tabs = {};
            let okAll = true;
            for (let i = 0; i < n; i++) {
              // ★★2026-11-07 修（实测 19:56:32「[slow] labPartyTabs 749ms」）：
              //   IL2CPP **数组**：length 在 +0x18、**数据从 +0x20 开始**（+0x10 是 bounds 指针！）。
              //   原来多读了一层 `arr.add(0x10).readPointer()` ⇒ 元素指针全是垃圾 ⇒ 类名校验失败
              //   ⇒ **每次都退回整树遍历**（~750ms）。别的地方读数组都是直接 `arr+0x20+i*8`（见 labPartyIconPos）。
              const psb = ptrOk(arr) ? arr.add(0x20 + i * 8).readPointer() : ptr(0);
              if (!ptrOk(psb)) { okAll = false; break; }
              const cls = klassNameOf(psb);
              // ★类名校验：偏移猜错时会读到别的东西 ⇒ 别硬用
              if (cls && cls.indexOf("PartsPartySelectButton") < 0) { okAll = false; break; }
              let nm = "";
              try { nm = _str(_inv(_inv(psb, "get_transform"), "get_name")); } catch (e) {}
              let x = null, y = null;
              try {
                const btn = psb.add(0x20).readPointer();
                if (ptrOk(btn) && env) {
                  const ss = _screenOf(btn, env.cam, env.mW2S);
                  if (ss) { x = Math.round(ss.x); y = Math.round(env.H - ss.y); }
                }
              } catch (e) {}
              cand.push({ name: nm, x: x, y: y });
              const m = /^PartsPartySelectButton(\d+)/.exec(nm || "");
              if (m && x != null && y != null) tabs[m[1]] = { x: x, y: y };
            }
            if (okAll) {
              res.tabCand = cand;
              res.partyTabs = tabs;
              res.hit = "direct";           // 命中方式（direct=直接读数组 / walk=整树兜底）
              return res;
            }
            res.err = "直接命中失败（offset 或类名对不上）→ 退回整树遍历";
          } else {
            res.err = "页签数组读不到（n=" + n + "）→ 退回整树遍历";
          }
        }
      } catch (e) { res.err = "" + e; }
    }
    // ── ② 兜底：整棵对话框子树遍历（老办法，慢 ~900ms）──
    const tr = _topDialogTr();
    if (!tr) { res.err = "没有顶层对话框"; return res; }
    const _pk = String(tr);
    const _hit = (g_partyTabsCache && g_partyTabsCache.key === _pk) ? g_partyTabsCache : null;
    if (_hit) {
      res.tabCand = _hit.cand;
      res.partyTabs = _hit.tabs;
      res.hit = "walk(cached)";
      return res;
    }
    const cand = [];
    _rawWalk(function (nd) {
      if (nd.active !== true) return;
      if (!/party|team|tab|select/i.test(nd.name)) return;
      cand.push(nd);
    }, false, tr);
    const out = [];
    for (const nd of cand) {
      let p = null;
      try { p = env ? _ndXY(nd, env) : null; } catch (e2) {}
      out.push({ name: nd.name, x: p && p.onscreen ? p.x : null,
                 y: p && p.onscreen ? p.y : null });
    }
    res.tabCand = out.slice(0, 40);
    res.partyTabs = {};
    for (const o of out) {
      const m = /^PartsPartySelectButton(\d+)/.exec(o.name || "");
      if (m && o.x != null && o.y != null) res.partyTabs[m[1]] = { x: o.x, y: o.y };
    }
    res.hit = "walk";
    g_partyTabsCache = { key: _pk, cand: res.tabCand, tabs: res.partyTabs };
  } catch (e) { res.err = "" + e; }
  return res;
}

// ══════════════════════════════════════════════════════════════════════════════
// ★特别装备（EX 装备）三连页 —— 2026-10-11 实机节点名 + dump.cs 字段核对
//   ①「特别装备设定」  容器 DialogCommonFullSize(Clone) → 零件 PartsDialogPartyExtraEquip
//       按钮 ThreeButtonLeft/Center/Right = 取消 / 自动装备 / 装备确定
//       ★isChangeExtraEquip() = **有没有改动** ⇒ 决定点「装备确定」还是「取消」（= 用户说的"没变化就不亮"）
//   ②「自动特别装备设定」容器 DialogCommonBig(Clone) → 零件 PartsDialogRecommendSettingExtraEquip
//       4 行 AllSlot/WeaponSlot/ArmorSlot/AccessorySlot：SelectSort_XXX / SelectSort_XXX_Label
//       复选框 PartsDialogCommonItem_CHECK_BOX；按钮 TwoButtonLeft/Right = 取消 / 确认
//   ③「优先属性值」    容器 DialogCommonFullSize(Clone) → 零件 PartsDialogExtraEquipSort
//       选项 filterList1..3 里的 PartsUnitSortTypeToggle(Clone)；按钮 TwoButtonLeft/Right
//   ★① 和 ③ 的**节点名一模一样**（DialogCommonFullSize(Clone)）⇒ pageTop 分不开，
//     必须靠"里面的零件类名"定位（下面 _findPartTop 就是从 DialogArea 最上层往下找零件）。
// ══════════════════════════════════════════════════════════════════════════════
const EXEQ_PARTY_CLS = "PartsDialogPartyExtraEquip";
const EXEQ_SETTING_CLS = "PartsDialogRecommendSettingExtraEquip";
const EXEQ_SORT_CLS = "PartsDialogExtraEquipSort";

// 无参 bool 方法 → true/false（如 isChangeExtraEquip() / get_IsSelected()）
function _invBool(obj, name) {
  try {
    if (!ptrOk(obj)) return null;
    const m = _mInfo(g_il2cpp.object_get_class(obj), name);
    if (!m) return null;
    const ex = Memory.alloc(8); ex.writePointer(ptr(0));
    const r = g_il2cpp.runtime_invoke(m, obj, ptr(0), ex);
    if (r.isNull() || !ex.readPointer().isNull()) return null;
    return r.add(0x10).readU8() !== 0;
  } catch (e) { return null; }
}

// ★★特别装备三连页的**组件查找缓存**（2026-11-08）：
//   `GetComponentInChildren` 要遍历整棵子树 —— 在**编组页**上问签名极贵（那棵树很大，
//   实测能把"预览页出现→点挑战"拖到 7~8s）。
//   缓存"上一次查过的那个对话框对象 ⇒ 结果"，指针相同就直接复用：
//     · 正缓存：命中 ⇒ 直接拿 comp/top（省掉整棵子树遍历，EX 页内轮询几乎零成本）
//     · 负缓存：这个对话框**里没有** EX 组件（编组页）⇒ 直接返回"不在三连页"（同样省掉遍历）
//   指针不同（换对话框 / 游戏重启后地址复用）⇒ 重新查并更新缓存。
//   ⚠ 负缓存成立的前提：EX 三连页是 DialogArea 下**独立的**顶层对话框（不是编组页 go 的子对象）——
//     否则编组页上 `GetComponentInChildren` 会假命中 EX 类。实测在编组页上签名恒为 ""，前提成立。
let _exeqCache = null;   // { dlg: NativePointer, top: 'party'|'setting'|'sort'|null, comp: ptr, dlgName: string }

// ★找出"当前这一层"是哪一页：从 DialogArea **最上层**往下一层层看，**第一个**含我们三类零件之一的
//   激活对话框就是它 —— 返回 {top:'party'/'setting'/'sort', comp, dlg, dlgName}。
//   为什么必须"第一个命中即返回"：① 特别装备设定那层页在 ②③ 打开时**仍然 active**（叠在下面），
//   如果反过来"谁先匹配谁赢"（或按固定优先级挑），就会永远读到下面那一层（2026-10-11 踩过）。
function _findExTopPart() {
  try {
    const cls = { party: EXEQ_PARTY_CLS, setting: EXEQ_SETTING_CLS, sort: EXEQ_SORT_CLS };
    const kk = {};
    for (const nm in cls) { const k = findClass(cls[nm]); if (k && !k.isNull()) kk[nm] = k; }
    if (!Object.keys(kk).length) return null;
    const tt = {};
    for (const nm in kk) { const t = _typeOf(kk[nm]); if (t && !t.isNull()) tt[nm] = t; }
    // ★★**只认最上层**（与 `_topDialogName` 完全同一套判定）——
    //   最上层不是 EX 页 ⇒ 返回 null（"当前不在三连页"），**绝不往下一层回退**。
    //   为什么不能回退：①特别装备设定那层页在 ②③ 打开时**仍然 active**（叠在下面），
    //   回退就会永远读到 ①（旧页）⇒ 签名不变 ⇒ 误判"点击没生效" ⇒ 多点 ⇒ 点错按钮。
    const top = _topDialogGo();
    if (!top) return null;
    const go = top.go;
    const dlgName = top.name;
    // 缓存（同一个对话框对象 ⇒ 直接复用；正缓存 2s TTL / 负缓存 1.5s TTL）
    if (_exeqCache && _exeqCache.dlg.equals(go)) {
      if (_exeqCache.top && Date.now() - (_exeqCache.at || 0) < 2000) {
        return { top: _exeqCache.top, cls: cls[_exeqCache.top], comp: _exeqCache.comp, dlg: go, dlgName: dlgName };
      }
      if (!_exeqCache.top && Date.now() - (_exeqCache.at || 0) < 1500) return null;
    }
    // ★只在这一层里找 EX 组件
    for (const nm in tt) {
      let c = null;
      try { c = _inv2oi(go, "GetComponentInChildren", tt[nm], 1); } catch (e) {}
      if (!c || c.isNull()) { try { c = _inv1o(go, "GetComponentInChildren", tt[nm]); } catch (e) {} }
      if (c && !c.isNull()) {
        _exeqCache = { top: nm, comp: c, dlg: go, dlgName: dlgName, at: Date.now() };
        return { top: nm, cls: cls[nm], comp: c, dlg: go, dlgName: dlgName };
      }
    }
    // ★这一层没有 EX 组件 ⇒ 负缓存（编组页上反复问签名时省掉整棵大树遍历）
    _exeqCache = { top: null, comp: null, dlg: go, dlgName: dlgName, at: Date.now() };
    return null;
  } catch (e) {}
  return null;
}

// 某层对话框**子树内**的按钮（按名字）→ 坐标。
// ★必须限定在该子树里：Two/ThreeButton* 在别的弹窗里也有同名的，全树找会串坐标
// ★★通用按钮的**字段偏移**（dump.cs `DialogFundationObject`）：
//   oneButtonWhite 0x28 / oneButtonBlue 0x38 / twoLeft 0x48 / twoRight 0x58
//   threeLeft 0x70 / threeCenter 0x80 / threeRight 0x98
//   路径：对话框组件(DialogCommonAbstract) → +0x118 fundationObject → +偏移 = CustomUIButton
const _DLG_BTN_OFF = {
  OneButtonWhite: 0x28, OneButtonBlue: 0x38,
  TwoButtonLeft: 0x48, TwoButtonRight: 0x58,
  ThreeButtonLeft: 0x70, ThreeButtonCenter: 0x80, ThreeButtonRight: 0x98,
};
let _dlgAbsType = null;

/**
 * ★★**按需**取对话框的通用按钮（**不遍历子树**）—— 2026-11-08。
 *
 * 为什么要它：遍历整棵子树找按钮，在①页要 **350ms+**（DialogCommonFullSize =
 * 编组页的大容器，树里有几十张卡 + 一堆按钮）⇒ 这就是"点一下等半秒"的大头。
 * 而按钮其实是**对话框的固定字段** ⇒ 直接按偏移取 ⇒ 几 ms。
 */
function _dlgBtnByField(dlgComp, name, env) {
  const off = _DLG_BTN_OFF[name];
  if (off == null || !dlgComp || dlgComp.isNull() || !env) return null;
  try {
    const fo = dlgComp.add(0x118).readPointer();      // DialogCommonAbstract.foundationObject
    if (!ptrOk(fo)) return null;
    const btn = fo.add(off).readPointer();            // CustomUIButton
    if (!ptrOk(btn)) return null;
    // ★★**必须核对名字 + 激活状态**（2026-11-08 实测踩到）：
    //   对话框的按钮**字段全都存在**（不管这一页显示的是 One/Two/Three 哪种行），
    //   例如①页显示 ThreeButton 行时，twoRightButton 字段仍指向一个对象 ⇒
    //   直接按偏移取会**拿到"这一页根本没显示的按钮"**（坐标还是投影得出来）⇒ 点错。
    //   ⇒ 名字要对得上、而且必须**在层级里是激活的**。
    let g = null;
    try { g = _inv(btn, "get_gameObject"); } catch (e) {}
    if (!g || g.isNull()) return null;
    let nm = "";
    try { nm = _str(_inv(g, "get_name")); } catch (e) {}
    if (nm !== name) return null;
    let act = false;
    try { act = _invBool(g, "get_activeInHierarchy"); } catch (e) {}
    if (!act) return null;
    const tr = _inv(btn, "get_transform");
    if (!ptrOk(tr)) return null;
    const p = _ndXY({ tr: tr }, env);
    if (!p || !p.onscreen) return null;
    return { name: name, x: p.x, y: p.y, id: String(g) };
  } catch (e) { return null; }
}

/**
 * ★拿最上层对话框的 **`DialogBase`** 组件（不是 `DialogCommonAbstract`）。
 *
 * 为什么要它：`DialogCommonAbstract` 只是 `DialogBase` 的一个子类（通用确认框），
 *   而**三选一页 `DialogLabyrinthSelection` 是 `DialogBase` 的直接子类** ⇒ 只按
 *   `DialogCommonAbstract` 找会拿不到它，把这个页面的退出 tween / isProcess 全漏掉。
 *   `isProcess@0xB4`、`outMainTweenAlpha@0xE0` 都是 `DialogBase` 的字段，对两类页面通用。
 */
let _dlgBaseType = null;
function _dlgBaseComp(go) {
  try {
    if (!_dlgBaseType) {
      const k = findClass("DialogBase");
      if (!k || k.isNull()) return null;
      const t = _typeOf(k);
      if (!t || t.isNull()) return null;
      _dlgBaseType = t;
    }
    let c = null;
    try { c = _inv1o(go, "GetComponent", _dlgBaseType); } catch (e) {}
    if (!c || c.isNull()) {
      try { c = _inv2oi(go, "GetComponentInChildren", _dlgBaseType, 1); } catch (e) {}
    }
    return (c && !c.isNull()) ? c : null;
  } catch (e) { return null; }
}

/** ★拿最上层对话框的 `DialogCommonAbstract` 组件（按钮地基在它里面） */
function _dlgCommonComp(go) {
  try {
    if (!_dlgAbsType) {
      const k = findClass("DialogCommonAbstract");
      if (!k || k.isNull()) return null;
      const t = _typeOf(k);
      if (!t || t.isNull()) return null;
      _dlgAbsType = t;
    }
    let c = null;
    try { c = _inv1o(go, "GetComponent", _dlgAbsType); } catch (e) {}
    if (!c || c.isNull()) {
      try { c = _inv2oi(go, "GetComponentInChildren", _dlgAbsType, 1); } catch (e) {}
    }
    return (c && !c.isNull()) ? c : null;
  } catch (e) { return null; }
}

/**
 * ★★通用弹窗「按钮轻读」（2026-11-09）—— 与 [dialogButtonsSnapshot] 的"子树遍历"相对。
 *
 * 动机：dialog() 走 `_rawWalk(DialogArea)`，设备日志实测冷遍历 0.5~0.6s、大结算页 3~3.9s，
 *   且点击间隔 270ms > 180ms 快照 TTL ⇒ 每下点击都要重付一次。EX 三连页早就把这套读法
 *   换成了"按字段直取"（labExBtn，30ms）⇒ 通用弹窗照搬：
 *   `DialogCommonAbstract +0x118 fundationObject → One/Two/ThreeButton*`（dump.cs 核对），
 *   `DialogCommonBig / Middle / FullSize` 都是它的**直接子类、无自有字段** ⇒ 偏移通用。
 *
 * ★零遍历、零扫堆、零索引路径学习，只做：
 *   `_topDialogGo()`（与 labTop 同一个"最上层"权威判定）→ `GetComponent(DialogCommonAbstract)`
 *   → 7 个字段读 + 名字/activeInHierarchy 校验 → **只投影命中的那几颗**。
 * ★[topCls] 对不上不给按钮（不越框点）；一颗都没命中 ⇒ ok=false，
 *   调用方退回 `dialog()` 兜底（壳结构变了/不是通用弹窗），绝不硬点。
 */
function labDlgBtnSnapshot(topCls) {
  const out = { ok: false, top: "", buttons: [], ms: 0, err: null, src: "field" };
  const t0 = Date.now();
  const norm = function (s) { return String(s || "").replace(/\(Clone\)$/, ""); };
  try {
    const t = _topDialogGo();
    const top = t ? norm(t.name) : "";
    out.top = top;
    if (topCls && top !== norm(topCls)) { out.err = "top-mismatch"; out.ms = Date.now() - t0; return out; }
    if (!t || !top) { out.err = "no-dialog"; out.ms = Date.now() - t0; return out; }
    const comp = _dlgCommonComp(t.go);
    if (!comp) { out.err = "no-DialogCommonAbstract"; out.ms = Date.now() - t0; return out; }
    const env = _camEnv();
    if (!env) { out.err = "no-cam"; out.ms = Date.now() - t0; return out; }
    const names = ["OneButtonWhite", "OneButtonBlue", "TwoButtonLeft", "TwoButtonRight",
                   "ThreeButtonLeft", "ThreeButtonCenter", "ThreeButtonRight"];
    for (let i = 0; i < names.length; i++) {
      const b = _dlgBtnByField(comp, names[i], env);
      if (b) out.buttons.push(b);
    }
    out.ok = out.buttons.length > 0;
    if (!out.ok) out.err = "no-active-button";
  } catch (e) { out.err = String(e); }
  out.ms = Date.now() - t0;
  return out;
}

/**
 * ★★通用弹窗「关闭已启动」轻读（2026-11-09 定案）—— 替代"等窗口消失"当"这一下点生效了"。
 *
 * 为什么不能用 labTop 当判据：窗口关闭动画固定 200ms、退出 tween 跑完还要 ~300ms，
 *   对象到 **+800ms** 才整体消失 ⇒ 150ms / 270ms 的 probe 必然读到残留窗口
 *   ⇒ 判"没生效" ⇒ 补点第二下（第二下可能落在已经出现的地图节点上）。
 *
 * 实测 7 段关闭（门票 Big/Middle、遗物 Big、战后选人 Middle，2026-11-09）：
 *   · 按钮层 mState / initEnable / IsPressed / curBoxCollider **全程不变**
 *     （游戏关弹窗不经"禁用按钮"，回调里直接 Close() ⇒ 按钮上没有"已点击"标记）
 *   · `DialogBase.isProcess`(0xB4) 0→1 与退出 tween（mStarted=1、mFactor 0→1）
 *     在 **+200~300ms** 启动
 *   · 整条页（顶层 Dialog → DialogCommonAbstract）到 +800ms 才消失
 * ⇒ 用「退出动画已启动」当"点生效了"，比等窗口消失早 500~600ms；
 *   没启动就继续重试 ⇒ 不会"点了没反应就放弃"。
 *
 * ★零 hook、只读字段（不 invoke），实测 3~6ms。返回：
 *   {top, raw, path, proc, started, factor, closing, ms, err}
 *   closing = 退出动画正在跑（started=1 且 factor<0.95），或整条页已消失。
 *   ⚠基线 factor 可能是 1（上一次 out tween 跑完的残留）、proc 可能是 1（打开过程中），
 *     所以只能认「started 变 1 且 factor 从小往上涨」，**不能只看 proc==1**。
 */
function labDlgClosingSnapshot() {
  const out = { top: "", raw: "", path: "", proc: null, started: null, factor: null,
                closing: false, ms: 0, err: null };
  const t0 = Date.now();
  try {
    const t = _topDialogGo();
    out.raw = t ? String(t.name || "") : "";
    out.top = _normWindowName(out.raw);
    if (!t || !t.go || t.go.isNull()) {
      out.path = "no-top-dialog"; out.closing = true; out.ms = Date.now() - t0; return out;
    }
    // ★先按 DialogCommonAbstract 找（有类型缓存、快），拿不到再按 DialogBase 找 ——
    //   三选一（DialogLabyrinthSelection）是 DialogBase 直接子类，只按前者会整个漏掉。
    const dc = _dlgCommonComp(t.go) || _dlgBaseComp(t.go);
    if (!dc || dc.isNull()) {
      out.path = "no-DialogBase"; out.closing = true; out.ms = Date.now() - t0; return out;
    }
    out.path = "ok";
    out.proc = dc.add(0xB4).readU8();              // DialogBase.isProcess
    try {
      const oa = dc.add(0xE0).readPointer();       // outMainTweenAlpha（退出动画）
      if (!oa.isNull()) {
        out.started = oa.add(0x68).readU8();       // UITweener.mStarted
        out.factor = Math.round(oa.add(0x78).readFloat() * 1000) / 1000;
        out.closing = (out.started === 1 && out.factor < 0.95);
      }
    } catch (e) { out.err = String(e); }
  } catch (e) { out.err = String(e); out.path = "err"; }
  out.ms = Date.now() - t0;
  return out;
}

/**
 * ★三选一页的**阶段 1 探针**（2026-11-09）：顶框名 + 关闭判据 + 顺带诊断。
 *
 * 三选一（`DialogLabyrinthSelection`）也是 `DialogBase` 子类 ⇒ 退出 tween / isProcess 与
 *   确认框同源（`labDlgClosing` 已按 DialogBase 兜底），所以判据也相同：
 *   顶框已不是这个壳，或退出动画已启动（`started=1 && factor<0.95`）。
 * ★实测结论（2026-11-09，多局）：三选一页**没有**可读的退出 tween（`tw` 全程 0/0），
 *   真正的换页在点击后 ~1s（选卡动画）⇒ 判据实际落在「顶框变了」；`closing` 字段
 *   保留给"将来能读到 tween"的情况。
 *
 * 诊断字段（**只进 probe 轨迹日志，不参与判据** —— 已实测证伪）：
 *   · selNum       = `PartsLabyrinthSelectionItemView.selectionNum@0x38`（实测恒为 3，不是已选数量）
 *   · listN        = view+0x28 列表长度（实测选入前后不变）
 *   · remainText   = `DialogLabyrinthSelection+0x138` 文本（"还剩N次"，只跨页变化）
 *   · remainActive = `+0x130` GameObject 是否显示
 *   · cardIdx[]    = 每卡 `PartsSelectionItemInfo+0x98 unitChoiceIndex`
 *
 * 零 hook；只读字段 + 一次 `get_text`（UILabel 常规 getter），实测 11~18ms。
 * `_selDialogList(true)` = 不扫堆，拿不到就报 no-sel-instance（避免把游戏拖慢）。
 */
function labSelStateSnapshot() {
  const out = { ms: 0, path: "", top: "", inst: "", selNum: null, listN: null,
                remainText: null, remainActive: null, cardIdx: [], proc: null,
                started: null, factor: null, closing: false, tw: null, err: null };
  const t0 = Date.now();
  try {
    const t = _topDialogGo();
    out.top = _normWindowName(t ? String(t.name || "") : "");
    // ① 先读 DialogBase 通用状态：**不依赖三选一实例**，顶框是任意 DialogBase 子类就能读。
    //    这两项（isProcess / 退出 tween）才是"这个页面何时开始消失"的直接证据。
    if (t && t.go && !t.go.isNull()) {
      const db = _dlgCommonComp(t.go) || _dlgBaseComp(t.go);
      if (db && !db.isNull()) {
        try { out.proc = db.add(0xB4).readU8(); } catch (e) {}
        try {
          const oa = db.add(0xE0).readPointer();     // outMainTweenAlpha
          if (!oa.isNull()) {
            out.started = oa.add(0x68).readU8();
            out.factor = Math.round(oa.add(0x78).readFloat() * 1000) / 1000;
            out.tw = out.started + "/" + out.factor;
            // 与确认框同一个判据：退出动画在跑（started=1 且 factor 还没到 1）
            out.closing = (out.started === 1 && out.factor < 0.95);
          }
        } catch (e) { out.tw = "err"; }
      }
    }
    // ② 再读三选一特有字段（拿不到实例也不影响上面的通用状态）
    const sl = _selDialogList(true);
    if (!sl) { out.path = "no-sel-instance"; out.ms = Date.now() - t0; return out; }
    out.inst = "" + sl.d;
    out.listN = sl.n;
    const view = sl.d.add(0x140).readPointer();      // partsItemView
    if (!ptrOk(view)) { out.path = "no-parts-view"; out.ms = Date.now() - t0; return out; }
    out.path = "ok";
    out.selNum = view.add(0x38).readS32();           // ← 待定语义
    const rl = sl.d.add(0x138).readPointer();        // remainSelectionNumLabel
    if (ptrOk(rl)) { try { out.remainText = _str1(_inv(rl, "get_text")); } catch (e) { out.remainText = "err"; } }
    const ro = sl.d.add(0x130).readPointer();        // remainSelectionNumObject
    if (ptrOk(ro)) { try { out.remainActive = (_goActive(ro) === true); } catch (e) {} }
    for (let i = 0; i < sl.n && i < 8; i++) {
      try {
        const it = _listItem(sl.list, i);
        out.cardIdx.push(ptrOk(it) ? it.add(0x98).readS32() : null);
      } catch (e) { out.cardIdx.push(null); }
    }
  } catch (e) { out.err = String(e); out.path = "err"; }
  out.ms = Date.now() - t0;
  return out;
}

function _exeqButtons(rootTr, only) {
  const out = [];
  if (!rootTr || rootTr.isNull()) return out;
  const env = _camEnv();
  if (!env) return out;
  const re = /^(One|Two|Three)Button(Left|Right|Center|White|Blue)$/;
  const found = [];
  _rawWalk(function (nd) {
    if (nd.active !== true) return;
    if (!re.test(nd.name)) return;
    // ★★2026-11-08：**只要指定的那一个按钮**（only）—— 别把整棵子树里的
    //   One/Two/ThreeButton 全投影一遍坐标（实测①页有 ~90 个 ⇒ 投影 380ms，
    //   这就是"点一下要等半秒"的大头）。only=null 时保持老行为（全部投影）。
    if (only && nd.name !== only) return;
    found.push({ name: nd.name, tr: nd.tr });
  }, false, rootTr);                                   // 先便宜地筛名字，再只给命中的算坐标
  for (const f of found) {
    const p = _ndXY({ tr: f.tr }, env);
    if (p && p.onscreen) {
      // ★带上**按钮实例标识**（GameObject 指针）——同名按钮可能有多个（"第一个"不稳定，
      //   实测重读整包时会在两个候选之间跳：TwoButtonRight (1179,960) ↔ (1150,904)）
      //   ⇒ 调用方靠它把"多点"**锁定在同一个按钮实例**上 ⇒ 绝不会点到别的目标。
      let id = "";
      try { const g = _inv(f.tr, "get_gameObject"); if (g && !g.isNull()) id = String(g); } catch (e) {}
      out.push({ name: f.name, x: p.x, y: p.y, id: id });
    }
  }
  return out;
}
// ★★坐标"在动"这件事**不在 agent 侧解决**（2026-11-08 教训）：
//   切页动画期间按钮坐标在动（实测 setting「确认」动画中 (1182,965) vs 稳定 (1179,960)），
//   曾在这里加"等一帧再读、取稳定值"（Thread.sleep）⇒ EX 页上匹配到的按钮有十几个，
//   每个都睡 ⇒ 整包读变成几秒 ⇒ **agent 单通道被占满** ⇒ 整个助手卡死
//   （labExEquip 飞了 8 秒没回包，viewId 跟着超时掉线）。
//   ⇒ 正确做法在**调用方**：每次点击前**重读整包**（坐标会随动画推进而更新），
//     没生效就在**同一次调用里接着点**（"多点是手段"），不追求一次点准。

// ★★特别装备三连页的**签名**（页身份 + 页内关键状态）。
//   整包快照（labExEquip）与轻量读（labExEquipSig）**共用这一个函数** ⇒ 两边口径永远一致。
function _exeqSigOf(top, dlgName, change, row, checked, sel) {
  // ★带**最上层容器名**：这样"页面切了"能被立刻看见（容器名变了就是换了一层），
  //   而不必等页内状态变 —— 切页动画期间的"旧页读数"也就不会把状态机带偏。
  const c = String(dlgName || "").replace(/\((Clone|Instance)\)$/, "");
  if (top === "party") return "party@" + c + "|change=" + change;
  if (top === "setting") return "setting@" + c + "|row=" + row + "|checked=" + checked;
  if (top === "sort") return "sort@" + c + "|sel=" + sel;
  return top;
}

// ★★EX 三连页的**页内状态**（签名里那几个字段）—— 抽出来给 [labExEquipSig]（只要签名）
//   和 [labExBtn]（要按钮 + 签名）**共用** ⇒ 两边的签名口径永远一致（构造上就一致）。
//   · party  → change（有没有改动）
//   · setting→ row（第一行文字）+ checked（复选框状态）
//   · sort   → sel（当前选中的那个 toggle 文字）
function _exeqPageState(f) {
  const st = { change: "null", row: "?", checked: "null", sel: "?" };
  try {
    if (f.top === "party") {
      try {
        const b = _invBool(f.comp, "isChangeExtraEquip");
        st.change = (b === true) ? "true" : ((b === false) ? "false" : "null");
      } catch (e) {}
    } else if (f.top === "setting") {
      try {
        const arr = f.comp.add(0x90).readPointer();        // CustomUILabel[]
        if (ptrOk(arr)) {
          const n = arr.add(0x18).readS32();
          if (n > 0) {
            const l = arr.add(0x20).readPointer();          // ★只读第 0 个（签名只要第一行）
            if (ptrOk(l)) st.row = _str1(_inv(l, "get_text"));
          }
        }
      } catch (e) {}
      try {
        const cb = f.comp.add(0x98).readPointer();
        if (ptrOk(cb)) {
          const tg = cb.add(0x38).readPointer();
          if (ptrOk(tg)) st.checked = (tg.add(0x81).readU8() !== 0) ? "true" : "false";
        }
      } catch (e) {}
    } else {
      try {
        const lst = f.comp.add(0xA0).readPointer();         // List<PartsUnitSortTypeToggle>
        const n = ptrOk(lst) ? _listCount(lst) : -1;
        for (let i = 0; i < (n > 0 ? Math.min(n, 24) : 0); i++) {
          const it = _listItem(lst, i);
          if (!ptrOk(it)) continue;
          if (!_invBool(it, "get_IsSelected")) continue;     // ★只读选中那个的文字
          const lb = it.add(0x30).readPointer();             // typeLabel
          if (ptrOk(lb)) st.sel = _str1(_inv(lb, "get_text"));
          break;
        }
      } catch (e) {}
    }
  } catch (e) {}
  return st;
}

// ★**极轻**：只读特别装备三连页的**签名**（就一个字符串）。
//   不算坐标、不读按钮、不回传整包 JSON —— 状态机轮询专用。
function labExEquipSig() {
  const out = { sig: null, err: null };
  try {
    const f = _findExTopPart();
    if (!f) { out.sig = ""; return out; }                 // 不在三连页里
    const ps = _exeqPageState(f);
    out.sig = _exeqSigOf(f.top, f.dlgName, ps.change, ps.row, ps.checked, ps.sel);
  } catch (e) { out.err = String(e); }
  return out;
}

// ── ★★**按需读按钮**（2026-11-08）：EX 三连页"这一步只要点的那个按钮" ──
//   返回 {sig, top, btn:{name,x,y,id}} —— **不读** labels/terms/toggles/按钮全家桶（那是"整包"）。
//   ★为什么单独一个读：整包读会把**页里其它控件**也过一遍（setting 的 4 行文字 + 复选框、
//     sort 的 11 个 toggle 文字 —— 每个都是一次游戏线程调用）⇒ 纯浪费；本流程每步只点一个按钮。
//   ★按钮的取法（和"按需"配套）：
//     · 对话框自带按钮（ThreeButtonRight…）→ **按字段**取（DialogFundationObject），
//       快路径取不到才回退遍历（见 [_dlgBtnByField] / [_exeqButtons]）。
//     · prio0（setting 页 4 行右侧按钮的第 0 个）/ check（复选框）/ rare（sort 页「稀有度」）
//       → 各自的字段直取。
function _exeqXY(compPtr, env, name) {
  try {
    if (!ptrOk(compPtr) || !env) return null;
    const p = _screenOf(compPtr, env.cam, env.mW2S);
    if (!p) return null;
    const x = Math.round(p.x), y = Math.round(env.H - p.y);
    if (!(x >= 0 && x <= env.W && y >= 0 && y <= env.H)) return null;
    let id = "";
    try { const g = _inv(compPtr, "get_gameObject"); if (g && !g.isNull()) id = String(g); } catch (e) {}
    return { name: name, x: x, y: y, id: id };
  } catch (e) { return null; }
}

function labExBtn(key, preferId) {
  const res = { sig: null, top: null, btn: null, err: null };
  try {
    const f = _findExTopPart();
    if (!f) return res;                                  // 不在三连页
    res.top = f.top;
    const ps = _exeqPageState(f);
    res.sig = _exeqSigOf(f.top, f.dlgName, ps.change, ps.row, ps.checked, ps.sel);
    const env = _camEnv();
    let btn = null;
    if (_DLG_BTN_OFF[key]) {
      const dc = _dlgCommonComp(f.dlg);
      if (dc) btn = _dlgBtnByField(dc, key, env);
      if (!btn) {                                         // 兜底：字段取不到才遍历
        const arr = _exeqButtons(_inv(f.dlg, "get_transform"), key);
        // ★★同名按钮可能**不止一个**（嵌套弹窗/槽位里的也叫 ThreeButtonRight…）⇒ 口径与老实现一致：
        //   ① preferId（GameObject 指针）命中 ⇒ 就用它（把多点**锁在同一个按钮实例**上）；
        //   ② 否则取**最靠下**的那个（y 最大 = 底部主按钮行 —— 「取消/自动装备/装备确定」「确认」那一排）。
        let best = null;
        for (const b of arr) {
          if (b.name !== key) continue;
          if (preferId && b.id === preferId) { best = b; break; }
          if (best == null || b.y > best.y) best = b;
        }
        btn = best;
      }
    } else if (key === "prio0" && f.top === "setting") {
      const arr = f.comp.add(0x88).readPointer();           // 4 行右侧按钮（CustomUIButton[]）
      if (ptrOk(arr)) {
        const el = arr.add(0x20).readPointer();             // 第 0 个
        btn = _exeqXY(el, env, "prio0");
      }
    } else if (key === "check" && f.top === "setting") {
      const cb = f.comp.add(0x98).readPointer();
      if (ptrOk(cb)) btn = _exeqXY(cb.add(0x48).readPointer(), env, "check");
    } else if (key === "rare" && f.top === "sort") {
      const lst = f.comp.add(0xA0).readPointer();           // List<PartsUnitSortTypeToggle>
      const n = ptrOk(lst) ? _listCount(lst) : -1;
      for (let i = 0; i < (n > 0 ? Math.min(n, 24) : 0); i++) {
        const it = _listItem(lst, i);
        if (!ptrOk(it)) continue;
        let t = null;
        try { const lb = it.add(0x30).readPointer(); if (ptrOk(lb)) t = _str1(_inv(lb, "get_text")); } catch (e) {}
        if (t === "稀有度") { btn = _exeqXY(it, env, "rare"); break; }
      }
    }
    res.btn = btn;
  } catch (e) { res.err = String(e); }
  return res;
}

// 只读快照：**只读"当前最上层"那一页**（wantBtns=false 时连按钮坐标都不算，省掉一次子树遍历）
//   ★`only` = **只要投影这一个**（按钮名 / "prio0" / "check" / "rare"）；null = 老的全量行为。
function labExEquipSnapshot(diag, wantBtns, only) {
  const res = { jsBuild: JS_BUILD, top: null, page: null, sig: null, err: null };
  const env = _camEnv();
  const xy = function (o) {
    if (!env || !o || o.isNull()) return { x: null, y: null };
    try { const ss = _screenOf(o, env.cam, env.mW2S);
          return ss ? { x: Math.round(ss.x), y: Math.round(env.H - ss.y) } : { x: null, y: null }; }
    catch (e) { return { x: null, y: null }; }
  };
  const labelTexts = function (arrPtr, maxN) {          // CustomUILabel[] → 文字
    const out = [];
    let n = 0;
    try { n = ptrOk(arrPtr) ? Math.min(arrPtr.add(0x18).readS32(), maxN || 8) : 0; } catch (e) { n = 0; }
    for (let i = 0; i < n; i++) {
      let t = null;
      try {
        const l = arrPtr.add(0x20 + i * 8).readPointer();
        if (ptrOk(l)) t = _str1(_inv(l, "get_text"));
      } catch (e) {}
      out.push(t);
    }
    return out;
  };
  const ctrlCoords = function (arrPtr, maxN, onlyIdx) {  // 组件数组 → 坐标
    const out = [];
    let n = 0;
    try { n = ptrOk(arrPtr) ? Math.min(arrPtr.add(0x18).readS32(), maxN || 8) : 0; } catch (e) { n = 0; }
    for (let i = 0; i < n; i++) {
      // ★只投影指定的那一个（onlyIdx）—— 4 个 prioBtn 全投影也就 ~16ms，但顺手省掉
      if (onlyIdx != null && i !== onlyIdx) continue;
      let p = ptr(0);
      try { p = arrPtr.add(0x20 + i * 8).readPointer(); } catch (e) {}
      const c = xy(p); c.i = i; out.push(c);
    }
    return out;
  };
  try {
    const f = _findExTopPart();
    if (!f) return res;                                  // 当前最上层不是这三页
    res.top = f.top;
    // ★★2026-11-08 实测：整包读的耗时**大头不是坐标投影，而是 `_rawWalk` 遍历整棵子树**
    //   （①页 DialogCommonFullSize = 编组页的大容器 ⇒ 遍历要 350ms+）。
    //   ⇒ **按需**：知道要哪个按钮时，按**字段**直接取（几 ms），绝不遍历。
    const needDlgBtns = (wantBtns || diag) &&
      !(only === "prio0" || only === "check" || only === "rare");
    const o = { cls: f.cls, ptr: String(f.comp), dlg: String(f.dlg), dlgName: f.dlgName,
                buttons: [], slots: null };
    if (needDlgBtns) {
      // ★快路径：按字段取（只有"对话框自带按钮"才有字段；prio0/check/rare 走各自的字段）
      let got = null;
      if (!diag && only && _DLG_BTN_OFF[only]) {
        const dc = _dlgCommonComp(f.dlg);
        if (dc) got = _dlgBtnByField(dc, only, env);
      }
      o.buttons = got ? [got] : _exeqButtons(_inv(f.dlg, "get_transform"), only);
    }
    if (f.top === "party") {                             // ① 特别装备设定
      o.change = _invBool(f.comp, "isChangeExtraEquip"); // ★有没有改动
      o.canChange = null; o.unitIds = []; o.plateSlots = -1;
      try {
        const pm = f.comp.add(0x90).readPointer();
        if (ptrOk(pm)) {
          o.unitIds = _intListOf(pm.add(0x150).readPointer());
          o.canChange = (pm.add(0x170).readU8() !== 0);
        }
      } catch (e) {}
      try { const ar = f.comp.add(0x80).readPointer();
            o.plateSlots = ptrOk(ar) ? ar.add(0x18).readS32() : -1; } catch (e) {}
      if (diag) o.slots = _slotDump(f.comp, 0x10, 0x100);
      res.sig = _exeqSigOf("party", f.dlgName,
        o.change === true ? "true" : (o.change === false ? "false" : "null"), "?", "null", "?");
    } else if (f.top === "setting") {                    // ② 自动特别装备设定
      o.labels = labelTexts(f.comp.add(0x90).readPointer(), 8);   // ★4 行右侧当前值（文字）
      // ★2026-11-08：**不要按钮坐标时就不投影**（状态机轮询只要"状态变了没"，坐标是纯浪费）
      //   ★★只投影**要点的那个**（only="prio0" ⇒ 只投影第 0 行；其它 ⇒ 全投影）
      const prioOnly = (only === "prio0") ? 0 : null;
      o.prioBtn = wantBtns ? ctrlCoords(f.comp.add(0x88).readPointer(), 8, prioOnly) : [];  // 4 行右侧按钮坐标
      o.terms = []; o.checked = null; o.checkBtn = null;
      try {
        const cb = f.comp.add(0x98).readPointer();
        if (ptrOk(cb)) {
          const tg = cb.add(0x38).readPointer();
          if (ptrOk(tg)) o.checked = (tg.add(0x81).readU8() !== 0);   // UIToggle.mIsActive @0x81
          const tb = cb.add(0x48).readPointer();
          if (ptrOk(tb) && wantBtns && (!only || only === "check")) o.checkBtn = xy(tb);
        }
      } catch (e) {}
      try { const pm = f.comp.add(0xA0).readPointer();
            if (ptrOk(pm)) o.terms = _intListOf(pm.add(0x150).readPointer()); } catch (e) {}
      if (diag) o.slots = _slotDump(f.comp, 0x10, 0xC0);
      res.sig = _exeqSigOf("setting", f.dlgName, "null",
        (o.labels && o.labels.length ? (o.labels[0] || "?") : "?"),
        (o.checked === true ? "true" : (o.checked === false ? "false" : "null")), "?");
    } else {                                             // ③ 优先属性值
      o.cur = null; o.list = []; o.toggles = [];
      try {
        const pm = f.comp.add(0x98).readPointer();
        if (ptrOk(pm)) { o.cur = pm.add(0x150).readS32();
                         o.list = _intListOf(pm.add(0x158).readPointer()); }
      } catch (e) {}
      try {
        const lst = f.comp.add(0xA0).readPointer();                 // List<PartsUnitSortTypeToggle>
        const n = ptrOk(lst) ? _listCount(lst) : -1;
        for (let i = 0; i < (n > 0 ? Math.min(n, 24) : 0); i++) {
          const it = _listItem(lst, i);
          if (!ptrOk(it)) continue;
          let t = null;
          try { const lb = it.add(0x30).readPointer();               // typeLabel
                if (ptrOk(lb)) t = _str1(_inv(lb, "get_text")); } catch (e) {}
          // ★不要坐标时只带 label/sel（轮询读不需要投影坐标）
          // ★★2026-11-08：**只投影「稀有度」那一个**（only="rare"）—— 11 个全投影 ≈ 44ms，纯浪费
          const p = (wantBtns && (!only || only === "rare") && t === "稀有度") ? xy(it) : {};
          p.i = i; p.label = t; p.sel = _invBool(it, "get_IsSelected");
          o.toggles.push(p);
        }
      } catch (e) {}
      if (diag) o.slots = _slotDump(f.comp, 0x10, 0xB0);
      var selLbl = "?";
      for (var si = 0; si < o.toggles.length; si++) {
        if (o.toggles[si].sel) { selLbl = o.toggles[si].label || "?"; break; }
      }
      res.sig = _exeqSigOf("sort", f.dlgName, "null", "?", "null", selLbl);
    }
    res.page = o;
  } catch (e) { res.err = String(e); }
  return res;
}

// 最上层是不是"特别装备三连页"之一 → {top:'party'/'setting'/'sort', dlg} 或 {top:null}
//   （给 Python 用：这类页由我们自己驱动，**不要**再走通用弹窗的自动点）
function labExEquipTop() {
  const out = { top: null, dlg: null };
  try {
    const f = _findExTopPart();
    if (f) { out.top = f.top; out.dlg = f.dlgName; }
  } catch (e) { out.err = String(e); }
  return out;
}


// ── 选角色页（SUMMON_UNIT=1 且 pageTop 是对话框）：初始编成 / 门票召唤（★只读）──
// ★构建号：改过读逻辑就 +1。attach 时 pcr_auto 会打出来（rpc jsBuild）——用来确认
//   "正在跑的到底是哪一版脚本"，免得拿旧输出讨论半天（2026-10-10 踩过）。
const JS_BUILD = "cs81";     // ★cs81（2026-11-09）：三选一探针改为**并进 probeState**（probe 按 150ms 节奏跑、每次读取顺带带回 proc/tween/selNum，不再同步阻塞采样）；三选一探针 RPC 由 `debugSelTrace` 转正为 `labSelState`（含 started/factor/closing）。★cs80（2026-11-09）：`labDlgClosing` 取组件改为 **DialogCommonAbstract 优先、DialogBase 兜底** —— `DialogLabyrinthSelection`（三选一页）是 DialogBase 直接子类，原先只按 DialogCommonAbstract 找会整个漏掉，读不到它的退出 tween/isProcess。配套 `debugSelTrace` 补 proc/tw。★cs79（2026-11-09）【已于 cs81 转正为 labSelState】：新增三选一页采样 `PartsLabyrinthSelectionItemView.selectionNum@0x38`、卡片列表长度、游戏显示的剩余数文本(+0x138)、剩余数 UI 是否显示(+0x130)、每卡 unitChoiceIndex(+0x98)，用来确定 selectionNum 是"已选数量"还是"选中下标"及其响应速度。★cs78（2026-11-09）：`labDlgClosing` 失败时标 `path=err`（调用方据此区分"读失败"与"窗口没了"，读失败绝不当成"已离开"）；`tapAndCheck` 阶段 1 改为**单次快照探针** `probeState`（top 与判据同源，少一次 RPC，也避免两次读之间的状态错位）。★cs77（2026-11-09）：新增 `labDlgClosing` —— 通用弹窗「关闭已启动」轻读（退出 tween 的 mStarted/factor，零 hook、3~6ms），替代"等窗口消失"当"这一下点生效了"；实测整页到 +800ms 才消失、退出 tween +200~300ms 就启动。★cs74（2026-11-09）：新增 `labDlgBtn` —— 通用弹窗按钮按 `DialogCommonAbstract +0x118` **字段直取**（零遍历，30ms 级），替代 `dialog()` 的 DialogArea 子树遍历（冷 0.5~0.6s / 大页 3~3.9s）；门票三选一两连弹改用它现取坐标。★cs73（2026-11-08）：`labStatusLite` / `labSnapshot` 补 `guildId`（`LabyrinthTempData+0x1C`）—— 局内刷"已在迷宫里要不要沿用"要核对公会，否则手动开着 A 公会的局、配置是 B 公会时会直接沿用、跑完当成 B 公会的成绩。cs72（2026-11-08）：**"最上层"只剩一个实现 + 根没了不再扫全树** —— ①`_topDialogTr`（`findList(...,"top")` 的根）改为复用 `_topDialogGo`，不再自己写一遍判据（原来有 3 份实现，正是 EX 三连页多点/点错的根源）；②`_rootWalkNodes` 的遍历缓存**加校验"根是不是同一个"**（原来只看 key ⇒ `"top"` 这个根会变，1.5s 内会把上一个对话框的子树当成当前的返回）；③新增 `_walkForRoot`：**显式给了根却取不到（如当前没有对话框）⇒ 返回空，不再退化成全树**（`findList(…,"top")` 实测 2633ms → 3ms；而且全树会捞到别处的同名节点）。cs71：**读取全面"按需"** —— ①新增 `labExBtn(key)`：EX 三连页只读**要点的那个按钮** + 当前签名（不再读整包：整包会把 setting 的 4 行文字/复选框、sort 的 11 个 toggle 文字都过一遍，纯浪费）；②新增 `labExtra()`：非分派字段（eventId/questId/eventResultId/effectNum/curChoice/remainChoice + LTD choices）拆成独立读，handler 要时才调；③`labStatusLite` 补 `next`（移动分支用，一次内存读）。cs70：**EX 整包读的按钮改按字段取**（DialogFundationObject：twoLeft 0x48 / twoRight 0x58 / threeLeft 0x70 / threeCenter 0x80 / threeRight 0x98，经对话框组件 +0x118 foundationObject），**不再遍历整棵子树**（①页遍历要 350ms+）。cs69：labExEquip(full, only) 只投影要点的那个；cs68：**"当前最上层"统一成一个实现**（_topDialogGo）—— ①_exeqFind 只查**最上层那一层**、**绝不往回退**（回退会永远读到下面那层仍 active 的①页 ⇒ 签名不变 ⇒ 误判"没生效"⇒多点⇒点错按钮）；②签名里带**最上层容器名**（"页面切了"能立刻看见）。cs67：去掉 agent 侧坐标"稳定化"（Thread.sleep 堵死单通道）；cs64：_findExTopPart 组件查找缓存；cs63：labExEquipSig 轻量签名读
// ★cs62（2026-10-22）：新增 battleSettingsStart/Poll（战斗设定只读；getter 是 IFix 模板 ⇒ 投递游戏线程执行）
// ★JS 侧详细日志开关（2026-10-11 加）：默认关。
//   关掉的是"每次调用都会打、但正常情况没信息量"的那几条（如 findMap 的"跳过堆扫描"、
//   "有效活跃实例 数="）—— 换区途中 labStatus 读空时会连续调用，一秒钟刷好几行。
//   排查"读不到地图"时把它改 true，就能看到完整过程。
const JS_VERBOSE = false;
// ★控制台日志总闸（2026-10-12）：JS 里 400+ 处 console.log 绝大多数是过程日志 ——
//   正常模式**全关**（只放行含"错误/失败/异常/[!]"等字样的行），要排查把它开 true。
//   ★只影响 JS 往控制台打的日志；RPC 的返回值（labStatus/labMap/…）完全不受影响。
const _JS_RAW_LOG = console.log.bind(console);
console.log = function () {
  try {
    const s = Array.prototype.join.call(arguments, " ");
    if (JS_VERBOSE || /\[!\]|错误|失败|异常|Crash|SIGSEGV|ERR/.test(s)) {
      _JS_RAW_LOG.apply(null, arguments);
    }
  } catch (e) { _JS_RAW_LOG.apply(null, arguments); }
};
// ★类名是**实测纠错过的**（2026-10-10）：先按 dump.cs 猜了 PartsDialogLabyrinthTicketAcquireCharacter，
//   实机堆扫描 12 个候选**全是垃圾值** → 那类不在这页上。改用**节点名反查 dump.cs** 得到：
//   PartsDialogLabyrinthSelectableChara（TypeDefIndex 7813，PartsDialogAbstract 子类）
//        +0x80 scrollViewPanel(UIPanel)   +0x88 controller(PartsLabyrinthSelectableCharaScrollController)
//   PartsLabyrinthSelectableCharaScrollController（14190）:
//        +0x30 noCharaObject   +0x38 **unitIdList(List<int>)** ← 候选角色（显示顺序，权威表）  +0x40 mdm
//   图标（取坐标 / 已选标记）：PartsSelectLabyrinthCharaIconArray（14211）:
//        +0x30 unitIcons(List<IUnitIcon>)  ← 每个内容对象一屏 8 个
//   UnitIcon: +0x118 unitId（子类穿透见 PARTY_ICON_INNER）｜+0x88 checkIcon / +0x98 mySelectIcon（active=已选）
// 定位顺序：① 从 DialogArea 的对话框子树 GetComponentInChildren(类) 直取"当前这一页"的实例（最稳，不扫堆）
//          ② 兜底：堆扫描 + 取值范围校验（含 unitId 必须像样，字段槽会立刻露馅）
// Il2CppClass* → 托管 **System.Type 对象**（GetComponent 系必须传"对象"，不是 Il2CppType*）
// ★两步（和 pcrtool/worker/js/peizhi_tools.js 里的做法一致）：
//     il2cpp_class_get_type(klass) → Il2CppType* ，再 il2cpp_type_get_object(type*) → System.Type
//   2026-10-10 实机踩过：只做第一步 → GetComponentInChildren 永远返回 null（Type 参数是无效对象）
function _typeOf(klass) {
  try {
    if (!klass || klass.isNull()) return ptr(0);
    const mod = Process.findModuleByName("libil2cpp.so");
    if (!mod) return ptr(0);
    const gct = mod.findExportByName("il2cpp_class_get_type");
    if (!gct) return ptr(0);
    const ty = new NativeFunction(gct, "pointer", ["pointer"])(klass);
    if (!ty || ty.isNull()) return ptr(0);
    const tgo = mod.findExportByName("il2cpp_type_get_object");
    if (!tgo) return ty;                    // 没第二步就只能退回旧行为（基本匹配不到，但不崩）
    return new NativeFunction(tgo, "pointer", ["pointer"])(ty);
  } catch (e) { return ptr(0); }
}

// GameObject 上取某类的组件（含子节点）：GetComponentInChildren(System.Type)
// ★2026-10-11 踩过：这个函数**一开始漏定义了**，而冒烟脚本里给它写了桩 → 一直"绿"，实机才炸。
//   现在冒烟开头会断言"产品里真的定义了这些函数"，禁止用桩掩盖缺失。
function _compInChildren(go, klass) {
  try {
    if (!ptrOk(go) || !klass || klass.isNull()) return ptr(0);
    const t = _typeOf(klass);
    if (t.isNull()) return ptr(0);
    const c = _inv1o(go, "GetComponentInChildren", t);
    return (c && !c.isNull()) ? c : ptr(0);
  } catch (e) { return ptr(0); }
}

// GameObject 自己身上的组件（**不含子节点**）—— 含子节点会拿到"子树里第一个"，
// 实测 15 格全读成同一个图标对象（2026-10-11 踩过：每格 checkIcon 都是同一个 sprite）。
function _compSelf(go, klass) {
  try {
    if (!ptrOk(go) || !klass || klass.isNull()) return ptr(0);
    const t = _typeOf(klass);
    if (t.isNull()) return ptr(0);
    const c = _inv1o(go, "GetComponent", t);
    return (c && !c.isNull()) ? c : ptr(0);
  } catch (e) { return ptr(0); }
}

// 在 DialogArea 的每个对话框子树里找该类的组件（从最上层往下找，取第一个命中）
// dbg（可选）：把"为什么没找到"记下来 —— typePtr / dlgArea / children[{name,comp}] / err
function _compInDialogs(klass, dbg) {
  const note = function (k, v) { if (dbg) dbg[k] = v; };
  try {
    if (!g_dialogArea || g_dialogArea.isNull() ||
        _str(_inv(_inv(g_dialogArea, "get_gameObject"), "get_name")) !== "DialogArea") {
      g_dialogArea = _findDialogAreaTr();
    }
    note("dlgArea", !!(g_dialogArea && !g_dialogArea.isNull()));
    if (!g_dialogArea || g_dialogArea.isNull()) return ptr(0);
    const t = _typeOf(klass);
    note("typePtr", String(t));
    if (t.isNull()) return ptr(0);
    const cc = _childCountOf(g_dialogArea);               // ★脏数据闸门，见 [MAX_CHILD_COUNT]
    note("childCount", cc);
    const kids = [];
    for (let i = cc - 1; i >= 0; i--) {                 // 倒序：后者渲染在上层
      const ch = _inv1i(g_dialogArea, "GetChild", i);
      if (ch.isNull()) continue;
      const g = _inv(ch, "get_gameObject");
      if (g.isNull()) continue;
      const nm = _str(_inv(g, "get_name"));
      // ★先试 **includeInactive=true**（2026-10-17）：本构建里 1 参版（includeInactive=false）对
      //   "刚创建/被隐藏"的对话框会漏 ⇒ 商店/选择页那类"DialogArea 优先"就白建了、退化成全堆扫描（8s ✗）。
      //   与 [_compSelfTry] 同一条教训，但这里**只用于"找对话框实例"**（标签读取仍用严格版 ✓）。
      let c = _inv2oi(g, "GetComponentInChildren", t, 1);
      if (!c || c.isNull()) c = _inv1o(g, "GetComponentInChildren", t);
      kids.push({ name: nm, comp: (c && !c.isNull()) ? String(c) : null });
      if (c && !c.isNull()) { note("children", kids); return c; }
    }
    note("children", kids);
    return ptr(0);
  } catch (e) { note("err", String(e)); return ptr(0); }
}

// ★★收集 DialogArea 里**所有** `DialogLabyrinthSelection` 实例（从上到下），带**名字预筛**。
//   为什么要多候选（2026-10-17 实测卡死）：一轮里会有**多次**选择（打完 Boss：遗物 1 次 + 选人 2 次），
//   而**上一轮那个对话框实例会留在 DialogArea 里、卡片列表已清空** ✗。原来只用 `_compInDialogs`
//   取"第一个命中" ⇒ 每轮都读到那个空实例（探针里 `d` 40s 不变、`n=0`）⇒ 判"没卡片列表"⇒ 不敢点
//   ⇒ 卡到 40s 丢缓存、再自愈重启（慢且白烧）。这里把所有同类实例都收下来，由调用方挑"列表 1..8"的那份。
//   代价 ≈ 当前路径：先读 DialogArea 每个子节点的**名字**（便宜），只对名字像的子树调 GetComponentInChildren。
function _selDlgCandidates(k, max) {
  const out = [];
  try {
    if (!k || k.isNull()) return out;
    const area = _dialogAreaTr();
    if (!area) return out;
    const t = _typeOf(k);
    if (t.isNull()) return out;
    let cc = 0;
    try { const r = _inv(area, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
    for (let i = cc - 1; i >= 0 && out.length < (max || 4); i--) {   // 倒序：后者渲染在上层（新的优先）
      const ch = _inv1i(area, "GetChild", i);
      if (ch.isNull()) continue;
      const g = _inv(ch, "get_gameObject");
      if (g.isNull()) continue;
      const nm = _str(_inv(g, "get_name"));
      if (nm.indexOf("DialogLabyrinthSelection") < 0) continue;      // 只挑名字像的（省掉其余子树的深扫）
      // ★同 [_compInDialogs]：先试 includeInactive=true（对话框刚出现时可能是 inactive ✗）
      let c = _inv2oi(g, "GetComponentInChildren", t, 1);
      if (!c || c.isNull()) c = _inv1o(g, "GetComponentInChildren", t);
      if (c && !c.isNull()) out.push(c);
    }
  } catch (e) {}
  return out;
}

// 取"某个 DialogLabyrinthSelection 实例的卡片列表"（`+0x140 → +0x28`），n 必须 ∈ 1..8
function _selListOfInstance(d) {
  try {
    if (!ptrOk(d)) return null;
    const view = d.add(0x140).readPointer();
    if (!ptrOk(view)) return null;
    const list = view.add(0x28).readPointer();
    if (!ptrOk(list)) return null;
    const n = _listCount(list);
    if (n <= 0 || n > 8) return null;
    return { d: d, view: view, list: list, n: n };
  } catch (e) { return null; }
}

// List<IUnitIcon> → [{i, ptr, cls}]（数组数据起点：引用型数组 +0x20；个别版本 +0x18 → 两个都试）
function _iconListOf(p) {
  const out = [];
  try {
    if (!ptrOk(p)) return out;
    const n = _listCount(p);
    if (n <= 0 || n > 200) return out;
    const arr = p.add(0x10).readPointer();
    if (!ptrOk(arr)) return out;
    const scan = function (base) {
      const t = [];
      for (let i = 0; i < n; i++) {
        let o = null; try { o = arr.add(base + i * 8).readPointer(); } catch (e) { return t; }
        if (!ptrOk(o)) return t;
        let cls = ""; try { cls = klassNameOf(o); } catch (e) {}
        if (!/UnitIcon/.test(cls)) return t;
        t.push({ i: i, ptr: String(o), cls: cls });
      }
      return t;
    };
    let t = scan(0x20);
    if (t.length < n) { const t2 = scan(0x18); if (t2.length > t.length) t = t2; }
    for (const x of t) out.push(x);
  } catch (e) {}
  return out;
}

// 组件(+0x18)→GameObject 是否 active（判"已选"标记用）
function _compActive(c) {
  try {
    if (!ptrOk(c)) return null;
    const go = c.add(0x18).readPointer();
    if (!ptrOk(go)) return null;
    return _goActive(go);
  } catch (e) { return null; }
}

let g_csCache = null, g_csAt = 0;
const CS_TTL_MS = 1000;

// ★校验必须**卡取值范围**（尤其 unitId 要像样）：Memory.scanSync 命中的大多只是"别的对象里存着
//   这个类指针的字段槽"，光"指针非空/列表能读"挡不住（2026-10-10 实机踩过两次）。
function _csProbe(d) {
  const o = { addr: String(d), cls: "", ctrl: "", ctrlCls: "", n: null, ids: [], ok: false };
  try { o.cls = klassNameOf(d); } catch (e) {}
  try {
    const c = d.add(0x88).readPointer();                    // controller
    o.ctrl = String(c);
    if (ptrOk(c)) {
      o.ctrlCls = klassNameOf(c);
      const l = c.add(0x38).readPointer();                  // unitIdList
      o.n = ptrOk(l) ? listCount(l) : -1;
      o.ids = _intListOf(l).slice(0, 4);
    }
  } catch (e) {}
  o.ok = _csValid(d);
  return o;
}

function _csValid(d) {
  try {
    const ctrl = d.add(0x88).readPointer();                 // controller 必须在
    if (!ptrOk(ctrl)) return false;
    const lst = ctrl.add(0x38).readPointer();               // 候选表 unitIdList
    if (!ptrOk(lst)) return false;
    const n = listCount(lst);
    if (!(n >= 1 && n <= 80)) return false;
    const ids = _intListOf(lst);                            // ★取值抽查：角色 id 若是垃圾立刻露馅
    if (!ids.length) return false;
    for (let i = 0; i < Math.min(ids.length, 8); i++) {
      if (!(ids[i] >= 100000 && ids[i] < 400000)) return false;
    }
    return true;
  } catch (e) { return false; }
}

// ── 召唤/编成「候选角色」（2026-10-10 新增）──
// ★实机结论（2026-10-10 23:44 真跑）：`TempData.SummonCandidateUnitIdDictionary(+0x130)` **当时是空的**，
//   而 `TempData.SummonUnitItemId(+0x188)` 也读 0 —— 真正的"本次是哪个召唤道具"在 **LabyrinthStatus**
//   的 itemId(+0x14)（= labSnapshot 里那个 13000009）。所以本函数只做**交叉校验**用：
//   候选以 `labCharaGrid()` 逐格读到的 unitId 为准（那是页面真实显示的角色）。
//   另：已选/剩余可选 = LabyrinthStatus curChoice(+0x88) / remainChoice(+0x94)（也顺带读，供对账）。
function labSummonUnit() {
  const res = { ltd: null, itemId: null, cands: [], all: [], viewKeys: [],
                cur: null, remain: null, err: null };
  try {
    const ltd = (g_lab && !g_lab.isNull()) ? g_lab : findLab();
    if (!ltd || ltd.isNull()) { res.err = "还没抓到 LabyrinthTempData 实例"; return res; }
    res.ltd = String(ltd);
    try {                                      // ① 状态里的 itemId / 已选 / 剩余（权威）
      const st = ltd.add(0xE8).readPointer();
      if (ptrOk(st)) {
        res.itemId = decOI(st.add(0x14));
        res.cur = decOI(st.add(0x88));
        res.remain = decOI(st.add(0x94));
      }
    } catch (e) {}
    if (!res.itemId) { try { res.itemId = ltd.add(0x188).readS32(); } catch (e) {} }   // ② 兜底
    for (const r of enumDict(ltd.add(0x130).readPointer())) {
      res.all.push({ itemId: r.key, unitIds: _intListOf(r.val) });
    }
    for (const it of res.all) if (it.itemId === res.itemId) res.cands = it.unitIds;
    if (!res.cands.length && res.all.length === 1) res.cands = res.all[0].unitIds;
    try { for (const r of enumDict(ltd.add(0x1A0).readPointer())) res.viewKeys.push(r.key); } catch (e) {}
    if (!res.cands.length) {
      res.err = "候选表为空（itemId=" + res.itemId + "，表里 " + res.all.length
                + " 项）—— 以 labCharaGrid 逐格读到的 unitId 为准";
    }
  } catch (e) { res.err = "读取异常: " + e; }
  return res;
}

// ── 选角色页的「格子」：位置 + 每格是谁 + 整个图标区的"视觉指纹"（★走 UI 树）──
// 实测页面结构（节点名，2026-10-10；括号=实机看到的样子）：
//   PartsSelectLabyrinthCharaIconArray(Clone)          ← 每个内容对象一屏（8 格/行）
//     └ ButtonObj                                      ← 每格一个（有屏幕坐标）★位置就是它
//         ├ UnitTexture / UnitName                     ← 与 UnitIcon（+0x50 statusName/+0x48 name/+0x60 statusValue）同构
//         ├ StatusName(off) / StatusIcon(off) / StatusValue(off) / TopLeftSprite(off) / OverBottom
//         └ MySelectIcon（active）                     ← ★实测 = **收藏**，与"已选"无关（用户 2026-10-10 指正）
// ★"已选"由哪个节点表示**还没确认** → 不猜：算**整个图标区子树**的指纹（名字+active），
//   点一格它必变 → 重打印带出"哪个节点从 (off) 亮起来" → 就此认出真标记。
// 组件（每格的 UnitIcon / 文字 label）靠 _typeOf 两步 + GetComponentInChildren 取；
//   取不到时把"试过哪些类、拿到什么指针"一并回传（静默失败最费时间，2026-10-10 教训）。
const ICON_CLASSES = ["UnitIcon", "IUnitIcon", "UnitIconBase"];   // 逐格取图标组件时按序试
const LABEL_CLASSES = ["CustomUILabel", "UILabel", "CustomUIText", "Text"];

// ── 指纹的"抖动滤除"：UI 里有些节点在动画中反复 on/off（实测 2026-10-11：连续两次指纹翻转、
//    长度不变）→ 会把指纹噪声化。按"名字@坐标"记翻转历史，**1.5 秒内翻 ≥3 次**才算动画抖动。
//   ★不用"翻一次就算"：本页人工点选也翻转，若把点选也滤掉就永远看不出选中（当天踩过）。
let g_csPrevAct = {}, g_csFlipHist = {};
const CS_FLAP_WIN_MS = 1500, CS_FLAP_MIN = 3;
function _csFlap(key, act) {
  if (key === undefined) return false;
  const now = Date.now();
  const p = g_csPrevAct[key];
  g_csPrevAct[key] = act;
  if (p === undefined || p === act) return false;              // 没变 → 不是抖动
  const h = (g_csFlipHist[key] || []).filter(function (t) { return now - t < CS_FLAP_WIN_MS; });
  h.push(now); g_csFlipHist[key] = h;
  return h.length >= CS_FLAP_MIN;
}
function _csHash(s) {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h * 33) ^ s.charCodeAt(i)) >>> 0;
  return h.toString(16);
}

let g_csGridCache = null, g_csGridAt = 0;
const CS_GRID_TTL_MS = 1000;                                  // ★整树遍历 ~2s：缓存 1 秒，别每轮都扫
let g_csLabelPtrs = {};                                       // 两个标签的组件指针（缓存：以后每轮只读它们）

// ★只读"已选/可选"两个标签（~1ms）：整树遍历≈2s，不能每轮都干（实机每轮 2.2s → 点了要等一大拍）。
//   指针来自上一次整树遍历；取不到/读失败就 stale=true，让 Python 退回整树遍历。
function labCharaLabelText() {
  const out = { jsBuild: JS_BUILD, labels: {}, ptr: null, stale: false, err: null };
  try {
    const ks = Object.keys(g_csLabelPtrs);
    if (!ks.length) { out.stale = true; return out; }
    const main = g_csLabelPtrs["SelectableNumLabel"] || g_csLabelPtrs[ks[0]];
    out.ptr = main ? String(main) : null;      // ★标签对象地址：变了 = 页面被重建 → 要重扫整树
    for (const nm of ks) {
      try {
        const t = _str1(_inv(g_csLabelPtrs[nm], "get_text"));
        if (!t || t === "?") out.stale = true;
        out.labels[nm] = t;
      } catch (e) { out.stale = true; out.err = String(e); }
    }
  } catch (e) { out.stale = true; out.err = String(e); }
  return out;
}

function labCharaGrid(force) {
  if (!force && g_csGridCache && (Date.now() - g_csGridAt) < CS_GRID_TTL_MS) return g_csGridCache;
  const res = { jsBuild: JS_BUILD, cells: [], labels: {}, labelProbe: [], iconProbe: [],
                decide: null, arrays: 0, n: 0, fav: 0, selN: null, flap: 0,
                plate: [], plateKids: [], plateDump: [], arrKids: [], marks: [],
                visSig: "", arrVis: [], err: null };
  let arr = -1, cur = null, cellD = -1, arrD = -1, av = "";   // ★放 try 外：收尾还要用
  let plateD = -1, plateVis = "";                             // 预览区深度 / 子树亮灭指纹
  const plateSeen = {};                                       // 预览里去重（同一个图标只记一次）
  const mkSeen = {};                                          // "标记类"节点去重
  const arrNames = {};                                        // 图标区出现过的节点名（集合）
  try {
    const kIcon = findClass("UnitIcon");
    const env = _camEnv();                                    // 坐标只在需要时算（全树算坐标≈2000 次调用）
    _rawWalk(function (nd) {
      const nm = nd.name;
      const act = (nd.active === true);
      const key = nm + "@" + nd.x + "," + nd.y;                   // 节点稳定标识（名字+坐标）
      if (nm.indexOf("PartsSelectLabyrinthCharaIconArray") >= 0) { // 新的一屏
        if (arrD >= 0) res.arrVis.push(av);
        arr++; res.arrays = arr + 1; arrD = nd.d; av = "";
        return;
      }
      const fl = _csFlap(key, act);                               // 抖动节点 → 不进指纹
      if (fl) res.flap++;
      // ★"标记类"节点全树清单（选中角标就在这类里）：名字带 select/num/order/check/mark/badge/pick
      //   点一下 → 哪个节点从 (off) 变亮 = 认出来了（差分在图标/LTD 上都没变化时用这条）
      if (/select|num|order|check|mark|badge|pick/i.test(nm)) {
        const k = nm + "@" + nd.x + "," + nd.y;
        if (!mkSeen[k] && res.marks.length < 40) {
          mkSeen[k] = 1;
          const mxy = _ndXY({ tr: nd.tr }, env) || {};
          res.marks.push({ name: nm, active: act, d: nd.d, x: mxy.x, y: mxy.y });
        }
      }
      if (arrD >= 0 && nd.d > arrD) {
        arrNames[nm] = 1;                                         // 名字集合：有"新节点出现"就能发现
        if (!fl) av += (av ? "," : "") + nm + (act ? "+" : "-");
        if (nd.d === arrD + 1 && res.arrKids.indexOf(nm) < 0 && res.arrKids.length < 24) {
          res.arrKids.push(nm);                                   // 图标数组的直接子（应是 UnitIcon(Clone)）
        }
      }
      // 顶部"已选预览"（UnitSelectPlate）：里面每个 UnitIcon 就是**已选**的角色，顺序=点击顺序。
      // ★这是"哪几格已选"最干净的来源（0 个 = 一个都没选，实测与 0/3 一致）
      if (nm === "UnitSelectPlate") { plateD = nd.d; return; }
      if (plateD >= 0 && nd.d <= plateD) plateD = -1;         // 出了预览子树就收工（别把后面的节点也算进来）
      if (plateD >= 0 && nd.d > plateD && nd.d <= plateD + 8) {      // 实测层级较深（PartyEditPlate→…→UnitIcon）
        if (nd.d <= plateD + 8 && res.plateKids.length < 40) res.plateKids.push(nm + "@" + (nd.d - plateD));
        // ★预览区整棵子树的亮灭也进指纹：选中若在这里画（队伍编成式的"上排 3 个"），点一下必然变
        if (!fl) plateVis += (plateVis ? "," : "") + nm + (act ? "+" : "-");
        if (act && res.plateDump.length < 30) res.plateDump.push({ name: nm, d: nd.d - plateD });
        try {
          const g = _inv(nd.tr, "get_gameObject");
          // ★只取自身：上一次用"退子树第一"把整张候选表都捞进来了（2026-10-11 踩过）
          const ic = _compSelf(g, kIcon);
          if (ic && !ic.isNull() && !plateSeen[String(ic)] && res.plate.length < 12) {
            plateSeen[String(ic)] = 1;
            const r = partyUnitIdOfIcon(ic);
            if (r.unitId > 0) res.plate.push({ unitId: r.unitId, cls: r.cls, ptr: String(ic) });
          }
        } catch (e) {}
      }
      if (nm === "ButtonObj") {
        let go = null, pGo = null;

        try { go = _inv(nd.tr, "get_gameObject"); } catch (e) {}
        try { const p = _inv(nd.tr, "get_parent"); if (!p.isNull()) pGo = _inv(p, "get_gameObject"); } catch (e) {}
        const xy = _ndXY({ tr: nd.tr }, env) || {};
        cur = { arr: arr, x: (xy.x == null ? null : xy.x), y: (xy.y == null ? null : xy.y),
                unitId: null, iconCls: null, iconPtr: null, iconOn: null, parent: null,
                sel: null, selTry: null, fav: false, favProp: null, h: null, kids: [], vis: "" };
        cellD = nd.d;
        try { if (pGo && !pGo.isNull()) cur.parent = _str(_inv(pGo, "get_name")); } catch (e) {}
        // ★dump.cs：UnitIcon（1337）= cell 的脚本，但它挂在 **ButtonObj 的父**上
        //   （UnitIcon.curUIButton(+0x100) 才是 ButtonObj）→ 自己找不到就找父。
        for (const pair of [["parent", pGo], ["self", go]]) {
          if (cur.iconPtr || !pair[1] || pair[1].isNull()) continue;
          try {
            // 先只取自身；自身没有才退"子树里第一个" —— 实测格子的 UnitIcon 在其子树内，
            // 且**逐格 ucht 读出不同 unitId**（cs9/cs10 验证过），所以这条退路是对的；
            // 之前把 InChildren 完全去掉 → 15 格全空（2026-10-11 踩过）。
            let ic = _compSelf(pair[1], kIcon);
            if (!ic || ic.isNull()) ic = _compInChildren(pair[1], kIcon);
            if (!ic || ic.isNull()) continue;
            cur.iconOn = pair[0]; cur.iconPtr = String(ic);
            try {
              const r = partyUnitIdOfIcon(ic);                     // UnitIcon.unitId @ +0x118
              cur.iconCls = r.cls;
              if (r.unitId > 0) cur.unitId = r.unitId;
            } catch (e) { cur.iconErr = String(e); }
            try {                                                 // 权威"已选"：UnitIcon.IsCheck（virtual 属性）
              const b = _inv(ic, "get_IsCheck");
              if (b && !b.isNull()) cur.sel = !!b.add(0x10).readS32();
            } catch (e) {}
            try {                                                 // 权威"收藏"：IsActiveMySelectIcon
              const b2 = _inv(ic, "get_IsActiveMySelectIcon");
              if (b2 && !b2.isNull()) cur.favProp = !!b2.add(0x10).readS32();
            } catch (e) {}
            // ★选中勾：UnitIcon.checkIcon(+0x88, UISprite, protected)。实测节点 active 恒 off
            //   —— NGUI 惯例是"节点常亮、用 sprite 的 enabled/alpha 显隐"，所以必须读组件而不是节点。
            const ck = ic.add(0x88).readPointer();
            if (ptrOk(ck)) {
              cur.check = {};
              try { const b = _inv(ck, "get_enabled"); if (b && !b.isNull()) cur.check.en = !!b.add(0x10).readS32(); } catch (e) {}
              try { const f = _inv(ck, "get_alpha"); if (f && !f.isNull()) cur.check.al = Math.round(f.add(0x10).readFloat() * 100) / 100; } catch (e) {}
              // ★NGUI 换图 = 切"选中"最常见手法：直接把图名读出来（实测 en/alpha 恒 1，只有图名会变）
              try { cur.check.sn = _str1(_inv(ck, "get_spriteName")); } catch (e) {}
            }
            const ms = ic.add(0x98).readPointer();                // 收藏：UnitIcon.mySelectIcon(+0x98)
            if (ptrOk(ms)) {
              cur.favIcon = {};
              try { const b = _inv(ms, "get_enabled"); if (b && !b.isNull()) cur.favIcon.en = !!b.add(0x10).readS32(); } catch (e) {}
              try { const f = _inv(ms, "get_alpha"); if (f && !f.isNull()) cur.favIcon.al = Math.round(f.add(0x10).readFloat() * 100) / 100; } catch (e) {}
              try { cur.favIcon.sn = _str1(_inv(ms, "get_spriteName")); } catch (e) {}
            }
            if (cur.check && cur.check.sn) cur.vis += ",ck:" + cur.check.sn;   // 图名进指纹 → 点选必变
            if (cur.favIcon && cur.favIcon.sn) cur.vis += ",fv:" + cur.favIcon.sn;
            if (cur.check) cur.sel = !!cur.check.en;              // 有 checkIcon 就以它为准
            cur.selTry = {};                                      // 可疑"已选"属性逐个试（哪个存在、值是多少）
            for (const gn of ["get_IsCheck", "get_IsSelect", "get_IsSelected", "get_IsChoose"]) {
              try {
                const bg = _inv(ic, gn);
                if (bg && !bg.isNull()) cur.selTry[gn.slice(4)] = !!bg.add(0x10).readS32();
              } catch (e) {}
            }
          } catch (e) { cur.iconErr = String(e); }
        }
        // 【体检】只对第一格做：父名 / 数组直接子名 / 各类试取 —— 每步单独兜住
        if (res.cells.length === 0) {
          res.iconProbe.push({ step: "parent", parent: cur.parent,
                               comp: (go && !go.isNull()) ? "self-go-ok" : null });
          for (const cn of ICON_CLASSES) {
            for (const pair of [["self", go], ["parent", pGo]]) {
              if (!pair[1] || pair[1].isNull()) continue;
              const o = { cls: cn + "@" + pair[0], klass: null, comp: null, err: null };
              try {
                const kk = findClass(cn);
                o.klass = kk ? String(kk) : null;
                if (kk && !kk.isNull()) {
                  const cp = _compSelf(pair[1], kk);
                  o.comp = (cp && !cp.isNull()) ? String(cp) : null;
                }
              } catch (e) { o.err = String(e); }
              res.iconProbe.push(o);
            }
          }
        }
        res.cells.push(cur);
        return;
      }
      if (cur && nd.d > cellD - 1) {                        // ★含 ButtonObj 的**兄弟**：选中角标挂在
        if (nm === "MySelectIcon" && act) cur.fav = true;   //   UnitIcon 根下、浮在格子右上；只统计 ButtonObj 的
        if (cur.kids.length < 40) cur.kids.push({ name: nm, active: act, flap: fl });   // 后代永远看不到它
        if (!fl) cur.vis += (cur.vis ? "," : "") + nm + (act ? "+" : "-");
        return;
      }
      if (nm.indexOf("SelectMemberNum") >= 0 || nm.indexOf("SelectableNumLabel") >= 0) {
        let go = null;
        try { go = _inv(nd.tr, "get_gameObject"); } catch (e) {
          res.labelProbe.push({ node: nm, step: "get_gameObject", err: String(e) });
        }
        if (go && !go.isNull()) {
          for (const cn of LABEL_CLASSES) {               // 逐个试 label 类（每个都留证据）
            const o = { node: nm, cls: cn, klass: null, comp: null, text: null, err: null };
            let cp = null;
            try {
              const kk = findClass(cn);
              o.klass = kk ? String(kk) : null;
              if (kk && !kk.isNull()) {
                cp = _compInChildren(go, kk);
                o.comp = (cp && !cp.isNull()) ? String(cp) : null;
                if (cp && !cp.isNull()) o.text = _str1(_inv(cp, "get_text"));
              }
            } catch (e) { o.err = String(e); }
            res.labelProbe.push(o);
            if (o.text && o.text !== "?") {
              res.labels[nm] = o.text;
              res.labels[nm + "Ptr"] = o.comp;    // ★标签组件地址：把 hook 抓到的 set_text 事件对上是哪个标签
              if (cp && !cp.isNull()) g_csLabelPtrs[nm] = cp;   // 缓存：以后每轮只读这两个标签
              break;
            }
          }
        }
        return;
      }
      if (nm === "DecideButton") {
        const dxy = _ndXY({ tr: nd.tr }, env) || {};
        res.decide = { x: dxy.x, y: dxy.y };
      }
    }, false);
  } catch (e) { res.err = "读取异常: " + e; }
  if (arrD >= 0) res.arrVis.push(av);                   // 收最后一屏
  res.n = res.cells.length;
  res.fav = res.cells.filter(function (c) { return c.fav; }).length;
  res.selN = res.cells.filter(function (c) { return c.sel === true; }).length;   // IsCheck==true 的格数
  let s = Object.keys(arrNames).sort().join(",") + "|";  // 名字集合：有新节点出现就能发现；不受 active 抖动
  for (const c of res.cells) s += c.vis + "|";           // 各格后代（已滤抖动）+ 文字 + 按钮
  s += JSON.stringify(res.labels) + "|" + JSON.stringify(res.decide);
  // ★"标记类"节点的亮灭也进指纹：选中角标就在这类里 → 点一下必然触发重打印
  s += JSON.stringify(res.marks.map(function (m) { return m.name + (m.active ? "+" : "-"); })) + "|";
  s += plateVis + "|";                                   // 预览区子树（点选若在这里画，指纹必变）
  for (const c of res.cells) c.h = _csHash(c.vis);       // 每格一个短指纹（点哪个、哪个变）
  res.visSig = _csHash(s) + ":" + s.length;
  if (!res.err) { g_csGridCache = res; g_csGridAt = Date.now(); }   // 缓存（整树遍历很贵）
  return res;
}

// ── ★差分扫描：把每格图标对象的原始字段前后对比，机械地找出"点选后被改的字段" ──
// 为什么走这条：IsCheck 恒 false、checkIcon 恒 en=true、UnitSelectPlate 子树含候选列表（三条路都废，
// 2026-10-11）→ 不再猜语义，直接看**点选后哪个偏移跳变**。Python 每约 1 秒调一次并自己 diff。
const ICON_RAW_FROM = 0x10, ICON_RAW_TO = 0x160, ICON_RAW_STEP = 4;
function labIconRaw() {
  const g = g_csGridCache;
  const out = { from: ICON_RAW_FROM, step: ICON_RAW_STEP, cells: [], err: null };
  if (!g || !g.cells || !g.cells.length) { out.err = "还没有格子缓存（先让 labCharaGrid 跑一次）"; return out; }
  try {
    for (let i = 0; i < g.cells.length; i++) {
      const c = g.cells[i];
      if (!c || !c.iconPtr) continue;
      const o = ptr(c.iconPtr);
      const w = [];
      for (let off = ICON_RAW_FROM; off <= ICON_RAW_TO; off += ICON_RAW_STEP) {
        let v = null;
        try { v = o.add(off).readS32(); } catch (e) { v = null; }
        w.push(v);
      }
      out.cells.push({ i: i, unitId: c.unitId, ptr: c.iconPtr, words: w });
    }
    try {                                              // ①' 顺带差分 LabyrinthTempData 本体
      const ltd = (g_lab && !g_lab.isNull()) ? g_lab : findLab();
      if (ltd && !ltd.isNull()) {
        const w2 = [];
        for (let off = 0x10; off <= 0x1B0; off += ICON_RAW_STEP) {
          let v = null;
          try { v = ltd.add(off).readS32(); } catch (e) { v = null; }
          w2.push(v);
        }
        out.ltd = { ptr: String(ltd), from: 0x10, words: w2 };
      }
    } catch (e) {}
    try {                                              // ③' ★"真零件"内部的列表/字典**内容**也差分
      //   对象本身不变（同址）→ 只差分对象字段是不够的；选中的 unitId 就存在这些容器里
      const ta = g_csPageObjs["PartsDialogLabyrinthTicketAcquireCharacter"];
      if (ta && !ta.isNull()) {
        // ★定案后只留这两条（其余诊断已撤掉）：
        //   +0xE8 = **已选（按点击顺序）** ／ +0x100 = 候选池（显示顺序）—— 点一下看"新增/移除"即可核对
        const d = [];
        try { d.push({ name: "acquireIdList(+0xE8) 已选", words: _intListOf(ta.add(0xE8).readPointer()) }); } catch (e) {}
        try { d.push({ name: "sortedUnitIdList(+0x100) 候选", words: _intListOf(ta.add(0x100).readPointer()) }); } catch (e) {}
        out.drill = d;
      }
    } catch (e) {}
    try {                                              // ③ 页面零件对象（从标签往上找到的）也一起差分
      for (const cn of Object.keys(g_csPageObjs)) {
        const o = g_csPageObjs[cn];
        if (!o || o.isNull()) continue;
        const w3 = [];
        for (let off = 0x10; off <= 0x1A0; off += ICON_RAW_STEP) {
          let v = null;
          try { v = o.add(off).readS32(); } catch (e) { v = null; }
          w3.push(v);
        }
        (out.page = out.page || []).push({ cls: cn, ptr: String(o), from: 0x10, words: w3 });
      }
    } catch (e) {}
  } catch (e) { out.err = "读取异常: " + e; }
  return out;
}

// GameObject[] / Component[] 这类**托管数组**：长度在 +0x18，元素从 +0x20 起每 8 字节一个指针
// （与 enumDict 里读 _entries 数组的约定一致）
function _objArrayOf(arrPtr, maxN) {
  const out = [];
  try {
    if (!ptrOk(arrPtr)) return out;
    const n = arrPtr.add(0x18).readS32();
    const lim = Math.min(Math.max(n | 0, 0), maxN || 64);
    for (let i = 0; i < lim; i++) {
      const p = arrPtr.add(0x20 + i * 8).readPointer();
      out.push(ptrOk(p) ? { ptr: String(p), cls: klassNameOf(p) } : null);
    }
  } catch (e) {}
  return out;
}

// ── ★把"已选/可选"标签往上每层 GameObject 的**组件全列出来**（2026-10-11 加）──
// 为什么：节点名已经证明是 PartsDialogLabyrinthTicketAcquireCharacter(Clone)，
//   但按类名 GetComponent 取不到 → 不再猜类名，直接问这个 GameObject："你身上有哪些组件？"
function labGoComps(deep) {
  const res = { layers: [], mbType: null, api: null, err: null };
  try {
    const lp = g_csLabelPtrs["SelectableNumLabel"] || g_csLabelPtrs["SelectMemberNum"];
    if (!lp) { res.err = "还没定位到标签（要等一次整树遍历）"; return res; }
    let mbT = ptr(0);
    try {
      const k = findClass("MonoBehaviour");
      res.mbType = k ? String(k) : null;
      mbT = _typeOf(k);
    } catch (e) {}
    let tr = _inv(lp, "get_transform");
    for (let i = 0; i < (deep || 8) && tr && !tr.isNull(); i++) {
      let go = null;
      try { go = _inv(tr, "get_gameObject"); } catch (e) {}
      if (!go || go.isNull()) break;
      const row = { i: i, name: _str(_inv(go, "get_name")), comps: [], err: null };
      if (mbT && !mbT.isNull()) {
        try {
          const arr = _inv1o(go, "GetComponents", mbT);       // GameObject.GetComponents(Type)
          res.api = "GetComponents(MonoBehaviour)";
          for (const c of _objArrayOf(arr, 32)) {
            if (c && c.cls && c.cls !== "?" && c.cls !== "no-class") row.comps.push(c.cls);
          }
        } catch (e) { row.err = String(e); }
      }
      res.layers.push(row);
      try { tr = _inv(tr, "get_parent"); } catch (e) { break; }
    }
  } catch (e) { res.err = String(e); }
  return res;
}

// ── ★找"真元素"：从"已选/可选"标签**往上爬父链**，把页面零件对象抓出来（2026-10-11 加）──
// 为什么这么干：整树遍历只拿到节点名，而"存已选集合"的控制器对象挂在某个父节点上；
//   从根往下 GetComponentInChildren 在 DialogArea 子树里全是空（实测）→ 那就**反过来**从标签往上 GetComponent。
//   拿到对象后打"指针槽类名"快照，并纳入 labIconRaw 的差分 → 点一下就知道**哪个偏移在跳**。
let g_csPageObjs = {};
// ★2026-10-11 实机定案：真页面零件就是 **PartsDialogLabyrinthTicketAcquireCharacter**
//   （父链证据：SelectableNumLabel ← common_line_text ← SelectMemberNum ←
//     PartsDialogLabyrinthTicketAcquireCharacter(Clone) ← PartsParent ← Main ← DialogCommonFullSize ← DialogArea）
//   ★别再用 PartsDialogLabyrinthSelectableChara 找实例：堆扫描出来的全是"别的对象存着这个类指针的字段槽"。
const CS_PAGE_CLASSES = ["PartsDialogLabyrinthTicketAcquireCharacter",
                         "PartsDialogLabyrinthSelectableChara",
                         "PartsLabyrinthSelectableCharaScrollController",
                         "PartsSelectLabyrinthCharaIconArray",
                         "PartsDialogAbstract", "LabyrinthPanelData"];
function labPageProbe(deep, extra) {
  const res = { layers: [], hits: [], fields: [], tried: 0, err: null };
  try {
    const lp = g_csLabelPtrs["SelectableNumLabel"] || g_csLabelPtrs["SelectMemberNum"];
    if (!lp) { res.err = "还没定位到标签（要等一次整树遍历）"; return res; }
    const clsList = CS_PAGE_CLASSES.slice();
    for (const cn of (extra || [])) if (cn && clsList.indexOf(cn) < 0) clsList.push(cn);
    res.tried = clsList.length;
    let tr = _inv(lp, "get_transform");
    for (let i = 0; i < (deep || 8) && tr && !tr.isNull(); i++) {
      let go = null;
      try { go = _inv(tr, "get_gameObject"); } catch (e) {}
      if (!go || go.isNull()) break;
      const nm = _str(_inv(go, "get_name"));
      const row = { i: i, name: nm, comps: [] };
      for (const cn of clsList) {
        let c = null;
        try {
          const k = findClass(cn);
          if (!k || k.isNull()) continue;
          const t = _typeOf(k);
          if (!t || t.isNull()) continue;
          // ★这个构建的 GameObject **没有** GetComponent(Type)（dump.cs 已确认）→ 主用 GetComponentInChildren
          c = _inv2oi(go, "GetComponentInChildren", t, 1);        // includeInactive=true
          if (!c || c.isNull()) c = _inv1o(go, "GetComponentInChildren", t);
          if (!c || c.isNull()) c = _inv1o(go, "GetComponent", t); // 有的话也试一下
        } catch (e) { continue; }
        if (c && !c.isNull()) {
          row.comps.push(cn);
          res.hits.push({ depth: i, node: nm, cls: cn, ptr: String(c) });
          g_csPageObjs[cn] = c;
        }
      }
      res.layers.push(row);
      try { tr = _inv(tr, "get_parent"); } catch (e) { break; }
    }
    for (const cn of Object.keys(g_csPageObjs)) {
      const o = g_csPageObjs[cn];
      if (!o || o.isNull()) continue;
      const f = { cls: cn, ptr: String(o), ptrSlots: [] };
      for (let off = 0x10; off <= 0x1A0; off += 4) {     // 槽里"像对象"的（类名可读）列出来
        try {
          const p = o.add(off).readPointer();
          const kn = ptrOk(p) ? klassNameOf(p) : "";
          if (kn && kn !== "?" && kn !== "no-class" && kn !== "err") {
            f.ptrSlots.push("0x" + off.toString(16) + "=" + kn);
          }
        } catch (e) {}
      }
      res.fields.push(f);
    }
    // ★真页面零件 PartsDialogLabyrinthTicketAcquireCharacter 的字段（dump.cs 偏移早就核对过，只是一直没拿到真实例）
    //   +0x80 scrollController → +0x58 useUnitIconList ｜ +0x88 selectCharacterNumLabel（★自检：应等于我们读的那个标签）
    //   +0xE0 enterId  +0xE4 ticketId ｜ +0xE8 acquireIdList ｜ +0xF0 selectableNum
    //   +0x100 sortedUnitIdList ｜ +0x138 temporaryUnitParameterDictionary（**很可能=当前已选**）
    const ta = g_csPageObjs["PartsDialogLabyrinthTicketAcquireCharacter"];
    if (ta && !ta.isNull()) {
      const o = { ptr: String(ta), uiName: null, enterId: null, ticketId: null, selectableNum: null,
                  labelPtr: null, labelOk: null, acquire: [], sorted: [], tempKeys: [],
                  ctrlCls: null, ctrlIcons: null };
      try { o.uiName = _str(_inv(_inv(ta, "get_gameObject"), "get_name")); } catch (e) {}
      try { o.enterId = ta.add(0xE0).readS32(); } catch (e) {}
      try { o.ticketId = ta.add(0xE4).readS32(); } catch (e) {}
      try { o.selectableNum = ta.add(0xF0).readS32(); } catch (e) {}
      try {
        const l = ta.add(0x88).readPointer();
        o.labelPtr = ptrOk(l) ? String(l) : null;
        const cur = g_csLabelPtrs["SelectableNumLabel"];
        o.labelOk = (ptrOk(l) && cur) ? (String(l) === String(cur)) : null;
      } catch (e) {}
      try { o.acquire = _intListOf(ta.add(0xE8).readPointer()); } catch (e) {}
      try { o.sorted = _intListOf(ta.add(0x100).readPointer()); } catch (e) {}
      try { for (const r of enumDict(ta.add(0x138).readPointer())) o.tempKeys.push(r.key); } catch (e) {}
      try {
        const c = ta.add(0x80).readPointer();
        if (ptrOk(c)) {
          o.ctrlCls = klassNameOf(c);
          const l = c.add(0x58).readPointer();
          o.ctrlIcons = ptrOk(l) ? listCount(l) : -1;
        }
      } catch (e) {}
      res.ta = o;
    }
  } catch (e) { res.err = String(e); }
  return res;
}

// ── ★选角色页"定案版"读取（2026-10-11 实机确认）──
// 真零件：PartsDialogLabyrinthTicketAcquireCharacter（父链深 3，节点名同）
//   +0xE8  acquireIdList(List<int>)   ← **已选，按点击顺序**（实机与画面逐一对应 ✓）
//   +0xF0  selectableNum(int)         ← 能选几个（3）
//   +0x100 sortedUnitIdList(List<int>)← 候选池，**显示顺序**（权威，不受滚动影响）
//   +0x88  selectCharacterNumLabel    ← "已选/可选"标签（自检用：应等于屏上那个）
// 定位：缓存 TA 组件（整树遍历时顺带拿到）；缓存空了才从标签往上找一次（1 秒最多一次）
let g_csCharaPage = null, g_csCharaPageAt = 0;
const CS_CHARA_CLS = "PartsDialogLabyrinthTicketAcquireCharacter";   // 实测确认（父链深 3 的节点同名）
// ★精准定位：从 DialogArea 的子树 GetComponentInChildren(类, includeInactive=true) 直接拿
//   （不再整树遍历、不再堆扫描；类名/节点名都是 2026-10-11 实机确认过的）
let g_csCharaMiss = 0;
function labCharaPageFind() {
  const out = { ok: false, ptr: null, node: null, err: null };
  try {
    const k = findClass(CS_CHARA_CLS);
    if (!k || k.isNull()) { out.err = "没有这个类：" + CS_CHARA_CLS; return out; }
    const t = _typeOf(k);
    if (!t || t.isNull()) { out.err = "取 System.Type 失败"; return out; }
    if (!g_dialogArea || g_dialogArea.isNull() || !_dialogAreaOk(g_dialogArea)) {
      g_dialogArea = _findDialogAreaTr();
    }
    if (!g_dialogArea || g_dialogArea.isNull()) { out.err = "没找到 DialogArea"; return out; }
    let cc = 0;
    try { const r = _inv(g_dialogArea, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
    for (let i = cc - 1; i >= 0; i--) {              // 倒序：后加的在上层
      const ch = _inv1i(g_dialogArea, "GetChild", i);
      if (ch.isNull()) continue;
      const go = _inv(ch, "get_gameObject");
      if (go.isNull()) continue;
      let c = null;
      try { c = _inv2oi(go, "GetComponentInChildren", t, 1); } catch (e) {}      // includeInactive=true
      if (!c || c.isNull()) { try { c = _inv1o(go, "GetComponentInChildren", t); } catch (e) {} }
      if (c && !c.isNull()) {
        out.ok = true; out.ptr = String(c);
        try { out.node = _str(_inv(_inv(c, "get_transform"), "get_name")); } catch (e) {}
        g_csCharaPage = c;
        return out;
      }
    }
    out.err = "DialogArea 子树里没有 " + CS_CHARA_CLS;
  } catch (e) { out.err = String(e); }
  return out;
}
// DialogArea 校验（复用它，避免把别的节点当家）
function _dialogAreaOk(tr) {
  try { return _str(_inv(_inv(tr, "get_gameObject"), "get_name")) === "DialogArea"; } catch (e) { return false; }
}

function labCharaPageQuick() {
  const out = { jsBuild: JS_BUILD, ok: false, ta: null, node: null, cands: [], picked: [],
                selectableNum: null, label: null, cells: [], buttons: {}, friendCount: null, err: null };
  try {
    let ta = g_csCharaPage;
    // 缓存有效性：+0xF0 必须是 1..30（页面上限），否则视为失效重找
    // ★2026-10-31 补：**还要核对节点名**。上一局的面板被销毁后，旧指针偶尔仍能读出一个
    //   1..30 的数字 ⇒ 缓存被判"有效"⇒ 后面读 list 抛异常 ⇒ `out.ok` 永远停在 false，
    //   而且缓存不会自愈 ⇒ 上层白等 30s（13:52:37 那次就是这么卡死的）。
    if (ta && !ta.isNull()) {
      let sn = -1, nm = null;
      try { sn = ta.add(0xF0).readS32(); } catch (e) {}
      try { nm = _str(_inv(_inv(ta, "get_transform"), "get_name")); } catch (e) {}
      if (!(sn >= 1 && sn <= 30) || !nm || nm.indexOf(CS_CHARA_CLS) !== 0) {
        ta = null;
        g_csCharaPage = null;
      }
    }
    if (!ta || ta.isNull()) {
      if (Date.now() - g_csCharaPageAt < 500) { out.err = "页面零件未就绪"; return out; }   // 别每轮都重找
      g_csCharaPageAt = Date.now();
      const f = labCharaPageFind();
      if (!f.ok) { out.err = f.err || "没找到页面零件"; return out; }
      ta = g_csCharaPage;
    }
    out.ta = String(ta);
    try { out.node = _str(_inv(_inv(ta, "get_transform"), "get_name")); } catch (e) {}
    try { out.picked = _intListOf(ta.add(0xE8).readPointer()); } catch (e) {}          // ★已选（点击顺序）
    try { out.cands = _intListOf(ta.add(0x100).readPointer()); } catch (e) {}          // ★候选（显示顺序）
    try { out.selectableNum = ta.add(0xF0).readS32(); } catch (e) {}
    try { out.label = _str1(_inv(ta.add(0x88).readPointer(), "get_text")); } catch (e) {}
    const env = _camEnv();                       // 坐标换算环境（格子与按钮共用）
    try {                                                                             // 每格坐标（点击用）
      const c = ta.add(0x80).readPointer();                                            // scrollController
      const lst = ptrOk(c) ? c.add(0x58).readPointer() : ptr(0);                       // useUnitIconList
      const n = ptrOk(lst) ? listCount(lst) : 0;
      const items = ptrOk(lst) ? lst.add(0x10).readPointer() : ptr(0);
      for (let i = 0; i < n && i < 64; i++) {
        const ic = ptrOk(items) ? items.add(0x20 + i * 8).readPointer() : ptr(0);
        if (!ptrOk(ic)) continue;
        let uid = 0;
        try { uid = partyUnitIdOfIcon(ic).unitId; } catch (e) {}
        const it = { i: i, unitId: uid, x: null, y: null };
        if (env) {
          try { const ss = _screenOf(ic, env.cam, env.mW2S);
                if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); } } catch (e) {}
        }
        out.cells.push(it);
      }
    } catch (e) {}
    try {                              // ★四个按钮 + 两个计数标签（并**读出按钮文字**，靠文字核对而不是靠猜）
      //   +0x90 mapButton=左下「地图」｜+0x98 friendButton=左下「角色」｜+0xA0 relicButton=左下「迷宫遗物」
      //   +0xA8 decideButton=**右下「去邀请」**（本页"决定"文案就是它 —— 2026-10-11 实测 + 截图确认）
      const B = { map: 0x90, friend: 0x98, relic: 0xA0, decide: 0xA8 };
      const uk = findClass("CustomUILabel");
      for (const nm in B) {
        const b = ta.add(B[nm]).readPointer();
        if (!ptrOk(b)) continue;
        const it = { ptr: String(b), x: null, y: null, text: null };
        if (env) {
          try { const ss = _screenOf(b, env.cam, env.mW2S);
                if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); } } catch (e) {}
        }
        try {                                   // 按钮的说明文字（= 按钮上那行字）
          const go = _inv(b, "get_gameObject");
          if (ptrOk(go) && uk && !uk.isNull()) {
            const lab = _compInChildren(go, uk);
            if (lab && !lab.isNull()) it.text = _str1(_inv(lab, "get_text"));
          }
        } catch (e) {}
        out.buttons[nm] = it;
      }
      try { out.friendCount = _str1(_inv(ta.add(0xB0).readPointer(), "get_text")); } catch (e) {}
    } catch (e) {}
    out.ok = true;
  } catch (e) {
    out.err = String(e);
    // ★出错就丢掉缓存，下一轮重新找（否则坏指针会一直被当成"有效"，永久 ok=false）
    g_csCharaPage = null;
  }
  return out;
}

// ── ★抓"谁在更新计数标签"（2026-10-11 加）──
// 背景：为找"哪几格已选"已经排除了四条路 —— 节点显隐✗、图标字段差分✗、LTD 差分✗、sprite 图名✗。
// 换最直接的一招：**hook 计数标签的写入口**，抓调用栈。
//   你点一格 → 标签被 set_text('2/3'→'3/3') → 栈里就是"选中逻辑"所在方法；
//   把栈地址减去 libil2cpp 基址 = RVA → 拿 script.json（34 万个方法的 RVA→名字）反查 → 得到类名/方法名。
// 只记含 '数字/数字'（或"已选/选择"）的文本，避免 UILabel 刷屏。
let g_textHooked = null, g_textEv = [];
const TEXT_EV_MAX = 24;
function _btAddrs(ctx, n) {
  const out = [];
  try {
    const mod = Process.findModuleByName("libil2cpp.so");
    const bt = Thread.backtrace(ctx, Backtracer.ACCURATE) || [];
    for (const a of bt.slice(0, (n || 8) * 2)) {
      // ★只留 libil2cpp 内的地址：栈里常混进堆指针/其他 so
      //   （2026-10-11 实机见过某帧正好等于 SelectableNumLabel 的堆地址 → 那种帧查不到名字）
      if (!mod) { out.push({ a: String(a), off: null }); continue; }
      if (a.compare(mod.base) < 0 || a.compare(mod.base.add(mod.size)) >= 0) continue;
      out.push({ a: String(a), off: "0x" + a.sub(mod.base).toString(16) });
      if (out.length >= (n || 8)) break;
    }
  } catch (e) {}
  return out;
}
function labHookText() {
  if (g_textHooked) return g_textHooked;
  const out = { hooked: [], err: null, calls: 0, hits: 0 };
  try {
    const k = findClass("CustomUILabel");
    if (!k || k.isNull()) { out.err = "未找到类 CustomUILabel"; g_textHooked = out; return out; }
    const r = findMethodLike(k, "set_text");
    if (!r) { out.err = "未找到 CustomUILabel.set_text"; g_textHooked = out; return out; }
    const fp = methodPtr(r.m || r);
    if (!fp || fp.isNull()) { out.err = "set_text 指针为空"; g_textHooked = out; return out; }
    runOnGameThread(function () {
      Interceptor.attach(fp, {
        onEnter(a) {
          try {
            out.calls++;
            const self = (typeof safeArg === "function") ? safeArg(a, 0) : a[0];
            const sp = (typeof safeArg === "function") ? safeArg(a, 1) : a[1];
            let txt = null;
            try { if (sp && !sp.isNull()) txt = _str1(sp); } catch (e) {}
            if (!txt || !/\d\s*\/\s*\d|已选|选择/.test(txt)) return;
            out.hits++;
            if (g_textEv.length >= TEXT_EV_MAX) g_textEv.shift();
            // ★16 帧：实测调用链是 SetText ← 3 个未解析帧 ← RuntimeMethodInfo$$Invoke ← MethodBase$$Invoke
            //   ← **第 6 帧之后才是"谁发起了这次反射调用"**（上次只抓 6 帧，刚好在这之前截断）
            g_textEv.push({ ts: Date.now(), this: String(self), text: txt, bt: _btAddrs(this.context, 16) });
          } catch (e) {}
        }
      });
      out.hooked.push("CustomUILabel.set_text");
      console.log("[hook] 已挂 CustomUILabel.set_text —— 点一格，计数标签一更新就会记下调用栈");
    });
  } catch (e) { out.err = String(e); }
  g_textHooked = out;
  return out;
}
function labTextEvents() {
  let base = null;
  try { const m = Process.findModuleByName("libil2cpp.so"); if (m) base = String(m.base); } catch (e) {}
  return { base: base, n: g_textEv.length, ev: g_textEv.slice(-6) };
}

// ── ★直接问游戏：把"点击选中"时被调用的方法钩出来（2026-10-11 加）──
// 为什么不用反编译：dump.cs 只有签名、没有函数体（要看逻辑得 IDA/Ghidra）。而"哪一格被选"
// 我们猜了好几轮都不对（IsCheck 恒 false、checkIcon 恒 enabled=true）→ 改成**观测**：
//   · UISprite.set_spriteName(string)：NGUI 换图 = 切"选中/取消"最常见的手法（只记含
//     select/check/choice/fav 的图名，避免刷屏）
//   · UnitIcon 的 set_* 方法（含父类）：记 this + 参数，同一 (方法,对象,值) 只记一次
// 装好后你点一格 → 日志里出现"谁被设成了什么" → 顺藤摸瓜找到真正的字段。
let g_clickHooked = null;
function labHookClick() {
  if (g_clickHooked) return g_clickHooked;
  const out = { hooked: [], err: null, spriteCalls: 0, setterCalls: 0 };
  const arg = function (a, i) { try { return (typeof safeArg === "function") ? safeArg(a, i) : a[i]; } catch (e) { return null; } };
  try {
    runOnGameThread(function () {
      const seen = {};
      try {                                              // ① UISprite.set_spriteName(string)
        const sp = findClass("UISprite");
        const r = (sp && !sp.isNull()) ? findMethodLike(sp, "set_spriteName") : null;
        const fp = r ? methodPtr(r.m || r) : null;
        if (fp && !fp.isNull()) {
          Interceptor.attach(fp, {
            onEnter(a) {
              try {
                out.spriteCalls++;
                const nm = arg(a, 1);
                const s = (nm && !nm.isNull()) ? nm.readUtf8String() : "?";
                if (/select|check|choice|fav/i.test(s)) {
                  console.log("[hook] UISprite.set_spriteName this=" + arg(a, 0) + " → " + s);
                }
              } catch (e) {}
            }
          });
          out.hooked.push("UISprite.set_spriteName");
        }
      } catch (e) { out.err = "spriteName: " + e; }
      try {                                              // ② UnitIcon.set_*（含父类）
        const k = findClass("UnitIcon");
        let c = k, d = 0;
        while (c && !c.isNull() && d < 4) {
          const it = Memory.alloc(Process.pointerSize); it.writePointer(ptr(0));
          while (true) {
            const f = g_il2cpp.class_get_methods(c, it);
            if (f.isNull()) break;
            let nm = ""; try { nm = g_il2cpp.method_get_name(f).readUtf8String(); } catch (e) {}
            if (!/^set_/.test(nm) || seen[nm]) continue;
            const fp = methodPtr(f);
            if (!fp || fp.isNull()) continue;
            seen[nm] = 1;
            (function (name) {
              Interceptor.attach(fp, {
                onEnter(a) {
                  try {
                    out.setterCalls++;
                    const self = arg(a, 0), v = arg(a, 1);
                    const kk = name + "@" + self + "=" + v;
                    if (seen[kk]) return;
                    seen[kk] = 1;
                    console.log("[hook] UnitIcon." + name + " this=" + self + " arg=" + v);
                  } catch (e) {}
                }
              });
            })(nm);
            out.hooked.push("UnitIcon." + nm);
          }
          c = g_il2cpp.class_get_parent(c); d++;
        }
      } catch (e) { out.err = (out.err || "") + " unitIcon: " + e; }
      console.log("[hook] 已挂 " + out.hooked.length + " 个方法：" + out.hooked.join(", "));
    });
  } catch (e) { out.err = String(e); }
  g_clickHooked = out;
  return out;
}

// 扫不到时的"冷却"：别每轮都全堆扫描（实测一次 ~0.5-2s，会把主循环拖慢）
let g_csMissAt = 0;
const CS_MISS_COOLDOWN_MS = 5000;

function labCharaSelectSnapshot(diag) {
  const res = { jsBuild: JS_BUILD, pageTop: null, dlg: null, enterId: null, ticketId: null,
                selectableNum: null, acquire: [], sorted: [], info: [], tempKeys: [], label: null,
                buttons: {}, iconCount: null, icons: [], selCount: 0, err: null };
  try {
    return _labCharaSelectBody(diag, res);
  } catch (e) {                       // ★异常别再被吞掉：带回去给 Python 打出来
    res.err = "读取异常: " + e;
    return res;
  }
}

function _labCharaSelectBody(diag, res) {
  const _now = Date.now();
  if (!diag && g_csCache && (_now - g_csAt) < CS_TTL_MS) return g_csCache;
  try { res.pageTop = _topDialogName() || ""; } catch (e) {}
  res.clsName = "PartsDialogLabyrinthSelectableChara";
  const k = findClass("PartsDialogLabyrinthSelectableChara");
  if (!k || k.isNull()) { res.err = "未找到类 PartsDialogLabyrinthSelectableChara"; return res; }
  let d = diag ? ptr(0) : _instCacheGet("labCharaSel", "PartsDialogLabyrinthSelectableChara", _csValid);
  if (!d || d.isNull()) {
    const viaGo = _compInDialogs(k, diag ? (res.locate = {}) : null);   // ① 对话框子树里直取（最可靠，不扫堆）
    if (!viaGo || viaGo.isNull()) {
      if (!diag && (Date.now() - g_csMissAt) < CS_MISS_COOLDOWN_MS) {
        res.err = "本页没找到该零件（" + Math.round(CS_MISS_COOLDOWN_MS / 1000)
                  + " 秒内不再重扫，免得每轮拖慢主循环）";
        return res;
      }
      const good = _scanKlassValid(k, diag ? 12 : 8, _csValid);     // ② 兜底：堆扫描 + 严格校验
      if (diag) {
        // 诊断：再收一批"原始命中"（含字段槽）印出来比对
        // ★必须传"全收"的校验：_scanKlassValid 里 `if (!validate || !validate(addr)) continue`
        //   → 传 null 等于**全否**（上一版就栽在这，诊断表永远是 0）
        const raw = _scanKlassValid(k, 12, function () { return true; });
        res.diagInsts = raw.length;
        res.diagValid = good.length;
        res.probe = raw.map(function (x) { return _csProbe(x); });
      }
      if (!good.length) {
        g_csMissAt = Date.now();
        res.err = "没定位到「选角色页」实例（对话框子树里没有；堆扫描严格校验 0 个，原始命中 "
                  + (res.diagInsts == null ? "?" : res.diagInsts) + " 个）";
        return res;
      }
      d = good[0];
      res.how = "堆扫描";
    } else {
      d = viaGo;                                     // ★别忘了赋值（冒烟测试就是在这抓到漏掉的）
      res.how = "对话框子树 GetComponentInChildren";
    }
  }
  _instCachePut("labCharaSel", d);
  res.dlg = String(d);
  const ctrl = d.add(0x88).readPointer();               // controller
  res.ctrl = String(ctrl);
  try { res.ctrlCls = klassNameOf(ctrl); } catch (e) {}
  try { res.cands = _intListOf(ctrl.add(0x38).readPointer()); } catch (e) {}   // ★候选角色（权威）
  const env = _camEnv();
  // 屏上图标（坐标 + 已选）：内容对象 PartsSelectLabyrinthCharaIconArray(+0x30 unitIcons)
  try {
    const ak = findClass("PartsSelectLabyrinthCharaIconArray");
    const arrs = (ak && !ak.isNull()) ? _scanKlassValid(ak, 12, function (x) {
      const l = x.add(0x30).readPointer();
      if (!ptrOk(l)) return false;
      const n = listCount(l);
      return (n >= 1 && n <= 8);
    }) : [];
    res.arrCount = arrs.length;
    const seen = {};
    for (const a of arrs) {
      const ics = _iconListOf(a.add(0x30).readPointer());
      for (const x of ics) {
        const icon = ptr(x.ptr);
        let uid = 0, cls = x.cls;
        try { const r = partyUnitIdOfIcon(icon); uid = r.unitId; cls = r.cls; } catch (e) {}
        if (!(uid > 0) || seen[uid]) continue;
        seen[uid] = 1;
        const it = { i: res.icons.length, unitId: uid, cls: cls, selected: null, my: null, check: null };
        try { it.my = _compActive(icon.add(0x98).readPointer()); } catch (e) {}
        try { it.check = _compActive(icon.add(0x88).readPointer()); } catch (e) {}
        it.selected = !!(it.my || it.check);
        if (env) {
          try { const ss = _screenOf(icon, env.cam, env.mW2S);
                if (ss) { it.x = Math.round(ss.x); it.y = Math.round(env.H - ss.y); } } catch (e) {}
        }
        res.icons.push(it);
      }
    }
    res.iconCount = res.icons.length;
    res.selCount = res.icons.filter(function (o) { return o.selected; }).length;
  } catch (e) {}
  if (!diag) { g_csCache = res; g_csAt = _now; }
  return res;
}

// ── ★RPC 读路径的线程安全包装（关键修复）──
// frida 的 RPC 线程 ≠ 游戏主线程。_inv/_screenOf 全是裸 runtime_invoke，而本节开头已写明
// "通用小工具（均需在游戏线程内调用）"。只做 il2cpp_thread_attach 不够：ART 线程上下文
// (gs:[0xe0]) 仍为空 → 1) 托管 monitor 锁会 SEGV；2) 更常见的后果是 il2cpp/GC 的
// stop-the-world 要等本线程到达安全点而**永远等不到** → 游戏主线程死等 ⇒ 画面卡死、RPC 永不返回。
// 表现就是"脚本跑到一半游戏卡死"，且高频遍历时几乎必然发生。
// 用 withArt（Java.perform → JNI AttachCurrentThread）把 ART 上下文补齐后再执行，即安全。
// 注意：不开任何 Interceptor 钩子，"零钩子"架构保持不变。
function _rpcSafe(fn) {
  return function () {
    const args = Array.prototype.slice.call(arguments);
    let out;
    withArt(function () { out = fn.apply(null, args); });
    return out;
  };
}

// 本局"已持有角色"（速通组队要用：角色池，用户 2026-10-08）：
//   LabyrinthTempData+0x28 OwnUnitInfoSet: HashSet<LabyrinthUnitType>
//     LabyrinthUnitType: UnitId@0x10（**明文模板 id**，如 110301）/ UnitType@0x1C（1=USER 2=NPC）
//   ★NPC 形态也算"本轮抽到了"（抽到时账号未持有/练度不够 → 以 NPC 出场），所以两种都返回。
function labUnitsSnapshot() {
  // ★带"读了多少"的计数（2026-10-16 查"持有池读少"）：setCap = `_entries` 数组长度、
  //   setScan = 实际扫的槽、setEmpty = 空槽数、setHit = 命中对象数。
  //   用途：页面说"可编入 28"而这里只给 15 时，看 setCap/setEmpty 就能分清
  //   ① 集合本身就只有 15 个（cap≈17、空槽 2）⇒ 页面多出的是**页面侧**的东西（租借/NPC）；
  //   ② cap 明明 ≥28 却只命中 15 ⇒ 我们**漏读**（槽位步长/字段偏移不对），那才是 bug。
  const res = { unitIds: [], units: [], setCap: -1, setScan: 0, setEmpty: 0, setHit: 0, setCount: -1, setFree: -1, err: null };
  try {
    const ltd = _findLTD();
    if (!ltd) { res.err = "未定位 LabyrinthTempData"; return res; }
    const set = ltd.add(0x28).readPointer();
    if (!ptrOk(set)) { res.err = "OwnUnitInfoSet 为空"; return res; }
    // ★HashSet._count(@+0x20) / _freeCount(@+0x2C)：与 `_entries` 数组长度(cap) 交叉验证用。
    //   用途（2026-10-16 查"页面可编入 7 > 持有 3"）：`count == cap` 且都小 ⇒ **集合真的只有这么几个**
    //   （说明这不是"漏读"、而是这个字段本来就只装那些，页面侧多出的是别的来源）；
    //   若 `count > cap` 或 `cap` 很大却只命中几个 ⇒ 才是我们读法/偏移的问题。
    try { res.setCount = set.add(0x20).readS32(); } catch (e) {}
    try { res.setFree = set.add(0x2C).readS32(); } catch (e) {}
    const entries = set.add(0x18).readPointer();
    if (!ptrOk(entries)) { res.err = "OwnUnitInfoSet._entries 为空"; return res; }
    const cap = entries.add(0x18).readS32();
    res.setCap = cap;
    res.setScan = cap;
    for (let i = 0; i < cap; i++) {
      const slot = entries.add(0x20 + i * 16);
      let hc = -2; try { hc = slot.readS32(); } catch (e) {}
      if (hc === -1) { res.setEmpty++; continue; }     // -1 = 空槽
      const v = slot.add(8).readPointer();
      if (!ptrOk(v)) continue;
      let uid = -1, utype = null;
      try { uid = v.add(0x10).readS32(); } catch (e) {}   // 明文模板 id
      try { utype = v.add(0x1C).readS32(); } catch (e) {} // 1=USER 2=NPC
      if (uid >= 0) { res.unitIds.push(uid); res.units.push({ unitId: uid, unitType: utype }); res.setHit++; }
    }
    res.unitIds.sort(function (a, b) { return a - b; });
  } catch (e) { res.err = "" + e; }
  return res;
}

// 最上层那个 **Dialog*** 子节点的 Transform。
//   用途：结算/奖励/事件这类页面的按钮**只在最上层**；扫整个 DialogArea 会把被盖住的旧对话框
//   一起扫进去（实测 DialogArea 里同时挂着 10 个对话框）⇒ 又慢、又可能捞到**别的对话框里的同名按钮**。
// ★★2026-11-08：**改为复用 [_topDialogGo]**（不再自己写一遍判据）。
//   同一个"最上层"必须只有一个实现 —— 否则几处判定各说各话（EX 三连页就栽在这上面：
//   labTop 说最上层已是 setting，而查找还在读下面那层仍 active 的旧页 ⇒ 签名永不变化 ⇒ 多点 ⇒ 点错）。
function _topDialogTr() {
  try {
    const t = _topDialogGo();
    if (!t || !t.go) return null;
    const tr = _inv(t.go, "get_transform");
    return (tr && !tr.isNull()) ? tr : null;
  } catch (e) { return null; }
}

// ★★2026-11-08：**"给了根但根取不到"≠"扫全树"**。
//   实测：当前没有对话框时 `_subtreeRoot("top")` = null ⇒ 老的写法退化成 `_walkNodes()`（全树）
//   ⇒ 一次 **2633ms**（`findList(…,"top")` 实测），而且会把**别处**的同名节点一起捞出来。
//   而所有调用方本来就是"按根列表逐个试"（`top` → `dialog` → `layer`）⇒ 返回空最合适：
//   立刻试下一个根（`dialog` 7ms），或下一轮再看。全树只在**调用方没给根**时才用。
function _walkForRoot(rootName, root) {
  if (rootName && !root) return null;          // 显式要根、根却没了 ⇒ 空（不退化）
  return root ? _rootWalkNodes(root, rootName) : _walkNodes();
}

function _subtreeRoot(kind) {
  try {
    if (kind === "battle" || kind === "view") {
      const vm = _viewManagerInst();
      if (!vm || vm.isNull()) return null;
      const cv = vm.add(0x110).readPointer();
      if (!ptrOk(cv)) return null;
      const tr = _inv(cv, "get_transform");
      return (tr && !tr.isNull()) ? tr : null;
    }
    if (kind === "top") return _topDialogTr();          // 最上层对话框（结算/奖励这类页面看这层）
    if (kind === "dialog") return _dialogAreaTr();      // 整个 DialogArea
    // ★`layer`（2026-10-17 加）＝ **DialogArea 的父层**（实机祖先链里是 `IgnoreBlurLayer`）——
    //   覆盖 "DialogArea 自己 + 同层的兄弟节点"。用途：有些按钮（如事件演出「跳过」）**既不在当前视图、
    //   也不在 DialogArea 里**，但就挂在这个层上（比整棵 UI 树小得多 ✓，遍历代价可接受 ✓）。
    if (kind === "layer") {
      const d = _dialogAreaTr();
      if (!d) return null;
      const p = _inv(d, "get_parent");
      return (p && !p.isNull()) ? p : null;
    }
    if (kind === "result") {                            // 结算对话框自己那棵
      for (const nd of _walkNodes()) {
        if (nd.active === true && nd.tr && /DialogLabyrinthResult/i.test(nd.name || "")) return nd.tr;
      }
    }
  } catch (e) {}
  return null;
}

// ★按根子树的遍历缓存（2026-10-16：战斗中"单次探测 16s → agent 被误判掉线"的元凶之一）——
//   `findTextIn` 原来对**每个 root × 每个名单**都重新 `_rawWalk` 一遍子树（一次探测 3 根 × 2 名单 = 6 遍），
//   而带根的遍历**很贵**（几百节点）。给它加上和 `_walkNodes` 同样的 250ms TTL：一轮里的多次查询共用一次遍历。
const g_rootWalk = {};
// ★TTL 250ms → **1500ms**（2026-10-16 二次：修掉 `wantCoords` 后探测仍有 16s ⇒ 剩下的成本是
//   **遍历本身**（`_rawWalk` 每节点 ≈5 次调用，几百节点一次就是 1~3s，而一次探测要 3 根 × 2 名单 = 6 遍）。
//   同一轮里的多次查询共用一次遍历 ⇒ 6 遍降到 3 遍）
const ROOT_WALK_TTL_MS = 1500;
function _rootWalkNodes(rootTr, key) {
  const now = Date.now();
  const c = g_rootWalk[key];
  // ★★2026-11-08：缓存必须**校验根是不是同一个**。原来只看 key ⇒ `"top"` 这个根会变
  //   （最上层对话框换了），1.5s 内会把**上一个对话框的子树**当成当前的返回 ⇒ 读到别的页的按钮。
  //   （NativePointer 是对象、每次都是新引用 ⇒ 只能比 String()。）
  const rootStr = String(rootTr);
  if (c && c.rootStr === rootStr && (now - c.at) < ROOT_WALK_TTL_MS) return c.list;
  const out = [];
  try { _rawWalk(function (nd) { out.push(nd); }, false, rootTr); } catch (e) {}
  g_rootWalk[key] = { at: Date.now(), rootStr: rootStr, list: out };
  return out;
}

// ══════════════════════════════════════════════════════════════════════════════
// ★战斗设定读取（SaveDataManager，2026-10-22；只读，验证用）
//   与 pcrtool 的 `force_settings` 同源三项：
//     UseUbSetMode（连结爆发立即发动模式）
//     ResetSkillReserveEveryTime（发动时解除立即发动模式）
//     AutoSpeedSetting.ButtonState（自动战斗·战斗速度：0=继承 1=无法继承）
//   ⚠pcrtool 注明这三个 getter 是 **IFix 模板**（补丁激活后直调会断言失败）⇒ 只能游戏主线程执行；
//     而 Frida JS 单线程，RPC 里同步等主线程回调会**死锁** ⇒ 两段式：
//     `battleSettingsStart` 投递到 [runOnGameThread]，`battleSettingsPoll` 下一轮取结果。
// ══════════════════════════════════════════════════════════════════════════════
let g_battleSetRes = null;

/** 取 SaveDataManager 单例：ManagerSingleton<SaveDataManager>.get_Instance()（同 getApiMgr 那套） */
function _saveDataMgrInst() {
  const k = findClass("SaveDataManager");
  if (!k || k.isNull()) return { err: "未找到 SaveDataManager 类" };
  const m = findMethodAny(k, "get_Instance") || findMethodAny(k, "GetInstance");
  if (!m || m.isNull()) return { err: "未找到 get_Instance / GetInstance" };
  if (g_il2cpp.thread_attach) g_il2cpp.thread_attach(g_il2cpp.domain_get());
  const ex = Memory.alloc(8); ex.writePointer(ptr(0));
  const p = Memory.alloc(8); p.writePointer(ptr(0));       // 静态方法无参数
  const r = g_il2cpp.runtime_invoke(m, ptr(0), p, ex);
  if (!ex.readPointer().isNull()) return { err: "get_Instance 抛异常" };
  if (!r || r.isNull()) return { err: "get_Instance 返回 null（单例还没建好/不在可用页）" };
  return { inst: r };
}

/** 调无参属性 getter；返回 {boxed: 指针} 或 {err} */
function _callGet(inst, name) {
  try {
    const m = _mInfo(g_il2cpp.object_get_class(inst), name);
    if (!m) return { err: "没有方法 " + name };
    const ex = Memory.alloc(8); ex.writePointer(ptr(0));
    const r = g_il2cpp.runtime_invoke(m, inst, ptr(0), ex);
    if (!ex.readPointer().isNull()) return { err: name + " 抛异常" };
    return { boxed: r };
  } catch (e) { return { err: name + ": " + e }; }
}

/** 在游戏线程里执行的真正读取（值类型返回值是装箱对象 ⇒ bool 读 +0x10 的 u8） */
function _battleSettingsReadNow() {
  const g = _saveDataMgrInst();
  if (g.err) return { ok: false, err: g.err };
  const inst = g.inst;
  const out = { ok: true, jsBuild: JS_BUILD, cls: klassNameOf(inst), ptr: "" + inst };

  const ub = _callGet(inst, "get_UseUbSetMode");
  if (ub.err) out.useUbErr = ub.err;
  else out.useUbSetMode = (ub.boxed && !ub.boxed.isNull()) ? (ub.boxed.add(0x10).readU8() !== 0) : null;

  const rs = _callGet(inst, "get_ResetSkillReserveEveryTime");
  if (rs.err) out.resetErr = rs.err;
  else out.resetSkillReserveEveryTime = (rs.boxed && !rs.boxed.isNull()) ? (rs.boxed.add(0x10).readU8() !== 0) : null;

  const as = _callGet(inst, "get_AutoSpeedSetting");
  if (as.err) out.autoSpeedErr = as.err;
  else if (!as.boxed || as.boxed.isNull()) out.autoSpeedErr = "get_AutoSpeedSetting 返回 null";
  else {
    try { out.autoSpeedButtonState = as.boxed.add(0x10).readS32(); }   // 0=继承 1=无法继承
    catch (e) { out.autoSpeedErr = "读 ButtonState(+0x10) 失败: " + e; }
  }
  return out;
}

rpc.exports = {
  viewId: _rpcSafe(function () { try { return getViewId(); } catch (e) { return -999; } }),
  ready: function () { return !!g_il2cpp; },
  jsBuild: function () { return JS_BUILD; },        // attach 时打印，确认跑的是哪一版
  // ── ★选角色页（2026-10-11 定案，精准读取；不再有扫描/hook/差分）──
  //    labCharaPageQuick：已选(+0xE8) / 候选(+0x100) / 可选数(+0xF0) / 标签(+0x88) / 每格坐标(+0x80→+0x58)
  //    labCharaPageFind ：仅定位（DialogArea 子树 GetComponentInChildren），调试用
  labCharaPageQuick: _rpcSafe(function () { return labCharaPageQuick(); }),
  labCharaPageFind: _rpcSafe(function () { return labCharaPageFind(); }),
  // ↓↓↓ 探索期脚手架（整树遍历 / 堆扫描 / hook / 差分）：**已退役，不再导出**
  //     labCharaGrid / labIconRaw / labCharaLabelText / labPageProbe / labGoComps /
  //     labHookClick / labHookText / labTextEvents / labSummonUnit / labCharaSelect(+Diag)
  find: _rpcSafe(function (reStr) {
    try {
      const re = new RegExp(String(reStr), "i");
      let env = null;
      for (const nd of _walkNodes()) {                 // 读缓存（不算坐标）；名字不匹配就跳过 → 零额外调用
        if (nd.active !== true) continue;
        if (!re.test(nd.name)) continue;
        if (!env) { env = _camEnv(); if (!env) return null; }
        const p = _ndXY(nd, env);                      // 只对候选算坐标（~3 次调用/候选）
        if (p && p.onscreen) return { name: nd.name, x: p.x, y: p.y };
      }
      return null;
    } catch (e) { console.log("[find] 错误: " + e); return null; }
  }),
  // 列出"屏内+激活+名字匹配"的所有节点（DFS 顺序），供 Python 枚举多个同类按钮（如 3 个 DecideButton）
  // 同 find，但**连节点内的标签文字一起返回** → {name, text, x, y}
  //   ★用途：战斗结算那颗按钮在胜/负两页是**同一个 prefab 节点**（名字里都含 NextButton），
  //     靠标签文字分辨：胜利=「下一步」、失败=「重新挑战」（2026-10-12 实测：名字分辨不出来）。
  // ★可选根 `rootName`（2026-10-17）：与 `findList`/`findTextIn` 同一套（[_rootWalkNodes] + 1.5s TTL）。
  //   动机：全树一次 ≈2~3s ✗（实测 `[slow] findTextList 2938/3001/2910ms`，且三次之间隔了多轮 ⇒
  //   1.5s 的遍历缓存救不了），而多数调用找的是**对话框里**的东西（传 `"dialog"` 只走几十个节点 ✓）。
  findText: _rpcSafe(function (reStr, rootName) {
    try {
      const re = new RegExp(String(reStr), "i");
      const uk = findClass("CustomUILabel");
      const root = rootName ? _subtreeRoot(rootName) : null;
      const list = _walkForRoot(rootName, root);
      if (!list) return null;                 // ★显式要的根没了 ⇒ 空（不扫全树，见 [_walkForRoot]）
      let env = null;
      for (const nd of list) {
        if (nd.active !== true) continue;
        if (!re.test(nd.name)) continue;
        if (!env) { env = _camEnv(); if (!env) return null; }
        const p = _ndXY(nd, env);
        if (!p || !p.onscreen) continue;
        let t = null;
        try {
          const go = nd.tr ? _inv(nd.tr, "get_gameObject") : null;
          if (ptrOk(go) && uk && !uk.isNull()) {
            const lb = _compInChildren(go, uk);
            if (ptrOk(lb)) t = _str1(_inv(lb, "get_text"));
          }
        } catch (e) {}
        return { name: nd.name, text: t, x: p.x, y: p.y };
      }
      return null;
    } catch (e) { console.log("[findText] 错误: " + e); return null; }
  }),
  // ★只在某个子树里找文字（用户 2026-10-12："NextButton 的遍历不好，最好能准确定位"）：
  //   root="battle" ⇒ 只走战斗画布（快）；root 传空 ⇒ 整棵树（准但慢）。
  //   Python 侧的策略是**先 battle、找不到再整棵树**，所以这里错了也不会漏点。
  findTextIn: _rpcSafe(function (reStr, rootName, max, minY) {
    const out = [];
    try {
      const re = new RegExp(String(reStr), "i");
      const uk = findClass("CustomUILabel");
      const root = rootName ? _subtreeRoot(rootName) : null;
      if (rootName && !root) return out;                 // 指定了根却拿不到 → 让 Python 走整棵树
      let env = null;
      const cb = function (nd) {
        if (nd.active !== true) return;
        if (!re.test(nd.name)) return;
        if (!env) { env = _camEnv(); if (!env) return; }
        const p = _ndXY(nd, env);
        if (!p || !p.onscreen) return;
        if (minY != null && p.y < minY) return;
        let t = null;
        try {
          const go = nd.tr ? _inv(nd.tr, "get_gameObject") : null;
          if (ptrOk(go) && uk && !uk.isNull()) {
            const lb = _compInChildren(go, uk);
            if (ptrOk(lb)) t = _str1(_inv(lb, "get_text"));
          }
        } catch (e) {}
        out.push({ name: nd.name, text: t, x: p.x, y: p.y });
      };
      // ★`wantCoords` 必须是 **false**（2026-10-16 修，事故复盘）：传 true 会让 agent 给子树里
      //   **每个节点**都算一次屏幕坐标（≈3 次 il2cpp 调用/节点，几百节点 = 上千次 invoke ⇒ 十几秒），
      //   而 `cb` 里本来就只对**名字匹配**的节点算坐标 ⇒ 纯重复劳动。这一句就是"单次探测 16s"的来源。
      //   顺带：带根的遍历走 [_rootWalkNodes]（250ms TTL）⇒ 一轮里 root×名单 多次查询只遍历一次。
      // ★显式要的根没了 ⇒ 什么都不走（不扫全树，见 [_walkForRoot]）
      const _l = _walkForRoot(rootName, root);
      if (_l) { for (const nd of _l) cb(nd); }
    } catch (e) { console.log("[findTextIn] " + e); }
    return (max && out.length > max) ? out.slice(0, max) : out;
  }),
  // findText 的**列表版**：所有命中节点的 {name, text, x, y}。
  //   ★为什么需要：迷宫结算页的数值标签是 **CountUpUILabel**（自己不是文本组件，文本在子节点
  //     的 CustomUILabel 里）→ `get_text` 只会拿到 null（2026-10-12：结算页没分数就是这个原因）。
  //     这里统一走 `_compInChildren` 取子节点文字，与 findText 同一条读法。
  findTextList: _rpcSafe(function (reStr, max, minY, rootName) {
    //   minY（可选）：只收"屏幕 y ≥ minY"的节点 —— 用于定位**屏幕最底部**的按钮
    //   （战斗失败页的「结束/重新挑战」就在底部；2026-10-12）。
    //   ★`rootName`（可选，2026-10-17）：同 `findList`/`findTextIn`，传 `"dialog"` 只走 DialogArea
    //     子树（几十个节点 ✓）⇒ 那种 2~3s 的全树遍历就省了（实测 `[slow] findTextList 2938ms` ✗）。
    const out = [];
    try {
      const re = new RegExp(String(reStr), "i");
      const uk = findClass("CustomUILabel");
      const root = rootName ? _subtreeRoot(rootName) : null;
      const list = _walkForRoot(rootName, root);
      if (!list) return out;                  // ★显式要的根没了 ⇒ 空（不扫全树，见 [_walkForRoot]）
      let env = null;
      for (const nd of list) {
        if (nd.active !== true) continue;
        if (!re.test(nd.name)) continue;
        if (!env) { env = _camEnv(); if (!env) return out; }
        const p = _ndXY(nd, env);
        if (!p || !p.onscreen) continue;
        if (minY != null && p.y < minY) continue;
        let t = null;
        try {
          const go = nd.tr ? _inv(nd.tr, "get_gameObject") : null;
          if (ptrOk(go) && uk && !uk.isNull()) {
            const lb = _compInChildren(go, uk);
            if (ptrOk(lb)) t = _str1(_inv(lb, "get_text"));
          }
        } catch (e) {}
        out.push({ name: nd.name, text: t, x: p.x, y: p.y });
        if (out.length >= (max || 40)) break;
      }
    } catch (e) { console.log("[findTextList] 错误: " + e); }
    return out;
  }),
  // ★加了可选根 `rootName`（2026-10-16）：事件页原来只能**全树**找选项卡（实测 `[slow] findList 2.4~2.7s` ✗），
  //   而事件对话框就在 DialogArea 里 ⇒ 传 `"dialog"` 只走那棵子树（几十个节点 ✓）。不传则维持全树（兼容旧调用）。
  // ★★诊断用（2026-11-08）：同 `findList`，但每个命中额外带**父链**（末 6 层）。
  //   为什么需要：事件选项卡按钮在 `top`（最上层对话框子树）里只找到 **1/2** 颗，另一颗
  //   一定挂在**别的地方**。要决定"抽哪一层当根"（比整个 DialogArea 小、比最上层对话框大），
  //   必须先看清每一颗的父链。
  findListPath: _rpcSafe(function (reStr, rootName, maxDepth) {
    const out = [];
    try {
      const re = new RegExp(String(reStr), "i");
      const root = rootName ? _subtreeRoot(rootName) : null;
      if (!root) return out;
      const env = _camEnv();
      const lim = (maxDepth == null) ? 30 : maxDepth;
      let visited = 0;
      const walk = function (tr, chain, depth) {
        if (!tr || tr.isNull() || depth > lim || visited > 12000) return;
        visited++;
        const cc = _childCountOf(tr);
        for (let i = 0; i < cc; i++) {
          const ch = _inv1i(tr, "GetChild", i);
          if (ch.isNull()) continue;
          let nm = "";
          try { nm = _str(_inv(_inv(ch, "get_gameObject"), "get_name")) || ""; } catch (e) {}
          const nc = chain.concat([nm]);
          if (re.test(nm)) {
            let xy = null;
            if (env) { try { const ss = _screenOf(ch, env.cam, env.mW2S); if (ss) xy = { x: Math.round(ss.x), y: Math.round(env.H - ss.y) }; } catch (e) {} }
            out.push({ name: nm, x: xy ? xy.x : null, y: xy ? xy.y : null, depth: depth + 1, path: nc.slice(-6) });
          }
          walk(ch, nc, depth + 1);
        }
      };
      walk(root, [], 0);
    } catch (e) { out.push({ err: String(e) }); }
    return out;
  }),
  findList: _rpcSafe(function (reStr, rootName) {
    const out = [];
    try {
      const re = new RegExp(String(reStr), "i");
      const root = rootName ? _subtreeRoot(rootName) : null;
      const list = _walkForRoot(rootName, root);
      if (!list) return out;                  // ★显式要的根没了 ⇒ 空（不扫全树，见 [_walkForRoot]）
      let env = null;
      for (const nd of list) {
        if (nd.active !== true) continue;
        if (!re.test(nd.name)) continue;
        if (!env) { env = _camEnv(); if (!env) break; }
        const p = _ndXY(nd, env);
        if (p && p.onscreen) out.push({ name: nd.name, x: p.x, y: p.y });
      }
    } catch (e) { console.log("[findList] 错误: " + e); }
    return out;
  }),
  // ★战斗格子预览页（DialogQuestDetail）一步到位的读法（2026-10-16 加）：
  //   一次**最上层对话框子树**遍历，同时给出 ① 最上层对话框名 ②「挑战」(UIButton_Unit) 坐标
  //   ③ 编组页零件是否在屏（防"预览页其实被编组页盖住 → 按预览页坐标点会点到「战斗开始」"）。
  //   动机：原来是 2 次整树 find（编组页零件交叉验证）+ 1 次（挑战按钮），整树 ≈2s/次
  //   ⇒ 预览页一轮 6s+（实测"看七八秒敌人"）。这里只走"最上层那个 Dialog*"的子树（几十个节点），
  //   且坐标只算给命中的节点（`_rawWalk(cb,false)` 先便宜筛名）。
  //   ★若子树里没找到（页面结构变了），调用方会退回整树 find —— 与本文件 findTextIn 同款兜底策略。
  labQuestDetail: _rpcSafe(function (startBtnRe, rootKind, pathArr) {
    const out = { top: null, start: null, party: false, nodes: 0, ms: 0, err: null };
    const t0 = Date.now();
    try {
      const re = new RegExp(String(startBtnRe || "UIButton_Unit"), "i");
      const partyRe = /^(UnitIcon(L2|L1|C|R1|R2)|ButtonDecide)$/i;
      const tr = _topDialogTr();
      if (!tr) { out.err = "取不到最上层对话框"; out._ms = Date.now() - t0; return out; }
      out.top = _str(_inv(_inv(tr, "get_gameObject"), "get_name"));
      const want = [];
      _rawWalk(function (nd) {
        out.nodes++;
        if (nd.active !== true) return;
        if (partyRe.test(nd.name)) out.party = true;
        if (re.test(nd.name)) want.push(nd);
        // 不 return false：子树要整棵走完（节点少，顺便把编组页零件一起看出来）
      }, false, tr);
      if (want.length) {
        const env = _camEnv();
        if (env) {
          for (const nd of want) {
            const p = _ndXY(nd, env);
            if (p && p.onscreen) { out.start = { name: nd.name, x: p.x, y: p.y }; break; }
          }
        }
      }
      // ★对话框子树里没有（实测：挑战按钮常挂在 **View 层**，不在 DialogQuestDetail 子树，
      //   2026-10-20 16480 日志：子树 133 节点找不到 ⇒ 次次掉进 1s 级全树 find 兜底）
      //   ⇒ 走 `labBtn` 同款**路径缓存**：第一次学一次（~1.5s），之后沿路径直达几十 ms；
      //   学到的 root/path 回给 Kotlin 落盘 ⇒ **跨局回喂，连学习都省了**（2026-10-20 用户：
      //   "学了运行结束就没了没价值" —— 持久化之后才有价值）。
      if (!out.start) {
        const nm = String(startBtnRe || "UIButton_Unit");
        const b = _labBtnCore("QDStart|" + nm, nm, rootKind, pathArr, false);
        if (b && b.ok) {
          // ★hit 透传（2026-10-20 用户："每个学过的按钮都有日志吗"）："学习"/"路径" 让 Kotlin 能打日志
          out.start = { name: nm, x: b.x, y: b.y, hit: b.hit || "" };
          if (b.hit === "学习") { out.learnedRoot = b.root; out.learnedPath = b.path; }
        } else {
          // ★诊断（2026-10-20）：canvas 根加上后这里**不该**再静默失败——失败原因透给 Kotlin
          //   （16480 11:04 局：8 场走 find 兜底、学成日志一条没有，失败原因完全看不见）
          out.qdDiag = b ? (b.err || "not-found") : "no-cam-env";
        }
      }
    } catch (e) { out.err = "" + e; }
    out._ms = Date.now() - t0;
    return out;
  }),
  // 全量 dump（慢，少用）：这里必须给所有节点算坐标
  tree: _rpcSafe(function () {
    const out = [];
    try {
      const env = _camEnv();
      if (!env) return out;
      for (const nd of _walkNodes()) {
        if (nd.active !== true) continue;
        const p = _ndXY(nd, env);
        if (p && p.onscreen) out.push({ name: nd.name, x: p.x, y: p.y, d: nd.d });
      }
    } catch (e) {}
    return out;
  }),
  // 259(ViewLabyrinthTop) 是否"挑战中"：true/false；不在该视图或取不到 → None
  labChallenging: _rpcSafe(function () {
    try {
      const vm = _viewManagerInst();
      if (vm.isNull()) return null;
      const cv = vm.add(0x110).readPointer();
      if (cv.isNull()) return null;
      let nm = null;
      try { nm = g_il2cpp.class_get_name(g_il2cpp.object_get_class(cv)).readUtf8String(); } catch (e) {}
      if (!nm || nm.indexOf("ViewLabyrinthTop") < 0) return null;   // 非该视图 → 未知
      const cb = cv.add(0xE8).readPointer();                        // challengeButton
      if (cb.isNull()) return null;
      const go = cb.add(0x18).readPointer();                        // ChallengingObject
      if (go.isNull()) return null;
      const a = _goActive(go);
      return (a === null) ? null : !!a;
    } catch (e) { return null; }
  }),
  // ★战斗设定读取（只读，2026-10-22）：Start 投递到游戏线程 → Poll 取结果。
  //   调用约定：先用 Start，隔 ~120ms 反复 Poll 直到 `pending=false`（Kotlin 侧已封装）。
  battleSettingsStart: _rpcSafe(function () {
    g_battleSetRes = null;
    if (!g_il2cpp) return { queued: false, err: "il2cpp 未就绪" };
    try {
      const queued = runOnGameThread(function () {
        let data;
        try { data = _battleSettingsReadNow(); } catch (e) { data = { ok: false, err: "" + e }; }
        g_battleSetRes = { at: Date.now(), data: data || { ok: false, err: "空结果" } };
      });
      return { queued: !!queued };
    } catch (e) { return { queued: false, err: "" + e }; }
  }),
  battleSettingsPoll: function () {
    if (!g_battleSetRes) return { pending: true };
    const d = g_battleSetRes.data || { ok: false, err: "空结果" };
    const out = {};
    for (const k in d) out[k] = d[k];
    out.pending = false;
    out.at = g_battleSetRes.at;
    return out;
  },
  // ★按"元素名"取按钮坐标（学一次 → 之后只校验指针，零遍历）—— 刷开局用。
  //   学习只走 **当前 View 子树 + DialogArea 子树**（不是整棵 UI 树，实测整树 ~600 节点/2s）；
  //   之后每次只做：读缓存 Transform → 校验 GameObject 名一致 → 现算屏幕坐标（约 5 次调用）。
  //   校验不过（节点被重建/换页）就作废重学一次，调用方无感。
  // ★`fresh`=true（2026-10-19）：连**进程内**缓存 `g_labBtn[key]` 也一起丢掉 ⇒ 真的重新学一次。
  //   之前 Kotlin 只不传路径，但本进程里上次学的 slot 还在 ⇒ 仍走"路径"命中，白叫一声"重新学"。
  labBtn: _rpcSafe(function (key, name, rootKind, pathArr, fresh) {
    return _labBtnCore(key, name, rootKind, pathArr, fresh);
  }),
  // ★轻量"页面签名"：{view, top}。给 Kotlin 侧的按钮坐标缓存做 key ——
  //   同一页 + 同一顶层对话框下按钮位置是稳定的，可以直接点，省掉 2s 级的全树 find。
  uiSig: _rpcSafe(function () {
    try {
      let top = null;
      try { top = _topDialogName(); } catch (e) {}
      return { view: getViewId(), top: (top && top !== "null") ? top : null };
    } catch (e) { return { view: -1, top: null, err: String(e) }; }
  }),
  // ★当前选中的难度数字（公会选择/确认页上那颗"难度N"图标）：
  //   数字是 **UISprite 图标**（不是文字）⇒ 只能读它的 sprite 名（如 labyrinth_icon_difficulty_num_5）。
  //   返回 {sprite:"…", num:5}；读不到时 num=null。
  //   ⚠用途：难度已经等于配置时**不要再去点「难度变更」**（2026-10-19 用户）。
  labDiffNum: _rpcSafe(function () {
    try {
      const sp = findClass("UISprite");
      if (!sp || sp.isNull()) return { sprite: null, num: null, err: "no UISprite" };
      const cands = [];
      for (const nd of _walkNodes()) {
        if (nd.active !== true) continue;
        if (!/difficult/i.test(nd.name)) continue;
        let go = null, comp = null;
        try { go = nd.tr ? _inv(nd.tr, "get_gameObject") : null; } catch (e) {}
        if (!ptrOk(go)) continue;
        try { comp = _compSelf(go, sp); } catch (e) {}
        if (!comp || comp.isNull()) { try { comp = _compInChildren(go, sp); } catch (e) {} }
        let sn = null;
        if (comp && !comp.isNull()) { try { sn = _str1(_inv(comp, "get_spriteName")); } catch (e) {} }
        cands.push({ name: nd.name, sn: sn });
      }
      const hit = cands.find(function (x) { return /^labyrinth_icon_difficulty_num$/i.test(x.name) && x.sn; })
        || cands.find(function (x) { return /difficulty_num/i.test(x.name) && x.sn; });
      const m = hit ? String(hit.sn).match(/(\d+)\s*$/) : null;
      return { cands: cands, sprite: hit ? hit.sn : null, num: m ? parseInt(m[1], 10) : null };
    } catch (e) { return { sprite: null, num: null, err: String(e) }; }
  }),
  // 迷宫内完整状态快照（同 labStatus()，但返回对象给 Python）。
  // 额外带 pageTop = 当前最上层对话框名，供 Python 判"现在到底是哪一页"
  // （statusType 在换页时可能不变、view 也可能不变，只有这个能准确区分）。
  // ★轻量「当前窗口」（2026-11-06 状态机）：只读 DialogArea 顶层的对话框名并归一化，
  //   不构造快照、不扫树。给 60ms 级轮询用（labStatus 要 150~300ms）。
  labTop: _rpcSafe(function () { try { return labTopSnapshot(); } catch (e) { return null; } }),
  // ★轻量「窗口 + 队伍索引」（Boss 三队：切队不换窗口，需要 idx 才判得出）
  labPartyIdx: _rpcSafe(function () { try { return labPartyIdxSnapshot(); } catch (e) { return null; } }),
  // ★极简「当前 View + 是否在战斗」（比赛了 labStatus 快一个数量级）
  labView: _rpcSafe(function () { try { return labViewQuickSnapshot(); } catch (e) { return null; } }),
  // ★轻量「战斗结果原始值」（inBattle / resultEnable / gameState，用于标定胜负判据）
  labBattleResult: _rpcSafe(function () { try { return labBattleResultSnapshot(); } catch (e) { return null; } }),
  // ★轻量「编队页图标清单」（只读长度 + unitId，替代进场/滚动后的固定 1.5s 等待）
  //   ids = 队伍那一排（plate+0xC8）；listIds = 角色一览当前窗口（scrollController+0x70）
  labPartyIcons: _rpcSafe(function () { try { return labPartyIconsSnapshot(); } catch (e) { return null; } }),
  // ★轻量「单个角色图标坐标」（只给命中 uid 的那一个做投影，~20ms）
  //   via="槽位" → 撤人（点队伍那一排）；via="一览"/空 → 加人（点角色一览）
  labPartyIconPos: _rpcSafe(function (uid, via) { try { return labPartyIconPosSnapshot(uid, via); } catch (e) { return null; } }),
  // ★轻量「编队决策包」（ids + members + idx，不读坐标/名字，~25ms）
  labPartyPlan: _rpcSafe(function () { try { return labPartyPlanSnapshot(); } catch (e) { return null; } }),
  // ★★轻量「编队决策**精简读**」（2026-11-07 用户："只取需要的内容就好了"）：
  //   只取决策真正要的字段（队伍/槽位 id/一览 id+vis/按钮坐标…），**不读**每个 icon 的中文 label、
  //   **不返回**页签候选与诊断字段 ⇒ 每轮省掉一大坨序列化+传输+解析。
  //   字段名与 `labParty` 一致 ⇒ Kotlin 侧不用改。
  labPartyLite: _rpcSafe(function () { try { return labPartyLiteSnapshot(); } catch (e) { return null; } }),
  // ★编组页顶部页签（切队伍腾空用；以前每轮都从 labParty 里带 40 个候选回来）
  labPartyTabs: _rpcSafe(function () { try { return labPartyTabsSnapshot(); } catch (e) { return null; } }),
  // ★★**极简分派读**（2026-11-08）：只读分派要的字段（全是内存读）⇒ ~10ms；
  //   全量 labStatus 是 150~300ms（里面有一串同步调游戏方法的 decOI）。 
  labStatusLite: _rpcSafe(function () { try { return labStatusLite(); } catch (e) { return null; } }),
  // ★★**按需读：非分派字段**（2026-11-08）—— ids（event/quest/result/effect/choice）+ LTD 候选清单。
  //   只有个别 handler 用得到 ⇒ 不塞进每轮都跑的分派读（见 [labStatusLite]）。
  labExtra: _rpcSafe(function () { try { return labExtra(); } catch (e) { return null; } }),
  labStatus: _rpcSafe(function () {
    try {
      const t0 = Date.now();
      const st = labSnapshot();
      const t1 = Date.now();
      if (st) {
        st.pageTop = _topDialogName();
        const t2 = Date.now();
        const bs = battleStateSnapshot();     // 纯内存读，几乎零成本
        const t3 = Date.now();
        st.inBattle = bs.inBattle;
        st.battleView = bs.viewClass;
        st.battleState = bs.gameStateName;
        st.battleResultEnable = bs.resultEnable;
        st.battlePlausible = bs.plausible;
        // 细粒度耗时（ms）+ DialogArea 重扫累计次数，供 Python 打日志定位
        st._ms = { snap: t1 - t0, top: t2 - t1, battle: t3 - t2,
                   dlgBfs: g_dlgBfs, vmScans: g_vmScans };
      }
      return st;
    } catch (e) { return null; }
  }),
  // 地图上所有节点面板：blockId / panelState / 屏幕坐标
  labNodes: _rpcSafe(function () {
    // ★1.5s TTL（见 [NODES_TTL_MS] 注释）；空结果不缓存
    const _n = Date.now();
    if (g_nodesCache && (_n - g_nodesAt) < NODES_TTL_MS) return g_nodesCache;
    try {
      const r = labNodesSnapshot();
      if (r && r.length) { g_nodesCache = r; g_nodesAt = Date.now(); }
      return r;
    } catch (e) { return null; }
  }),
  // 当前通用对话框按钮（获得道具/提示/移动确认）：{open, buttons:[{name,x,y}], darkFilters}
  // ★可选参数 `topCls`（2026-10-17）= 调用方看到的 `pageTop`，供"学到的索引路径"额外比对顶框类名。
  //   `learned=true` 表示这次是**走学习路径**（零遍历）得出的结果。
  dialog: _rpcSafe(function (topCls, knownChains) {
    try { return dialogButtonsSnapshot(topCls || null, knownChains || null); } catch (e) { return null; }
  }),
  // ★★通用弹窗「按钮轻读」（2026-11-09）：字段直取，**零遍历**。
  //   返回 {ok, top, buttons:[{name,x,y,id}], ms, src, err}；topCls 对不上 / 按钮一颗都没激活 ⇒ ok=false
  //   ⇒ 调用方退回 `dialog()` 兜底。与 `labExBtn` 同款按需读（30ms 级 vs dialog 冷遍历 0.5~0.6s）。
  labDlgBtn: _rpcSafe(function (topCls) {
    try { return labDlgBtnSnapshot(topCls || null); }
    catch (e) { return { ok: false, top: "", buttons: [], ms: 0, err: String(e), src: "field" }; }
  }),
  // ★★「关闭已启动」轻读（2026-11-09 定案）：退出 tween（mStarted/factor）或整页已消失。
  //   给 tapAndCheck 阶段 1 用 —— 点一下后 +200~300ms 就能确认"生效了"，不必等窗口 800ms 消失。
  labDlgClosing: _rpcSafe(function () {
    try { return labDlgClosingSnapshot(); }
    catch (e) { return { top: "", path: "err", closing: false, ms: 0, err: String(e) }; }
  }),
  // ★三选一页的阶段 1 探针：顶框名 + 退出 tween 判据 + 顺带诊断（零 hook，8~18ms）。
  labSelState: _rpcSafe(function () {
    try { return labSelStateSnapshot(); }
    catch (e) { return { path: "err", closing: false, ms: 0, err: String(e) }; }
  }),
  // ★战斗结算页"对象字段"抓取（2026-10-16）：为"从字段读按钮、彻底不扫树"备料，见 battleResultDump
  battleResultDump: _rpcSafe(function () {
    try { return battleResultDump(); } catch (e) { return { ok: false, err: String(e) }; }
  }),
  // ★★战斗结算按钮的**字段读**（2026-10-16 定案）：
  //   实测证据（失败页 dump）：`DialogQuestFailed+0x118 = CustomUIButton(1619,989)` = 「重新挑战」✓
  //   （与树遍历给的坐标**完全一致** ✓）；`+0x120 = CustomUILabel` 是配对标签 ✓。
  //   胜利页的按钮不在对话框自身字段里 ⇒ 再**下探一层 `Parts*` 零件**（`DialogQuestResultWin+0x100=
  //   PartsQuestResultFormer`、`+0x128=PartsQuestResultLater` ✓）。
  //   返回 {ok, cands:[{x,y,label,src}]}：每个候选都带**配对标签文字**（判胜负 ✓）与坐标（判点哪 ✓）。
  //   读不出 ⇒ ok=false ⇒ 调用方回退树遍历（**只慢、不点错** ✓）。
  battleResultMem: _rpcSafe(function () { try { return battleResultMem(); } catch (e) { return { ok: false, err: String(e) }; } }),
  // ★结算按钮"学一次、之后只校验"（见 battleResultBtn 注释）：`{ok,hit,name,x,y,label}`
  battleResultBtn: _rpcSafe(function (minY) {
    try { return battleResultBtn(minY == null ? null : minY); } catch (e) { return { ok: false, err: String(e) }; }
  }),
  // ★★"只认页"的便宜探测（2026-10-23，两层化的第 1 层，见 [_resPageKindLite]）：
  //   `{ok, kind}`，kind ∈ "win" | "fail" | "ex" | null。战斗中每 ~300ms 问一次，几毫秒级；
  //   **答 null 就什么都不做**（不找按钮、不扫树）—— 这是把"认页"和"找按钮"拆开的关键。
  resPageKind: _rpcSafe(function () {
    try { return { ok: true, kind: _resPageKindLite() }; } catch (e) { return { ok: false, err: String(e) }; }
  }),
  // ★通用"读某个指针对象的字段槽"（2026-10-16，门票页取证用）：传指针串 ⇒ {cls, slots:[...]}
  objSlots: _rpcSafe(function (ptrStr, from, to) {
    try {
      const p = ptr(String(ptrStr));
      if (!ptrOk(p)) return { err: "指针不可用" };
      return { cls: _safeKlassOf(p), slots: _slotDump(p, from || 0x10, to || 0x1c0) };
    } catch (e) { return { err: String(e) }; }
  }),
  // 游戏 UI 相机的像素尺寸 —— 这才是我们坐标（WorldToScreenPoint）所在坐标系，比 adb wm size 权威
  screen: _rpcSafe(function () {
    try {
      const cam = _uiCamera();
      if (cam.isNull()) return null;
      let w = 0, h = 0;
      try { const pw = _inv(cam, "get_pixelWidth"); if (!pw.isNull()) w = pw.add(0x10).readS32(); } catch (e) {}
      try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) h = ph.add(0x10).readS32(); } catch (e) {}
      return (w > 0 && h > 0) ? { w: w, h: h } : null;
    } catch (e) { return null; }
  }),
  // EVENT_CHOICE(4)：DialogLabyrinthEvent 的选项卡（eventId + 每张卡的文字与按钮坐标）
  labEvent: _rpcSafe(function () { try { return labEventSnapshot(); } catch (e) { return null; } }),
  // 战斗胜利后的「选取奖励角色」页：3 张卡的 unitId / unitType / 决定按钮坐标
  //   force=true：绕过 1s 读缓存（卡片滑动动画期间重读用）
  labSelection: _rpcSafe(function (force) { try { return labSelectionSnapshot(false, !!force); } catch (e) { return null; } }),
  // ★选取页读取的**自证**（2026-10-16）：cards 为空时调用，回答"卡在哪一步"——
  //   `diag.scanned`(扫了几个实例) / `diag.picked`(选中哪个实例、几张卡) / `diag.probe[]`(每个实例的
  //   view/list/n/**units=有 unitInfo 的卡数**) / `diag.err`。用来区分：找不到实例 / 列表读不到 /
  //   `+0xB8 unitInfo` 为空（偏移变了）/ UnitId(ObscuredInt) 解不出来。
  labSelectionDiag: _rpcSafe(function (force) {
    try {
      const dg = {};
      const r = labSelectionSnapshot(dg, !!force);
      return { cards: (r.cards || []).length, ticketId: r.ticketId, diag: dg };
    } catch (e) { return { err: "" + e }; }
  }),
  // 整张地图（算路线用）：{curArea, curBlockId, panels:[{area,column,row,blockId,blockType,isVisited,questId,next[]}]}
  labMap: _rpcSafe(function () { try { return labMapSnapshot(); } catch (e) { return null; } }),
  // 遗物/道具选择页：**带缓存**返回（该页候选卡在同一个对话框存活期间不变，而读取含全堆扫描，很贵）。
  // 钥匙 = "最上层对话框名"：换页/换对话框立刻失效；再叠 2s TTL 兜底。无对话框时不缓存。
  labChoices: _rpcSafe(function (force) {
    try {
      const pt = _topDialogName() || "";
      const now = Date.now();
      // force=true：绕过 2s 读缓存 + 1.2s 失败退避（卡片"落位"/滑动动画期间重读用）
      if (!force && pt && g_choiceCache && g_choiceCache.pt === pt && (now - g_choiceCache.at) < CHOICE_TTL_MS)
        return g_choiceCache.val;
      const val = labChoicesSnapshot(!!force);
      // ★只缓存"有内容"的结果：对话框刚出现那一瞬可能读到空（UI 还没铺好），
      //   若把空结果缓存 2s，就会连续多轮都报"读不到候选卡"（实测踩过）。
      if (pt && val && (val.cards || []).length) g_choiceCache = { pt: pt, at: now, val: val };
      return val;
    } catch (e) { return null; }
  }),
  // 商店页：{coin, rerollCost, pages, items[{rewardId,rewardCount,price,isSold,availability}], slots[]}
  labShop: _rpcSafe(function () { try { return labShopSnapshot(); } catch (e) { return null; } }),
  // 商店读不到商品时的现场（候选实例字段 + 运行时字段表）
  labShopDiag: _rpcSafe(function () { try { return labShopDiag(); } catch (e) { return null; } }),
  // 迷宫结算页（打完最终 Boss 的 RESULT ①②屏）：主按钮坐标（从对象现算）+ 结算数值
  labResult: _rpcSafe(function () { try { return labResultSnapshot(); } catch (e) { return null; } }),
  // ★结算奖励（LabyrinthTempData 奖励列表，见 labRewardsSnapshot）
  labRewards: _rpcSafe(function () { try { return labRewardsSnapshot(); } catch (e) { return null; } }),
  // 本局已持有遗物 + 公会：{guildId, relics:[{relicId,count}], err}
  labOwnRelics: _rpcSafe(function () { try { return labOwnRelicsSnapshot(); } catch (e) { return null; } }),
  // 本局已持有角色（角色池）：{unitIds:[...], units:[{unitId,unitType}], err} —— NPC 形态也含在内
  labUnits: _rpcSafe(function () { try { return labUnitsSnapshot(); } catch (e) { return null; } }),
  // 编队页（DialogCommonPartyEdit）**只读**：已编成角色（左→右）/ 按钮坐标 / 固定编入 / 本关限定
  // ★第二个参数 `noXY`：跳过"每个 icon 的坐标投影"（那是 labParty 893ms 的大头）。
  //   需要点某个角色时，再用 `labPartyIconPos(uid)` 单点取坐标（~20ms）。
  labParty: _rpcSafe(function (diag, noXY) { try { return labPartySnapshot(diag ? true : null, noXY ? true : null); } catch (e) { return null; } }),
  // 只做"定位"（不读字段）：验证 DialogArea 子树能不能拿到编组页真实例
  labPartyFind: _rpcSafe(function () { try { return labPartyFind(); } catch (e) { return { ok: false, err: String(e) }; } }),
  // ★让编组页的读取缓存立刻失效（点完一下必须读到最新编队 —— 移除会让人右移，
  //   拿 1s TTL 内的旧读数去决定下一步会点错方向）
  labPartyFlush: _rpcSafe(function () { try { g_partyCache = null; g_partyAt = 0; return true; } catch (e) { return false; } }),
  // ★**丢缓存重解析**（2026-10-16，用户问"重启是不是理论上能解决"）：把**所有读取缓存**清掉，
  //   让下一次读取从"活着的视图"重新解析。用途 = 页面读不到时的**中间档**（比重启游戏便宜 30~40s）。
  //   清的东西：实例缓存 `g_instCache`（下标 `labSel/labEvent/labResult…`）+ 各页 TTL 结果缓存
  //   （sel/party/choice）+ 编组页对话框实例 + LTD 指针 `g_lab`（下一次由 `ViewLabyrinthMap+0xF8` 重取）。
  //   ★能治好哪两类：① 缓存里那个实例已经被复用/销毁（`_selPickValid` 这类浅校验仍可能通过）；
  //     ② `g_lab` 指着上一局/旧实例。治不好：页面**自己没建好**（字段就是空）⇒ 那才需要重启重建。
  labResetCaches: _rpcSafe(function () {
    try {
      const n = Object.keys(g_instCache).length;
      for (const k in g_instCache) delete g_instCache[k];
      g_selCache = null; g_selAt = 0;
      g_partyCache = null; g_partyAt = 0;
      g_partyDlg = null; g_partyDlgAt = 0;
      g_partyTabsCache = null;
      g_choiceCache = null; g_choiceFailAt = 0;
      g_lab = null;
      return { ok: true, cleared: n };
    } catch (e) { return { ok: false, err: String(e) }; }
  }),
  // 特别装备三连页（只读快照）：① 特别装备设定 ② 自动特别装备设定 ③ 优先属性值
  //   full=true 时连"该层对话框子树内的按钮坐标"一起算（要点的时候才需要，平时省掉一次子树遍历）
  labExEquip: _rpcSafe(function (full, only) { try { return labExEquipSnapshot(false, !!full, only || null); } catch (e) { return null; } }),
  labExEquipDiag: _rpcSafe(function () { try { return labExEquipSnapshot(true, true); } catch (e) { return null; } }),
  // 最上层是不是这三页之一（给 Python 判断"这页由我们自己驱动、不要自动点通用弹窗"）
  labExEquipTop: _rpcSafe(function () { try { return labExEquipTop(); } catch (e) { return { top: null, err: String(e) }; } }),
  // ★★**极轻**：只读三连页的**签名**（一个字符串）—— 状态机轮询专用
  //   不算坐标、不读按钮、不回传整包 JSON（只要 labels[0] / checked / 选中的那一项）
  labExEquipSig: _rpcSafe(function () { try { return labExEquipSig(); } catch (e) { return { sig: null, err: String(e) }; } }),
  // ★★**按需读按钮**（2026-11-08）：三连页"这一步只点一个按钮" ⇒ 只读那一个 + 当前签名。
  //   返回 {sig, top, btn:{name,x,y,id}} —— **不读**整包（labels/terms/toggles/按钮全家桶）。
  labExBtn: _rpcSafe(function (key, preferId) { try { return labExBtn(key || "", preferId || ""); } catch (e) { return { sig: null, top: null, btn: null, err: String(e) }; } }),
  // 诊断版：带"每个对象槽挂的是什么类"的现场 dump（★不再把内部 diag 覆盖成空对象）
  labPartyDiag: _rpcSafe(function () { try { return labPartySnapshot(true); } catch (e) { return null; } }),
  // 选角色页旧读法（PartsDialogLabyrinthSelectableChara + 堆扫描）已在 2026-10-11 退役：
  // 那个类不是本页零件（堆扫描出来的都是"别的对象存着这个类指针的字段槽"），改走 labCharaPageQuick。
  // 游戏主循环心跳（看门狗）：{frame, time} —— 判断"卡住"是游戏冻结还是我们点错
  gameTick: _rpcSafe(function () { try { return gameTickSnapshot(); } catch (e) { return null; } }),
  // 卡死现场：所有线程的调用栈（返回字符串数组）
  stuckDump: _rpcSafe(function () { try { return globalThis.stuckDump(); } catch (e) { return ["stuckDump 失败: " + e]; } }),
  // 遗物页读不到卡时的现场诊断（失败时由 Python 自动调用并打印）
  labChoicesDiag: _rpcSafe(function () { try { return labChoicesDiagSnapshot(); } catch (e) { return null; } }),
  // 选取页读取失败的现场诊断（通道/偏移/卡片列表长度/unitInfo 命中数）
  labSelectionDiag: _rpcSafe(function () {
    const diag = {};
    let cards = [];
    try { const r = labSelectionSnapshot(diag); cards = (r && r.cards) || []; } catch (e) { diag.err = "" + e; }
    return { diag: diag, cardCount: cards.length };
  })
};

// ── "最近一次重操作"标记（卡死现场判读用）──
// 卡住时最想知道的是："我们的线程当时在干什么"。所有重操作（堆扫描 / 全树遍历）都会打标记，
// stuckDump 会把它打在最前面 —— 若现场显示"最近重操作=scanKlass(...)"且我们的线程栈停在扫描里，
// 那就是我们拖住了 GC；若我们线程空闲，则游戏侧的等待与我们无关。
let g_op = { name: "(无)", at: 0 };
function _opBegin(name) { g_op.name = name; g_op.at = 0; }
function _opEnd() { g_op.name = "(无)"; g_op.at = Date.now(); }

// ── 游戏主循环"心跳"（看门狗用）──
// 读 UnityEngine.Time.frameCount / realtimeSinceStartup。
// ★这是区分"谁卡了"的决定性信号：
//   帧计数**不涨** ⇒ 游戏主循环被冻结（frida/adb/GC/游戏自身 bug 都可能）
//   帧计数**在涨** ⇒ 游戏活着 ⇒ 页面"元素不变"是我们点错地方/页面逻辑问题，与卡死无关
// ★UnityEngine.Time 缓存的 Il2CppClass。
//   为什么要缓存：Time 在 UnityEngine.CoreModule.dll 里，而 findClass 的"快路"只查
//   Assembly-CSharp.dll → 每次都会掉到"全程序集逐类扫名字"的慢路（几万次 class_get_name）。
//   gameTick 是**每轮都调**的（看门狗），不缓存就是每轮白烧一遍。类指针在进程内是稳定的。
let g_timeKlass = null;
function _timeKlass() {
  try {
    if (g_timeKlass && !g_timeKlass.isNull()) {
      const nm = g_il2cpp.class_get_name(g_timeKlass);
      if (!nm.isNull() && nm.readUtf8String() === "Time") return g_timeKlass;
    }
    g_timeKlass = findClass("Time");
    return (g_timeKlass && !g_timeKlass.isNull()) ? g_timeKlass : null;
  } catch (e) { return null; }
}

function gameTickSnapshot() {
  const out = { frame: null, time: null, exists: false, err: null };
  try {
    const k = _timeKlass();
    if (!k || k.isNull()) { out.err = "未找到 Time 类（UnityEngine.Time 取不到）"; return out; }
    out.exists = true;
    if (g_il2cpp.thread_attach) { try { g_il2cpp.thread_attach(g_il2cpp.domain_get()); } catch (e) {} }
    // ★取不到时要能说出**为什么**：方法没找到 / 抛了异常 / 返回空 —— 三种都写进 err，别再静默给 null
    const mf = _mInfo(k, "get_frameCount");
    if (!mf) out.err = "Time 上没有 get_frameCount";
    else {
      const ex = Memory.alloc(8); ex.writePointer(ptr(0));
      const r = g_il2cpp.runtime_invoke(mf, ptr(0), ptr(0), ex);
      const ep = ex.readPointer();
      if (!ep.isNull()) out.err = "get_frameCount 抛异常（返回地址 " + r + "）";
      else if (r.isNull()) out.err = "get_frameCount 返回空";
      else out.frame = r.add(0x10).readS32();
    }
    const mt = _mInfo(k, "get_realtimeSinceStartup");
    if (mt) {
      const ex2 = Memory.alloc(8); ex2.writePointer(ptr(0));
      const r2 = g_il2cpp.runtime_invoke(mt, ptr(0), ptr(0), ex2);
      if (!r2.isNull() && ex2.readPointer().isNull()) out.time = r2.add(0x10).readFloat();
    }
  } catch (e) { out.err = "" + e; }
  return out;
}

// 抓"卡死现场"：所有线程 + 各自调用栈（返回字符串数组，同时打到 console）
function stuckDumpImpl() {
  const lines = [];
  try {
    lines.push("最近重操作: " + g_op.name
      + (g_op.at ? ("（结束于 " + ((Date.now() - g_op.at) / 1000).toFixed(2) + "s 前）") : ""));
    const ts = Process.enumerateThreads();
    lines.push("线程数=" + ts.length);
    const symCache = new Map();
    const symOf = function (a) {
      const k = String(a);
      if (symCache.has(k)) return symCache.get(k);
      let s = k;
      try { const d = DebugSymbol.fromAddress(a); if (d && d.name) s = d.name; } catch (e) {}
      symCache.set(k, s);
      return s;
    };
    const groups = new Map();
    for (const t of ts) {
      let syms = [];
      try {
        const bt = Thread.backtrace(t.context, Backtracer.ACCURATE).slice(0, 10);
        syms = bt.map(symOf);
      } catch (e) { syms = ["(取栈失败: " + e + ")"]; }
      const nm = String(t.name || "");
      const key = nm + "|" + syms.join("\n");
      const g = groups.get(key);
      if (g) { g.n++; g.tids.push(t.id); continue; }
      groups.set(key, {
        n: 1, tids: [t.id], name: nm, syms: syms,
        mine: /frida|gum|gmain|js|agent/i.test(nm),
        core: /UnityMain|GC |Finalizer|libili|priconne|Unity/i.test(nm),
        hit: syms.some(function (s) { return /libil2cpp|libunity|libtersafe/.test(s); }),
      });
    }
    const arr = Array.from(groups.values()).sort(function (a, b) {
      return (b.mine - a.mine) || (b.core - a.core) || (b.hit - a.hit) || (b.n - a.n);
    });
    let skipped = 0;
    for (const g of arr) {
      if (!(g.mine || g.core || g.hit)) { skipped += g.n; continue; }   // 无关线程只计数
      lines.push("── tid=" + g.tids.join(",") + " ×" + g.n + "  name=" + (g.name || "(无名)")
        + (g.mine ? "   ★我们的线程" : ""));
      for (const s of g.syms) lines.push("     " + s);
    }
    lines.push("（另有 " + skipped + " 个完全无关的线程栈已省略：looper/audio/binder 等）");
  } catch (e) { lines.push("stuckDump 失败: " + e); }
  return lines;
}

// 抓"卡死现场"：**按栈分组去重 + 优先打印"我们自己的线程/主线程/命中 il2cpp 的线程"**
// （旧版按 tid 顺序打全量 1409 行，把真正相关的挤到截断之外了）
globalThis.stuckDump = function () {
  const lines = stuckDumpImpl();
  for (const l of lines) console.log("[stuck] " + l);
  return lines;
};

// ── 卡死现场诊断 ──
// 若游戏再次卡死，在 REPL 里跑 stuck()：会列出全部线程 + 各自调用栈。
// 判读：游戏主线程停在 GC_stop_world / pthread_cond_wait / futex 之类 ⇒ 就是我们线程
// 未进 ART 线程表、GC 等不到安全点（本修复要治的病）；停在我们自己的 JS 调用里 ⇒ 另有原因。
globalThis.stuck = function () {
  const ts = Process.enumerateThreads();
  console.log("[stuck] 线程数=" + ts.length);
  for (const t of ts) {
    console.log("[stuck] ── tid=" + t.id + " name=" + t.name + " state=" + t.state);
    try {
      const bt = Thread.backtrace(t.context, Backtracer.ACCURATE).slice(0, 10);
      for (const a of bt) console.log("[stuck]      " + a + "  " + DebugSymbol.fromAddress(a));
    } catch (e) { console.log("[stuck]      取栈失败: " + e); }
  }
  return "若主线程停在 GC/futex/cond_wait，即为跨线程 GC 死等";
};

// ===== 遍历当前界面(currentView)或指定对象，列出节点名+屏幕坐标（找按钮用）=====
function _dumpNodes(rootObj, tag) {
  const rtu = findClass("RectTransformUtility");
  const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
  const cam = _uiCamera();
  if (cam.isNull() || !mW2S) { console.log(tag + " 无相机/换算方法"); return; }
  let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
  const root = _inv(rootObj, "get_transform");
  if (root.isNull()) { console.log(tag + " 无 transform"); return; }
  const queue = [root]; let n = 0;
  while (queue.length && n < 800) {
    const tr = queue.shift(); n++;
    let cc = 0; try { const r = _inv(tr, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
    for (let i = 0; i < cc; i++) { const ch = _inv1i(tr, "GetChild", i); if (!ch.isNull()) queue.push(ch); }
    const gobj = _inv(tr, "get_gameObject");
    if (gobj.isNull()) continue;
    const nm = _str(_inv(gobj, "get_name"));
    const ss = _screenOf(tr, cam, mW2S);
    console.log(tag + "   " + nm + (ss ? "  (" + Math.round(ss.x) + "," + Math.round(ss.y) + ") [y=" + Math.round(H - ss.y) + "]" : ""));
  }
  console.log(tag + "   节点数=" + n);
}

globalThis.viewInfo = function () {
  if (!g_il2cpp) { console.log("[view] il2cpp 未就绪"); return; }
  const vm = _viewManagerInst();
  if (vm.isNull()) { console.log("[view] 未取到 ViewManager"); return; }
  const cv = vm.add(0x110).readPointer();   // currentView (ViewBase)
  if (cv.isNull()) { console.log("[view] currentView 为空"); return; }
  console.log("[view] currentView=" + cv + " 类=" + klassNameOf(cv) + "  ViewId=" + getViewId());
  runOnGameThread(function () { try { _dumpNodes(cv, "[view]"); } catch (e) { console.log("[view] 错误: " + e); } });
};

// 在"顶层 UI 根"下按名字子串查找节点，只打印命中项 + 坐标（比 rootInfo 简洁，找按钮用）
globalThis.findNode = function (namePart) {
  if (!g_il2cpp) { console.log("[find] il2cpp 未就绪"); return; }
  const vm = _viewManagerInst();
  if (vm.isNull()) { console.log("[find] 未取到 ViewManager"); return; }
  const cv = vm.add(0x110).readPointer();
  if (cv.isNull()) { console.log("[find] currentView 为空"); return; }
  const pat = String(namePart || "").toLowerCase();
  runOnGameThread(function () {
    try {
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      const cam = _uiCamera();
      if (cam.isNull() || !mW2S) { console.log("[find] 无相机/换算方法"); return; }
      let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
      let tr = _inv(cv, "get_transform");
      let p = _inv(tr, "get_parent"); let up = 0;
      while (!p.isNull() && up < 8) { tr = p; p = _inv(tr, "get_parent"); up++; }
      const q = [tr]; let n = 0, hits = 0;
      while (q.length && n < 8000) {
        const t = q.shift(); n++;
        let cc = 0; try { const r = _inv(t, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
        for (let i = 0; i < cc; i++) { const ch = _inv1i(t, "GetChild", i); if (!ch.isNull()) q.push(ch); }
        const gobj = _inv(t, "get_gameObject");
        if (gobj.isNull()) continue;
        const nm = _str(_inv(gobj, "get_name"));
        if (nm.toLowerCase().indexOf(pat) >= 0) {
          const ss = _screenOf(t, cam, mW2S);
          console.log("[find] " + nm + (ss ? "  (" + Math.round(ss.x) + "," + Math.round(ss.y) + ") [androidY=" + Math.round(H - ss.y) + "]" : ""));
          hits++;
        }
      }
      console.log("[find] 命中 " + hits + " / 遍历 " + n);
    } catch (e) { console.log("[find] 错误: " + e); }
  });
};

// 全量列出"顶层 UI 根"下的所有节点名+坐标（找按钮用）。findAll() 全列；findAll("btn") 按名子串过滤。
// 名字含按钮关键词的标 ★，便于快速定位弹窗的关闭/确认按钮。
// 查"黎明界顶（259）是否挑战中"：读 ViewLabyrinthTop.challengeButton(+0xE8) 的 ChallengingObject(+0x18)
// 是否激活。字段布局来自 dump.cs：LabyrinthChallengeButton{ Button@0x10, ChallengingObject@0x18, ... }
globalThis.labState = function () {
  if (!g_il2cpp) { console.log("[lab] il2cpp 未就绪"); return; }
  const vm = _viewManagerInst();
  if (vm.isNull()) { console.log("[lab] 未取到 ViewManager"); return; }
  const cv = vm.add(0x110).readPointer();
  if (cv.isNull()) { console.log("[lab] currentView 为空"); return; }
  let nm = "?"; try { nm = klassNameOf(cv); } catch (e) {}
  console.log("[lab] currentView 类=" + nm + "  ViewId=" + getViewId() + "（259=LABYRINTH_TOP）");
  if (nm.indexOf("ViewLabyrinthTop") < 0) { console.log("[lab] 当前不是 ViewLabyrinthTop（“挑战中”标记只在该视图上），跳过。"); return; }
  const cb = cv.add(0xE8).readPointer();
  if (cb.isNull()) { console.log("[lab] challengeButton 为空（当前视图可能不是 ViewLabyrinthTop）"); return; }
  const btn = cb.add(0x10).readPointer();
  const chgGo = cb.add(0x18).readPointer();
  const lbl = cb.add(0x20).readPointer();
  runOnGameThread(function () {
    try {
      const act = chgGo.isNull() ? null : _goActive(chgGo);
      const bAct = btn.isNull() ? null : _goActive(btn);
      const txt = lbl.isNull() ? "null" : _str1(_inv(lbl, "get_text"));
      console.log("[lab] challengeButton=" + cb);
      console.log("[lab]   Button=" + btn + " active=" + bAct);
      console.log("[lab]   ChallengingObject=" + chgGo + " active=" + act + "  ← true 即“挑战中”");
      console.log("[lab]   Label=" + lbl + " text=" + txt);
      console.log("[lab] 判定：" + (chgGo.isNull() ? "ChallengingObject 缺失，无法判断" : (act === true ? "★挑战中（应走 Resume/继续）" : "未挑战（应走 Enter/新开局）")));
    } catch (e) { console.log("[lab] 错误: " + e); }
  });
};

// ===== 迷宫内"节点状态"检测（LabyrinthStatus）=====
// 依据 dump.cs：
//   LabyrinthTempData(+0xE8) = LabyrinthStatus
//     Type(+0x10) = eLabyrinthStatusType（普通枚举，直读）
//     Choices(+0x80) = List<LabyrinthChoiceInfo>（页面上的候选，RewardId 为 ObscuredInt）
//     CurrentChoiceCount(+0x88) / RemainChoiceCount(+0x94)（ObscuredInt）
//   当前节点类型：CurrentPanel(+0xD0) -> mapInfo(+0x10) -> BlockType(+0x40)
const LAB_STATUS_TYPE = {
  0: "NONE", 1: "SUMMON_UNIT", 2: "SUMMON_TICKET_CHOICE", 3: "USE_RELIC", 4: "EVENT_CHOICE",
  5: "QUEST", 6: "BOSS", 7: "SHOP", 8: "EVENT_EFFECT", 9: "RELIC_CHOICE", [-1]: "INVALID",
};

// 定位当前存活的 LabyrinthTempData（复用找图缓存；stale 才重扫，避免每轮全堆扫描）
function _findLTD() {
  const k = findClass("LabyrinthTempData");
  const pk = findClass("LabyrinthPanelData");
  if (!k || k.isNull()) return null;
  const alive = (l) => {
    try {
      if (!ptrOk(l) || !objIsClass(l, k)) return false;
      const cp = l.add(0xD0).readPointer();
      return ptrOk(cp) && (!pk || pk.isNull() || objIsClass(cp, pk));
    } catch (e) { return false; }
  };
  if (typeof g_lab !== "undefined" && g_lab && alive(g_lab)) return g_lab;
  try { const arr = findMap(); if (arr && arr.length) { g_lab = arr[0].o; return g_lab; } } catch (e) {}
  return null;
}

// 采集一次迷宫内完整状态；失败返回 null
function labSnapshot() {
  const ltd = _findLTD();
  if (!ltd || ltd.isNull()) return null;
  const out = {
    area: ltd.add(0x40).readS32(), blockId: ltd.add(0x44).readS32(),
    enterId: ltd.add(0x58).readS32(), difficulty: ltd.add(0x5C).readS32(),
    guildId: ltd.add(0x1C).readS32(),       // ★当前局的初始公会（见 labStatusLite 里的说明）
    panelType: null, panelTypeName: null, next: [],
    statusType: null, statusTypeName: null,
    curChoice: null, remainChoice: null, choices: [],
  };
  // 当前 View（页面判据）：statusType 在页面切换时**可能不变**（实测：点完"挑战"后
  // statusType 仍是 QUEST(5)，界面却已换成选人页/战斗页），所以必须另带页面标识。
  try { out.viewId = getViewId(); } catch (e) { out.viewId = null; }
  try {
    const vm2 = _viewManagerInst();
    const cv2 = vm2.isNull() ? ptr(0) : vm2.add(0x110).readPointer();
    out.viewClass = cv2.isNull() ? null : klassNameOf(cv2);
  } catch (e) { out.viewClass = null; }
  const cp = ltd.add(0xD0).readPointer();
  if (ptrOk(cp)) {
    const mi = cp.add(0x10).readPointer();
    if (ptrOk(mi)) {
      out.panelType = mi.add(0x40).readS32();
      out.panelTypeName = BLOCK_TYPE[out.panelType] || ("?" + out.panelType);
      out.next = safeEnumListOI(mi.add(0x60).readPointer());
    }
  }
  const st = ltd.add(0xE8).readPointer();
  if (ptrOk(st)) {
    out.statusType = st.add(0x10).readS32();
    out.statusTypeName = LAB_STATUS_TYPE[out.statusType] || ("?" + out.statusType);
    // 各流程用的 id（LabyrinthStatus 其它字段）：event=+0x34 quest=+0x28 eventResult=+0x40 relic=+0x58 item=+0x14 effectNum=+0x4C
    out.eventId = decOI(st.add(0x34));
    out.questId = decOI(st.add(0x28));
    out.eventResultId = decOI(st.add(0x40));
    out.relicId = decOI(st.add(0x58));
    out.itemId = decOI(st.add(0x14));
    out.effectNum = decOI(st.add(0x4C));
    out.curChoice = decOI(st.add(0x88));
    out.remainChoice = decOI(st.add(0x94));
    const ch = st.add(0x80).readPointer();
    if (ptrOk(ch)) {
      let items = [];
      try { items = safeEnumListRef(ch); } catch (e) {}
      if (!items.length) {   // 兜底：走 get_Count/get_Item
        const n = _listCount(ch);
        if (n > 0 && n < 32) for (let i = 0; i < n; i++) { const it = _listItem(ch, i); if (ptrOk(it)) items.push(it); }
      }
      for (let i = 0; i < items.length; i++) {
        const it = items[i];
        if (!ptrOk(it)) continue;
        out.choices.push({
          i: i, ptr: String(it),
          choiceNum: decOI(it.add(0x10)), rewardType: decOI(it.add(0x1C)),
          rewardId: decOI(it.add(0x28)), rewardCount: decOI(it.add(0x34)),
        });
      }
    }
  }
  return out;
}

// ★★**极简分派读**（2026-11-08）：主循环每轮都要读状态来**分派**。
//   全量 `labSnapshot` 里除了分派字段，还带 choices / curChoice / questId / eventId …
//   —— 那些**只有个别 handler 用得到**（事件页要 eventId、编组页要 questId…），
//   而分派本身一个都用不上 ⇒ 按"需要什么读什么"的原则拆开：
//     · 分派读 = 本函数（纯内存读，实测 ~1ms）
//     · 按需读 = [labExtra]（ids/choices，handler 要时才调）
//   ★实测更正（2026-11-08）：`decOI` **不是**"同步调游戏方法"（它就是 hidden^key 的内存读），
//     所以全量快照本身只有 ~1ms；真正贵的是 `_topDialogName()`（6ms，逐个子节点调游戏方法）。
function labStatusLite() {
  const ltd = _findLTD();
  if (!ltd || ltd.isNull()) return null;
  const out = {
    area: ltd.add(0x40).readS32(), blockId: ltd.add(0x44).readS32(),
    enterId: ltd.add(0x58).readS32(), difficulty: ltd.add(0x5C).readS32(),
    // ★★当前这一局是从**哪个初始公会**开的（`LabyrinthTempData+0x1C`，2026-11-08 加）。
    //   用途：局内刷"已在迷宫里要不要沿用"必须核对公会 —— 否则手动开着 A 公会的局、
    //   配置是 B 公会时会直接沿用，跑完当成 B 公会的成绩（API 刷那条路一直有这个校验）。
    guildId: ltd.add(0x1C).readS32(),
    panelType: null, panelTypeName: null,
    statusType: null, statusTypeName: null,
    next: [],
  };
  try { out.viewId = getViewId(); } catch (e) { out.viewId = null; }
  try {
    const vm2 = _viewManagerInst();
    const cv2 = vm2.isNull() ? ptr(0) : vm2.add(0x110).readPointer();
    out.viewClass = cv2.isNull() ? null : klassNameOf(cv2);
  } catch (e) { out.viewClass = null; }
  const cp = ltd.add(0xD0).readPointer();
  if (ptrOk(cp)) {
    const mi = cp.add(0x10).readPointer();
    if (ptrOk(mi)) {
      out.panelType = mi.add(0x40).readS32();
      out.panelTypeName = BLOCK_TYPE[out.panelType] || ("?" + out.panelType);
      // ★"可前往"（移动分支用；一次内存读，零成本 ⇒ 留在分派读里，省一次往返）
      try { out.next = safeEnumListOI(mi.add(0x60).readPointer()); } catch (e) { out.next = []; }
    }
  }
  const st = ltd.add(0xE8).readPointer();
  if (ptrOk(st)) {
    out.statusType = st.add(0x10).readS32();
    out.statusTypeName = LAB_STATUS_TYPE[out.statusType] || ("?" + out.statusType);
  }
  out.pageTop = _topDialogName();
  const bs = battleStateSnapshot();          // 纯内存读，几乎零成本
  out.inBattle = bs.inBattle;
  out.battleView = bs.viewClass;
  out.battleState = bs.gameStateName;
  out.battleResultEnable = bs.resultEnable;
  return out;
}

// ★★**按需读：非分派字段**（2026-11-08）—— ids + 候选清单。
//   只有个别 handler 用得到：事件页要 eventId/eventResultId、编组页要 questId、
//   遗物三选一要 LTD 的 choices（诊断用）⇒ 不塞进每轮都跑的分派读里。
//   全是**内存读**（decOI / 列表内部数组），实测 ~0ms。
function labExtra() {
  const out = {
    eventId: null, questId: null, eventResultId: null, effectNum: null,
    curChoice: null, remainChoice: null, choices: [],
  };
  try {
    const ltd = _findLTD();
    if (!ltd || ltd.isNull()) return out;
    const st = ltd.add(0xE8).readPointer();
    if (!ptrOk(st)) return out;
    out.eventId = decOI(st.add(0x34));
    out.questId = decOI(st.add(0x28));
    out.eventResultId = decOI(st.add(0x40));
    out.effectNum = decOI(st.add(0x4C));
    out.curChoice = decOI(st.add(0x88));
    out.remainChoice = decOI(st.add(0x94));
    const ch = st.add(0x80).readPointer();
    if (ptrOk(ch)) {
      let items = [];
      try { items = safeEnumListRef(ch); } catch (e) {}
      if (!items.length) {   // 兜底：走 get_Count/get_Item
        const n = _listCount(ch);
        if (n > 0 && n < 32) for (let i = 0; i < n; i++) { const it = _listItem(ch, i); if (ptrOk(it)) items.push(it); }
      }
      for (let i = 0; i < items.length; i++) {
        const it = items[i];
        if (!ptrOk(it)) continue;
        out.choices.push({
          i: i, ptr: String(it),
          choiceNum: decOI(it.add(0x10)), rewardType: decOI(it.add(0x1C)),
          rewardId: decOI(it.add(0x28)), rewardCount: decOI(it.add(0x34)),
        });
      }
    }
  } catch (e) {}
  return out;
}

// 诊断打印
globalThis.labStatus = function () {
  if (!g_il2cpp) { console.log("[st] il2cpp 未就绪"); return null; }
  const s = labSnapshot();
  if (!s) { console.log("[st] 未定位 LabyrinthTempData（不在迷宫里？）"); return null; }
  console.log("[st] 区域=" + s.area + " 节点 BlockId=" + s.blockId + " 类型=" + s.panelTypeName + "(" + s.panelType + ")"
    + "  enterId=" + s.enterId + " 难度=" + s.difficulty);
  console.log("[st] 可前往=" + (s.next.length ? "[" + s.next.join(", ") + "]" : "(无)"));
  console.log("[st] 状态 StatusType=" + s.statusTypeName + "(" + s.statusType + ")"
    + "  已选=" + s.curChoice + " 剩余可选=" + s.remainChoice);
  if (s.choices.length) {
    for (const c of s.choices) {
      console.log("[st]   候选#" + c.i + "  ptr=" + c.ptr + "  choiceNum=" + c.choiceNum
        + "  rewardType=" + c.rewardType + "  rewardId=" + c.rewardId + "  rewardCount=" + c.rewardCount);
    }
  } else console.log("[st]   候选=(空)");
  return s;
};

// ── ★实例缓存："只抓需要的内容" ──
// _scanKlass 是 `Process.enumerateRanges` + `Memory.scanSync` 的**全堆扫描**（实测 1~2s/次）。
// 但这类"页面级对象"（选取页 DialogLabyrinthSelection、事件页 DialogLabyrinthEvent）在同一页面
// 存活期间**地址稳定**（il2cpp GC 非移动式），没理由每轮重扫整个堆。
// 做法：首次扫描后记住**选中的那个实例**，以后每轮只做 O(1) 校验 —— object_get_class 读首字段
// + class_get_name 比对类名（2 次调用）→ 通过就直接用；失败（换页/销毁/被复用）才重扫。
// 返回 null 表示"缓存失效且未重扫"，调用方走原扫描路径。
const g_instCache = {};

/** ★`labBtn` 的"学一次指针"缓存：key → {tr, name}（换页/节点重建时校验失败即作废重学） */
const g_labBtn = {};

/**
 * ★`labBtn` 的实现主体（2026-10-20 从 RPC 里抽出来）：`labBtn` 和 `labQuestDetail`（start 提取）
 *   共用。返回 {ok, x, y, hit, root, path, waiting, err}：
 *   · ok=true 时 x/y 是按钮的**屏幕坐标**（现算）；
 *   · hit="学习" 时 root/path 是新学的路径 —— 调用方（Kotlin）要落盘，下次跨局回喂。
 */
function _labBtnCore(key, name, rootKind, pathArr, fresh) {
  const out = { ok: false };
  try {
    if (fresh) delete g_labBtn[key];
    const env = _camEnv();
    if (!env) return out;
    const put = function (tr) {
      const ss = _screenOf(tr, env.cam, env.mW2S);
      if (!ss) return false;
      const x = Math.round(ss.x), y = Math.round(env.H - ss.y);
      if (x < 0 || y < 0 || x > env.W || y > env.H) return false;
      out.x = x; out.y = y; return true;
    };
    const roots = [];
    try {
      const vm = _viewManagerInst();
      if (!vm.isNull()) {
        const cv = vm.add(0x110).readPointer();
        if (ptrOk(cv)) {
          const tr = _inv(cv, "get_transform");
          if (ptrOk(tr)) {
            roots.push({ kind: "view", tr: tr });
            // ★canvas 根（2026-10-20）：预览页「挑战」按钮实测**不在 View/DialogArea 子树里**
            //   （QDStart 每次学习都静默失败 ⇒ 每场战斗退回 1.1s 全树 find，16480 09:46 局 11 次共 12.4s），
            //   它挂在 Canvas 下的别的容器 ⇒ 从 currentView 往上爬到最顶，把这个根也纳入学习/回喂。
            let top = tr;
            // ⚠GetParent 在这个剥离版里不存在（qdDiag=not-found ×12 的原因）⇒ get_parent 优先
            for (let i = 0; i < 8; i++) {
              let p2 = null;
              try { p2 = _inv(top, "get_parent"); } catch (e) {}
              if (!p2 || p2.isNull() || !ptrOk(p2)) {
                try { p2 = _inv(top, "GetParent"); } catch (e) {}
              }
              if (!p2 || p2.isNull() || !ptrOk(p2)) break;
              top = p2;
            }
            if ("" + top !== "" + tr) roots.push({ kind: "canvas", tr: top });
          }
        }
      }
    } catch (e) {}
    try { const d = _dialogAreaTr(); if (ptrOk(d)) roots.push({ kind: "dlg", tr: d }); } catch (e) {}
    const nameOf = function (tr) {
      try {
        const go = _inv(tr, "get_gameObject");
        return ptrOk(go) ? _str(_inv(go, "get_name")) : "";
      } catch (e) { return ""; }
    };
    // ① 命中缓存：沿**路径**（父子索引链）直达 —— O(深度)≈10 次调用，不遍历
    //    ★rootKind/pathArr 可以由 Kotlin 侧从磁盘"固定路径表"传进来 ⇒ 连进程内的学习都省了
    let slot = g_labBtn[key];
    if (!slot && rootKind && pathArr && pathArr.length != null) {
      slot = { root: rootKind, path: pathArr };
    }
    if (slot && slot.path) {
      const r = roots.find(function (x) { return x.kind === slot.root; });
      const tr = r ? _pathAt(r.tr, slot.path) : null;
      if (tr && nameOf(tr) === name) {
        if (put(tr)) { out.ok = true; out.hit = "路径"; return out; }
        out.waiting = true;                 // 位置有效但还没在屏上 ⇒ 保留，稍后重试
        return out;
      }
      delete g_labBtn[key];                 // 路径失效（UI 变了）⇒ 重学
    }
    // ② 学习：在子树里找一次，记下**路径**（下次直达）
    for (const r of roots) {
      const p = _pathFind(r.tr, name);
      if (!p) continue;
      const tr = _pathAt(r.tr, p);
      if (tr && put(tr)) {
        g_labBtn[key] = { path: p, name: name, root: r.kind };
        out.ok = true; out.hit = "学习";
        out.root = r.kind;            // 回给 Kotlin，落盘成"固定路径表"
        out.path = p;
        return out;
      }
    }
    // ③ 兜底（2026-10-20）：三个根的 _pathFind 都找不到（实测 Canvas 子树太大，DFS 找不到/超时
    //    —— qdDiag=not-found ×12），但全树 _walkNodes（`find` 同款）每次都能找到 ⇒
    //    用它找到按钮后反推**满链**（从按钮往上爬到 scene 根），存成 root="canvas" 的索引链。
    //    学习成本 = 一次全树遍历（~1.1s，只此一次），之后沿链 descend + 终点名校验直达。
    const canvasR = roots.find(function (x) { return x.kind === "canvas"; });
    if (canvasR) {
      try {
        for (const nd of _walkNodes()) {
          if (nd.active !== true || nd.name !== name) continue;
          const chain = _trChain(nd.tr, canvasR.tr);
          if (!chain || !chain.length) continue;
          const tr2 = _trByChain(canvasR.tr, chain);       // 链必须可复现才存
          if (!tr2 || !put(tr2)) continue;
          g_labBtn[key] = { path: chain.map(function (c) { return c.i; }), name: name, root: "canvas" };
          out.ok = true; out.hit = "学习";
          out.root = "canvas"; out.path = chain.map(function (c) { return c.i; });
          return out;
        }
        out.err = "walk-not-found";                         // 诊断：全树也没有这个名字
      } catch (e) { out.err = "walk:" + e; }
    } else {
      out.err = "no-canvas-root";
    }
    return out;
  } catch (e) { out.err = String(e); return out; }
}
function _instCacheGet(key, className, validate) {
  const c = g_instCache[key];
  if (!c || !ptrOk(c)) return null;
  try {
    const kk = g_il2cpp.object_get_class(c);
    if (kk.isNull()) { delete g_instCache[key]; return null; }
    const nm = g_il2cpp.class_get_name(kk);
    const nmS = (nm && !nm.isNull()) ? nm.readUtf8String() : "";
    if (nmS.indexOf(className) >= 0 && (!validate || validate(c))) return c;
  } catch (e) {}
  delete g_instCache[key];
  return null;
}
function _instCachePut(key, p) { if (p && ptrOk(p)) g_instCache[key] = p; }
function _instCacheDel(key) { delete g_instCache[key]; }

// ★★统一的"**什么时候才允许全堆扫描**"闸门（2026-10-17，用户："不用 8s 的扫描就能读到吗"）。
//   背景（实机日志证明）：页面**刚切过来**时 DialogArea 里还没有那个对话框 —— 这是**正常过渡**
//   （几百 ms 就建好），不是"需要兜底"✗。旧代码在这种瞬间立刻扫堆（1~9s ✗），既慢又占住
//   单条 RPC 通道 ⇒ `viewId` 超时 ⇒ 被判"agent 掉线" ⇒ 自愈重启（商店那次直接把一局跑废）。
//   现在的规矩：**先快速失败、下一轮再读**；只有同一页**连续 [SCAN_AFTER_MS] 都拿不到**才允许扫一次
//   （扫完计时归零 ⇒ 最多每 10s 一次，绝不会一轮里连扫）。
const SCAN_AFTER_MS = 10000;
const g_scanMiss = {};        // key → 首次 miss 的时刻（0/缺失 = 没在计时）
function _scanGate(key) {
  const now = Date.now();
  if (!g_scanMiss[key]) { g_scanMiss[key] = now; return false; }   // 刚 miss ⇒ 先不扫
  if ((now - g_scanMiss[key]) < SCAN_AFTER_MS) return false;       // 还没等够 ⇒ 继续等
  g_scanMiss[key] = 0;                                             // 放行一次（之后重新计时）
  return true;
}
function _scanGateHit(key) { delete g_scanMiss[key]; }             // 读到了 ⇒ 计时归零

// ★带校验的堆扫描：**上限只对"校验通过"的命中计数**。
// 为什么必须这样：Memory.scanSync 扫的是"类指针字节"，命中的地址里绝大多数只是**别的对象里
// 存着这个类指针的字段槽**，而 objIsClass(a, klass) 读 *(void**)a 恰好就等于那个类指针 →
// 自己验证自己必然通过。于是 `_scanKlass(kls, 256)` 的 256 个名额会被垃圾先占满，
// **真实例根本没机会进列表**（实测踩过多次：商店/选取页/事件页都因此读到空）。
function _scanKlassValid(kls, limit, validate) {
  const out = [];
  if (!kls || kls.isNull()) return out;
  const kb = kls.toString(16).padStart(16, "0").match(/../g).reverse().join(" ");
  const lim = limit || 200;
  _opBegin("scanKlassValid(limit=" + lim + ")");
  try {
    for (const r of Process.enumerateRanges("r--").filter(x => !x.file && x.size >= 0x1000).sort((a, b) => b.size - a.size)) {
      if (r.file || r.size < 0x1000) continue;
      try {
        const ms = Memory.scanSync(r.base, r.size, kb);
        for (const m of ms) {
          try {
            if (!objIsClass(m.address, kls)) continue;
            if (!validate || !validate(m.address)) continue;   // ← 假命中在这里被挡掉，且**不占名额**
            out.push(m.address);
            if (out.length >= lim) return out;
          } catch (e) {}
        }
      } catch (e) {}
    }
  } finally { _opEnd(); }
  return out;
}

// 堆扫描某类的所有实例（与 diagKlass 同一套做法，提成公共函数）
function _scanKlass(kls, limit) {
  const out = [];
  if (!kls || kls.isNull()) return out;
  const kb = kls.toString(16).padStart(16, "0").match(/../g).reverse().join(" ");
  const lim = limit || 200;
  _opBegin("scanKlass(limit=" + lim + ")");
  try {
    for (const r of Process.enumerateRanges("r--").filter(x => !x.file && x.size >= 0x1000).sort((a, b) => b.size - a.size)) {
      if (r.file || r.size < 0x1000) continue;
      try {
        const ms = Memory.scanSync(r.base, r.size, kb);
        for (const m of ms) {
          try { if (objIsClass(m.address, kls)) { out.push(m.address); if (out.length >= lim) return out; } } catch (e) {}
        }
      } catch (e) {}
    }
  } finally { _opEnd(); }
  return out;
}

// 取"当前这个选择对话框"的卡片列表（选角色页 / 遗物页**共用同一个 DialogLabyrinthSelection**）：
//   DialogLabyrinthSelection+0x140 partsItemView(PartsLabyrinthSelectionItemView)
//     -> +0x28 itemSelectionPartsList: List<PartsSelectionItemInfo>
// ★走实例缓存 + 强校验（view 指针 + 表长 1..8）：稳态下零堆扫描。
//  ★`noScan=true`（2026-10-17 用户批准）：**跳过堆扫描兜底**。遗物/门票的读取（[labChoicesSnapshot]）
//  走这条 —— 那次 `_scanKlass(DialogLabyrinthSelection, 512)` 实测 **~3.1s/次** ✗，而且一旦扫描
//  放进来就会顺路把扫堆的假命中一起带出来（正是"垃圾候选 ⇒ 退避第一件"的源头）。选人页仍用默认
//  （`noScan` 缺省 false，行为不变）。
function _selDialogList(noScan) {
  try {
    const k = findClass("DialogLabyrinthSelection");
    if (!k || k.isNull()) return null;
    // ★★候选顺序（2026-10-17 修"读到上一轮旧实例 ⇒ 永远空列表"）：
    //   ① 实例缓存里那个（若还在且形状对）
    //   ② DialogArea 里**所有** `DialogLabyrinthSelection`（[_selDlgCandidates]，新的在上）
    //   ③ 老路 `_compInDialogs`（按组件类找，名字不完全匹配时也能用）
    //   然后**逐个**用 [_selListOfInstance] 校验"列表 1..8"，第一个通过的才是我们要的。
    //   ⚠为什么必须逐个：一轮里会有多次选择（Boss 后：遗物 + 选人 ×2），上一轮那个实例
    //   会留在 DialogArea 里、列表已清空 ⇒ 只取第一个 ⇒ 永远 n=0 ⇒ 卡到丢缓存/自愈重启（实测 40s+）。
    const cands = [];
    const push = function (d) {
      if (!ptrOk(d)) return;
      for (let i = 0; i < cands.length; i++) if (String(cands[i]) === String(d)) return;
      cands.push(d);
    };
    push(_instCacheGet("labSel", "DialogLabyrinthSelection", _selPickValid));
    for (const c of _selDlgCandidates(k, 4)) push(c);
    push(_compInDialogs(k));
    for (const d0 of cands) {
      const hit = _selListOfInstance(d0);
      if (hit) { _instCachePut("labSel", d0); _selScanDone(); return { d: hit.d, list: hit.list, n: hit.n }; }
    }
    if (cands.length && !g_selFastSaid) {
      g_selFastSaid = true;
      console.log("[sel] 候选都不带有效列表（共 " + cands.length + " 个；列表 0 或 >8）⇒ " +
                  (noScan ? "不扫堆（本路径禁用）" : "等扫堆门槛"));
    }
    // ④ 兜底：堆扫描（**10s 门槛**；遗物/门票路径用 `noScan` 关掉，见 [CHOICE_SCAN_FALLBACK]）
    if (!noScan && _selScanDue()) {
      for (const cand of _scanKlass(k, 512)) {
        const hit = _selListOfInstance(cand);
        if (hit) { _instCachePut("labSel", cand); _selScanDone(); return { d: hit.d, list: hit.list, n: hit.n }; }
      }
      _selScanDone();
    }
    return null;
  } catch (e) { return null; }
}

// 读一张候选卡（PartsSelectionItemInfo）。同一张卡 prefab 承载三种内容（dump.cs 偏移）：
//   +0xB8 unitInfo   … 选角色页的卡 → 返回 null（交给 labSelection）
//   +0xA8 choiceInfo … 遗物/道具"选择"（rewardId@0x28 …）
//   +0xA0 relicIcon  … 遗物图标卡（itemId@0x40、button@0x20）★ EX 战斗后的遗物页走这条
// 点击坐标 = 这张卡自己的按钮：selectButton(@0x60) 优先，退 relicIcon.button(@0x20)。
function _readChoiceCard(o, cam, mW2S, H) {
  if (!ptrOk(o)) return null;
  try {
    // ★不再**直接丢弃**"带 +0xB8 unitInfo 的卡"（2026-10-16 修，门票页"固定 3 张却只读到 1 张"的元凶）：
    //   门票页的印记卡**同时带** unitInfo 与 choiceInfo，这一句原来把它们全 return null 掉了
    //   ⇒ `labChoices` 只报出 1 张 ⇒ 我们判"没读全"⇒ 只能留人工/卡住 ✗。
    //   现在照常返回并标 `hasUnit`/`choiceIdx`，由调用方决定用不用。
    //   ⚠真正的"选角色页卡"走的是 `labSelectionSnapshot` 那条路，不受影响；既没有 choiceInfo
    //     也没有 relicIcon 的卡仍会在下面 `return null`（挡掉误命中 ✓）。
    const hasUnit = ptrOk(o.add(0xB8).readPointer());
    const ci = o.add(0xA8).readPointer();
    const ri = o.add(0xA0).readPointer();
    let cNum = null, rType = null, rId = null, rCnt = null, src = null;
    if (ptrOk(ci)) {
      cNum = decOI(ci.add(0x10)); rType = decOI(ci.add(0x1C));
      rId = decOI(ci.add(0x28)); rCnt = decOI(ci.add(0x34)); src = "choiceInfo";
    } else if (ptrOk(ri)) {
      rId = ri.add(0x40).readS32();                          // PartsLabyrinthRelicIcon.itemId
      rCnt = 1; src = "relicIcon";
    } else return null;
    if (!(rId > 0)) return null;                             // id 不合理 → 不是真候选卡（挡掉误命中）
    const ss = _screenOf(o, cam, mW2S);
    let bx = null, by = null;
    const btnPtrs = [o.add(0x60).readPointer()];
    if (ptrOk(ri)) btnPtrs.push(ri.add(0x20).readPointer());
    for (const bp of btnPtrs) {
      if (!ptrOk(bp)) continue;
      const bs = _screenOf(bp, cam, mW2S);
      if (bs) { bx = Math.round(bs.x); by = Math.round(H - bs.y); break; }
    }
    return {
      ptr: String(o), src: src,
      hasUnit: hasUnit, choiceIdx: (function () { try { return decOI(o.add(0x98)); } catch (e) { return null; } })(),
      choiceNum: cNum, rewardType: rType, rewardId: rId, rewardCount: rCnt,
      x: ss ? Math.round(ss.x) : null, y: ss ? Math.round(H - ss.y) : null,
      decideX: bx, decideY: by,
    };
  } catch (e) { return null; }
}

// 读"遗物/道具选择"弹窗上每张候选卡**自己**的 rewardId，并配上它 DecideButton 的点击坐标。
// 硬绑定：PartsSelectionItemInfo.choiceInfo(+0xA8) -> LabyrinthChoiceInfo.rewardId(+0x28)
// 返回 { cards:[{rewardId,choiceNum,rewardType,rewardCount,x,y,decideX,decideY}], decides:[{x,y}] }（cards 按 decideX 升序）
// 遗物/道具选择页的读取缓存（钥匙 = 最上层对话框名 + TTL）
let g_choiceCache = null;
const CHOICE_TTL_MS = 2000;
let g_choiceFailAt = 0;      // 上次"一张卡都没扫到"的时刻（失败退避用）

// ★★遗物/门票候选的**扫堆兜底开关**（2026-10-17 用户批准："1.可以，那就关掉"）—— 默认**关**。
//   为什么关：① 它很贵（`_scanKlass(512)` 实测 ~3.1s ✗，还正好把便宜调用挤超时 ⇒ 看着像"agent 掉线"）；
//   ② 它会**吐垃圾**（同类型对象全捞 ⇒ 60 个假卡、rewardId 读成 1e7~2e9 的垃圾）⇒ 决策层全认不出 ⇒
//      `pickCard` 兜底"取第一张" = **盲选**（10-17 那次就是这么点出去的）✗。
//   关掉之后的处置链（Kotlin 侧）：读不到/读到垃圾 ⇒ ① `labResetCaches` 丢缓存重解析 ⇒
//   ② 仍不行 ⇒ **自愈重启**（≤2 次/页）⇒ ③ 停手留人工。**结构化路径**（对话框自己的卡片列表）不受影响 ✓。
const CHOICE_SCAN_FALLBACK = false;

function labChoicesSnapshot(force) {
  // ★原来的"8s 失败退避"已撤（2026-10-16）：它把**精确读**（结构化路径，几十 ms ✓）也一起挡了 ✗
  //   ⇒ 优化等于没生效。现在改成：**结构化路径每轮都做**，只有它拿不到、且**连续 10s** 都拿不到时
  //   才允许扫堆兜底（见 [SEL_SCAN_AFTER_MS] / `_selScanDue`）。
  const res = { cards: [], decides: [] };
  const k = findClass("PartsSelectionItemInfo");
  if (!k || k.isNull()) return res;
  const rtu = findClass("RectTransformUtility");
  const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
  const cam = _uiCamera();
  if (cam.isNull() || !mW2S) return res;
  let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
  // ① 结构化路径（首选，零堆扫描）：对话框自己的卡片列表
  //    DialogLabyrinthSelection+0x140 partsItemView(PartsLabyrinthSelectionItemView)
  //      -> +0x28 itemSelectionPartsList: List<PartsSelectionItemInfo>
  //    遗物/道具页(SetupItem) 与选角色页(SetupUnit) **共用这一个列表** —— 不必扫堆。
  const sl = _selDialogList(true);                 // ★true = 不扫堆（见 [CHOICE_SCAN_FALLBACK]）
  if (sl) {
    for (let i = 0; i < sl.n; i++) {
      const c = _readChoiceCard(_listItem(sl.list, i), cam, mW2S, H);
      if (c) { c.i = i; res.cards.push(c); }
    }
  }
  // ② 兜底：结构化路径拿不到（列表偏移变了/页面结构特殊）→ 才用堆扫描（旧路径，**10s 门槛** ✓）
  //   ★★收紧（2026-10-17 事故，用户："退避第一件了？"→"说明读取方式有问题？"）：
  //   `_scanKlass(PartsSelectionItemInfo, 64)` 是**同类型对象全捞**，会把别处（角色一览/其它列表）
  //   的同型卡也带进来 ✗ ⇒ 它们的 `choiceInfo(+0xA8)` 根本不是"本次候选" ⇒ rewardId 读成垃圾
  //   （实测一口气给 **60 张**、id 是 `1179648/2084821607/1374371792…`）⇒ 决策层一张都认不出 ⇒
  //   `pickCard` 兜底"取第一张" = **盲选** ✗（那一手就是这么点出去的）。
  //   ⇒ 只对**扫堆兜底**加两道合理性过滤：① id 像个真 id（`1 ≤ id < 1e6`；实测垃圾值都 ≥1e7）
  //     ② 那张卡的「决定」按钮在屏内（真候选一定可点）。**结构化路径不加**（它是权威读法、
  //     本来就只有 3 张，加过滤反而可能误伤）。
  //   ★★2026-10-17 再改：整个兜底**默认关闭**（[CHOICE_SCAN_FALLBACK]=false，用户批准）。理由见该常量：
  //     它 ~3.1s/次且只会吐垃圾；现在"读不到 ⇒ 丢缓存重解析 ⇒ 自愈重启"这条链兜着。下面两道过滤保留，
  //     万一将来要重开（改常量即可）仍在。`_selScanDue()` 也不再在这里被"消费"（`_selDialogList(true)`
  //     已把它绕开），所以不会再出现"扫堆把便宜调用挤超时"那种假掉线。
  if (CHOICE_SCAN_FALLBACK && !res.cards.length && k && !k.isNull() && _selScanDue()) {
    const _sc = _scanKlass(k, 64);
    _selScanDone();
    res.scanned = _sc.length;
    let dropped = 0;
    for (const o of _sc) {
      const c = _readChoiceCard(o, cam, mW2S, H);
      if (!c) continue;
      const rid = c.rewardId;
      const btnOn = (c.decideX != null && c.decideY != null &&
                     c.decideX >= 0 && c.decideX <= 1920 && c.decideY >= 0 && c.decideY <= 1080);
      if (!(rid >= 1 && rid < 1000000) || !btnOn) { dropped++; continue; }
      res.cards.push(c);
    }
    res.scanDropped = dropped;
  }
  // 兜底：一个按钮坐标都没拿到（页面结构特殊）→ 才退回"遍历找 DecideButton 名字，按 x 就近配对"
  if (res.cards.length && res.cards.every(c => c.decideX == null)) {
    try {
      _rawWalk(function (nd) {
        if (nd.active === true && nd.onscreen && nd.name === "DecideButton") res.decides.push({ x: nd.x, y: nd.y });
      }, true, _dialogAreaTr());
      for (const c of res.cards) {
        let best = null, bd = 1e9;
        for (const d of res.decides) { const dd = Math.abs(d.x - c.x); if (dd < bd) { bd = dd; best = d; } }
        c.decideX = best ? best.x : null;
        c.decideY = best ? best.y : null;
      }
    } catch (e) {}
  }
  res.cards.sort((a, b) => (a.decideX == null ? 1e9 : a.decideX) - (b.decideX == null ? 1e9 : b.decideX));
  // 空结果自证：把"卡在哪一步"写进结果（Python 会打出来）——下次遇到空卡不用再猜
  if (!res.cards.length) {
    res.why = "klass=" + (k && !k.isNull() ? "ok" : "缺")
            + " 结构列表=" + (sl ? ("n=" + sl.n) : "没读到(DialogLabyrinthSelection+0x140/+0x28)")
            + " 堆扫描=" + (CHOICE_SCAN_FALLBACK ? (res.scanned == null ? "-" : res.scanned) : "关")
            + " 扫堆丢弃=" + (res.scanDropped == null ? "-" : res.scanDropped)
            + " 兜底DecideButton=" + res.decides.length;
  }
  g_choiceFailAt = res.cards.length ? 0 : Date.now();     // 一张卡都没有 → 记下时刻，1.2s 内不重扫
  return res;
}

// 诊断打印
globalThis.labChoices = function () {
  if (!g_il2cpp) { console.log("[ch] il2cpp 未就绪"); return null; }
  const r = labChoicesSnapshot();
  console.log("[ch] DecideButton 数=" + r.decides.length + "  候选卡数=" + r.cards.length);
  for (const c of r.cards) {
    console.log("[ch]   rewardId=" + c.rewardId + " choiceNum=" + c.choiceNum + " rewardType=" + c.rewardType
      + " rewardCount=" + c.rewardCount + "  卡片@(" + c.x + "," + c.y + ")  点它=(" + c.decideX + "," + c.decideY + ")");
  }
  return r;
};

// 诊断快照（给 Python 自动调用；REPL 的 diagChoices() 另有人类可读版）：
// 回答"这类卡到底有没有 / 每个指针字段是什么类 / 页面里像按钮的节点叫什么"
function labChoicesDiagSnapshot() {
  const out = { top: null, listN: null, cards: [], heapTotal: null, heapGood: null, nodes: [] };
  try { out.top = _topDialogName(); } catch (e) {}
  // ① 结构化路径（现在的读法）：对话框自己的卡片列表 → 每项三个字段的**实际类名**
  const sl = _selDialogList();
  if (sl) {
    out.listN = sl.n;
    for (let i = 0; i < sl.n; i++) {
      const o = _listItem(sl.list, i);
      const row = { i: i, p: String(o) };
      try {
        if (ptrOk(o)) {
          const rp = o.add(0xA0).readPointer(); row.ri = ptrOk(rp) ? klassNameOf(rp) : "null";
          const cp = o.add(0xA8).readPointer(); row.ci = ptrOk(cp) ? klassNameOf(cp) : "null";
          const up = o.add(0xB8).readPointer(); row.ui = ptrOk(up) ? klassNameOf(up) : "null";
        }
      } catch (e) { row.err = "" + e; }
      out.cards.push(row);
    }
  }
  // ② 旧路径（堆扫描）到底能捞到几张"真卡" —— 对比用，解释为什么以前读不到
  try {
    const k = findClass("PartsSelectionItemInfo");
    if (k && !k.isNull()) {
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      const cam = _uiCamera();
      let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
      const insts = _scanKlass(k, 64);
      out.heapTotal = insts.length;
      let good = 0;
      if (!cam.isNull() && mW2S) for (const o of insts) if (_readChoiceCard(o, cam, mW2S, H)) good++;
      out.heapGood = good;
    }
  } catch (e) {}
  // ③ 页面节点名（核对结构用）
  const cnt = {};
  try { _rawWalk(function (nd) { if (nd.active === true) cnt[nd.name] = (cnt[nd.name] || 0) + 1; }, false, _dialogAreaTr()); } catch (e) {}
  out.nodes = Object.keys(cnt)
    .filter(function (n) { return /btn|button|decide|select|choice|ok|confirm|item|card|root/i.test(n); })
    .map(function (n) { return n + "×" + cnt[n]; });
  return out;
}

// 诊断：遗物/道具选择页为什么读不到卡（REPL 里直接跑 diagChoices()）
// 一次性回答三件事：卡类是否存在且激活 / 卡的 +0xA8、+0xB8 到底是什么类 / 「选择」按钮的真名
globalThis.diagChoices = function () {
  const d = labChoicesDiagSnapshot();
  console.log("[dc] 顶框=" + d.top + "  卡片列表长度=" + d.listN
    + "  堆扫描=" + d.heapTotal + "（其中合格=" + d.heapGood + "）");
  for (const r of d.cards) console.log("[dc]   卡#" + r.i + " " + JSON.stringify(r));
  console.log("[dc] DialogArea 内像按钮/卡的节点名：" + (d.nodes.length ? d.nodes.join(", ") : "(无)"));
  return "把 [dc] 开头的行全部发我";
};

// ── 商店页（DialogLabyrinthShop）──
// 一次读全：分组商品列表（**含需要滚动才能看到的那部分**）+ 金币 + 刷新费 + 屏上可点的「购买」槽位。
// 链路（dump.cs 已核对）：
//   DialogLabyrinthShop+0x100 controller(PartsLabyrinthShopScrollController)
//     +0x38 lineupInfos: List<List<LabyrinthShopLineupInfo>>（分组；createSortItemList 说明组内已排序）
//   LabyrinthShopLineupInfo: LineupId@0x10 RewardType@0x1C RewardId@0x20 RewardCount@0x2C
//                            Price@0x38 IsSold@0x44 Availability@0x50（1=PURCHASE 可买）
//   DialogLabyrinthShop: currentCoin@0x118(UILabel) resetCost@0x170(int)
// 商店实例校验：+0x100 必须是个 PartsLabyrinthShopScrollController。
// ★不能只靠"类名对得上"就认（_scanKlass 的假命中也会通过 objIsClass），必须验一个真字段。
function _shopValid(d) {
  try {
    const ctl = d.add(0x100).readPointer();
    if (!ptrOk(ctl)) return false;
    if (klassNameOf(ctl).indexOf("PartsLabyrinthShopScrollController") < 0) return false;
    // ★还必须"真的有商品"：过期的旧商店实例同样能通过类名检查，但它的 lineup 是空的
    //   （实测症状：金币=None、resetCost=0、商品=0 —— 正是抓到了旧实例）
    const outer = ctl.add(0x38).readPointer();
    if (!ptrOk(outer)) return false;
    return _listCount(outer) >= 1;
  } catch (e) { return false; }
}

// 读 UIPanel 的"实际裁剪区"（NGUI finalClipRegion）：Vector4(x,y,z,w) = 矩形 (x,y)~(x+z, y+w)，
// 坐标系是 UI 根（像素、**左下原点**），尺寸=屏幕。用于判断"按钮是不是真的在可视区内"。
// ★为什么需要：屏内(onscreen) ≠ 可视 —— 商店面板比屏幕小，下方印记行的按钮虽在屏幕内，
//   却被 scrollPanel 裁掉（实测 y=906 那一排就是被裁的），点它没用。
function _panelClipRegion(panel) {
  try {
    const m = _mInfo(g_il2cpp.object_get_class(panel), "get_finalClipRegion");
    if (!m) return null;
    const ex = Memory.alloc(8); ex.writePointer(ptr(0));
    const r = g_il2cpp.runtime_invoke(m, panel, ptr(0), ex);
    if (r.isNull() || !ex.readPointer().isNull()) return null;
    return { x: r.add(0x10).readFloat(), y: r.add(0x14).readFloat(),
             z: r.add(0x18).readFloat(), w: r.add(0x1C).readFloat() };
  } catch (e) { return null; }
}

// TransformPoint(tr, Vector3) → Vector3（把"局部坐标"点转到世界坐标）
function _trPoint(tr, x, y, z) {
  try {
    const m = _mInfo(g_il2cpp.object_get_class(tr), "TransformPoint");
    if (!m) return null;
    const v = Memory.alloc(16);
    v.writeFloat(x); v.add(4).writeFloat(y); v.add(8).writeFloat(z);
    const params = Memory.alloc(8); params.writePointer(v);
    const ex = Memory.alloc(8); ex.writePointer(ptr(0));
    const r = g_il2cpp.runtime_invoke(m, tr, params, ex);
    if (r.isNull() || !ex.readPointer().isNull()) return null;
    return { x: r.add(0x10).readFloat(), y: r.add(0x14).readFloat(), z: r.add(0x18).readFloat() };
  } catch (e) { return null; }
}

// 世界坐标 → 屏幕（**安卓口径：左上原点**，与 nd.x/nd.y 同一套）
function _worldToScreen(env, w) {
  try {
    if (!w) return null;
    const vec = Memory.alloc(16);
    vec.writeFloat(w.x); vec.add(4).writeFloat(w.y); vec.add(8).writeFloat(w.z);
    const params = Memory.alloc(16); params.writePointer(env.cam); params.add(8).writePointer(vec);
    const ex = Memory.alloc(8); ex.writePointer(ptr(0));
    const sp = g_il2cpp.runtime_invoke(env.mW2S, ptr(0), params, ex);
    if (sp.isNull()) return null;
    return { x: sp.add(0x10).readFloat(), y: env.H - sp.add(0x14).readFloat() };
  } catch (e) { return null; }
}

// UIPanel 的 finalClipRegion → 屏幕矩形（左上原点）。
// ★坐标空间（2026-10-07 实测反推定案）：finalClipRegion 是**面板局部坐标，但原点是面板
//   rect 的"左下角"**，而 TransformPoint 要的是"相对 transform 位置(=pivot/中心)"——
//   两者差半个 rect。实测（缩放 s=1.5、面板中心屏幕位 948/680）：
//     不校正 → x[942,2670] y[-121,539]  ← 上排第 1 个（x=492）被误判"被裁"
//     减去 (z/2, w/2)=(576,220) → **x[78,1806] y[208,869]** ← 与画面完全吻合 ✓
//       （上排 y=525、x=492/1068/1644 全在带内；下排 y=906 在带外 → 全"被裁"）
function _panelClipScreen(panel, env) {
  const out = { band: null, raw: null };
  try {
    const cr = _panelClipRegion(panel);
    if (!cr) return out;
    out.raw = cr;
    const tr = _inv(panel, "get_transform");
    if (tr.isNull()) return out;
    const rectOf = function (a, b) {
      if (!a || !b) return null;
      return { left: Math.round(Math.min(a.x, b.x)), right: Math.round(Math.max(a.x, b.x)),
               top: Math.round(Math.min(a.y, b.y)), bottom: Math.round(Math.max(a.y, b.y)) };
    };
    out.band = rectOf(_worldToScreen(env, _trPoint(tr, cr.x - cr.z / 2, cr.y - cr.w / 2, 0)),
                      _worldToScreen(env, _trPoint(tr, cr.x + cr.z / 2, cr.y + cr.w / 2, 0)));
  } catch (e) { out.err = "" + e; }
  return out;
}


// ★商店"扫堆兜底"开关（2026-10-17 用户实机日志证明它是**卡死源头**，默认关闭）：
//   关掉后拿不到实例就立刻返回 err，Kotlin 下一轮再读（页面建好后 DialogArea 一定能拿到 ✓）。
const SHOP_SCAN_FALLBACK = false;

function labShopSnapshot(diag) {
  // ★`src`/`ms`/`dlgMiss`（2026-10-17 用户："商店还是在扫描"）：把"这次实例是**从哪拿到的**、
  //   花了多久"直接回给 Kotlin —— 原来"DialogArea 优先未命中"只 `console.log`（frida 侧输出，
  //   应用日志里看不到 ✗）⇒ 用户和我都只能猜"到底扫没扫"。现在：cache / dlg / scan 三档 + 耗时。
  const res = { coin: null, rerollCost: null, pages: [], items: [], slots: [], err: null, src: null, ms: 0, dlgMiss: null };
  const _t0 = Date.now();
  let _src = null;
  try {
    const k = findClass("DialogLabyrinthShop");
    if (!k || k.isNull()) { res.err = "未找到 DialogLabyrinthShop"; return res; }
    let d = _instCacheGet("labShop", "DialogLabyrinthShop", _shopValid);
    if (d) _src = "cache";
    // ★★先走 DialogArea（2026-10-17 修，实测首次读 8956ms ✗）：商店对话框**一定在 DialogArea 里**
    //   （`pageTop=DialogLabyrinthShop` ✓），一条 `GetComponentInChildren` 几十 ms 就能拿到 ✓；
    //   而原来第一条路就是 `_scanKlassValid(4)` = **全堆扫描**（实测 8.9s ✗）⇒ 直接把 T_SLOW=8s 超时
    //   打满、还顺带挤超时并发的 `viewId`/`gameTick`（帧看门狗那一拍读不到帧 ✗）。
    //   扫堆降级为**兜底**（DialogArea 里没有时，例如换页途中/结构异常）。
    if (!d) {
      const _dbg = {};
      const cd = _compInDialogs(k, _dbg);
      if (ptrOk(cd) && _shopValid(cd)) { d = cd; _instCachePut("labShop", cd); _src = "dlg"; }
      else {
        // ★诊断（2026-10-17）：DialogArea 优先**没命中** ⇒ 把"各子节点名 + 有没有该类组件"打出来，
        //   好区分"名字对不上/组件不在那个子树上"与"DialogArea 本身没定位到"，别再靠猜 ✓
        //   （09:13 那次就是没命中、退化成 8.4s 全堆扫描 ✗）。
        // ★现在**同时回给 Kotlin**（`dlgMiss`）：应用日志里能直接看到原因，不用再靠 frida 控制台 ✗。
        res.dlgMiss = "dlgArea=" + _dbg.dlgArea +
          " childCount=" + _dbg.childCount + " type=" + _dbg.typePtr +
          " 子节点=" + (_dbg.children
            ? _dbg.children.map(function (x) { return x.name + (x.comp ? "✔" : ""); }).join(",")
            : "-");
        console.log("[shop] DialogArea 优先未命中：" + res.dlgMiss);
      }
    }
    if (!d) {
      // ★★**不再扫堆**（2026-10-17 实机事故，日志 12:02:24 / 12:10:13 两次"agent 掉线"全是这里）：
      //   刚走进商店节点时对话框还没建好（`pageTop=无`）⇒ 缓存与 DialogArea 都拿不到 ⇒ 旧代码
      //   **立刻全堆扫描**（实测 8~9s ✗）⇒ 单条 RPC 通道被占住 ⇒ `viewId` 超时 ⇒ 判"agent 掉线"
      //   ⇒ 自愈重启（12:10 那次自愈又没回到迷宫，**本局直接收工** ✗）。
      //   而商店对话框**永远在 DialogArea 里**（整份日志里成功读取全是 `cache`/`dlg`，从未 `scan` ✓）
      //   ⇒ 这条兜底不但没用，还是唯一能把 agent 卡死的地方 ⇒ **默认关闭**；拿不到就立刻返回，
      //   让 Kotlin 下一轮再读（几百 ms 后页面就建好了）。
      if (SHOP_SCAN_FALLBACK) {
        for (const c of _scanKlassValid(k, 4, _shopValid)) { d = c; _instCachePut("labShop", c); _src = "scan"; break; }
      }
    }
    if (diag) diag.inst = d ? String(d) : null;
    if (!d) {
      res.err = "商店对话框还没建好（DialogArea 里没有 DialogLabyrinthShop）→ 下一轮再读";
      return res;
    }
    try {
      const lb = d.add(0x118).readPointer();
      if (ptrOk(lb)) res.coin = parseInt(String(_str1(_inv(lb, "get_text"))).replace(/[^0-9]/g, ""), 10);
    } catch (e) {}
    try { res.rerollCost = d.add(0x170).readS32(); } catch (e) {}
    const ctl = d.add(0x100).readPointer();
    if (ptrOk(ctl)) {
      const outer = ctl.add(0x38).readPointer();
      if (ptrOk(outer)) {
        const np = _listCount(outer);
        for (let i = 0; i < np; i++) {
          const inner = _listItem(outer, i);
          if (!ptrOk(inner)) continue;
          const n = _listCount(inner);
          const page = [];
          for (let j = 0; j < n; j++) {
            const it = _listItem(inner, j);
            if (!ptrOk(it)) continue;
            const o = {
              page: i, idx: j, lineupId: decOI(it.add(0x10)),
              rewardType: it.add(0x1C).readS32(), rewardId: decOI(it.add(0x20)),
              rewardCount: decOI(it.add(0x2C)), price: decOI(it.add(0x38)),
              isSold: decOI(it.add(0x44)), availability: it.add(0x50).readS32(),
            };
            page.push(o); res.items.push(o);
          }
          res.pages.push(page);
        }
      }
    }
    // 屏上可点的「购买」按钮：按 上→下、左→右 排序 = 视觉顺序（第 k 个槽 ≈ 列表第 k 项）
    // ★两步解耦：①先拿槽位（裁剪区算不出来也不能把槽位弄丢）②再算可视窗口（失败只影响 vis）
    const _sl0 = [];
    const _tw0 = Date.now();
    try {
      // ★`wantCoords` 必须 false（2026-10-17 修）：原来是 `true` ⇒ 给 DialogArea 里**每个节点**都算
      //   一次屏幕坐标（≈3 次 il2cpp 调用/节点，几百节点 ⇒ 每次读 ~500ms ✗，而商店页每买一次就重读 ✗）。
      //   这里改成"只对名字命中的节点算坐标"（与别处一致 ✓）。
      // ★★走**商店对话框自己的子树**（2026-10-18 用户："商店怎么变成扫描了"）：之前从 DialogArea 根往下走，
      //   而 DialogArea 里同时挂着好几个对话框（含隐藏的），几百节点 × ~5 次 il2cpp 调用 ≈ 300~400ms/次
      //   （实测设备日志 cache 也要 330~430ms）—— 看着就像"在扫描"。PurchaseButton 必然在商店对话框子树内
      //   ⇒ 从 `d.get_transform()` 起走，节点数掉一个量级；裁剪区/按钮坐标仍按需现算。
      let _env = null;
      let _root = null;
      try { const _dt = _inv(d, "get_transform"); if (ptrOk(_dt)) _root = _dt; } catch (e) {}
      _rawWalk(function (nd) {
        if (nd.active !== true) return;
        if (nd.name !== "PurchaseButton") return;
        if (!_env) { _env = _camEnv(); if (!_env) return; }
        const p = _ndXY({ tr: nd.tr, name: nd.name }, _env);
        if (p && p.onscreen) _sl0.push({ x: p.x, y: p.y });
      }, false, _root || _dialogAreaTr());
    } catch (e) { res.slotErr = "walk: " + e; }
    res.msWalk = Date.now() - _tw0;
    try {
      const rtu2 = findClass("RectTransformUtility");
      const mW2S2 = (rtu2 && !rtu2.isNull()) ? _mInfo(rtu2, "WorldToScreenPoint") : null;
      const cam2 = _uiCamera();
      let H2 = 1080;
      try { const ph = _inv(cam2, "get_pixelHeight"); if (!ph.isNull()) H2 = ph.add(0x10).readS32(); } catch (e) {}
      const panel = d.add(0x108).readPointer();          // scrollPanel(UIPanel)
      if (ptrOk(panel)) {
        const env2 = { cam: cam2, mW2S: mW2S2, H: H2, W: 1920 };   // ★变量名是 mW2S2（上面刚取的）
        // finalClipRegion →（左下角原点校正）→ 世界 → 屏幕。实测 x[78,1806] y[208,869] 与画面吻合。
        const cb = _panelClipScreen(panel, env2);
        res.clip = cb.band; res.clipRaw = cb.raw; res.bandErr = cb.err || null;
        // 底部两个按钮：关闭(@0x110) / 用NNN更新(@0x130)，以及游戏自带的"还剩几次可刷"(get_resettableNum)
        const _btnXY = function (obj) {
          try {
            if (!ptrOk(obj)) return null;
            const s = _screenOf(obj, cam2, mW2S2);
            if (!s) return null;
            return { x: Math.round(s.x), y: Math.round(H2 - s.y) };
          } catch (e) { return null; }
        };
        res.closeBtn = _btnXY(d.add(0x110).readPointer());
        res.rerollBtn = _btnXY(d.add(0x130).readPointer());
        try {
          const rn = _inv(d, "get_resettableNum");
          if (!rn.isNull()) res.resettable = rn.add(0x10).readS32();
        } catch (e) {}
      }
    } catch (e) { res.clipErr = "clip: " + e; }
    const _band = res.clip || null;
    for (const s of _sl0) {
      let vis = null;
      if (_band) {
        vis = (s.x >= _band.left + 8 && s.x <= _band.right - 8
               && s.y >= _band.top + 8 && s.y <= _band.bottom - 8);
      }
      res.slots.push({ x: s.x, y: s.y, vis: vis });
    }
    res.slots.sort(function (a, b) { return (a.y - b.y) || (a.x - b.x); });
  } catch (e) { res.err = "" + e; } finally {
    // ★finally 保证**早退也带**（`return res` 会跳过 finally 之后的语句，所以放这里 ✓）
    res.src = _src;
    res.ms = Date.now() - _t0;
  }
  return res;
}

// ── 迷宫结算页（DialogLabyrinthResult）——打完最终 Boss 后的 RESULT ①②屏 ──
// 链路（dump.cs 已核对）：
//   DialogLabyrinthResult : DialogBase
//     +0x108 resultItemView  (PartsLabyrinthResultItemView)   ← ①「加入的角色」屏
//                +0x48 nextButton (CustomUIButton)  = 「下一步」
//     +0x110 resultScoreView (PartsLabyrinthResultScoreView)  ← ②「CLEAR + 总分」屏
//                +0x68 closeButton (CustomUIButton) = 「关闭」
//                +0x30 scoreLabel(CountUpUILabel) 总分 ／ +0x58 difficultyNumLabel ／
//                +0x60 difficultyScoreRateLabel ／ +0x38 累计点 ／ +0x40 强化点 ／ +0x48 获得迷宫币
//                  （PartsPontInfo：PointLabel@+0x10）
// ★按钮坐标一律**从对象现算**（get_transform → WorldToScreenPoint），不写死坐标；
//   「撤退（无报酬）/返回（有报酬）」不是这两个字段，所以天然不可能误点。
function _labResultValid(d) {
  try {
    const it = d.add(0x108).readPointer();
    if (ptrOk(it) && klassNameOf(it).indexOf("PartsLabyrinthResultItemView") >= 0) return true;
    const sc = d.add(0x110).readPointer();
    if (ptrOk(sc) && klassNameOf(sc).indexOf("PartsLabyrinthResultScoreView") >= 0) return true;
    return false;
  } catch (e) { return false; }
}

function labResultSnapshot() {
  const res = { ok: false, which: null, x: null, y: null, cands: {}, text: {}, err: null, src: null, ms: 0 };
  const _t0 = Date.now();
  let _src = null;
  try {
    const k = findClass("DialogLabyrinthResult");
    if (!k || k.isNull()) { res.err = "未找到 DialogLabyrinthResult 类"; return res; }
    let d = _instCacheGet("labResult", "DialogLabyrinthResult", _labResultValid);
    if (d) _src = "cache";
    // ★★DialogArea 优先（2026-10-17，与商店/选择页/事件页同一套）：结算对话框**一定在 DialogArea 里**
    //   （`pageTop=DialogLabyrinthResult` ✓）⇒ 一条 `GetComponentInChildren` 几十 ms ✓。
    //   原来缓存没命中就**直接全堆扫描**（`_scanKlassValid` 实测数秒 ✗）—— 而这页**每局只出现一次**
    //   ⇒ 缓存永远命中不了 ⇒ **每次结算都要扫一遍堆** ✗✗（正是"结算页慢"的一大来源，
    //   而且大范围 invoke 在换页瞬间还有把游戏卡住的风险 ✗）。扫堆只留作最后兜底。
    if (!d) {
      const cd = _compInDialogs(k);
      if (ptrOk(cd) && _labResultValid(cd)) { d = cd; _instCachePut("labResult", cd); _src = "dlg"; }
    }
    if (!d) {
      // ★受 [_scanGate] 约束（2026-10-17）：这页每局只出现一次、实例缓存永远命不中，
      //   页面刚弹出那几百 ms 子树里可能还没有对话框 —— 那时**先快速失败**（Kotlin 下轮再读 ✓），
      //   只有连续 10s 都拿不到才允许扫一次（真出现就是结构/名字变了，日志里有 `src=scan`）。
      if (_scanGate("result")) {
        for (const c of _scanKlassValid(k, 6, _labResultValid)) { d = c; _instCachePut("labResult", c); _src = "scan"; break; }
      }
    }
    if (d) _scanGateHit("result");
    if (!d) { res.err = "未找到结算对话框实例（校验未通过）"; res.src = _src; res.ms = Date.now() - _t0; return res; }
    const cam = _uiCamera();
    const rtu = findClass("RectTransformUtility");
    const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
    let H = 1080;
    try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
    const xy = function (obj) {
      try {
        if (!ptrOk(obj)) return null;
        const s = _screenOf(obj, cam, mW2S);
        if (!s) return null;
        return { x: Math.round(s.x), y: Math.round(H - s.y) };
      } catch (e) { return null; }
    };
    const on = function (obj) {          // 这个子视图的 GameObject 现在激活吗
      try { return ptrOk(obj) ? (_goActive(_inv(obj, "get_gameObject")) === true) : false; } catch (e) { return false; }
    };
    const label = function (obj) {
      try { return ptrOk(obj) ? _str1(_inv(obj, "get_text")) : null; } catch (e) { return null; }
    };
    // ★★两套偏移都读、挑"按钮屏内 + 子视图激活"的那份（2026-10-17 抓取实证）：
    //   `DialogLabyrinthResult` 上同时挂着两组 `PartsLabyrinthResultItemView/ScoreView`：
    //     +0x108/+0x110 ⇒ 屏幕坐标 **(-960,540)** = **屏外/隐藏**那份 ✗（PC 偏移只写了这一套）
    //     +0x248/+0x250 ⇒ 屏幕坐标 **(960,540)**  = **活的**那份 ✓
    //   原来只读前者 ⇒ `cands` 拿不到屏内坐标 ⇒ 白白退到节点树兜底（"不经过扫描"就做不到 ✗）。
    //   按钮字段本身与 PC 完全一致：ItemView+0x48 =「下一步」、ScoreView+0x68 =「关闭」✓。
    const _readSet = function (offIt, offSc) {
      const o = { next: null, close: null, score: null, diffNum: null, scoreRate: null,
                  point: null, enhance: null, coin: null };
      try {
        const it = d.add(offIt).readPointer();
        if (ptrOk(it)) {
          const p = xy(it.add(0x48).readPointer());
          o.next = { x: p ? p.x : null, y: p ? p.y : null, on: on(it) };
        }
        const sc = d.add(offSc).readPointer();
        if (ptrOk(sc)) {
          const p = xy(sc.add(0x68).readPointer());
          o.close = { x: p ? p.x : null, y: p ? p.y : null, on: on(sc) };
          o.score = label(sc.add(0x30).readPointer());
          o.diffNum = label(sc.add(0x58).readPointer());
          o.scoreRate = label(sc.add(0x60).readPointer());
          const _p = function (x) { return ptrOk(x) ? x.add(0x10).readPointer() : ptr(0); };
          o.point = label(_p(sc.add(0x38).readPointer()));    // 累计点（迷宫令牌）
          o.enhance = label(_p(sc.add(0x40).readPointer()));  // 强化点（阿尔法碎片）
          o.coin = label(_p(sc.add(0x48).readPointer()));     // 获得迷宫币
        }
      } catch (e) {}
      return o;
    };
    const _onsc = function (c) {
      return !!(c && c.x !== null && c.y !== null &&
                c.x >= 0 && c.x <= 1920 && c.y >= 0 && c.y <= 1080);
    };
    const _rank = function (o) {
      let s = 0;
      if (_onsc(o.next)) s += 2;
      if (_onsc(o.close)) s += 2;
      if (o.next && o.next.on) s += 1;
      if (o.close && o.close.on) s += 1;
      if (o.score && o.score !== "?" && o.score !== "null") s += 1;
      return s;
    };
    const _A = _readSet(0x108, 0x110);      // PC 那套偏移（实测常是"屏外/隐藏"那份）
    const _B = _readSet(0x248, 0x250);      // 实测"活的"那份 ✓
    const _useB = _rank(_B) > _rank(_A);
    const _use = _useB ? _B : _A;
    res.cands.next = _use.next;
    res.cands.close = _use.close;
    res.text.score = _use.score;
    res.text.diffNum = _use.diffNum;
    res.text.scoreRate = _use.scoreRate;
    res.text.point = _use.point;
    res.text.enhance = _use.enhance;
    res.text.coin = _use.coin;
    res.offsets = _useB ? "0x248/0x250" : "0x108/0x110";     // 证据：这次用的是哪一套
    // 选"当前该点的那颗"：子视图**激活**的优先（①②两屏各一个）；都判不出就按 ①→② 顺序取有坐标的
    const nx = res.cands.next, cl = res.cands.close;
    const has = function (c) { return c && c.x !== null && c.y !== null; };
    let pick = null;
    if (has(nx) && nx.on) pick = "next";
    else if (has(cl) && cl.on) pick = "close";
    else if (has(nx)) pick = "next";
    else if (has(cl)) pick = "close";
    if (pick) { res.which = pick; res.x = res.cands[pick].x; res.y = res.cands[pick].y; res.ok = true; }
    else res.err = res.err || "两个子视图都没读到按钮坐标";
    res.src = _src; res.ms = Date.now() - _t0;      // ★实例来源/耗时（早退也要带上，见 [labShopSnapshot]）
    return res;
  } catch (e) { res.err = "" + e; res.src = _src; res.ms = Date.now() - _t0; return res; }
}

// ── ★结算奖励（2026-10-18，LabyrinthTempData 内存字段；不读奖励页 UI，快且稳）──────
//   LTD（dump.cs TypeDefIndex 21014）:
//     +0xB8  QuestFinishRewardList       : List<InventoryInfo>
//     +0x140 ResultInfo                  : LabyrinthResultInfo（各分项 + 迷宫点/强化点/迷宫币）
//     +0x148 NormalTreasureBoxRewardList : List<LabyrinthTreasureBoxResult>
//     +0x150 RareTreasureBoxRewardList   : List<LabyrinthTreasureBoxResult>
//     +0x158 ResultChallengeRewardList   : List<InventoryInfo>
//     +0x168 RewardInfoList              : List<InventoryInfo>
//   InventoryInfo(2730): Id=ObscuredInt@0x10 / Type=eInventoryType@0x1C / Count=ObscuredInt@0x20
//   LabyrinthTreasureBoxResult(3023): RewardList: List<InventoryInfo>@0x10
//   LabyrinthResultInfo(14220): UnitScore@0x10 RelicScore@0x14 MoveScore@0x18
//     BattleNormal@0x1C BattleHard@0x20 BattleBoss@0x24 ResultScore@0x28
//     LabyrinthPoint@0x2C EnhancePoint@0x34 LabyrinthCoin@0x40(InventoryInfo)
function _invInfo(p) {
  if (!ptrOk(p)) return null;
  try { return { id: decOI(p.add(0x10)), type: p.add(0x1C).readS32(), count: decOI(p.add(0x20)) }; }
  catch (e) { return null; }
}
function _invList(lp) {
  const out = [];
  if (!ptrOk(lp)) return out;
  let n = 0; try { n = _listCount(lp); } catch (e) { n = 0; }
  for (let i = 0; i < n; i++) {
    const v = _invInfo(_listItem(lp, i));
    if (v) out.push(v);
  }
  return out;
}
function _boxList(lp) {
  const out = [];
  if (!ptrOk(lp)) return out;
  let n = 0; try { n = _listCount(lp); } catch (e) { n = 0; }
  for (let i = 0; i < n; i++) {
    const box = _listItem(lp, i);
    if (!ptrOk(box)) continue;
    out.push(_invList(box.add(0x10).readPointer()));
  }
  return out;
}
function labRewardsSnapshot() {
  const res = { ok: false, err: null, result: null,
                questFinish: [], challenge: [], rewardInfo: [], normalBox: [], rareBox: [] };
  try {
    const ltd = _findLTD();
    if (!ltd) { res.err = "未定位 LabyrinthTempData"; return res; }
    const ri = ltd.add(0x140).readPointer();
    if (ptrOk(ri)) {
      const r = {};
      try {
        r.unitScore = ri.add(0x10).readS32();
        r.relicScore = ri.add(0x14).readS32();
        r.moveScore = ri.add(0x18).readS32();
        r.battleNormal = ri.add(0x1C).readS32();
        r.battleHard = ri.add(0x20).readS32();
        r.battleBoss = ri.add(0x24).readS32();
        r.resultScore = ri.add(0x28).readS32();
        r.point = ri.add(0x2C).readS32();
        r.enhance = ri.add(0x34).readS32();
        r.coin = _invInfo(ri.add(0x40).readPointer());
      } catch (e) {}
      res.result = r;
    }
    res.questFinish = _invList(ltd.add(0xB8).readPointer());
    res.challenge = _invList(ltd.add(0x158).readPointer());
    res.rewardInfo = _invList(ltd.add(0x168).readPointer());
    res.normalBox = _boxList(ltd.add(0x148).readPointer());
    res.rareBox = _boxList(ltd.add(0x150).readPointer());
    res.ok = true;
    return res;
  } catch (e) { res.err = "" + e; return res; }
}

// 本局"已持有遗物 + 公会"（动态算商店池/概率用）：
//   LabyrinthTempData+0x1C GuildId
//   LabyrinthTempData+0x38 OwnRelicInfoList: List<LabyrinthRelicInfo>
//     LabyrinthRelicInfo: RelicId@0x10 / EffectedCount@0x1C / Enable@0x28
function labOwnRelicsSnapshot() {
  const res = { guildId: null, relics: [], err: null };
  try {
    const ltd = _findLTD();
    if (!ltd) { res.err = "未定位 LabyrinthTempData"; return res; }
    try { res.guildId = ltd.add(0x1C).readS32(); } catch (e) {}
    const list = ltd.add(0x38).readPointer();
    if (!ptrOk(list)) { res.err = "OwnRelicInfoList 为空"; return res; }
    const n = _listCount(list);
    for (let i = 0; i < n; i++) {
      const it = _listItem(list, i);
      if (!ptrOk(it)) continue;
      res.relics.push({ relicId: decOI(it.add(0x10)), count: decOI(it.add(0x1C)) });
    }
    res.err = null;
  } catch (e) { res.err = "" + e; }
  return res;
}

// 商店读取失败的现场诊断（Python 在读到 0 商品时自动调用一次）：
//   ① 列出所有 DialogLabyrinthShop 候选实例各自的关键字段（哪个是"活的"一眼可见）
//   ② 把**第一个候选的运行时字段表**打出来（inspect）—— dump.cs 的偏移若过期，这里立刻暴露真偏移
function labShopDiag() {
  const out = { scanned: 0, cands: [], err: null };
  try {
    const k = findClass("DialogLabyrinthShop");
    if (!k || k.isNull()) { out.err = "未找到 DialogLabyrinthShop 类"; return out; }
    const insts = _scanKlass(k, 12);
    out.scanned = insts.length;
    for (const d of insts) {
      const row = { p: String(d) };
      try {
        const ctl = d.add(0x100).readPointer();
        row.ctl = ptrOk(ctl) ? klassNameOf(ctl) : "null";
        if (ptrOk(ctl)) {
          const o2 = ctl.add(0x38).readPointer();
          row.groups = ptrOk(o2) ? _listCount(o2) : "list=null";
          if (ptrOk(o2) && row.groups > 0) {
            const g0 = _listItem(o2, 0);
            row.g0n = ptrOk(g0) ? _listCount(g0) : "null";
          }
        }
      } catch (e) { row.err = "" + e; }
      try { const lb = d.add(0x118).readPointer(); row.coin = ptrOk(lb) ? _str1(_inv(lb, "get_text")) : "null"; } catch (e) {}
      try { row.resetCost = d.add(0x170).readS32(); } catch (e) {}
      out.cands.push(row);
    }
    if (insts.length) {                       // ① 字段表（会打到 console）
      try { inspect(insts[0]); } catch (e) {}
      try { const ctl = insts[0].add(0x100).readPointer(); if (ptrOk(ctl)) inspect(ctl); } catch (e) {}
    }
  } catch (e) { out.err = "" + e; }
  return out;
}

// 找到"当前活跃的 ViewLabyrinthMap"（与 LTD 挂接的那个）。缓存 g_vm，失效才重扫。
// ★失败退避（2026-10-12 性能修复）：找不到活跃 VM 时，这里**每次都会把匿名堆全扫一遍**
//   （enumerateRanges "r--" + 每段 scanSync，1~2s 级）；而地图页每轮都会问一次 ⇒ 一旦持续
//   找不到就是每轮白扫。改成：扫失败后 2s 内直接返回 null 不再重扫（扫到就清零）。
var g_vmFailAt = 0;
const VM_SCAN_BACKOFF_MS = 2000;
function _findVM(ltd) {
  try {
    if (g_vm && !g_vm.isNull()) {
      const ld = g_vm.add(0xF8).readPointer();
      const parts = g_vm.add(0xD0).readPointer();
      if (!ld.isNull() && ld.equals(ltd) && !parts.isNull()) return g_vm;
    }
  } catch (e) {}
  // ★★② 先试 ViewManager.currentView（**零扫描**，2026-10-17）：在迷宫地图页时 `currentView` 就是
  //   `ViewLabyrinthMap` ⇒ 一条指针链（`ViewManager.Instance +0x110`）就能拿到，不必扫匿名堆
  //   （那种扫一次 1~2s ✗，是 `[slow] labNodes 980~1790ms` 的主要嫌疑）。校验与老路一致（`+0xF8`==LTD ✓）。
  try {
    const vmgr = _viewManagerInst();
    if (vmgr && !vmgr.isNull()) {
      const cv = vmgr.add(0x110).readPointer();
      if (ptrOk(cv)) {
        const vn = _safeKlassOf(cv);
        if (vn && vn.indexOf("ViewLabyrinthMap") >= 0) {
          const ld2 = cv.add(0xF8).readPointer();
          const parts2 = cv.add(0xD0).readPointer();
          if (!ld2.isNull() && ld2.equals(ltd) && !parts2.isNull()) {
            g_vm = cv; g_vmFailAt = 0;
            return cv;
          }
        }
      }
    }
  } catch (e) {}
  if (Date.now() - g_vmFailAt < VM_SCAN_BACKOFF_MS) return null;   // ★刚扫过没找到 → 别再来
  g_vmScans++;                    // ★真的要扫了 → 记一次（Python 侧靠它出声：1~2s 级的重活）
  const _vmT0 = Date.now();
  const vmk = findClass("ViewLabyrinthMap");
  if (!vmk || vmk.isNull()) return null;
  const kb = vmk.toString(16).padStart(16, "0").match(/../g).reverse().join(" ");
  for (const r of Process.enumerateRanges("r--").filter(x => !x.file && x.size >= 0x1000).sort((a, b) => b.size - a.size)) {
    try {
      const ms = Memory.scanSync(r.base, r.size, kb);
      for (const m of ms) {
        const a = m.address;
        try {
          if (!objIsClass(a, vmk)) continue;
          const ld = a.add(0xF8).readPointer();
          const parts = a.add(0xD0).readPointer();
          if (!ld.isNull() && ld.equals(ltd) && !parts.isNull()) {
            g_vm = a; g_vmFailAt = 0;
            console.log("[nodes] 扫匿名堆找到 ViewLabyrinthMap（第 " + g_vmScans + " 次，耗时 " + (Date.now() - _vmT0) + "ms）");
            return a;
          }
        } catch (e) {}
      }
    } catch (e) {}
  }
  g_vmFailAt = Date.now();          // ★记下"扫过没找到"的时刻 → 2s 内不再重扫
  console.log("[nodes] 扫匿名堆**未命中**（第 " + g_vmScans + " 次，耗时 " + (Date.now() - _vmT0) + "ms）⇒ 2s 内不再扫");
  return null;
}

// 读地图上的"可视节点面板"：走游戏自己的 PartsLabyrinthMap.visualPanelList(+0x70)，不用堆扫描。
// panelState(ePanelState)：0=SELECTED_NOW_SECTOR(当前所在) 1=NOT_SELECTED_NOW 2=SELECTED_OLD
//                         3=NOT_SELECTED_OLD 4=SELECTABLE_FUTURE_SECTOR(可前往!) 5=NOT_SELECTABLE_FUTURE
// 字段：panel.PanelData(+0x70) -> mapInfo(+0x10) -> BlockId(ObscuredInt@0x34) / BlockType(@0x40)；panelState@0x90
// ★地图节点快照的 TTL（2026-10-16 哨兵实锤 `[slow] labNodes 872~880ms`，而地图没动时每轮重读没意义）：
//   1.5s 内复用；**只在读到非空时才缓存**（空结果是"还没读到"，不该被缓存住）。
let g_nodesCache = null, g_nodesAt = 0;
const NODES_TTL_MS = 1500;
function labNodesSnapshot(diag) {
  const res = [];
  const ltd = _findLTD();
  if (!ltd) { if (diag) diag.err = "未定位 LabyrinthTempData（不在迷宫？）"; return res; }
  const vm = _findVM(ltd);
  if (!vm) { if (diag) diag.err = "未找到活跃 ViewLabyrinthMap"; return res; }
  const partsMap = vm.add(0xD0).readPointer();
  if (!ptrOk(partsMap)) { if (diag) diag.err = "PartsLabyrinthMap 为空"; return res; }
  const listObj = partsMap.add(0x70).readPointer();
  if (!ptrOk(listObj)) { if (diag) diag.err = "visualPanelList 为空"; return res; }
  const count = _listCount(listObj);
  if (diag) diag.count = count;
  if (count < 0 || count > 256) { if (diag) diag.err = "visualPanelList 长度异常=" + count; return res; }
  const rtu = findClass("RectTransformUtility");
  const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
  const cam = _uiCamera();
  if (cam.isNull() || !mW2S) { if (diag) diag.err = "无相机/换算方法"; return res; }
  let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
  for (let i = 0; i < count; i++) {
    const panel = _listItem(listObj, i);
    if (panel.isNull()) continue;
    const pd = panel.add(0x70).readPointer();
    if (!ptrOk(pd)) continue;
    const mi = pd.add(0x10).readPointer();
    if (!ptrOk(mi)) continue;
    const st0 = panel.add(0x90).readS32();
    // ★只给"用得到坐标"的面板算屏幕坐标（2026-10-17 性能）：`_screenOf` 是 ~3 次 invoke **每个面板**，
    //   而一张地图 70~80 个面板里绝大多数是"已走过/不可前往"（1/2/3/5）⇒ 全算一遍纯浪费（实测
    //   `[slow] labNodes 980~1790ms` 的一部分）。Kotlin 侧**只用** `panelState==4`（可前往）和
    //   `==0`（当前所在，日志用）这几颗的 x/y，其余状态给 null ✓（`Route.pickNode` 本来就要求 x!=null + state==4 ✓）。
    const needXY = (st0 === 0 || st0 === 4);
    let x = null, y = null;
    if (needXY) {
      const ss = _screenOf(panel, cam, mW2S);
      if (!ss) continue;
      x = Math.round(ss.x); y = Math.round(H - ss.y);
    }
    res.push({
      blockId: decOI(mi.add(0x34)), blockType: mi.add(0x40).readS32(),
      panelState: st0, x: x, y: y,
    });
  }
  res.sort((a, b) => a.blockId - b.blockId);
  return res;
}

const LAB_PANEL_STATE = { 0: "NOW", 1: "NOW_OFF", 2: "OLD", 3: "OLD_OFF", 4: "GO★", 5: "FUTURE_OFF" };

globalThis.labNodes = function () {
  if (!g_il2cpp) { console.log("[nd] il2cpp 未就绪"); return null; }
  const diag = {};
  const ns = labNodesSnapshot(diag);
  console.log("[nd] ViewId=" + getViewId() + "（264=迷宫地图）"
    + "  visualPanelList 长度=" + (diag.count == null ? "?" : diag.count)
    + "  输出=" + ns.length + (diag.err ? "  错误=" + diag.err : ""));
  for (const n of ns) {
    console.log("[nd]   B" + n.blockId + " " + (BLOCK_TYPE[n.blockType] || ("?" + n.blockType))
      + " 状态=" + (LAB_PANEL_STATE[n.panelState] || ("?" + n.panelState)) + "  点它=(" + n.x + "," + n.y + ")");
  }
  return ns;
};

// ★★通用弹窗按钮"学一次、以后直达"（2026-10-17，用户："通用弹窗「确认」(OneButtonWhite…) 这个也许能学"）──
//   为什么要学：`DialogArea` 子树遍历在小弹窗上几十 ms、在**大弹窗**（`DialogCommonFullSize`/`PartyEdit`）
//   实测 ≈900ms/轮；而 `DialogCommon*` 这类"结果/确认页"**不在 Kotlin 的 ownPage 节流名单里** ⇒ 每轮
//   (100ms) 都查一次 ⇒ 弹窗停留 2s 要遍历十几次，而且"点确认"被卡在遍历返回之后（= 点得慢的成因之一）。
//   学什么：按钮的**索引路径**（`DialogArea → … → OneButtonWhite`，每级记「名字 + GetSiblingIndex」）。
//     · 不记坐标：坐标随分辨率/布局变；
//     · 不记组件指针：对话框每次是 `(Clone)`，关掉后旧指针多半已销毁；
//     · 记索引路径：每次重新 descend，**逐级校验名字**（任一级不符立即放弃 ⇒ 回退遍历）。
//   ⇒ 命中成本 = 每级 2 次调用（`GetChild` + `get_name`），一条链 3~5 级 ≈ 十几次调用
//     （对比遍历几百个节点、上千次调用）。语义与现在**完全等价**：仍要求节点名逐级一致
//     （= 白名单那套"认得出才点"），不会出现"同一位置换了颗「取消」"的误点。
var g_dlgLearn = [];                 // [{key, dlg, name, chain:[{n,i}]}] —— 会话内有效，重挂脚本即清空
const DLG_LEARN_MAX = 8;             // 最多记几条（每种弹窗的按钮集各一条）
// ★是否**使用**学习结果（false = 只记不用，见 _dlgTryLearned 开头的事故说明）
const DLG_LEARN_USE = false;

function _dlgNorm(s) { return String(s || "").replace(/\(Clone\)$/, ""); }

// 从节点往上攒"索引路径"（到 rootTr 为止，不含 rootTr）。返回 null = 信息不全（放弃学习）。
function _trChain(tr, rootTr) {
  const chain = [];
  try {
    let cur = tr;
    for (let i = 0; i < 16; i++) {
      if (!ptrOk(cur)) return null;
      if (rootTr && ptrOk(rootTr) && String(cur) === String(rootTr)) break;
      let idx = -1, nm = "";
      try { const r = _inv(cur, "GetSiblingIndex"); idx = r.isNull() ? -1 : r.add(0x10).readS32(); } catch (e) {}
      try { nm = _str(_inv(_inv(cur, "get_gameObject"), "get_name")); } catch (e) {}
      if (idx < 0 || !nm || nm === "?" || nm === "null") return null;
      chain.unshift({ n: nm, i: idx });
      cur = _inv(cur, "get_parent");
    }
  } catch (e) { return null; }
  return chain.length ? chain : null;
}

// 按学到的路径从 rootTr 逐级 descend，**每级校验名字 + active**；任一不符 ⇒ null（调用方回退遍历，绝不硬点）
// ★active 校验是 2026-10-17 事故后补的：Unity 里**隐藏（inactive）节点照样有 transform、照样能算出
//   "屏内坐标"** ⇒ 只校验名字会拿到"幽灵按钮"（那次连点 40+ 次 (960,666) 就是这么来的）。
function _trByChain(rootTr, chain) {
  let cur = rootTr;
  // ★第 1 级按**名字**在子节点里找（2026-10-20）：DialogArea 下多个弹窗实例的**堆叠顺序会变**
  //   （16480 09:48:20 实测 Small/Big/Middle 三条链同时失效 → 全部重学 3s 级），按索引 descend
  //   顺序一变就整批作废；第 1 级改为按名字定位后不受顺序影响。第 2 级起结构稳定，仍按 {n,i}。
  let start = 0;
  try {
    const c0 = chain[0];
    if (c0) {
      let n = -1;
      try { n = _inv(cur, "get_childCount").add(0x10).readS32(); } catch (e) {}
      for (let i = 0; i < (n > 0 ? n : 0); i++) {
        const ch = _inv1i(cur, "GetChild", i);
        if (ch.isNull()) continue;
        const go2 = _inv(ch, "get_gameObject");
        if (!ptrOk(go2)) continue;
        if (_str(_inv(go2, "get_name")) === c0.n) {
          if (_goActive(go2) !== true) return null;      // 名字对上但隐藏 → 幽灵，回退遍历
          cur = ch; start = 1; break;
        }
      }
      if (start === 0) return null;                      // 第 1 级名字都找不到 → 失效
    }
  } catch (e) { return null; }
  try {
    for (let i = start; i < chain.length; i++) {
      if (!ptrOk(cur)) return null;
      const ch = _inv1i(cur, "GetChild", chain[i].i);
      if (ch.isNull()) return null;
      const go2 = _inv(ch, "get_gameObject");
      if (!ptrOk(go2)) return null;
      if (_str(_inv(go2, "get_name")) !== chain[i].n) return null;
      if (_goActive(go2) !== true) return null;      // ★隐藏节点不算
      cur = ch;
    }
    return ptrOk(cur) ? cur : null;
  } catch (e) { return null; }
}

function _dlgLearnPut(dlg, name, chain) {
  try {
    const key = _dlgNorm(dlg) + "|" + chain.map(function (c) { return c.n + "#" + c.i; }).join("/");
    for (let i = 0; i < g_dlgLearn.length; i++) if (g_dlgLearn[i].key === key) return false;
    g_dlgLearn.unshift({ key: key, dlg: dlg, name: name, chain: chain });
    while (g_dlgLearn.length > DLG_LEARN_MAX) g_dlgLearn.pop();
    return true;
  } catch (e) { return false; }
}

// 试"学到的路径"：命中 ⇒ 直接给坐标（零遍历）；否则 null ⇒ 回退遍历
function _dlgTryLearned(topCls, use) {
  try {
    // ★★使用条件（2026-10-17 事故后收紧，2026-10-20 重开）：
    //   首版只校验了"名字路径"，没校验"节点此刻 active/到底是不是当前那张对话框上的节点"，
    //   于是 `Navigator.dialogConfirmButton()` 那条**不带 topCls** 的 `dialog()` 调用也走了学习
    //   路径，拿到一颗**同名幽灵节点** ⇒ 无限点 (960,666)。
    //   ⇒ 现在 [use] 由调用方传：**只有带 topCls 的调用才允许 true**；且逐级校验 active
    //     （_trByChain）与顶框类名匹配都在。事故两条根因分别被这两条堵死。
    if (!use) return null;
    if (!g_dlgLearn.length) return null;
    const top = _dlgNorm(topCls);
    if (!top) return null;                       // ★不带 topCls（如 Navigator 那条）一律不用学习结果
    const rootTr = _dialogAreaTr();
    if (!rootTr) return null;
    let env = null, group = null, groupKey = null;
    for (let i = 0; i < g_dlgLearn.length; i++) {
      const e = g_dlgLearn[i];
      if (top && _dlgNorm(e.dlg) !== top) continue;          // 顶框类名对不上 ⇒ 不是这类弹窗
      const tr = _trByChain(rootTr, e.chain);
      if (!tr) continue;
      // 只取"同一个对话框节点"那组（同一弹窗的多颗按钮一起返回，语义与遍历一致）
      const gk = _dlgNorm(e.chain[0].n) + "#" + e.chain[0].i;
      if (groupKey === null) { groupKey = gk; group = []; }
      if (gk !== groupKey) continue;
      if (!env) { env = _camEnv(); if (!env) return null; }
      const p = _ndXY({ tr: tr }, env);
      if (!p || !p.onscreen) continue;
      group.push({ name: e.name, x: p.x, y: p.y });
      if (group.length >= 4) break;
    }
    if (!group || !group.length) return null;
    group.sort(function (a, b) { return a.x - b.x; });
    return { open: true, buttons: group, darkFilters: 0, learned: true };
  } catch (e) { return null; }
}

// 弹窗检测：列出当前"屏内+激活"的通用对话框按钮（One/Two/ThreeButton*）及暗色遮罩数量。
// 用途：NONE 状态下若有弹窗，必须先关掉再前进；单按钮直接点，多按钮由调用方按名单挑"确定"。
// ★小 TTL 缓存（2026-10-12 性能修复）：DialogArea 子树遍历也不便宜（编组页那种 ~900ms/轮）。
//   180ms 内的重复询问直接复用 —— 点完按钮后 Python 会 sleep 0.7s 再读，不会拿"点之前"的旧状态。
// ★`topCls` 可选（2026-10-17）：调用方把 `pageTop` 传进来，学习路径会额外比对"顶框类名"。
var g_dlgSnapCache = null, g_dlgSnapAt = 0;
const DLG_SNAP_TTL_MS = 180;
function dialogButtonsSnapshot(topCls, knownChains) {
  const _now = Date.now();
  // ★学习/使用总开关（2026-10-20）：**只有带 topCls 的调用**才允许（2026-10-17 事故保险，
  //   见 _dlgTryLearned 注释）。回喂的持久化链也走这里（Kotlin 从 LabBtnStore 传进来）。
  const canLearn = !!topCls;
  if (canLearn && knownChains && knownChains.length != null) {
    for (let i = 0; i < knownChains.length; i++) {
      const it = knownChains[i];
      if (!it) continue;
      const chain = [];
      const ca = it.chain || [];
      for (let j = 0; j < ca.length; j++) chain.push({ n: ca[j].n, i: ca[j].i });
      if (chain.length) _dlgLearnPut(it.dlg || _dlgNorm(topCls), it.name, chain);
    }
  }
  if (g_dlgSnapCache && (_now - g_dlgSnapAt) < DLG_SNAP_TTL_MS) return g_dlgSnapCache;
  // ① 先试"学到的索引路径"（零遍历，见本节开头）
  const learned = _dlgTryLearned(topCls, canLearn);
  if (learned) { g_dlgSnapCache = learned; g_dlgSnapAt = _now; return learned; }
  // ② 回退：DialogArea 子树遍历（顺手把遇到的按钮路径学下来）
  const buttons = []; let darks = 0;
  const reBtn = /^(One|Two|Three)Button(Left|Right|Center|White|Blue)?$/;
  const reDark = /^PartsDialogDarkFilter/;
  let env = null;
  const freshLearned = [];                 // ★本次遍历新学到的链 → 回传 Kotlin 落盘
  try {
    // ★只走 DialogArea 子树（几十个节点），不再全树遍历（661 节点 ≈ 1.8s）：
    //   可点的通用按钮（One/Two/ThreeButton*）只存在于对话框里，没必要扫整棵树。
    //   万一 DialogArea 定位不到（不在迷宫/异常），退回原来的全树遍历（语义不变）。
    const rootTr = _dialogAreaTr();
    const useRoot = rootTr || null;
    const cb = function (nd) {
      if (nd.active !== true) return;
      if (reDark.test(nd.name)) { darks++; return; }   // 遮罩只要计数，不用算坐标
      if (!reBtn.test(nd.name)) return;
      if (!env) { env = _camEnv(); if (!env) return; }
      const p = _ndXY(nd, env);
      if (p && p.onscreen) buttons.push({ name: nd.name, x: p.x, y: p.y });
      // ★学习：记下这颗按钮的"索引路径"（下次同类弹窗直接 descend，不再遍历）
      //   ⚠只在带 topCls 的调用才记（canLearn）——回传给 Kotlin 落盘后跨局回喂。
      if (canLearn && useRoot) {
        const ch2 = _trChain(nd.tr, useRoot);
        if (ch2 && ch2.length) {
          // ★_dlgLearnPut 返回是否**真的新增**（2026-10-20 修：之前用"数组长度/顶部名字"判断，
          //   已存在的条目被误判成新学 ⇒ "已学成落盘"每轮重复刷，16480 11:04 局 Middle 刷了 18 次）
          if (_dlgLearnPut(ch2[0].n, nd.name, ch2)) {
            freshLearned.push({ dlg: ch2[0].n, name: nd.name, chain: ch2 });
          }
        }
      }
    };
    if (useRoot) _rawWalk(cb, false, useRoot);
    else { for (const nd of _walkNodes()) cb(nd); }     // 退回：复用 find/findList 的共享遍历
  } catch (e) {}
  buttons.sort((a, b) => a.x - b.x);
  g_dlgSnapCache = {
    open: buttons.length > 0, buttons: buttons, darkFilters: darks, learned: false,
    learnedList: canLearn ? freshLearned : [],
  };
  g_dlgSnapAt = _now;
  return g_dlgSnapCache;
}

globalThis.dialogBtns = function () {
  if (!g_il2cpp) { console.log("[dlg] il2cpp 未就绪"); return null; }
  const r = dialogButtonsSnapshot();
  console.log("[dlg] 通用对话框按钮数=" + r.buttons.length + "  暗色遮罩=" + r.darkFilters);
  for (const b of r.buttons) console.log("[dlg]   " + b.name + "  (" + b.x + "," + b.y + ")");
  return r;
};

// EVENT_CHOICE：读 DialogLabyrinthEvent 的选项卡。
// eventId@0x170  currentSelection@0x174  partsEventView@0x140
//   PartsLabyrinthSelectionEventView.eventSelectionPartsList@0x30 = List<PartsSelectionEventInfo>（有序）
//     PartsSelectionEventInfo: commentLabel@0x48 resultLabel@0x50 selectButton(CustomUIButton)@0x80
// 实例缓存校验：这个地址现在还"像个活的"事件对话框吗（eventId>0 且候选表长度合法）
function _evPickValid(d) {
  try {
    if (d.add(0x170).readS32() <= 0) return false;
    const view = d.add(0x140).readPointer();
    if (!ptrOk(view)) return false;
    const list = view.add(0x30).readPointer();
    if (!ptrOk(list)) return false;
    const n = _listCount(list);
    return (n > 0 && n <= 8);
  } catch (e) { return false; }
}

function labEventSnapshot(diag) {
  const res = { eventId: null, currentSelection: null, cards: [] };
  const k = findClass("DialogLabyrinthEvent");
  if (!k || k.isNull()) { if (diag) diag.err = "未找到 DialogLabyrinthEvent 类"; return res; }
  if (diag) diag.cls = String(k);
  const rtu = findClass("RectTransformUtility");
  const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
  const cam = _uiCamera();
  if (cam.isNull() || !mW2S) { if (diag) diag.err = "无相机/换算方法"; return res; }
  let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
  // 不依赖 activeInHierarchy（对 MonoBehaviour 不可靠）：
  // 判据 = eventId>0（只有 Setup 过的才是活的）+ partsEventView.eventSelectionPartsList 长度合法
  const _hitEv = diag ? null : _instCacheGet("labEvent", "DialogLabyrinthEvent", _evPickValid);
  // ★★先走 DialogArea（2026-10-17，同商店/选择页那套）：事件对话框**就在 DialogArea 里**
  //   （`pageTop` 就是 `DialogLabyrinthEvent`）⇒ 一条 `GetComponentInChildren` 几十 ms ✓；
  //   而原来第一条路是 `_scanKlass(k, 256)` = **全堆按类名扫描**（实测 2~3s ✗，事件页每 8s 就来一个）
  //   ⇒ 那正是把并发便宜调用（`viewId`/`gameTick`）挤超时的重活之一。
  //   扫堆降级为**兜底**：DialogArea 拿到的不是活实例（`eventId<=0`）时才扫。
  let insts = _hitEv ? [_hitEv] : null;
  let _fromArea = false;
  if (!insts) {
    const cd = _compInDialogs(k);
    if (ptrOk(cd)) { insts = [cd]; _fromArea = true; }
  }
  if (diag) { diag.scanned = insts ? insts.length : 0; diag.cached = !!_hitEv; diag.inited = 0; diag.cands = 0; }
  const _collect = function (list0, cds) {
    for (const d of list0) {
      try {
        const eid = d.add(0x170).readS32();
        if (eid <= 0) continue;
        if (diag) diag.inited++;
        const view = d.add(0x140).readPointer();
        if (!ptrOk(view)) continue;
        const list = view.add(0x30).readPointer();
        if (!ptrOk(list)) continue;
        const n = _listCount(list);
        if (n <= 0 || n > 8) continue;
        cds.push({ d: d, eid: eid, list: list, n: n });
      } catch (e) {}
    }
  };
  const cands = [];
  if (insts) _collect(insts, cands);
  if (!cands.length && _fromArea && _scanGate("event")) {   // ★DialogArea 那份不是活实例 ⇒ 才扫堆（兜底，受闸门约束）
    // ★同 [_scanGate]（2026-10-17）：事件对话框刚弹出时 `eventId<=0` 是**正常**（还没 Setup），
    //   此处原来立刻全堆扫描（2~3s ✗）⇒ 现在等够 10s 才扫一次。
    if (diag) diag.scanFallback = true;
    _collect(_scanKlass(k, 256), cands);
  }
  if (cands.length) _scanGateHit("event");
  if (diag) diag.cands = cands.length;
  if (_hitEv && !cands.length) _instCacheDel("labEvent");    // 缓存实例已失效 → 下轮重扫
  if (!cands.length) return res;
  // 优先与 LabyrinthStatus.eventId 对齐，其次取第一个
  let want = null;
  try {
    const ltd = _findLTD();
    if (ltd) { const st = ltd.add(0xE8).readPointer(); if (ptrOk(st)) want = decOI(st.add(0x34)); }
  } catch (e) {}
  const pickd = cands.find(c => c.eid === want) || cands[0];
  if (!_hitEv) _instCachePut("labEvent", pickd.d);           // 首次扫描选出 → 记下来给后续轮次
  res.eventId = pickd.eid;
  res.currentSelection = pickd.d.add(0x174).readS32();
  for (let i = 0; i < pickd.n && i < 3; i++) {
    const it = _listItem(pickd.list, i);
    if (!ptrOk(it)) continue;
    let bx = null, by = null;
    try {
      const btn = it.add(0x80).readPointer();                 // selectButton(CustomUIButton)
      if (ptrOk(btn)) { const ss = _screenOf(btn, cam, mW2S); if (ss) { bx = Math.round(ss.x); by = Math.round(H - ss.y); } }
    } catch (e) {}
    const cl = it.add(0x48).readPointer();                    // commentLabel
    const rl = it.add(0x50).readPointer();                    // resultLabel
    res.cards.push({
      i: i, btnX: bx, btnY: by,
      comment: ptrOk(cl) ? _str1(_inv(cl, "get_text")) : "",
      result: ptrOk(rl) ? _str1(_inv(rl, "get_text")) : "",
    });
  }
  return res;
}

globalThis.labEvent = function () {
  if (!g_il2cpp) { console.log("[ev] il2cpp 未就绪"); return null; }
  const diag = {};
  const r = labEventSnapshot(diag);
  console.log("[ev] ViewId=" + getViewId() + "  类=" + (diag.cls || "?")
    + "  扫描实例=" + (diag.scanned == null ? "?" : diag.scanned)
    + "  eventId>0 的=" + (diag.inited == null ? "?" : diag.inited)
    + "  有效候选=" + (diag.cands == null ? "?" : diag.cands)
    + (diag.err ? "  错误=" + diag.err : ""));
  console.log("[ev] eventId=" + r.eventId + " currentSelection=" + r.currentSelection + " 选项卡数=" + r.cards.length);
  for (const c of r.cards) {
    console.log("[ev]   #" + (c.i + 1) + " 点它=(" + c.btnX + "," + c.btnY + ")  comment=\"" + c.comment + "\"  result=\"" + c.result + "\"");
  }
  return r;
};

const BtnishRe = /btn|button|close|cancel|confirm|decide|select|choice|tab|关|确|取消|按/i;
function _goActive(gobj) { try { const r = _inv(gobj, "get_activeInHierarchy"); if (r.isNull()) return null; return r.add(0x10).readU8() !== 0; } catch (e) { return null; } }
globalThis.findAll = function (filter, onlyVisible) {
  if (!g_il2cpp) { console.log("[all] il2cpp 未就绪"); return; }
  const vm = _viewManagerInst();
  if (vm.isNull()) { console.log("[all] 未取到 ViewManager"); return; }
  const cv = vm.add(0x110).readPointer();
  if (cv.isNull()) { console.log("[all] currentView 为空"); return; }
  let re = null;
  if (filter) { try { re = new RegExp(String(filter), "i"); } catch (e) { console.log("[all] 正则无效: " + e); return; } }
  runOnGameThread(function () {
    try {
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      const cam = _uiCamera();
      if (cam.isNull() || !mW2S) { console.log("[all] 无相机/换算方法"); return; }
      let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
      let tr = _inv(cv, "get_transform");
      let p = _inv(tr, "get_parent"); let up = 0;
      while (!p.isNull() && up < 8) { tr = p; p = _inv(tr, "get_parent"); up++; }
      let W = Math.round(H * 16 / 9); try { const pw = _inv(cam, "get_pixelWidth"); if (!pw.isNull()) W = pw.add(0x10).readS32(); } catch (e) {}
      const st = [{ t: tr, d: 0 }]; let n = 0, shown = 0, vis = 0; const out = [];
      while (st.length && n < 12000) {
        const it = st.pop(); const t = it.t; const d = it.d; n++;
        let cc = 0; try { const r = _inv(t, "get_childCount"); cc = r.isNull() ? 0 : r.add(0x10).readS32(); } catch (e) {}
        const kids = []; for (let i = 0; i < cc; i++) { const ch = _inv1i(t, "GetChild", i); if (!ch.isNull()) kids.push(ch); }
        for (let i = kids.length - 1; i >= 0; i--) st.push({ t: kids[i], d: d + 1 });   // 逆序入栈 → 先序 DFS，缩进即真实层级
        const gobj = _inv(t, "get_gameObject");
        if (gobj.isNull()) continue;
        const nm = _str(_inv(gobj, "get_name"));
        const act = _goActive(gobj);
        const ss = _screenOf(t, cam, mW2S);
        const ay = ss ? Math.round(H - ss.y) : null;
        const onscr = ss ? (ss.x >= 0 && ss.x <= W && ay >= 0 && ay <= H) : false;
        if (act === true && onscr) vis++;
        if (re && !re.test(nm)) continue;
        if (onlyVisible && (act !== true || !onscr)) continue;
        let flags = "";
        if (act === false) flags += " [inactive]";
        if (ss && !onscr) flags += " [offscreen]";
        out.push("  ".repeat(Math.min(d, 7)) + (BtnishRe.test(nm) ? "★" : " ") + nm + (ss ? "  (" + Math.round(ss.x) + "," + Math.round(ss.y) + ") [androidY=" + ay + "]" : "") + flags);
        shown++;
      }
      console.log("[all] 屏幕 " + W + "x" + H + "；顶层向上 " + up + " 层；遍历 " + n + " 节点；屏内且激活=" + vis + "；输出 " + shown + " 行（★=名字像按钮）");
      for (const l of out) console.log("[all] " + l);
    } catch (e) { console.log("[all] 错误: " + e); }
  });
};

// 只列"屏内 + 激活 + 名字像按钮"的节点 —— 找弹窗按钮首选。
// findBtn() 用默认正则；findBtn("right|close") 可自定义（正则，忽略大小写）。
globalThis.findBtn = function (filter) {
  const re = filter ? String(filter) : "btn|button|close|cancel|confirm|decide|select|choice|tab|关|确|取消|按";
  console.log("[btn] 正则: " + re + "（仅屏内+激活）");
  return findAll(re, true);
};

// 按 android 坐标反查：列出屏幕位置落在 (ax,ay) 半径 r 内的所有节点名。
// 用法：截图上量到"关闭"按钮大致在 (960, 900) → findAt(960, 900) 就能知道那是什么节点。
globalThis.findAt = function (ax, ay, r) {
  if (!g_il2cpp) { console.log("[at] il2cpp 未就绪"); return; }
  const vm = _viewManagerInst();
  if (vm.isNull()) { console.log("[at] 未取到 ViewManager"); return; }
  const cv = vm.add(0x110).readPointer();
  if (cv.isNull()) { console.log("[at] currentView 为空"); return; }
  const R = (r == null) ? 60 : r;
  runOnGameThread(function () {
    try {
      const rtu = findClass("RectTransformUtility");
      const mW2S = (rtu && !rtu.isNull()) ? _mInfo(rtu, "WorldToScreenPoint") : null;
      const cam = _uiCamera();
      if (cam.isNull() || !mW2S) { console.log("[at] 无相机/换算方法"); return; }
      let H = 1080; try { const ph = _inv(cam, "get_pixelHeight"); if (!ph.isNull()) H = ph.add(0x10).readS32(); } catch (e) {}
      const ux = ax, uy = H - ay;   // androidY = H - unityY
      let tr = _inv(cv, "get_transform");
      let p = _inv(tr, "get_parent"); let up = 0;
      while (!p.isNull() && up < 8) { tr = p; p = _inv(tr, "get_parent"); up++; }
      const q = [tr]; let n = 0, hits = 0;
      while (q.length && n < 12000) {
        const t = q.shift(); n++;
        let cc = 0; try { const rr = _inv(t, "get_childCount"); cc = rr.isNull() ? 0 : rr.add(0x10).readS32(); } catch (e) {}
        for (let i = 0; i < cc; i++) { const ch = _inv1i(t, "GetChild", i); if (!ch.isNull()) q.push(ch); }
        const gobj = _inv(t, "get_gameObject");
        if (gobj.isNull()) continue;
        const ss = _screenOf(t, cam, mW2S);
        if (!ss) continue;
        if (Math.abs(ss.x - ux) <= R && Math.abs(ss.y - uy) <= R) {
          const nm = _str(_inv(gobj, "get_name"));
          console.log("[at] ★ " + nm + "  (" + Math.round(ss.x) + "," + Math.round(ss.y) + ") [androidY=" + Math.round(H - ss.y) + "]  Δ=(" + Math.round(ss.x - ux) + "," + Math.round(H - ss.y - ay) + ")");
          hits++;
        }
      }
      console.log("[at] 目标 android(" + ax + "," + ay + ") 半径 " + R + " → 命中 " + hits + " / 遍历 " + n);
    } catch (e) { console.log("[at] 错误: " + e); }
  });
};

// 从 currentView 的 transform 向上走到最顶层 UI 根，再全量遍历（用于找底部导航栏等持久元素）
globalThis.rootInfo = function () {
  if (!g_il2cpp) { console.log("[view] il2cpp 未就绪"); return; }
  const vm = _viewManagerInst();
  if (vm.isNull()) { console.log("[view] 未取到 ViewManager"); return; }
  const cv = vm.add(0x110).readPointer();
  if (cv.isNull()) { console.log("[view] currentView 为空"); return; }
  runOnGameThread(function () {
    try {
      let tr = _inv(cv, "get_transform");
      if (tr.isNull()) { console.log("[view] 无 transform"); return; }
      let p = _inv(tr, "get_parent"); let up = 0;
      while (!p.isNull() && up < 8) { tr = p; p = _inv(tr, "get_parent"); up++; }
      console.log("[view] 顶层根向上 " + up + " 层");
      _dumpNodes(tr, "[root]");
    } catch (e) { console.log("[view] 错误: " + e); }
  });
};

// ══════════════════════════════════════════════════════════════════════════════
// ★RPC 耗时哨兵（2026-10-16 用户："我们从 frida 获取数据的操作，本身就有影响游戏的风险？"）
//   —— **是**。我们的读操作不是"旁观"：每个 RPC 都在**游戏主线程**上同步执行 il2cpp 调用
//   （`runtime_invoke`/`object_get_class`/UTF-16 解码…），我们慢 = 游戏卡；占太久还会让上层
//   "读帧数/读页面号"超时 ⇒ 被误判成 agent 掉线 ⇒ 自愈逻辑可能把游戏杀掉。
//   所以"谁慢"必须**立刻可见**：给每个导出的 RPC 套一层计时，超过 [SLOW_RPC_MS] 就在 logcat
//   打一行 `[slow] <名字> <耗时>ms`，参数原样透传、异常原样抛出（行为不变，只多一行日志）。
(function () {
  const SLOW_RPC_MS = 500;
  let n = 0;
  for (const k of Object.keys(rpc.exports)) {
    const f = rpc.exports[k];
    if (typeof f !== "function") continue;
    rpc.exports[k] = function () {
      const t0 = Date.now();
      try { return f.apply(null, arguments); }
      finally {
        const d = Date.now() - t0;
        if (d >= SLOW_RPC_MS) console.log("[slow] " + k + " " + d + "ms");
      }
    };
    n++;
  }
  console.log("[slow] 耗时哨兵已装：监控 " + n + " 个 RPC，阈值 " + SLOW_RPC_MS + "ms");
})();
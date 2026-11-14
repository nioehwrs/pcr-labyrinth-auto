package com.lmj.labyrinthauto.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lmj.labyrinthauto.core.OverlayStatus
import com.lmj.labyrinthauto.reroll.RerollSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * ★状态悬浮窗（2026-10-17 用户要求，2026-10-17 改版为胶囊条 + 三气泡）：
 * 游戏画面上叠**一条胶囊**（两端半圆），里面是三个独立气泡 ——
 *  `第 2/3 局`（琥珀）  `第 3 层`（青）  `编队：水兔 环奈 …`（深灰）。
 *
 * 硬性要求（逐条对应）：
 *  - **不承担任何功能**：不可点、不可交互、不接事件（`FLAG_NOT_TOUCHABLE`）⇒ 不会挡游戏操作；
 *  - **位置**：横向居中、距顶固定偏移处（`Gravity.TOP or CENTER_HORIZONTAL` + `y = 96`）；
 *  - **只在游戏前台时看得见**：普通 overlay 窗口，游戏全屏时叠在上面。
 *
 * 设计点：
 *  - 外层 `LinearLayout` 用 **大圆角**（cornerRadius 远大于半高）⇒ 两端自然变成半圆（胶囊）；
 *  - 三个气泡各自 `GradientDrawable` 小圆角 + 不同底色，段与段之间留 6px 间距，**独立成框**；
 *  - 空段（如还没进迷宫时 `floor`/`state` 为空）对应气泡 `GONE`，不显示空框；全空时退化为 `…`。
 *
 * 权限：`Settings.canDrawOverlays`。助手首页有开关 + 授权入口；
 * 服务启动时也会自检一次（见 `AutoForegroundService.reportOverlay`）。
 */
object OverlayWindow {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var app: Context? = null
    private var container: View? = null
    private var dot: View? = null
    private var chipRun: TextView? = null
    private var chipFloor: TextView? = null
    private var chipState: TextView? = null
    private var placeholder: TextView? = null
    private var job: Job? = null
    private var lp: WindowManager.LayoutParams? = null      // ★当前窗口参数（applyAlpha/applyDraggable 用）
    private var wm: WindowManager? = null
    private var dragOn = false                               // ★当前是否处于"可拖动"模式
    private var dragging = false
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var dragIdealX = 0f                               // ★浮点理想位置（避免 int 截断累积漂移）
    private var dragIdealY = 0f
    private var posCenter: Pair<Int, Int>? = null              // ★持久化的窗口**中心点**（拖动位置语义）

    private val _shown = MutableStateFlow(false)
    val shown = _shown.asStateFlow()

    // 三气泡配色（视图本身不透明；整体透明度由窗口 alpha=0.79 提供，
    // 这样才能在 Android12+ Untrusted-Touch 下既穿透又尽量实）
    private const val C_RUN = 0xFFD98E04.toInt()       // 琥珀（局）
    private const val C_FLOOR = 0xFF1E88A8.toInt()      // 青（层）
    private const val C_STATE = 0xFF2B2F36.toInt()      // 深灰（状态）
    /** ★需要人接手时的第 3 格底色（橙红，明显但不刺眼） */
    private const val C_WARN = 0xFFD84315.toInt()
    private const val STROKE = 0x55FFFFFF.toInt()
    private const val STROKE_WARN = 0xFFFFD180.toInt()
    // 状态色点（实心圆）
    private const val DOT_IDLE = 0xFF9E9E9E.toInt()     // 灰：空闲
    private const val DOT_WARN = 0xFFFFA726.toInt()     // 橙：需要人接手
    private const val DOT_RUN = 0xFF4CAF50.toInt()      // 绿：运行中
    private const val DOT_ERR = 0xFFE53935.toInt()      // 红：出错

    /** 进程内尽早调一次（Application / 首页 / 服务都行，重复调无害） */
    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun hasPermission(): Boolean = app?.let { Settings.canDrawOverlays(it) } ?: false

    /** 单个气泡：小圆角胶囊 + 阴影描边，保证在游戏画面上读得清 */
    private fun makeChip(c: Context, bg: Int): TextView =
        TextView(c).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(20, 8, 20, 8)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            // ★单行 + 末尾省略：长文本（编队 5 个名字）绝不再竖排换行把窗口撑成大块
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f
                setColor(bg)
                setStroke(1, STROKE)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(6, 0, 6, 0) }
        }

    fun show() {
        scope.launch {
            val c = app ?: return@launch
            if (!Settings.canDrawOverlays(c) || container != null) return@launch
            val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val dm = c.resources.displayMetrics
            // ★按横屏长边算（手机 900x1600 跑游戏横屏成 1600x900 ⇒ 取 1600），避免按竖屏窄边把条限太短
            val screenLong = maxOf(dm.widthPixels, dm.heightPixels)
            chipRun = makeChip(c, C_RUN)
            chipFloor = makeChip(c, C_FLOOR)
            // 编队名最长：给状态气泡一个上限（约 70% 长边），超出末尾省略，避免把整条撑爆
            chipState = makeChip(c, C_STATE).apply { maxWidth = (screenLong * 0.70f).toInt() }
            placeholder = TextView(c).apply {
                text = "…"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(20, 8, 20, 8)
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
            }
            dot = View(c).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(DOT_IDLE)
                }
                val d = (12 * c.resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(d, d).apply {
                    setMargins(4, 0, 10, 0)   // 与第一个气泡留点距离
                }
            }

            val bar = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14, 10, 14, 10)
                // ★大圆角 ⇒ 两端自然收成半圆（胶囊条）；底色半透明，气泡更跳
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 100f
                    setColor(0xFF0E1116.toInt())
                    setStroke(1, 0x33FFFFFF)
                }
                addView(dot)
                addView(chipRun)
                addView(chipFloor)
                addView(chipState)
                addView(placeholder)
            }

            // ★用透明根容器包住胶囊：窗口宽度固定为屏幕长边的 80%（手机 900x1600 跑游戏横屏成
            //   1600x900 ⇒ 按 1600 算 ⇒ 窗口 1280，居中）；胶囊本身仍 WRAP_CONTENT + 水平居中，
            //   ⇒ 内容短时紧凑、内容长时最多铺到窗口 80% 上限，绝不竖排换行撑成大块。
            val root = FrameLayout(c).apply {
                addView(
                    bar,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL,
                    ),
                )
            }

            val lp = WindowManager.LayoutParams(
                // ★宽度改 WRAP_CONTENT（2026-10-20 用户拖动测试反馈）：原来固定"屏幕长边 80%" ⇒
                //   容器 1280px 宽、胶囊只是居中其中 ⇒ 拖动范围被容器限制（左右各 160px）、且
                //   悬浮窗"需要那么大"看不出来。内容上限仍由 chipState.maxWidth(70% 长边) 保证，
                //   窗口跟着内容走：拖动范围=整个屏幕。
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 96   // 固定距顶偏移（设计值）；挡按钮问题已解决，回退到原来的 96（2026-10-17 曾临时上调到 24 避开弹窗）
                // 2026-10-18：Android 12(API31+) 的 Untrusted-Touch 拦截按*窗口* alpha 判定，与卡片不透明度无关。
                // 若 alpha>0.8 会直接丢弃触摸（logcat: "Untrusted touch due to occlusion"），即使已设
                // FLAG_NOT_TOUCHABLE 也不放穿透。必须 ≤0.8 才被系统信任、允许穿透到游戏。
                // ★2026-10-20：alpha 改为用户可调（0.20~0.79，RerollSettings.overlayAlpha），
                //   上限 0.79 正是为了不碰这条系统线；下限 0.2（用户："完全消失了不如直接不开"）。
                alpha = RerollSettings.loadOverlayAlpha(c) / 100f
                // ★可拖动模式（2026-10-20）：开着时不设 FLAG_NOT_TOUCHABLE（窗口自己收触摸拖动调位置）；
                //   关闭时恢复穿透。启动运行前会强制关掉（用户设计）。
                // ⚠2026-11-01 用户实测 bug：初始 flags 里**只有** FLAG_NOT_FOCUSABLE，**没有** NOT_TOUCHABLE，
                //   而这里原来只在 dragOn=true 时"把它摘掉" ⇒ 拖动态持久化成 false 时启动后窗口仍然吃触摸
                //   （点悬浮窗那块区域穿不到游戏），必须先手动开一次再被强制锁回去（走 [applyDraggable] 才 OR 上 flag）。
                //   ⇒ 两个方向都要**显式设**，不能依赖初始值。
                dragOn = RerollSettings.loadOverlayDraggable(c)
                flags = if (dragOn) {
                    flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                } else {
                    flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }
            }
            // ★拖过的位置持久化（**中心点**语义，2026-10-20）：存的是窗口中心，渲染时换算成左缘
            //   x = cx - 宽/2（clamp 屏幕内）。WRAP_CONTENT 下宽要 layout 后才知道 => addView 后 post 里设。
            val pos = RerollSettings.loadOverlayPos(c)
            if (pos != null) {
                posCenter = pos
                lp.gravity = Gravity.TOP or Gravity.START
            }
            this@OverlayWindow.lp = lp
            this@OverlayWindow.wm = wm
            if (dragOn) attachDrag(c, root)
            runCatching { wm.addView(root, lp) }
                .onFailure { return@launch }
            container = root
            if (pos != null) {
                // 中心点 -> 左缘（layout 后才有宽）
                root.post { recenter() }
            }
            // ★初始化空闲态：第一格置"未运行"（已在运行中则不动，避免覆盖"第 x/x 局"/"运行结束"）
            if (OverlayStatus.snap.value.run.isEmpty()) OverlayStatus.setRun("未运行")
            _shown.value = true
            job?.cancel()
            job = launch {
                OverlayStatus.snap.collect { s ->
                    (dot?.background as? GradientDrawable)?.setColor(if (s.attention) DOT_WARN else dotColor(s.level))
                    // ★run 由关键点事件直接置（"未运行"初始化 / "第 x/x 局"每局 / "运行结束 x/x"结束），这里只原样显示
                    applyChip(chipRun, s.run, pulse = false)
                    applyChip(chipFloor, s.floor, pulse = false)
                    // ★状态段每次内容变化都轻微闪一下（"变化段高亮"），其余段不闪
                    applyChip(chipState, s.state, pulse = true)
                    // ★需要人接手（停手/等待接手）：第 3 格换橙红底 + 亮边，一眼能看见；内容一变自动恢复
                    applyChipBg(
                        chipState,
                        if (s.attention) C_WARN else C_STATE,
                        if (s.attention) 2 else 1,
                        if (s.attention) STROKE_WARN else STROKE,
                    )
                    placeholder?.visibility = View.GONE
                    // ★内容变长 => 窗口宽度变（WRAP_CONTENT）=> 按**中心点**重算左缘，
                    //   保持居中、放不下才贴边（2026-10-20 用户：拖到最左后运行中会不会超出）
                    if (posCenter != null) recenter()
                }
            }
        }
    }

    /**
     * ★应用透明度（2026-10-20 用户：滑条 0.20~0.79）。窗口在显示中就实时更新；没显示则下次
     * [show] 生效（show 里也是读同一设置）。上限 0.79 是 Android 12+ Untrusted-Touch 系统线。
     */
    fun applyAlpha(alphaPct: Int) {
        scope.launch {
            val l = lp ?: return@launch
            val w = wm ?: return@launch
            l.alpha = (alphaPct.coerceIn(20, 79)) / 100f
            container?.let { runCatching { w.updateViewLayout(it, l) } }
        }
    }

    /**
     * ★应用"可拖动"开关（2026-10-20 用户设计）：
     *  - 开：摘掉 FLAG_NOT_TOUCHABLE + 挂拖动手势（拖动调位置，位置持久化）；
     *    ⚠此时悬浮窗会拦截其区域内的触摸（这就是要它关掉的原因）；
     *  - 关：恢复穿透（flag 加回去），位置保留。
     */
    fun applyDraggable(ctx: Context, on: Boolean) {
        scope.launch {
            val l = lp ?: return@launch
            val w = wm ?: return@launch
            val v = container ?: return@launch
            dragOn = on
            if (on) {
                l.flags = l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                attachDrag(ctx, v)
            } else {
                dragging = false
                v.setOnTouchListener(null)
                l.flags = l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                // 同样存**中心点**（和拖动松手、recenter 语义统一，别混左缘/中心）
                RerollSettings.saveOverlayPos(ctx, l.x + v.width / 2, l.y + v.height / 2)
            }
            runCatching { w.updateViewLayout(v, l) }
        }
    }

    /**
     * 拖动手势：按下后移动即拖窗口（lp.x/y 跟手），松手存位置。
     *
     * 2026-10-20 用户测试反馈修两点：
     *  1) 不跟手/跳一下：DOWN 时把 gravity 从"CENTER_HORIZONTAL"切到"TOP|START"会瞬间跳 160px
     *     （居中的 x=0 语义 != 左上锚的 x=0）=> 切换时同步把 x 重置为窗口当前左缘；
     *  2) 拖动范围：窗口已改 WRAP_CONTENT（容器=内容），再对 x/y 做屏幕内 clamp => 全屏可拖、
     *     不会被拖出屏幕。
     */
    private fun attachDrag(ctx: Context, v: View) {
        // 虚线框已去掉（2026-10-20 用户：没什么用）：拖动不改任何背景
        val dm = ctx.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        v.setOnTouchListener { view, ev ->
            if (!dragOn) return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    dragLastX = ev.rawX; dragLastY = ev.rawY
                    // 浮点"理想位置"（2026-10-20 用户：拖久了和手指有偏移）：每步 delta 是小数，
                    // 原来直接 toInt() 累加平均每步丢 0.5px => 拖几秒漂上百像素。
                    // 现在理想位置用 float 保精度，只在写 lp.x/y 时取整 => 误差不累积。
                    dragIdealX = lp?.x?.toFloat() ?: 0f
                    dragIdealY = lp?.y?.toFloat() ?: 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val l = lp
                    val w = wm
                    if (dragging && l != null && w != null) {
                        if (l.gravity != (Gravity.TOP or Gravity.START)) {
                            // 切左上锚的同时重置 x：窗口当前左缘 = (屏幕宽 - 内容宽) / 2（居中态）
                            val cw = if (view.width > 0) view.width else (screenW / 2)
                            l.gravity = Gravity.TOP or Gravity.START
                            dragIdealX = ((screenW - cw) / 2).toFloat()
                            dragIdealY = view.top.toFloat()
                            dragLastX = ev.rawX; dragLastY = ev.rawY   // 重置后再取 delta，绝不跳
                        }
                        // delta 累加到浮点理想位置上，再取整写窗口
                        dragIdealX += ev.rawX - dragLastX
                        dragIdealY += ev.rawY - dragLastY
                        dragLastX = ev.rawX; dragLastY = ev.rawY
                        var nx = dragIdealX.toInt()
                        var ny = dragIdealY.toInt()
                        val vw = if (view.width > 0) view.width else 0
                        val vh = if (view.height > 0) view.height else 0
                        if (vw in 1 until screenW) nx = nx.coerceIn(0, screenW - vw)
                        if (vh in 1 until screenH) ny = ny.coerceIn(0, screenH - vh)
                        l.x = nx
                        l.y = ny
                        runCatching { w.updateViewLayout(view, l) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    // ★存**中心点**（不是左缘）：内容变长时窗口宽变化，中心语义才不会把右缘伸出屏幕
                    val l2 = lp
                    if (l2 != null) {
                        val cw2 = if (view.width > 0) view.width else 0
                        val ch2h = if (view.height > 0) view.height else 0
                        val cx = l2.x + cw2 / 2
                        val cy = l2.y + ch2h / 2
                        posCenter = cx to cy
                        RerollSettings.saveOverlayPos(ctx, cx, cy)
                    }
                    true
                }
                else -> false
            }
        }
    }
    /**
     * ★按**中心点**重算窗口左缘（2026-10-20）：内容变长 => 窗口变宽（WRAP_CONTENT）时，
     *   保持中心不动（左缘左移）；实在放不下（比屏幕还宽）才贴左边。show 后、每次状态
     *   文本变化都会调它 —— 拖到最左也不会在运行中被顶出屏幕。
     */
    private fun recenter() {
        val c = posCenter ?: return
        val v = container ?: return
        val l = lp ?: return
        val w = wm ?: return
        v.post {
            val vw = v.width
            if (vw <= 0) return@post
            val dm2 = (app ?: return@post).resources.displayMetrics
            l.gravity = Gravity.TOP or Gravity.START
            l.x = (c.first - vw / 2).coerceIn(0, maxOf(0, dm2.widthPixels - vw))
            // ★y 也要按**中心**语义减半个高（2026-10-20 用户：关开一次悬浮窗就下移一点 ——
            //   原来只减了 x 的半个宽、y 直接把中心当左上角 ⇒ 每次恢复低半个窗高，再拖再存会累积）
            l.y = (c.second - v.height / 2).coerceIn(0, maxOf(0, dm2.heightPixels - if (v.height > 0) v.height else 120))
            runCatching { w.updateViewLayout(v, l) }
        }
    }
    private fun dotColor(l: OverlayStatus.Level): Int = when (l) {
        OverlayStatus.Level.IDLE -> DOT_IDLE
        OverlayStatus.Level.RUNNING -> DOT_RUN
        OverlayStatus.Level.ERROR -> DOT_ERR
    }

    /** 文本非空才显示气泡，空则隐藏（不显示空框）；pulse=true 且内容变化 ⇒ 轻微闪一下 */
    private fun applyChip(chip: TextView?, text: String, pulse: Boolean) {
        if (chip == null) return
        if (text.isEmpty()) {
            chip.visibility = View.GONE
            return
        }
        val changed = chip.text.toString() != text
        chip.text = text
        chip.visibility = View.VISIBLE
        if (pulse && changed) {
            chip.clearAnimation()
            chip.alpha = 0.45f
            chip.animate().alpha(1f).setDuration(450).start()
        }
    }

    /**
     * 换气泡底色/描边（"需要人接手"用）。同配置不重建 —— collect 每拍都会走，
     * 避免每拍 new 一个 drawable。
     */
    private fun applyChipBg(chip: TextView?, bg: Int, stroke: Int, strokeColor: Int) {
        if (chip == null) return
        val tag = "$bg/$stroke/$strokeColor"
        if (chip.tag == tag) return
        chip.tag = tag
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f
            setColor(bg)
            setStroke(stroke, strokeColor)
        }
    }

    /** 隐藏（并停止刷新） */
    fun hide() {
        scope.launch {
            job?.cancel()
            job = null
            val c = app
            val v = container
            if (c != null && v != null) {
                runCatching { (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) }
            }
            container = null
            dot = null
            chipRun = null
            chipFloor = null
            chipState = null
            placeholder = null
            lp = null
            wm = null
            posCenter = null
            dragOn = false
            dragging = false
            _shown.value = false
        }
    }

    fun toggle() {
        if (_shown.value) hide() else show()
    }
}

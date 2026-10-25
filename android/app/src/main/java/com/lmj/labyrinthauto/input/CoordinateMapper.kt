package com.lmj.labyrinthauto.input

/**
 * 坐标映射：把"参考系（1920×1080，PC 版/agent 用的那套坐标）"换算到真实屏幕像素。
 *
 * 来源说明（PLAN.md §2「借用件」要求保留来源）：
 *  - 结构抄自 `limingjie-assistant/android/.../automation/CoordinateMapper.kt`
 *    （纯几何计算，无业务逻辑；本工程独立实现 + 改动：包名、`PixelRect.contains` 改为闭区间语义不变）。
 *  - 与 PC 版的一致性：PC 版所有坐标都基于 `MAZE_SCREEN_W/H = 1920×1080`，
 *    所以这里固定 referenceSize = 1920×1080，两侧对拍时无需额外换算。
 */

data class ScreenPoint(val x: Float, val y: Float)

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "尺寸必须大于 0" }
    }
}

data class PixelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right > left && bottom > top) { "矩形范围无效" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(point: ScreenPoint): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom
}

data class CoordinateCalibration(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

data class CoordinateMapping(
    val referenceSize: PixelSize,
    val screenBounds: PixelRect,
    val viewport: PixelRect,
    val scale: Float,
    val calibration: CoordinateCalibration,
) {
    /** 参考系坐标 → 真实屏幕坐标；出界或落在黑边上 ⇒ null（**绝不猜**，与 PC 版 tap_xy 的总闸一致）。 */
    fun toScreen(referencePoint: ScreenPoint): ScreenPoint? {
        val refBounds = PixelRect(
            0f, 0f,
            referenceSize.width.toFloat(), referenceSize.height.toFloat(),
        )
        if (!refBounds.contains(referencePoint)) return null
        val mapped = ScreenPoint(
            x = viewport.left + referencePoint.x * scale + calibration.offsetX,
            y = viewport.top + referencePoint.y * scale + calibration.offsetY,
        )
        return mapped.takeIf(screenBounds::contains)
    }

    /** 真实屏幕坐标 → 参考系坐标（反查/诊断用）。 */
    fun toReference(screenPoint: ScreenPoint): ScreenPoint? {
        val calibrated = PixelRect(
            viewport.left + calibration.offsetX,
            viewport.top + calibration.offsetY,
            viewport.right + calibration.offsetX,
            viewport.bottom + calibration.offsetY,
        )
        if (!calibrated.contains(screenPoint)) return null
        return ScreenPoint(
            x = (screenPoint.x - calibrated.left) / scale,
            y = (screenPoint.y - calibrated.top) / scale,
        )
    }
}

class CoordinateMapper(
    private val referenceSize: PixelSize = PixelSize(1920, 1080),
) {
    fun createMapping(
        screenBounds: PixelRect,
        calibration: CoordinateCalibration = CoordinateCalibration(),
    ): CoordinateMapping {
        val scale = minOf(
            screenBounds.width / referenceSize.width,
            screenBounds.height / referenceSize.height,
        )
        val viewportWidth = referenceSize.width * scale
        val viewportHeight = referenceSize.height * scale
        val viewport = PixelRect(
            left = screenBounds.left + (screenBounds.width - viewportWidth) / 2f,
            top = screenBounds.top + (screenBounds.height - viewportHeight) / 2f,
            right = screenBounds.left + (screenBounds.width + viewportWidth) / 2f,
            bottom = screenBounds.top + (screenBounds.height + viewportHeight) / 2f,
        )
        return CoordinateMapping(referenceSize, screenBounds, viewport, scale, calibration)
    }
}

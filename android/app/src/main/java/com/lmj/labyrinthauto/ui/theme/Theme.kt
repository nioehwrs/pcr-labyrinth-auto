package com.lmj.labyrinthauto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 助手自己的配色/字号/圆角（2026-10-17 用户："有没有好一点的 Android UI 设计或者模板"）。
 *
 * 不引第三方 UI 库 —— 用**已经在依赖里的 Material 3**，只把它用对（原来这里是裸的
 * `MaterialTheme { Surface { … } }`，等于默认样例：全 bodySmall、无层级、无深色）。
 *
 * 三条取舍：
 *  · **默认浅色**（2026-10-18 用户："刚装应用时界面初始改为浅色"），深色可一键切
 *    （深色压在游戏画面上不刺眼、状态色更醒目）；
 *  · 主色取**金琥珀**（"黎明"）而不是 Material 默认紫 —— 一眼能认出是哪个 app；
 *  · 字号层级在这里定死（下面 [AppTypography]），界面里只许用这几档，不再随手 bodySmall。
 */

/** 深色（手动切换后使用） */
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFF2C078),
    onPrimary = Color(0xFF412D00),
    primaryContainer = Color(0xFF5D4200),
    onPrimaryContainer = Color(0xFFFFDFA6),
    secondary = Color(0xFFD9C4A3),
    onSecondary = Color(0xFF3B2F16),
    background = Color(0xFF111316),
    onBackground = Color(0xFFE5E3E1),
    surface = Color(0xFF111316),
    onSurface = Color(0xFFE5E3E1),
    surfaceVariant = Color(0xFF26282C),
    onSurfaceVariant = Color(0xFFC6C7CB),
    surfaceContainer = Color(0xFF1B1D21),
    outline = Color(0xFF8E9096),
    outlineVariant = Color(0xFF3A3C41),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** 日间浅色 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDFA6),
    onPrimaryContainer = Color(0xFF261900),
    secondary = Color(0xFF705C3A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFBF7F2),
    onBackground = Color(0xFF1B1B1D),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF1B1B1D),
    surfaceVariant = Color(0xFFEFE6D8),
    onSurfaceVariant = Color(0xFF4B4639),
    surfaceContainer = Color(0xFFF6F1EA),
    outline = Color(0xFF7C766A),
    outlineVariant = Color(0xFFD6CCBE),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

/** 状态色点用（绿=好 / 琥珀=在等 / 红=坏 / 灰=没信息） */
object StatusColors {
    val good = Color(0xFF4CAF7D)
    val warn = Color(0xFFE0A83B)
    val bad = Color(0xFFE05C4B)
    val idle = Color(0xFF6E7178)
}

/**
 * 字号层级：**只允许用这几档**，否则界面会退回"一片小字"。
 * · titleLarge  区块标题
 * · bodyLarge   状态值（最该被看到的那一行）
 * · bodyMedium  说明/次要值
 * · bodySmall   日志、辅助行（等宽字体在调用处指定）
 * · labelSmall  角标/计数
 */
private val AppTypography: Typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp),
    )
}

/** 圆角统一 12/16/20 —— 卡片和按钮同一套半径，界面才像"一套的" */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun LabyrinthTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

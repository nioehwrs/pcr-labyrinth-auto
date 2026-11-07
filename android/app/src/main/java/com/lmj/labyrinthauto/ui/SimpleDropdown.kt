package com.lmj.labyrinthauto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** 一个下拉项：主标题 +（可空）副标题（副标题用于把"这条结果到底是什么"写清楚） */
data class DropItem(val title: String, val sub: String = "")

/**
 * 紧凑下拉框（单选）。
 *
 * ★为什么不用 Material 的 `DropdownMenu`：横屏 1600×900 实测它在这套「长卡片 + LazyColumn item +
 *   weight 行」里会弹到**窗口左上角**（2026-10-30 用户报过，见 `AccountsCard` 的「服务器」下拉）。
 *   这里沿用那套做法：`onGloballyPositioned` 自己量按钮的窗口矩形 → 右对齐贴按钮下方 →
 *   下面放不下就翻到上方 → 夹进窗口边界。外观仍用 `Surface` + `DropdownMenuItem`。
 *
 * @param items     选项（空表时按钮显示"—"且点不开）
 * @param selected  当前选中下标（-1 = 未选）
 * @param maxWidth  按钮里文字的最大宽度（横屏很宽，别让一个长事件名把整行挤爆）
 */
@Composable
fun SimpleDropdown(
    items: List<DropItem>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxWidth: Dp = 420.dp,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val cur = items.getOrNull(selected)

    Box(modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
        OutlinedButton(
            onClick = { if (items.isNotEmpty()) open = true },
            enabled = enabled && items.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                (cur?.title ?: (if (items.isEmpty()) "—" else "（请选择）")) + " ▾",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxWidth),
            )
        }
    }

    if (!open) return
    DropdownPopup(
        anchor = anchor,
        items = items,
        selected = selected,
        onPick = { open = false; onPick(it) },
        onDismiss = { open = false },
    )
}

/**
 * 下拉菜单本体（按钮式 [SimpleDropdown] 与行内式 [InlineDropdown] 共用）。
 *
 * 位置自己算：贴住锚点下方，放不下就翻到上方，再夹进窗口边界
 * （Material 的 `DropdownMenu` 在这套长卡片里会弹到窗口左上角，见文件头注释）。
 */
@Composable
internal fun DropdownPopup(
    anchor: Rect,
    items: List<DropItem>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val cfg = LocalConfiguration.current
    val marginPx = with(density) { 6.dp.roundToPx() }
    val screenW = cfg.screenWidthDp.dp
    val screenH = cfg.screenHeightDp.dp
    val pad = 10.dp
    // 状态栏高度：菜单往上翻的时候别顶到状态栏底下
    val topSafe = 24.dp
    val topSafePx = with(density) { topSafe.roundToPx() }
    // ★菜单宽度只能**显式算**：里面的上下渐隐层用了 fillMaxWidth，取的是"最大约束"，
    //   不显式定宽就会把菜单撑成满宽（内容只有几个字也一整条，用户 2026-11-03 反馈）。
    //   这里按最长的标题/副标题实测文字宽度来算，再夹进 [minMenuW, maxMenuW]。
    val anchorW = with(density) { anchor.width.toDp() }
    val maxMenuW = (screenW - pad * 2).coerceAtMost(560.dp)
    val minMenuW = anchorW.coerceIn(110.dp, 260.dp).coerceAtMost(maxMenuW)
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.bodyMedium
    val subStyle = MaterialTheme.typography.labelSmall
    val longestPx = remember(items, titleStyle, subStyle) {
        var w = 0
        for (it2 in items) {
            w = maxOf(w, measurer.measure(AnnotatedString(it2.title), titleStyle, maxLines = 1).size.width)
            if (it2.sub.isNotBlank()) {
                w = maxOf(w, measurer.measure(AnnotatedString(it2.sub), subStyle, maxLines = 1).size.width)
            }
        }
        w
    }
    // 左右 padding(14+10) + 选中勾(16) + 勾前间距(8) ≈ 48dp
    val menuW = with(density) { longestPx.toDp() + 48.dp }.coerceIn(minMenuW, maxMenuW)
    // 菜单高度：尽量把全部选项排完，再看上下哪边宽；再夹一层屏高保险
    val availBelow = screenH - with(density) { anchor.bottom.toDp() } - pad
    val availAbove = with(density) { anchor.top.toDp() } - pad - topSafe
    val maxMenuH = minOf(
        68.dp * items.size + 8.dp,
        maxOf(availBelow, availAbove, 160.dp),
        screenH * 0.75f,
    )
    val provider = remember(anchor, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // ★不用框架给的 anchorBounds（横屏下它就是错的），用自己量的按钮矩形
                val a = if (anchor.width > 0f) {
                    IntRect(
                        anchor.left.toInt(),
                        anchor.top.toInt(),
                        anchor.right.toInt(),
                        anchor.bottom.toInt(),
                    )
                } else {
                    anchorBounds
                }
                val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                // ★横向：先试**左边缘对齐锚点**（行内下拉看着才像挂在它下面）；
                //   右边放不下再右对齐；都放不下（菜单比屏幕还宽）才夹进窗口。
                val w = popupContentSize.width
                val x = when {
                    a.left + w <= windowSize.width - marginPx -> a.left
                    a.right - w >= marginPx -> a.right - w
                    else -> (a.right - w).coerceIn(marginPx, maxX)
                }
                // ★菜单**要么完全在下、要么完全在上**，绝不压住下拉框本身
                //   （用户 2026-11-03："下拉框展开时盖住了下拉框本身，调整一下总长度"）
                val belowY = a.bottom + marginPx
                val fitsBelow = belowY + popupContentSize.height <= windowSize.height - marginPx
                val y = if (fitsBelow) {
                    belowY
                } else {
                    (a.top - popupContentSize.height - marginPx).coerceAtLeast(topSafePx)
                }
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val menuColor = MaterialTheme.colorScheme.surfaceContainer
        val scroll = rememberScrollState()
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = menuColor,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .width(menuW)
                .heightIn(max = maxMenuH),
        ) {
            Box {
                Column(
                    Modifier.verticalScroll(scroll),
                    verticalArrangement = Arrangement.Top,
                ) {
                    // ★不用 DropdownMenuItem：它自带 fillMaxWidth，会把菜单撑到最大宽度，
                    //   内容只有几个字时右边一大片空白（用户 2026-11-03 指出）。
                    //   这里自己拼一行，宽度交给内容决定，再由外层 widthIn 收上限。
                    items.forEachIndexed { i, it ->
                        Row(
                            Modifier
                                .clickable { onPick(i) }
                                .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    it.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (it.sub.isNotBlank()) {
                                    Text(
                                        it.sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (i == selected) {
                                Spacer(Modifier.width(10.dp))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "当前",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
                // ★上下小三角：内容多时提示"还能滚"（跟「渠道服选择」一个做法，用户 2026-11-03 要求）
                if (scroll.canScrollBackward) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(Brush.verticalGradient(listOf(menuColor, Color.Transparent))),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "上面还有",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .size(18.dp),
                        )
                    }
                }
                if (scroll.canScrollForward) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, menuColor))),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "下面还有",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

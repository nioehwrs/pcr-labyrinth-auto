package com.lmj.labyrinthauto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **行内下拉**（"嵌进一句话里"用）：不是按钮，而是一段带下拉箭头的可点文字 + 一条淡下划线。
 *
 * 用户口径（2026-11-03）："当前的下拉框对于正常一行文字来说太大了……我更希望是嵌入一局话里的、
 * 更简单的下拉框，并且和前后文**在同一行**，而不是换行"。
 *  ⇒ 宽度交给行内的 `weight`，文字过长省略号；点开后仍是同一份菜单（[DropdownPopup]）。
 */
@Composable
fun InlineDropdown(
    items: List<DropItem>,
    selected: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 320.dp,
    placeholder: String = "（请选择）",
    /** ★文字样式（要和句子里的固定文字**完全一致**，否则看着大小/基线都不一样） */
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val cur = items.getOrNull(selected)
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    Box(modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(enabled = items.isNotEmpty()) { open = true }
                .drawBehind {
                    val y = size.height - 1.dp.toPx()
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .padding(horizontal = 2.dp),
        ) {
            Text(
                cur?.title ?: if (items.isEmpty()) "—" else placeholder,
                style = textStyle,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxWidth),
            )
            Text(
                "▼",
                // 箭头比文字小一点，但**同一套字体度量**，别用别的 typography（会撑高行、把文字顶偏）
                style = textStyle.copy(fontSize = textStyle.fontSize * 0.7f),
                color = MaterialTheme.colorScheme.primary,
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
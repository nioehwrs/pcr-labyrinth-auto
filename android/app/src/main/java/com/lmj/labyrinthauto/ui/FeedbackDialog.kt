package com.lmj.labyrinthauto.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.lmj.labyrinthauto.core.DiagPack
import com.lmj.labyrinthauto.core.Feedback
import com.lmj.labyrinthauto.core.LogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 反馈对话框（2026-11-01 用户方案）：**昵称 + 内容 + 可选诊断包 → 直接开 issue**。
 *
 * 两个入口共用这一个对话框：
 *  * 首页右上角「反馈」→ [initialPack] = null，附件默认不附带；
 *  * 诊断包「保存到本地」那页的「提交反馈」→ 传进来已生成的包，**自动选中**。
 *
 * 口径：
 *  * 昵称、内容**必填**（各至少 1 个非空白字符）；昵称提交过一次就记住；
 *  * 附件**可不选**；选的话是「一整次运行」或「某一局」，取日志的逻辑与日志页「生成诊断包」一致；
 *  * 失败只报错、不吞包：cache 里的诊断包还在，用户可以重试或改走「保存到本地」。
 */
private data class Attach(
    /** 给人看的说明（"第 3 局 · 22:16" / "已选诊断包 lab-diag_xxx.zip"） */
    val label: String,
    /** 需要现场打包的日志（与 [pack] 二选一） */
    val logs: List<File> = emptyList(),
    /** 已经打好的包（来自诊断包页的入口） */
    val pack: DiagPack.Result? = null,
)

@Composable
fun FeedbackDialog(initialPack: DiagPack.Result? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var nick by remember { mutableStateOf(Feedback.loadNick(ctx)) }
    var content by remember { mutableStateOf("") }
    var attach by remember {
        mutableStateOf(
            initialPack?.let { Attach(label = "已选诊断包 ${it.name}", pack = it) },
        )
    }
    var pickerOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<Feedback.Sent?>(null) }

    val canSend = nick.isNotBlank() && content.isNotBlank() && !busy &&
        sent == null && Feedback.configured()

    fun send() {
        if (!canSend) return
        busy = true
        error = null
        step = "准备中…"
        scope.launch {
            val r = runCatching {
                Feedback.saveNick(ctx, nick)                    // 提交一次就记住昵称
                val zip: File? = attach?.let { a ->
                    a.pack?.file ?: withContext(Dispatchers.IO) {
                        DiagPack.build(ctx, a.label, a.logs).file
                    }
                }
                Feedback.submit(ctx, nick.trim(), content.trim(), zip) { s ->
                    scope.launch { step = s }
                }
            }
            busy = false
            r.onSuccess { sent = it }
                .onFailure { error = it.message ?: "提交失败（原因未知）" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("反馈") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (!Feedback.configured()) {
                    Text(
                        "这个包没内置反馈通道（构建时缺少 gitee.properties），只能手动到仓库提 issue。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    label = { Text("昵称（会显示在 issue 上，随意填）") },
                    singleLine = true,
                    enabled = sent == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("反馈内容（必填）") },
                    minLines = 4,
                    maxLines = 8,
                    enabled = sent == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("附件：", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        attach?.label ?: "不附带",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (sent == null) {
                        TextButton(onClick = { pickerOpen = true }) {
                            Text(if (attach == null) "选择" else "更换")
                        }
                    }
                }
                Text(
                    "可附一整次运行或某一局（与日志页生成诊断包同一份逻辑）；不带也行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Text(step, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "提交失败（内容没丢，可改好再试）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                sent?.let { s ->
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("已提交，谢谢！", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        s.issueUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(s.issueUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    )
                    Row {
                        TextButton(onClick = {
                            clip.setText(AnnotatedString(s.issueUrl))
                            Toast.makeText(ctx, "链接已复制", Toast.LENGTH_SHORT).show()
                        }) { Text("复制链接") }
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(s.issueUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text("打开") }
                    }
                }
            }
        },
        confirmButton = {
            if (sent != null) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = { send() }, enabled = canSend) {
                    Text(if (busy) "提交中…" else "提交")
                }
            }
        },
        dismissButton = {
            if (sent == null) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            }
        },
    )

    if (pickerOpen) {
        AttachPickerDialog(
            onPick = { a ->
                attach = a
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * 附件选择：两层，和日志页一样 ——「本次运行」= 会话 + 该次全部单局；「某一局」= 会话 + 这一局。
 * 顶部第一项是「不附带」。
 */
@Composable
private fun AttachPickerDialog(onPick: (Attach?) -> Unit, onDismiss: () -> Unit) {
    val sessions = remember { LogFile.listSessions(20) }
    // 与日志页同一口径：只有会话日志的运行折叠到底部（每次启动都会新增一条，摊开会把有用的挤走）
    val withRuns = remember(sessions) { sessions.filter { it.runs.isNotEmpty() || it.current } }
    val emptyOnly = remember(sessions) { sessions.filter { it.runs.isEmpty() && !it.current } }
    var openSession by remember {
        mutableStateOf<String?>((withRuns.firstOrNull { it.runs.isNotEmpty() } ?: withRuns.firstOrNull())?.key)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择附件（诊断包）") },
        text = {
            Column {
                Text(
                    "整次运行 = 会话 + 该次全部单局；某一局 = 会话 + 这一局。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(380.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("不附带", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text("选这个", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                    if (sessions.isEmpty()) {
                        item { Text("还没有日志（跑一局就有了）", style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(withRuns) { s ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    openSession = if (openSession == s.key) null else s.key
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (openSession == s.key) "▾" else "▸",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        s.timeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onPick(Attach(s.title, logs = s.files.map { it.f })) }) {
                                    Text("整次", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (openSession == s.key) {
                                for (r in s.runs) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                // 单局包 = 会话 + 这一局（与日志页 makePack 一致）
                                                onPick(
                                                    Attach(
                                                        "${s.title} / ${r.title}",
                                                        logs = listOfNotNull(s.sessionFile?.f) + r.files.map { it.f },
                                                    ),
                                                )
                                            }
                                            .padding(start = 18.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "└ ${r.title}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            r.timeText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (s.runs.isEmpty()) {
                                    Text(
                                        "这次还没有单局日志（会话日志在「整次」里）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    if (emptyOnly.isNotEmpty()) {
                        item {
                            Text(
                                "其余 ${emptyOnly.size} 次运行只有会话日志（可在「日志」页单独打包）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

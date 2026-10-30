package com.lmj.labyrinthauto.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.lmj.labyrinthauto.core.Account
import com.lmj.labyrinthauto.core.Accounts
import com.lmj.labyrinthauto.frida.AccessCapture
import com.lmj.labyrinthauto.net.bilibili.GameServer
import kotlinx.coroutines.launch

/**
 * 账号卡，**两级折叠**（2026-10-28 用户："下面详细信息（账密/uid 等）可以折叠在某个地方，
 * 点开账号第一层只能看到单选已有账号这一层"）。
 *
 * ```
 * ▾ 账号                                        大号 · 国服 Bilibili   ← 点标题栏收起/展开
 *   (o) 大号        国服 Bilibili ｜ 547898207          [编辑] [×]     ← 第一层：只有单选
 *   ( ) 华为小号    华为渠道服 ｜ 未抓取                [编辑] [×]
 *   [＋ 新建账号]
 *   ── 点「编辑」才展开 ↓（第二层：详情）──
 *   名称 / 服务器（下拉）
 *   B 服 → B 站账号 + 密码
 *   渠道服 → uid + access_key + [抓取 uid / access_key]
 * ```
 *
 * 抓取（渠道服）= [AccessCapture]：单独启动一次渠道客户端、截渠道 SDK 登录时游戏自己传给
 * `Cute.SdkLoginTask.SetParameter` 的 uid / access_key，然后退出游戏。抓到就把凭证写回这条账号
 * 并**顺手选中它**（凭证会镜像给刷开局链路）。
 */
@Composable
fun AccountsCard(
    modifier: Modifier = Modifier,
    onSelected: (Account?) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(Accounts.all(ctx)) }
    var selId by remember { mutableStateOf(Accounts.selected(ctx)?.id ?: list.firstOrNull()?.id) }
    var open by remember { mutableStateOf(true) }               // 第一层：账号列表
    var editId by remember { mutableStateOf<Long?>(null) }      // 第二层：正在编辑哪一条
    var captureFor by remember { mutableStateOf<Account?>(null) }
    var pendingDelete by remember { mutableStateOf<Account?>(null) }   // 待确认删除的账号

    val sel = list.firstOrNull { it.id == selId }
    val editing = list.firstOrNull { it.id == editId }

    val captureBusy by AccessCapture.busy.collectAsState()
    val captureStatus by AccessCapture.status.collectAsState()

    fun refresh() {
        list = Accounts.all(ctx)
        if (editId != null && list.none { it.id == editId }) editId = null
        onSelected(Accounts.selected(ctx))
    }

    fun pick(id: Long) {
        selId = id
        Accounts.select(ctx, id)
        onSelected(list.firstOrNull { it.id == id })
    }

    fun save(account: Account) {
        val saved = Accounts.save(ctx, account)
        list = Accounts.all(ctx)
        if (saved.id == selId) onSelected(saved)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ── 卡片标题栏（整体收起/展开）──
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("账号", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    sel?.let { "${it.name} · ${it.server.displayName}" } ?: "未选择",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (open) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
            }

            if (!open) return@Column

            // ───────────────── 第一层：单选已有账号（只有这一层） ─────────────────
            if (list.isEmpty()) {
                Text(
                    "还没有账号：点下面「新建账号」。B 服填账密；渠道服先点「编辑」再抓 uid / access_key。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (a in list) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { pick(a.id) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = a.id == selId, onClick = { pick(a.id) })
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${a.server.displayName} ｜ ${a.summary}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { editId = if (editId == a.id) null else a.id }) {
                        Text(if (editId == a.id) "收起" else "编辑", style = MaterialTheme.typography.labelMedium)
                    }
                    // ★先弹确认再删（2026-10-30 用户："删除的时候加一个确认弹窗，避免点错"）
                    IconButton(onClick = { pendingDelete = a }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除账号")
                    }
                }
            }
            TextButton(onClick = {
                val a = Accounts.save(ctx, Accounts.create(ctx, Accounts.defaultServer(ctx)))
                list = Accounts.all(ctx)
                selId = a.id
                Accounts.select(ctx, a.id)
                editId = a.id          // 新建后直接进第二层，省一次点击
                onSelected(a)
            }) { Text("＋ 新建账号") }

            // ───────────────── 第二层：选中那条账号的详情 ─────────────────
            if (editing != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "详情 · ${editing.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AccountEditor(
                    account = editing,
                    captureBusy = captureBusy,
                    captureStatus = captureStatus,
                    onSave = { save(it) },
                    onCapture = { captureFor = it },
                )
            }
        }
    }

    // ── 删除确认（★2026-10-30 用户："删除的时候加一个确认弹窗，避免点错"）──
    pendingDelete?.let { victim ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除账号？") },
            text = {
                Text(
                    "「${victim.name} · ${victim.server.displayName}」里保存的凭证（账密 / uid / access_key）" +
                        "会一起删掉，删了要重新填或重新抓取。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    Accounts.delete(ctx, victim.id)
                    selId = Accounts.selected(ctx)?.id
                    refresh()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // ── 抓取确认（渠道服）──
    captureFor?.let { a ->
        AlertDialog(
            onDismissRequest = { captureFor = null },
            title = { Text("抓取 uid / access_key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "会用「${a.server.displayName}」的客户端（${a.server.packageName}）启动游戏一次，" +
                            "等它自己完成渠道登录，把游戏服登录用的 uid / access_key 取回来，然后关掉游戏。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "· 会先杀掉这个客户端再启动（需要 root）\n" +
                            "· 游戏停在「点击屏幕」时助手会自己点，用来触发渠道登录\n" +
                            "· 要是渠道 SDK 弹了自己的账号登录框，仍然需要你手动点一下\n" +
                            "· 不跑迷宫、不动存档",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    captureFor = null
                    scope.launch {
                        try {
                            val creds = AccessCapture.capture(ctx, a.server)
                            // 抓完可能已经在界面上改过别的字段了 → 以最新那条为准写回
                            val cur = Accounts.all(ctx).firstOrNull { it.id == a.id } ?: a
                            val saved = Accounts.save(
                                ctx,
                                // channel_id 也要跟着存：接口刷开局时 sdk_login 必须带对（MuMu=65）
                                cur.copy(
                                    loginId = creds.uid,
                                    secret = creds.accessKey,
                                    channelId = creds.channelId,
                                ),
                            )
                            Accounts.select(ctx, saved.id)
                            list = Accounts.all(ctx)
                            selId = saved.id
                            onSelected(saved)
                            Toast.makeText(
                                ctx,
                                "已抓到 uid=${saved.loginId}（账号已选中）",
                                Toast.LENGTH_LONG,
                            ).show()
                        } catch (t: Throwable) {
                            Toast.makeText(
                                ctx,
                                t.message ?: "抓取失败（看日志）",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }) { Text("开始抓取") }
            },
            dismissButton = { TextButton(onClick = { captureFor = null }) { Text("取消") } },
        )
    }
}

/**
 * 第二层：一条账号的详情编辑区。
 *
 * 文本状态用 `remember(account.id)` 绑在**这条账号**上：切到别的账号就重置，
 * 但每次敲键回写（[_onSave]）不会把光标打断（id 没变 ⇒ 状态不重建）。
 */
@Composable
private fun AccountEditor(
    account: Account,
    captureBusy: Boolean,
    captureStatus: String?,
    onSave: (Account) -> Unit,
    onCapture: (Account) -> Unit,
) {
    var showSecret by remember { mutableStateOf(false) }
    var serverMenu by remember { mutableStateOf(false) }
    var nameText by remember(account.id) { mutableStateOf(account.name) }
    var loginText by remember(account.id) { mutableStateOf(account.loginId) }
    var secretText by remember(account.id) { mutableStateOf(account.secret) }
    var channelText by remember(account.id) { mutableStateOf(account.channelId) }

    // ★外部写入要能回显（最典型：抓取成功后 uid / access_key 由程序写进账号表）：
    //   只绑 id 的话输入框会一直显示旧内容（空）—— 明明存进去了却看着像没抓到。
    //   用户自己敲字时 onSave 立刻把同值写回 account，两边相等 ⇒ 不会打断光标。
    LaunchedEffect(account.id, account.loginId) {
        if (loginText != account.loginId) loginText = account.loginId
    }
    LaunchedEffect(account.id, account.secret) {
        if (secretText != account.secret) secretText = account.secret
    }
    LaunchedEffect(account.id, account.channelId) {
        if (channelText != account.channelId) channelText = account.channelId
    }

    OutlinedTextField(
        nameText,
        { v -> nameText = v; onSave(account.copy(name = v)) },
        label = { Text("名称（给自己看的备注）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    // 服务器：B 服 / 各联运渠道服（同一套渠道服网关，只是客户端包名不同）
    //
    // ★2026-10-30 用户（横屏 1600×900 实测）：Material 的 `DropdownMenu` 会弹到**窗口左上角**，
    //   因为框架给的弹窗锚点在这张超长卡片 + LazyColumn item + weight 行里退化成了窗口原点。
    //   外观保持原样（Surface + DropdownMenuItem，就是原来那条下拉），但**位置自己算**：
    //   按钮用 onGloballyPositioned 量出窗口坐标 → 右对齐贴按钮下方；下面放不开就翻到上方；
    //   再夹进窗口边界。任何方向/分辨率都不会再跑到左上角。
    fun pickServer(s: GameServer) {
        serverMenu = false
        // 换服务器等于换账号体系：凭证语义不同，清掉免得张冠李戴
        // （channel_id 也是渠道维度的，一并清掉，等重新抓取）
        loginText = ""
        secretText = ""
        channelText = ""
        showSecret = false
        onSave(account.copy(server = s, loginId = "", secret = "", channelId = ""))
    }
    var anchorRect by remember { mutableStateOf(Rect.Zero) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("服务器", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box(Modifier.onGloballyPositioned { anchorRect = it.boundsInWindow() }) {
            OutlinedButton(onClick = { serverMenu = true }) {
                Text(account.server.displayName + " ▾")
            }
        }
    }
    if (serverMenu) {
        val density = LocalDensity.current
        val marginPx = with(density) { 6.dp.roundToPx() }
        // ★菜单高度 = "按钮上方/下方哪边宽就按哪边"，尽量把 9 项**一次排完**（用户 2026-10-30：
        //   "一页不够显示全部 9 项，用户看不出能拖动怎么办"）。原先固定 60% 屏高，横屏下必然截断；
        //   现在先量出按钮在窗口里的位置，用真实剩余空间来定高度，能放下就不滚。
        val screenH = LocalConfiguration.current.screenHeightDp.dp
        val itemH = 48.dp                                   // DropdownMenuItem 的最小行高
        val wanted = itemH * GameServer.entries.size + 8.dp  // 9 项正好排满（含上下各 4dp 余量）
        val pad = 10.dp
        val availBelow = (screenH - with(density) { anchorRect.bottom.toDp() } - pad)
        val availAbove = (with(density) { anchorRect.top.toDp() } - pad)
        val maxMenuH = minOf(
            wanted,
            maxOf(availBelow, availAbove, 120.dp),          // 哪边宽用哪边（provider 会自动翻面）
            screenH * 0.92f,                                 // 再保险：别顶穿窗口
        )
        val provider = remember(anchorRect, marginPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // ★关键：不用框架传进来的 anchorBounds（横屏下它就是错的），
                    //   用我们自己量到的按钮矩形；量不到才退回框架值。
                    val a = if (anchorRect.width > 0f) {
                        IntRect(
                            anchorRect.left.toInt(),
                            anchorRect.top.toInt(),
                            anchorRect.right.toInt(),
                            anchorRect.bottom.toInt(),
                        )
                    } else {
                        anchorBounds
                    }
                    val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
                    // 右对齐按钮（= Material 下拉的默认对齐），再夹进窗口
                    val x = (a.right - popupContentSize.width).coerceIn(marginPx, maxX)
                    val below = a.bottom + marginPx
                    val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
                    val y = if (below <= maxY) {
                        below
                    } else {
                        (a.top - popupContentSize.height - marginPx).coerceIn(marginPx, maxY)
                    }
                    return IntOffset(x, y)
                }
            }
        }
        Popup(
            popupPositionProvider = provider,
            onDismissRequest = { serverMenu = false },
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
                    .widthIn(min = 150.dp, max = 280.dp)
                    .heightIn(max = maxMenuH),
            ) {
                Box {
                    Column(Modifier.verticalScroll(scroll)) {
                        for (s in GameServer.entries) {
                            DropdownMenuItem(
                                text = { Text(s.displayName) },
                                onClick = { pickServer(s) },
                                trailingIcon = {
                                    if (s == account.server) {
                                        Icon(Icons.Filled.Check, contentDescription = "当前")
                                    }
                                },
                            )
                        }
                    }
                    // ★滚动提示（用户 2026-10-30："万一有用户看不出这个 menu 可以拖动"）：
                    //   还有内容在视野外时，那一侧压一条渐隐 + 箭头 —— 不用文字也知道能滑。
                    if (scroll.canScrollBackward) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(26.dp)
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
                                .height(26.dp)
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

    if (account.isChannel) {
        Text(
            "渠道服没有可用的账号密码登录：需要 uid + access_key（游戏服直通）。" +
                "点下面「抓取」，助手会自己启动这个渠道客户端把它们取回来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ★2026-10-30 用户：uid / access_key / channel_id **不可手改、但要能复制**（只由「抓取」写入）。
        //   readOnly 的 TextField 仍支持长按选择，右侧再给一个一键复制按钮。
        OutlinedTextField(
            loginText,
            { _ -> },
            label = { Text("uid") },
            singleLine = true,
            readOnly = true,
            trailingIcon = { CopyIconButton(loginText, "uid") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            secretText,
            { _ -> },
            label = { Text("access_key") },
            singleLine = true,
            readOnly = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Row {
                    CopyIconButton(secretText, "access_key")
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            imageVector = if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showSecret) "隐藏" else "显示",
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // channel_id：接口刷开局时 sdk_login 必须带对（各渠道不同，MuMu=65）。
        // 抓取会自动填；手填的 uid/access_key 若来自别处，这里得自己补。
        OutlinedTextField(
            channelText,
            { _ -> },
            label = { Text("channel_id") },
            singleLine = true,
            readOnly = true,
            trailingIcon = { CopyIconButton(channelText, "channel_id") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onCapture(account) },
            enabled = !captureBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (captureBusy) "抓取中…（看下面进度）" else "抓取 uid / access_key")
        }
        if (captureBusy) {
            Text(
                captureStatus ?: "准备中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "抓取会重启这个客户端并注入游戏进程，整套大约 30~90s；期间游戏在前台。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        OutlinedTextField(
            loginText,
            { v -> loginText = v; onSave(account.copy(loginId = v)) },
            label = { Text("B 站账号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            secretText,
            { v -> secretText = v; onSave(account.copy(secret = v)) },
            label = { Text("B 站密码") },
            singleLine = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showSecret = !showSecret }) {
                    Icon(
                        imageVector = if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showSecret) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Text(
        "凭证只保存在本机（应用私有目录）；B 服仅用于向 B 站官方接口登录，" +
            "渠道服仅用于向渠道服游戏网关登录。不会上传到任何第三方服务器。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!account.ready) {
        Text(
            if (account.isChannel) "还没抓到 uid / access_key —— 这个账号暂时不能用来刷开局。"
            else "账号或密码没填完 —— 刷开局前补齐。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * 只读字段旁边的一键复制按钮（2026-10-30 用户：凭证不允许手改，但必须能复制走）。
 * 空值不写剪贴板，免得把"空"粘到别处还以为复制成功。
 */
@Composable
private fun CopyIconButton(value: String, label: String) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    IconButton(
        onClick = {
            if (value.isBlank()) return@IconButton
            clipboard.setText(AnnotatedString(value))
            Toast.makeText(ctx, "已复制 $label", Toast.LENGTH_SHORT).show()
        },
    ) {
        Icon(Icons.Filled.ContentCopy, contentDescription = "复制 $label")
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.busnotice.commute.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Candidate
import com.busnotice.commute.CommuteService
import com.busnotice.commute.Learn
import com.busnotice.commute.LiveState
import com.busnotice.commute.Reverse
import com.busnotice.commute.Scheduler
import com.busnotice.commute.Trip
import com.busnotice.commute.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一项设置检查：granted = 已满足；optional = 可选项（不算「还差」） */
private data class Perm(
    val title: String, val desc: String, val granted: Boolean, val optional: Boolean = false, val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(resumeTick: Int, onNew: () -> Unit, onEdit: (Trip) -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dimColor = Fuse.dim(dark)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var version by remember { mutableStateOf(0) }
    val trips = remember(resumeTick, version) { TripStore.all(ctx) }
    val live by LiveState.snapshot.collectAsState()
    var confirmDelete by remember { mutableStateOf<Trip?>(null) }
    var reversing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var permSheet by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    val perms = remember(resumeTick, version) { permChecks(ctx) { p -> permLauncher.launch(p) } }
    val days = remember(resumeTick) { com.busnotice.commute.TraceLog.dayCount(ctx) }
    val diag = remember(resumeTick, version) { com.busnotice.commute.Diag.last(ctx) }

    val listState = rememberLazyListState()
    // 点「测试」或行程开始后，实时看板插到列表顶部：滚回顶部让看板完整露出来，不被挤到上面
    val liveId = live?.tripId
    LaunchedEffect(liveId) { if (liveId != null) listState.animateScrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        GradientBackground(dark)
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = ink,
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onNew, icon = { Icon(Icons.Default.Add, null) }, text = { Text("新建行程", fontWeight = FontWeight.Bold) },
                    shape = CircleShape,
                    containerColor = if (dark) Fuse.Yellow else Fuse.Night,
                    contentColor = if (dark) Color(0xFF111111) else Color.White,
                )
            },
            snackbarHost = { SnackbarHost(snack) { Snackbar(it, shape = RoundedCornerShape(16.dp)) } },
        ) { pad ->
            // 没有顶栏：标题放进列表第一项；列表本身从状态栏下面开始（会裁切），内容不会画到状态栏文字下面
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = pad.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "title") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("通勤胶囊", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ink, modifier = Modifier.weight(1f))
                        Box {
                            RoundButton(onClick = { menuOpen = true }, dark = dark) {
                                Text("⋯", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
                            }
                            DropdownMenu(
                                expanded = menuOpen, onDismissRequest = { menuOpen = false },
                                shape = MenuShape, containerColor = Fuse.surface(dark),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("build-${com.busnotice.commute.Updater.currentBuild(ctx)} · 检查更新") },
                                    onClick = { menuOpen = false; com.busnotice.commute.Updater.force.intValue++ },
                                )
                                if (days > 0) DropdownMenuItem(
                                    text = { Text("导出行程记录（${days} 天）") },
                                    onClick = { menuOpen = false; com.busnotice.commute.TraceLog.share(ctx) },
                                )
                                diag?.let { d ->
                                    DropdownMenuItem(
                                        text = {
                                            Text("最近状态：$d", style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.widthIn(max = 280.dp))
                                        },
                                        onClick = {}, enabled = false,
                                    )
                                }
                            }
                        }
                    }
                }
                live?.let { s -> item(key = "live") { LiveBoard(s, trips.firstOrNull { it.id == s.tripId }, dark) } }
                // 缺必需设置时放上面显眼处，全齐了就放到列表最后
                val permUrgent = perms.any { !it.granted && !it.optional }
                if (permUrgent) item(key = "perm") { PermRow(perms, dark) { permSheet = true } }
                if (trips.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有行程", style = MaterialTheme.typography.titleMedium, color = ink)
                        Spacer(Modifier.height(4.dp))
                        Text("新建一条，选好上车站和时间就行", style = MaterialTheme.typography.bodyMedium, color = dimColor)
                    }
                }
                items(trips, key = { it.id }) { t ->
                    TripCard(
                        trip = t,
                        running = live?.tripId == t.id,
                        dark = dark,
                        onToggle = { on ->
                            val nt = t.copy(enabled = on)
                            TripStore.upsert(ctx, nt); Scheduler.schedule(ctx, nt); version++
                        },
                        onTest = {
                            CommuteService.start(ctx, t.id, manual = true)
                            scope.launch { snack.showSnackbar("已开始测试 30 分钟，切到别的 App 看胶囊") }
                        },
                        onEdit = { onEdit(t) },
                        onReverse = {
                            if (!reversing) {
                                reversing = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { Reverse.build(t) } }
                                    reversing = false
                                    r.onSuccess { res ->
                                        if (res.warnings.isNotEmpty()) snack.showSnackbar(res.warnings.joinToString("；"))
                                        if (res.trip.legs.isNotEmpty()) onEdit(res.trip)
                                    }.onFailure { snack.showSnackbar("生成回程失败：网络不通？") }
                                }
                            }
                        },
                        onDelete = { confirmDelete = t },
                        onApplyLearned = { sg ->
                            val nt = t.copy(startMinute = sg.start, endMinute = sg.end)
                            TripStore.upsert(ctx, nt); Scheduler.schedule(ctx, nt); version++
                            scope.launch { snack.showSnackbar("时间窗改为 ${hhmm(sg.start)}–${hhmm(sg.end)}") }
                        },
                    )
                }
                if (!permUrgent && perms.isNotEmpty()) item(key = "perm") { PermRow(perms, dark) { permSheet = true } }
            }
        }
    }

    if (permSheet) {
        ModalBottomSheet(
            onDismissRequest = { permSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Fuse.surface(dark), contentColor = ink,
        ) { PermSheet(perms) }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            title = { Text("删除「${t.name}」？") },
            confirmButton = {
                DialogButton("删除", primary = true, onClick = {
                    Scheduler.cancel(ctx, t.id); TripStore.delete(ctx, t.id); confirmDelete = null; version++
                })
            },
            dismissButton = { DialogButton("取消", onClick = { confirmDelete = null }) },
        )
    }
}

/** 设置检查一行：「设置检查  N / M ›」，缺必需项时显示琥珀色提示 */
@Composable
private fun PermRow(perms: List<Perm>, dark: Boolean, onOpen: () -> Unit) {
    val ink = Fuse.ink(dark)
    val ok = perms.count { it.granted }
    val missing = perms.count { !it.granted && !it.optional }
    Row(
        Modifier.fillMaxWidth().glass(dark).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("设置检查", fontSize = 14.sp, color = ink)
        Spacer(Modifier.weight(1f))
        if (missing > 0) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Fuse.Amber))
            Spacer(Modifier.width(6.dp))
            Text("还差 $missing 项设置", fontSize = 13.sp, color = Fuse.Amber, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
        }
        Text("$ok / ${perms.size} ›", fontSize = 14.sp, color = ink, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun PermSheet(perms: List<Perm>) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置检查", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        perms.filter { !it.optional }.forEach { PermItem(it) }
        val opt = perms.filter { it.optional }
        if (opt.isNotEmpty()) {
            Text("可选", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
            opt.forEach { PermItem(it) }
        }
    }
}

@Composable
private fun PermItem(p: Perm) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge)
            Text(p.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (p.granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = Fuse.BusGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("已开启", style = MaterialTheme.typography.labelLarge, color = Fuse.BusGreen)
            }
        } else {
            PillButton(onClick = p.action) { Text("去开启") }
        }
    }
}

@Composable
private fun TripCard(
    trip: Trip, running: Boolean, dark: Boolean,
    onToggle: (Boolean) -> Unit, onTest: () -> Unit, onEdit: () -> Unit, onReverse: () -> Unit, onDelete: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onApplyLearned: (Learn.Suggestion) -> Unit,
) {
    val ctx = LocalContext.current
    val ink = Fuse.ink(dark)
    val dimColor = Fuse.dim(dark)
    val fade = if (trip.enabled) 1f else 0.55f
    var more by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().glass(dark).clickable(onClick = onEdit).padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(trip.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(fade))
                Text("${trip.daysText()} · 至 ${hhmm(trip.endMinute)}", fontSize = 13.sp, color = dimColor,
                    modifier = Modifier.padding(top = 3.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(hhmm(trip.startMinute), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ink,
                    style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.alpha(fade))
                Switch(
                    checked = trip.enabled, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = if (dark) Fuse.Yellow else Fuse.Night,
                        checkedThumbColor = if (dark) Fuse.Night else Color.White,
                        checkedBorderColor = Color.Transparent,
                        uncheckedTrackColor = Color(0x667F7F8C),
                        uncheckedThumbColor = Color.White,
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
        RouteRow(trip, dark, Modifier.padding(top = 6.dp).alpha(fade))
        // 下次触发的说明可能较长：单独占一行（最多两行），不和「测试」「⋯」挤在一起被截断
        val nextText = if (!running && trip.enabled) Scheduler.nextTriggerText(trip, ctx) else null
        if (nextText != null) {
            Text(nextText, fontSize = 13.sp, color = dimColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp, end = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (nextText != null) 0.dp else 6.dp)) {
            Box(Modifier.weight(1f)) {
                when {
                    running -> Text(
                        "运行中", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (dark) Color(0xFF7FE0A6) else Color(0xFF167A40),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Fuse.BusGreen.copy(alpha = if (dark) 0.25f else 0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    nextText != null -> {}
                    else -> Text("已暂停", fontSize = 13.sp, color = dimColor)
                }
            }
            TextButton(onClick = onTest, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("测试", fontSize = 13.sp, color = dimColor)
            }
            Box {
                TextButton(onClick = { more = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("⋯", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = dimColor)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }, shape = MenuShape, containerColor = Fuse.surface(dark)) {
                    DropdownMenuItem(text = { Text("生成回程") }, onClick = { more = false; onReverse() })
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { more = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { more = false; onDelete() })
                }
            }
        }
    }
}

/** 线路小牌：步行（去第一段上车点） — 各段线路牌，段内多个可选线路用「/」隔开 */
@Composable
private fun RouteRow(trip: Trip, dark: Boolean, modifier: Modifier = Modifier) {
    val ink = Fuse.ink(dark)
    val legs = trip.legs.filter { it.candidates.isNotEmpty() }
    if (legs.isEmpty()) {
        Text("还没有设置线路", fontSize = 13.sp, color = Fuse.dim(dark), modifier = modifier.padding(vertical = 4.dp))
        return
    }
    @Composable
    fun Dash() = Text("—", fontSize = 13.sp, color = ink.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 6.dp))

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // 第一段总是先走到上车点（App 按步行时间算出发），所以开头放一个「步」
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.07f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) { Text("步", fontSize = 12.sp, color = ink) }
        legs.take(4).forEach { leg ->
            Dash()
            leg.candidates.take(3).forEachIndexed { i, c: Candidate ->
                if (i > 0) Text("/", fontSize = 12.sp, color = ink.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = 3.dp))
                LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c))
            }
        }
        if (legs.size > 4) { Dash(); Text("…", fontSize = 13.sp, color = ink) }
    }
}

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** 全部设置检查（含已满足的），申请方式和以前一样 */
private fun permChecks(ctx: Context, request: (Array<String>) -> Unit): List<Perm> {
    val out = mutableListOf<Perm>()
    val pkg = Uri.parse("package:${ctx.packageName}")
    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recover { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    out += Perm("精确定位", "算你走到车站要多久", fine) {
        request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    out += Perm("后台定位：始终允许", "闹钟在后台叫醒时也能定位", fine && granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
        // 系统要求先有前台定位才能申请后台定位
        if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION))
            request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }
    out += Perm("身体活动（计步）", "判断你下了地铁、开始走路", granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) {
        request(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
    }
    val notif = granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    out += Perm("通知", "显示流体云胶囊", notif) { request(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
    if (Build.VERSION.SDK_INT >= 36) {
        val promoted = notif && ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        out += Perm("实时通知 / 流体云", "让胶囊出现在状态栏", promoted) {
            if (!granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else open(Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
    }
    out += Perm("精确闹钟", "准时预热", ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
    }
    out += Perm("电池不限制", "也请在 设置→电池 里允许后台活动和自启动",
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
    }
    out += Perm("通知使用权", "读 NFC 交通卡刷卡和系统到站提醒，进出站时间更准；只看出行通知",
        com.busnotice.commute.TransitNotif.enabled(ctx), optional = true) {
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    out += Perm("出行识别", "无障碍：行程中读系统出行胶囊、交通卡弹窗的文字，只记在本机",
        com.busnotice.commute.TransitA11y.enabled(ctx), optional = true) {
        open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    return out
}

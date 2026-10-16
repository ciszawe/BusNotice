@file:OptIn(ExperimentalMaterial3Api::class)

package com.busnotice.commute.ui

import androidx.compose.foundation.clickable

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.busnotice.commute.CommuteService
import com.busnotice.commute.Learn
import com.busnotice.commute.LiveSnapshot
import com.busnotice.commute.LiveState
import com.busnotice.commute.Reverse
import com.busnotice.commute.Scheduler
import com.busnotice.commute.Trip
import com.busnotice.commute.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Perm(val title: String, val desc: String, val action: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(resumeTick: Int, onNew: () -> Unit, onEdit: (Trip) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var version by remember { mutableStateOf(0) }
    val trips = remember(resumeTick, version) { TripStore.all(ctx) }
    val live by LiveState.snapshot.collectAsState()
    var confirmDelete by remember { mutableStateOf<Trip?>(null) }
    var reversing by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { version++ }
    val perms = remember(resumeTick, version) { missingPerms(ctx) { p -> permLauncher.launch(p) } }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("通勤胶囊", fontWeight = FontWeight.SemiBold) }, scrollBehavior = scroll) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Default.Add, null) }, text = { Text("新建行程") })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            live?.let { s -> item { LiveCard(s) } }
            if (perms.isNotEmpty()) item { PermCard(perms) }
            com.busnotice.commute.Diag.last(ctx)?.let { d ->
                item {
                    Text("最近状态：$d", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            item(key = "ver") {
                androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 4.dp)) {
                    Text("build-${com.busnotice.commute.Updater.currentBuild(ctx)} · 检查更新",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { com.busnotice.commute.Updater.force.intValue++ })
                    val days = remember(resumeTick) { com.busnotice.commute.TraceLog.dayCount(ctx) }
                    if (days > 0) Text("    导出行程记录（${days} 天）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { com.busnotice.commute.TraceLog.share(ctx) })
                }
            }
            if (trips.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有行程", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("新建一条，选好上车站和时间就行", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(trips, key = { it.id }) { t ->
                TripCard(
                    trip = t,
                    running = live?.tripId == t.id,
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
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除「${t.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    Scheduler.cancel(ctx, t.id); TripStore.delete(ctx, t.id); confirmDelete = null; version++
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun LiveCard(s: LiveSnapshot) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                if (s.legCount > 1) "${s.tripName} · 第 ${s.legIndex + 1}/${s.legCount} 段 · ${s.legTitle}" else "${s.tripName} · ${s.legTitle}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.headline, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                if (!s.riding) {
                    FilledTonalButton(onClick = { CommuteService.instance?.onBoarded() },
                        contentPadding = PaddingValues(horizontal = 14.dp)) { Text("已上车") }
                    Spacer(Modifier.width(6.dp))
                }
                OutlinedButton(onClick = { CommuteService.stop(ctx) },
                    contentPadding = PaddingValues(horizontal = 14.dp)) { Text("结束") }
            }
            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            s.progress?.let {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { it.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
        }
    }
}

@Composable
private fun PermCard(perms: List<Perm>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text("还差几项设置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            perms.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(p.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    FilledTonalButton(onClick = p.action) { Text("去开启") }
                }
            }
        }
    }
}

@Composable
private fun TripCard(
    trip: Trip, running: Boolean,
    onToggle: (Boolean) -> Unit, onTest: () -> Unit, onEdit: () -> Unit, onReverse: () -> Unit, onDelete: () -> Unit,
    onApplyLearned: (Learn.Suggestion) -> Unit,
) {
    val ctx = LocalContext.current
    Card(shape = RoundedCornerShape(24.dp), onClick = onEdit) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(trip.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("${trip.daysText()} · ${trip.timeText()}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = trip.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                trip.legs.joinToString("  →  ") { it.title() }.ifEmpty { "还没有设置线路" },
                style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                trip.legs.firstOrNull()?.stopName()?.let { "从 $it 上车" } ?: "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (running) "运行中" else if (trip.enabled) Scheduler.nextTriggerText(trip, ctx) else "已暂停",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTest) { Icon(Icons.Default.PlayArrow, "测试") }
                IconButton(onClick = onReverse) { Icon(Icons.Default.Refresh, "生成回程") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除", Modifier.size(22.dp)) }
            }
        }
    }
}

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private fun missingPerms(ctx: Context, request: (Array<String>) -> Unit): List<Perm> {
    val out = mutableListOf<Perm>()
    val pkg = Uri.parse("package:${ctx.packageName}")
    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recover { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
        out += Perm("精确定位", "算你走到车站要多久") {
            request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    } else if (!granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
        out += Perm("后台定位：始终允许", "闹钟在后台叫醒时也能定位") {
            request(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }
    if (!granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)) {
        out += Perm("身体活动（计步）", "判断你下了地铁、开始走路") { request(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) }
    }
    if (!granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
        out += Perm("通知", "显示流体云胶囊") { request(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
    } else if (Build.VERSION.SDK_INT >= 36 &&
        !ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()) {
        out += Perm("实时通知 / 流体云", "让胶囊出现在状态栏") {
            open(Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
    }
    if (!ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        out += Perm("精确闹钟", "准时预热") { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)) }
    }
    if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
        out += Perm("电池不限制", "也请在 设置→电池 里允许后台活动和自启动") {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
        }
    }
    return out
}

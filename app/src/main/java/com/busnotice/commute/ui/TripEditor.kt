@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.busnotice.commute.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import com.busnotice.commute.ChelaileApi
import com.busnotice.commute.Learn
import com.busnotice.commute.Loc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import com.busnotice.commute.R
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.busnotice.commute.Candidate
import com.busnotice.commute.Leg
import com.busnotice.commute.Scheduler
import com.busnotice.commute.Trip
import com.busnotice.commute.TripStore

private val DAY_NAMES = arrayOf("一", "二", "三", "四", "五", "六", "日")

fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun TripEditor(
    trip: Trip,
    onChange: (Trip) -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onAddBus: (Int) -> Unit,
    onAddMetro: (Int) -> Unit,
    onEditShuttle: (Int, Int) -> Unit,
) {
    val ctx = LocalContext.current
    var pickTime by remember { mutableStateOf<Int?>(null) } // 0=开始 1=结束
    var locating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun updateLeg(i: Int, leg: Leg) = onChange(trip.copy(legs = trip.legs.toMutableList().also { it[i] = leg }))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (TripStore.get(ctx, trip.id) == null) "新建行程" else "编辑行程") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = {
                        val legs = trip.legs.filter { it.candidates.isNotEmpty() }
                        if (legs.isEmpty()) {
                            Toast.makeText(ctx, "至少添加一条线路", Toast.LENGTH_SHORT).show()
                        } else {
                            val t = trip.copy(legs = legs)
                            TripStore.upsert(ctx, t); Scheduler.schedule(ctx, t); onSaved()
                        }
                    }) { Text("保存", fontWeight = FontWeight.SemiBold) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                OutlinedTextField(
                    value = trip.name, onValueChange = { onChange(trip.copy(name = it)) },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Section("哪几天") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_NAMES.forEachIndexed { i, n ->
                            val on = trip.dayMask and (1 shl i) != 0
                            FilterChip(selected = on, onClick = { onChange(trip.copy(dayMask = trip.dayMask xor (1 shl i))) },
                                label = { Text(n) })
                        }
                    }
                }
            }
            item {
                Section("出门时间窗") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pickTime = 0 }) { Text(hhmm(trip.startMinute), style = MaterialTheme.typography.titleMedium) }
                        Text("  到  ")
                        OutlinedButton(onClick = { pickTime = 1 }) { Text(hhmm(trip.endMinute), style = MaterialTheme.typography.titleMedium) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("提前预热定位", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 20).forEach { m ->
                            FilterChip(selected = trip.warmupMin == m, onClick = { onChange(trip.copy(warmupMin = m)) },
                                label = { Text("$m 分钟") })
                        }
                    }
                }
            }
            item {
                Section("什么时候弹出胶囊") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !trip.leaveMode(), onClick = { onChange(trip.copy(leaveRadiusM = 0)) },
                            label = { Text("到点就弹") })
                        FilterChip(selected = trip.leaveMode(), onClick = {
                            if (!trip.leaveLat.isNaN()) onChange(trip.copy(leaveRadiusM = trip.leaveRadiusM.takeIf { it > 0 } ?: 300))
                            else if (!locating) {
                                locating = true
                                scope.launch {
                                    val l = Loc.current(ctx); locating = false
                                    if (l == null) Toast.makeText(ctx, "定位失败，到出发地再试", Toast.LENGTH_SHORT).show()
                                    else onChange(trip.copy(leaveLat = l.latitude, leaveLng = l.longitude, leaveRadiusM = 300))
                                }
                            }
                        }, label = { Text(if (locating) "定位中…" else "离开出发地才弹") })
                    }
                    Spacer(Modifier.height(6.dp))
                    if (trip.leaveMode()) {
                        Text("时间窗内每 4 分钟粗定位一次，走出这个范围就自动弹出。适合下班时间不固定：时间窗可以放宽，比如 17:30 到 22:00。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(200, 300, 500).forEach { r ->
                                FilterChip(selected = trip.leaveRadiusM == r, onClick = { onChange(trip.copy(leaveRadiusM = r)) },
                                    label = { Text("$r 米") })
                            }
                        }
                        TextButton(onClick = {
                            if (!locating) {
                                locating = true
                                scope.launch {
                                    val l = Loc.current(ctx); locating = false
                                    if (l == null) Toast.makeText(ctx, "定位失败", Toast.LENGTH_SHORT).show()
                                    else { onChange(trip.copy(leaveLat = l.latitude, leaveLng = l.longitude)); Toast.makeText(ctx, "已把这里设为出发地", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }) { Text("把我现在的位置设为出发地") }
                    } else {
                        Text("下班时间不固定的话，选「离开出发地才弹」，在公司里设一次就行。也可以在下拉快捷开关里加「通勤胶囊」，出门点一下。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(trip.legs) { i, leg ->
                LegCard(
                    index = i, leg = leg, isLast = i == trip.legs.size - 1,
                    onChange = { updateLeg(i, it) },
                    onRemove = { onChange(trip.copy(legs = trip.legs.toMutableList().also { l -> l.removeAt(i) })) },
                    onAddBus = { onAddBus(i) }, onAddMetro = { onAddMetro(i) },
                    onAddShuttle = { onEditShuttle(i, -1) },
                    onEditShuttle = { ci -> onEditShuttle(i, ci) },
                    learnedRide = remember(trip.id, i) { Learn.rideMin(ctx, trip.id, i) },
                )
            }
            item {
                OutlinedButton(onClick = { onChange(trip.copy(legs = trip.legs + Leg(emptyList()))) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("添加一段（换乘）")
                }
            }
        }
    }

    pickTime?.let { which ->
        val init = if (which == 0) trip.startMinute else trip.endMinute
        val st = rememberTimePickerState(initialHour = init / 60, initialMinute = init % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = null },
            confirmButton = {
                TextButton(onClick = {
                    val m = st.hour * 60 + st.minute
                    var t = if (which == 0) trip.copy(startMinute = m) else trip.copy(endMinute = m)
                    if (t.endMinute <= t.startMinute) t = t.copy(endMinute = (t.startMinute + 30).coerceAtMost(23 * 60 + 59))
                    onChange(t); pickTime = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text("取消") } },
            text = { TimePicker(state = st) },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun LegCard(
    index: Int, leg: Leg, isLast: Boolean,
    onChange: (Leg) -> Unit, onRemove: () -> Unit,
    onAddBus: () -> Unit, onAddMetro: () -> Unit, onAddShuttle: () -> Unit, onEditShuttle: (Int) -> Unit,
    learnedRide: Int? = null,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    editing?.let { ci ->
        leg.candidates.getOrNull(ci)?.let { c ->
            CandidateDialog(c, onDismiss = { editing = null }) { nc ->
                onChange(leg.copy(candidates = leg.candidates.toMutableList().also { it[ci] = nc })); editing = null
            }
        }
    }
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${index + 1} 段", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "删除这段") }
            }
            if (index > 0) {
                Stepper("从上一段下车走过来", leg.transferWalkMin, 0, 30) { onChange(leg.copy(transferWalkMin = it)) }
            }
            if (leg.candidates.isEmpty()) {
                Text("同一处上车的几种车都可以加进来，哪个先到提示哪个", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            leg.candidates.forEachIndexed { ci, c ->
                Row(
                    Modifier.clickable { if (c.type == Candidate.Type.SHUTTLE) onEditShuttle(ci) else editing = ci }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransitIcon(c)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.label() + when (c.type) {
                            Candidate.Type.BUS -> "  开往 ${c.endSn}"
                            Candidate.Type.SHUTTLE -> "  通勤车"
                            Candidate.Type.METRO -> "  地铁"
                        }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${c.stopName} 上车" + (if (c.alightName.isNotEmpty()) " · ${c.alightName} 下车" else "") +
                                when (c.type) {
                                    Candidate.Type.SHUTTLE -> " · " + c.departures.sorted().joinToString(" ") { hhmm(it) }
                                    Candidate.Type.METRO -> (if (c.metroDir.isNotEmpty()) " · 往${c.metroDir}" else "") +
                                        " · 高峰 ${c.peakHw()} / 平峰 ${c.offPeakHw()} 分钟一班"
                                    else -> ""
                                },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { if (c.type == Candidate.Type.SHUTTLE) onEditShuttle(ci) else editing = ci }) { Text("编辑") }
                    IconButton(onClick = {
                        onChange(leg.copy(candidates = leg.candidates.toMutableList().also { it.removeAt(ci) }))
                    }) { Icon(Icons.Default.Close, "移除", Modifier.size(20.dp)) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onAddBus, label = { Text("+ 公交") })
                AssistChip(onClick = onAddShuttle, label = { Text("+ 通勤车") })
                AssistChip(onClick = onAddMetro, label = { Text("+ 地铁") })
            }
            Stepper(if (isLast) "大约坐多久（快到下车站时提醒）" else "这段大约坐多久", leg.rideMin, 1, 120) {
                onChange(leg.copy(rideMin = it))
            }
            learnedRide?.let {
                Text("实测通常坐 $it 分钟，推算时按实测来", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 点已添加的公交/地铁弹出来改：地铁改方向和高峰/平峰间隔，两者都能改下车站 */
@Composable
fun CandidateDialog(c: Candidate, onDismiss: () -> Unit, onSave: (Candidate) -> Unit) {
    val ctx = LocalContext.current
    var alight by remember { mutableStateOf(c.alightName) }
    var peak by remember { mutableStateOf(c.peakHw()) }
    var off by remember { mutableStateOf(c.offPeakHw()) }
    var dir by remember { mutableStateOf(c.metroDir) }
    var dirs by remember { mutableStateOf<List<ChelaileApi.SubwayDir>>(emptyList()) }
    if (c.isMetro()) LaunchedEffect(c) {
        dirs = withContext(Dispatchers.IO) {
            runCatching { ChelaileApi.subwayDirs(c.lat, c.lng, c.stopName, c.cityId) }.getOrNull()
        }.orEmpty().filter { ChelaileApi.sameLine(c.name, it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${c.label()} · ${c.stopName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (c.isMetro()) {
                    if (dirs.isNotEmpty()) {
                        Text("方向", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            dirs.forEach { d ->
                                FilterChip(selected = dir == d.dest, onClick = { dir = d.dest }, label = { Text("开往 ${d.dest}") })
                            }
                        }
                    }
                    Stepper("高峰几分钟一班", peak, 1, 30) { peak = it }
                    Stepper("平峰几分钟一班", off, 1, 30) { off = it }
                }
                OutlinedTextField(alight, { alight = it }, label = { Text("下车站") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (c.isMetro()) Text("在地铁段每次手动点「已上车」，会拿真实上车时间修正推算，越用越准。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val changedSchedule = c.isMetro() && (peak != c.peakHw() || off != c.offPeakHw() || dir != c.metroDir)
                if (changedSchedule) Learn.resetMetro(ctx, c)
                onSave(c.copy(
                    alightName = alight.trim(),
                    alightLat = if (alight.trim() == c.alightName) c.alightLat else Double.NaN,
                    alightLng = if (alight.trim() == c.alightName) c.alightLng else Double.NaN,
                    headwayMin = if (c.isMetro()) peak else c.headwayMin,
                    offPeakHeadwayMin = if (c.isMetro()) off else c.offPeakHeadwayMin,
                    metroDir = dir,
                ))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 公交蓝色大巴、地铁绿色 M、通勤车橙色：列表里一眼分开 */
@Composable
fun TransitIcon(c: Candidate) {
    val (res, color) = when (c.type) {
        Candidate.Type.METRO -> R.drawable.ic_metro to Color(0xFF1AAE6F)
        Candidate.Type.SHUTTLE -> R.drawable.ic_bus to Color(0xFFFF8A3D)
        Candidate.Type.BUS -> R.drawable.ic_bus to Color(0xFF3D7BFF)
    }
    Icon(painterResource(res), null, tint = color, modifier = Modifier.size(22.dp))
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("$value 分钟", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

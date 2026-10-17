@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.busnotice.commute.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.busnotice.commute.ChelaileApi
import com.busnotice.commute.Learn
import com.busnotice.commute.MetroRoutes
import com.busnotice.commute.Loc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import com.busnotice.commute.R
import kotlinx.coroutines.launch
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

    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    GlassPage(
        title = if (TripStore.get(ctx, trip.id) == null) "新建行程" else "编辑行程",
        onBack = onBack,
        actions = {
            PillButton(onClick = {
                val legs = trip.legs.filter { it.candidates.isNotEmpty() }
                if (legs.isEmpty()) {
                    Toast.makeText(ctx, "至少添加一条线路", Toast.LENGTH_SHORT).show()
                } else {
                    val t = trip.copy(legs = legs)
                    TripStore.upsert(ctx, t); Scheduler.schedule(ctx, t); onSaved()
                }
            }, modifier = Modifier.padding(start = 8.dp)) { Text("保存", fontWeight = FontWeight.Bold) }
        },
    ) {
            item {
                GlassField(
                    value = trip.name, onValueChange = { onChange(trip.copy(name = it)) },
                    label = "名称", singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Section("哪几天") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAY_NAMES.forEachIndexed { i, n ->
                            val on = trip.dayMask and (1 shl i) != 0
                            GlassChip(selected = on, onClick = { onChange(trip.copy(dayMask = trip.dayMask xor (1 shl i))) },
                                label = n, modifier = Modifier.widthIn(min = 44.dp))
                        }
                    }
                }
            }
            item {
                Section("出门时间窗") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeBox(hhmm(trip.startMinute), dark) { pickTime = 0 }
                        Text("到", color = dim, modifier = Modifier.padding(horizontal = 12.dp))
                        TimeBox(hhmm(trip.endMinute), dark) { pickTime = 1 }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("提前预热定位", fontSize = 14.sp, color = dim)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 15, 20).forEach { m ->
                            GlassChip(selected = trip.warmupMin == m, onClick = { onChange(trip.copy(warmupMin = m)) },
                                label = "$m 分钟")
                        }
                    }
                }
            }
            item {
                Section("什么时候弹出胶囊") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassChip(selected = !trip.leaveMode(), onClick = { onChange(trip.copy(leaveRadiusM = 0)) },
                            label = "到点就弹")
                        GlassChip(selected = trip.leaveMode(), onClick = {
                            if (!trip.leaveLat.isNaN()) onChange(trip.copy(leaveRadiusM = trip.leaveRadiusM.takeIf { it > 0 } ?: 300))
                            else if (!locating) {
                                locating = true
                                scope.launch {
                                    val l = Loc.current(ctx); locating = false
                                    if (l == null) Toast.makeText(ctx, "定位失败，到出发地再试", Toast.LENGTH_SHORT).show()
                                    else onChange(trip.copy(leaveLat = l.latitude, leaveLng = l.longitude, leaveRadiusM = 300))
                                }
                            }
                        }, label = if (locating) "定位中…" else "离开出发地才弹")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (trip.leaveMode()) {
                        Text("时间窗内每 2 分钟粗定位一次，走出这个范围就自动弹出。适合下班时间不固定：时间窗可以放宽，比如 17:30 到 22:00。",
                            fontSize = 12.sp, lineHeight = 17.sp, color = dim)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(100, 150, 200, 300).forEach { r ->
                                GlassChip(selected = trip.leaveRadiusM == r, onClick = { onChange(trip.copy(leaveRadiusM = r)) },
                                    label = "$r 米")
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        PillButton(secondary = true, onClick = {
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
                            fontSize = 12.sp, lineHeight = 17.sp, color = dim)
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
                    nextStop = trip.legs.getOrNull(i + 1)?.candidates?.firstOrNull(),
                    onSplit = {
                        // 一段里上车站不同的几条线 → 按上车站拆成先后几段（地铁在前）
                        val groups = leg.candidates.groupBy { it.stopName }.values
                            .sortedBy { g -> if (g.any { it.type == Candidate.Type.METRO }) 0 else 1 }
                        val parts = groups.mapIndexed { k, g -> Leg(g, rideMin = leg.rideMin, transferWalkMin = if (k == 0) leg.transferWalkMin else 3) }
                        onChange(trip.copy(legs = trip.legs.toMutableList().also { l -> l.removeAt(i); l.addAll(i, parts) }))
                    },
                )
            }
            item {
                PillButton(secondary = true, onClick = { onChange(trip.copy(legs = trip.legs + Leg(emptyList()))) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("添加一段（换乘）")
                }
            }
    }

    pickTime?.let { which ->
        val init = if (which == 0) trip.startMinute else trip.endMinute
        val st = rememberTimePickerState(initialHour = init / 60, initialMinute = init % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = null },
            shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
            confirmButton = {
                DialogButton("确定", primary = true, onClick = {
                    val m = st.hour * 60 + st.minute
                    var t = if (which == 0) trip.copy(startMinute = m) else trip.copy(endMinute = m)
                    if (t.endMinute <= t.startMinute) t = t.copy(endMinute = (t.startMinute + 30).coerceAtMost(23 * 60 + 59))
                    onChange(t); pickTime = null
                })
            },
            dismissButton = { DialogButton("取消", onClick = { pickTime = null }) },
            text = { TimePicker(state = st, colors = glassTimePickerColors(dark)) },
        )
    }
}

/** 一个小节 = 一张玻璃卡：小标题 + 内容 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassCard {
        SectionLabel(title)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** 时间窗里的时间块：半透明圆角框 + 大号等宽数字，点开选时间 */
@Composable
private fun TimeBox(text: String, dark: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(FieldShape)
            .background(Color.White.copy(alpha = if (dark) 0.08f else 0.55f))
            .border(1.dp, if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f), FieldShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark), style = TnumStyle)
    }
}

@Composable
private fun LegCard(
    onSplit: () -> Unit = {},
    nextStop: Candidate? = null,
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
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${index + 1} 段", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "删除这段", tint = dim) }
            }
            if (index > 0) {
                Stepper("从上一段下车走过来", leg.transferWalkMin, 0, 30) { onChange(leg.copy(transferWalkMin = it)) }
            }
            if (leg.candidates.isEmpty()) {
                Text("同一处上车的几种车都可以加进来，哪个先到提示哪个；先坐 A 再换 B 请用下面的「添加一段（换乘）」",
                    fontSize = 12.sp, lineHeight = 17.sp, color = dim, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (leg.candidates.map { it.stopName }.distinct().size > 1) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(FieldShape)
                        .background(Fuse.Amber.copy(alpha = if (dark) 0.18f else 0.12f))
                        .border(1.dp, Fuse.Amber.copy(alpha = 0.35f), FieldShape)
                        .padding(12.dp),
                ) {
                    Text("这一段里的线路是「二选一」：哪个先到提示哪个。它们上车站不同，如果你是先坐一个再换另一个，应该拆成两段。",
                        fontSize = 13.sp, lineHeight = 18.sp, color = ink)
                    Spacer(Modifier.height(8.dp))
                    PillButton(onClick = onSplit) { Text("拆成换乘（按上车站分段）") }
                }
            }
            if (leg.candidates.isNotEmpty()) {
                // 已添加的线路：放在一块更浅的圆角底上，行间淡分隔线
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(FieldShape)
                        .background(Color.White.copy(alpha = if (dark) 0.05f else 0.4f)),
                ) {
                    leg.candidates.forEachIndexed { ci, c ->
                        if (ci > 0) HorizontalDivider(Modifier.padding(start = 12.dp), thickness = 1.dp, color = Fuse.hairline(dark))
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (c.type == Candidate.Type.SHUTTLE) onEditShuttle(ci) else editing = ci }
                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.label() + when (c.type) {
                                    Candidate.Type.BUS -> "  开往 ${c.endSn}"
                                    Candidate.Type.SHUTTLE -> "  通勤车"
                                    Candidate.Type.METRO -> "  地铁"
                                }, fontSize = 15.sp, color = ink)
                                Text(
                                    "${c.stopName} 上车" + (if (c.alightName.isNotEmpty()) " · ${c.alightName} 下车" else "") +
                                        when (c.type) {
                                            Candidate.Type.SHUTTLE -> " · " + c.departures.sorted().joinToString(" ") { hhmm(it) }
                                            Candidate.Type.METRO -> (if (c.metroDir.isNotEmpty()) " · 往${c.metroDir}" else "") +
                                                " · 高峰 ${c.peakHw()} / 平峰 ${c.offPeakHw()} 分钟一班"
                                            else -> ""
                                        },
                                    fontSize = 12.sp, lineHeight = 16.sp, color = dim,
                                )
                            }
                            TextButton(onClick = { if (c.type == Candidate.Type.SHUTTLE) onEditShuttle(ci) else editing = ci }) {
                                Text("编辑", color = ink)
                            }
                            IconButton(onClick = {
                                onChange(leg.copy(candidates = leg.candidates.toMutableList().also { it.removeAt(ci) }))
                            }) { Icon(Icons.Default.Close, "移除", Modifier.size(20.dp), tint = dim) }
                        }
                    }
                }
            }
            FlowRow(
                Modifier.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassChip(selected = false, onClick = onAddBus, label = "+ 公交")
                GlassChip(selected = false, onClick = onAddShuttle, label = "+ 通勤车")
                GlassChip(selected = false, onClick = onAddMetro, label = "+ 地铁")
            }
            // 地铁段：按站数自动估，手填的只在拿不到数据时兜底
            val metro = leg.candidates.firstOrNull { it.isMetro() }.takeIf { leg.isMetroOnly() }
            val ctx = LocalContext.current
            val target = metro?.let {
                if (!it.alightLat.isNaN()) Triple(it.alightName.ifEmpty { "下车站" }, it.alightLat, it.alightLng)
                else nextStop?.let { n -> Triple(it.alightName.ifEmpty { n.stopName }, n.lat, n.lng) }
            }
            var route by remember(metro, target) { mutableStateOf<ChelaileApi.MetroRoute?>(null) }
            LaunchedEffect(metro, target) {
                if (metro != null && target != null) route = withContext(Dispatchers.IO) {
                    MetroRoutes.get(ctx, metro, target.first, target.second, target.third)
                }
            }
            Stepper(if (isLast) "大约坐多久（快到下车站时提醒）" else if (metro != null) "拿不到站数时按这个算" else "这段大约坐多久",
                leg.rideMin, 1, 120) {
                onChange(leg.copy(rideMin = it))
            }
            val noteColor = if (dark) Color(0xFF7FE0A6) else Color(0xFF167A40)
            when {
                learnedRide != null -> Text("实测通常坐 $learnedRide 分钟，推算时按实测来", fontSize = 12.sp, lineHeight = 17.sp,
                    color = noteColor)
                route != null -> Text(route!!.let { r ->
                    "自动：${r.stations.first().name} → ${r.stations.last().name}，共 ${r.hops} 站，约 ${(r.durSec + 59) / 60} 分钟；坐满 3 次后改用你的实测用时"
                }, fontSize = 12.sp, lineHeight = 17.sp, color = noteColor)
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
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DialogShape, containerColor = Fuse.surface(dark), titleContentColor = ink, textContentColor = ink,
        title = { Text("${c.label()} · ${c.stopName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (c.isMetro()) {
                    if (dirs.isNotEmpty()) {
                        SectionLabel("方向")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            dirs.forEach { d ->
                                GlassChip(selected = dir == d.dest, onClick = { dir = d.dest }, label = "开往 ${d.dest}")
                            }
                        }
                    }
                    Stepper("高峰几分钟一班", peak, 1, 30) { peak = it }
                    Stepper("平峰几分钟一班", off, 1, 30) { off = it }
                }
                GlassField(alight, { alight = it }, label = "下车站", singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (c.isMetro()) Text("在地铁段每次手动点「已上车」，会拿真实上车时间修正推算，越用越准。",
                    fontSize = 12.sp, lineHeight = 17.sp, color = Fuse.dim(dark))
            }
        },
        confirmButton = {
            DialogButton("保存", primary = true, onClick = {
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
            })
        },
        dismissButton = { DialogButton("取消", onClick = onDismiss) },
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
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 14.sp, color = ink, modifier = Modifier.weight(1f))
        RoundButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, dark = dark, size = 34.dp) {
            Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
        }
        Text("$value 分钟", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ink, style = TnumStyle,
            modifier = Modifier.padding(horizontal = 10.dp))
        RoundButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, dark = dark, size = 34.dp) {
            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
        }
    }
}

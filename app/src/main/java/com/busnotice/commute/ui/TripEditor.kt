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
            itemsIndexed(trip.legs) { i, leg ->
                LegCard(
                    index = i, leg = leg, isLast = i == trip.legs.size - 1,
                    onChange = { updateLeg(i, it) },
                    onRemove = { onChange(trip.copy(legs = trip.legs.toMutableList().also { l -> l.removeAt(i) })) },
                    onAddBus = { onAddBus(i) }, onAddMetro = { onAddMetro(i) },
                    onAddShuttle = { onEditShuttle(i, -1) },
                    onEditShuttle = { ci -> onEditShuttle(i, ci) },
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
) {
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
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                    Candidate.Type.METRO -> if (c.headwayMin > 0) " · 约 ${c.headwayMin} 分钟一班" else ""
                                    else -> ""
                                },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (c.type == Candidate.Type.SHUTTLE) TextButton(onClick = { onEditShuttle(ci) }) { Text("编辑") }
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
            if (!isLast) {
                Stepper("这段大约坐多久", leg.rideMin, 1, 120) { onChange(leg.copy(rideMin = it)) }
            }
        }
    }
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

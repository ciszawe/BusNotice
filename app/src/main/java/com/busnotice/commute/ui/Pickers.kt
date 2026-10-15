@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.busnotice.commute.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.busnotice.commute.Candidate
import com.busnotice.commute.ChelaileApi
import com.busnotice.commute.Loc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ 公共小部件

@Composable
private fun PickerScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp),
            content = content,
        )
    }
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "清空") } },
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun LazyListScope.header(text: String) = item {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

private fun LazyListScope.loading() = item {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private fun LazyListScope.hint(text: String) = item {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp))
}

private fun distText(m: Int) = if (m >= 1000) "%.1f 公里".format(m / 1000.0) else "$m 米"

// ------------------------------------------------------------------ 公交

private sealed interface BusStage {
    data object Search : BusStage
    data class Station(val title: String, val physicalStId: String, val namesakeStId: String, val fallback: List<ChelaileApi.NearStop>) : BusStage
    data class Line(val name: String, val dirs: List<ChelaileApi.LineDir>, val dirIndex: Int) : BusStage
    data class Alight(val detail: ChelaileApi.LineDetail, val board: ChelaileApi.StopPoint) : BusStage
}

/** 选公交：搜线路/站点，或从附近站点进入 → 选上车站 → 选下车站（可跳过） */
@Composable
fun BusPicker(onBack: () -> Unit, onPicked: (Candidate) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<BusStage>(BusStage.Search) }
    var query by remember { mutableStateOf("") }
    var nearby by remember { mutableStateOf<List<ChelaileApi.NearStop>?>(null) }
    var nearbyError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ChelaileApi.SearchResult?>(null) }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loc = Loc.current(ctx)
        if (loc == null) { nearbyError = "拿不到位置，可以直接搜索"; return@LaunchedEffect }
        nearby = withContext(Dispatchers.IO) {
            runCatching { ChelaileApi.nearbyStops(loc.latitude, loc.longitude) }.getOrNull()
        }?.filter { !it.isSubway && it.lines.isNotEmpty() }
        if (nearby == null) nearbyError = "附近站点加载失败，可以直接搜索"
    }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { result = null; return@LaunchedEffect }
        delay(350)
        searching = true
        result = withContext(Dispatchers.IO) { runCatching { ChelaileApi.search(q) }.getOrNull() }
        searching = false
    }

    /** 选定了上车站：拉线路详情拿坐标，进入「选下车站」 */
    fun board(lineId: String, order: Int, sId: String) {
        if (busy) return
        busy = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { ChelaileApi.lineDetail(lineId) }.getOrNull() }
            busy = false
            val st = d?.stations?.firstOrNull { it.order == order } ?: d?.stations?.firstOrNull { it.sId == sId }
            if (d == null || st == null) Toast.makeText(ctx, "线路详情加载失败", Toast.LENGTH_SHORT).show()
            else stage = BusStage.Alight(d, st)
        }
    }

    fun finish(d: ChelaileApi.LineDetail, b: ChelaileApi.StopPoint, alight: String) = onPicked(
        Candidate(
            type = Candidate.Type.BUS, name = d.name, stopName = b.name, lat = b.lat, lng = b.lng,
            lineId = d.lineId, stationId = b.sId, endSn = d.endSn, targetOrder = b.order, alightName = alight,
        )
    )

    when (val s = stage) {
        BusStage.Search -> PickerScaffold("添加公交", onBack) {
            item { SearchField(query, "搜线路号或站名") { query = it } }
            if (busy) loading()
            if (query.isBlank()) {
                header("附近站点")
                when {
                    nearby != null && nearby!!.isEmpty() -> hint("附近没有公交站，试试搜索")
                    nearby != null -> items(nearby!!.groupBy { it.name }.values.toList()) { group ->
                        val st = group.first()
                        ListItem(
                            headlineContent = { Text(st.name) },
                            supportingContent = {
                                Text(group.flatMap { it.lines }.map { it.name }.distinct().take(8).joinToString(" · "), maxLines = 1)
                            },
                            trailingContent = { Text(distText(group.minOf { it.distanceM })) },
                            leadingContent = { Icon(Icons.Default.LocationOn, null) },
                            modifier = Modifier.clickable {
                                stage = BusStage.Station(st.name, st.physicalStId, st.namesakeStId, group)
                            },
                        )
                    }
                    nearbyError != null -> hint(nearbyError!!)
                    else -> loading()
                }
            } else {
                val r = result
                when {
                    searching && r == null -> loading()
                    r == null -> hint("搜索失败，检查网络")
                    else -> {
                        val lines = r.lines.filter { !it.isSubway }
                        val stations = r.stations.filter { !it.isSubway }
                        if (lines.isEmpty() && stations.isEmpty()) hint("没有找到")
                        if (lines.isNotEmpty()) header("线路")
                        items(lines) { l ->
                            ListItem(
                                headlineContent = { Text("${l.name}路", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text(l.dirs.first().let { "${it.startSn} ⇄ ${it.endSn}" }) },
                                modifier = Modifier.clickable { stage = BusStage.Line(l.name, l.dirs, 0) },
                            )
                        }
                        if (stations.isNotEmpty()) header("站点")
                        items(stations) { st ->
                            ListItem(
                                headlineContent = { Text(st.name) },
                                leadingContent = { Icon(Icons.Default.LocationOn, null) },
                                modifier = Modifier.clickable {
                                    stage = BusStage.Station(st.name, st.physicalStId, st.namesakeStId, emptyList())
                                },
                            )
                        }
                    }
                }
            }
        }

        is BusStage.Station -> {
            var platforms by remember(s) { mutableStateOf<List<ChelaileApi.NearStop>?>(null) }
            var failed by remember(s) { mutableStateOf(false) }
            LaunchedEffect(s) {
                val r = if (s.physicalStId.isNotEmpty()) withContext(Dispatchers.IO) {
                    runCatching { ChelaileApi.stationLines(s.physicalStId, s.namesakeStId) }.getOrNull()
                } else null
                platforms = (r?.takeIf { it.isNotEmpty() } ?: s.fallback).filter { it.lines.isNotEmpty() }
                failed = platforms!!.isEmpty()
            }
            PickerScaffold(s.title, { stage = BusStage.Search }) {
                if (busy) loading()
                val ps = platforms
                when {
                    ps == null -> loading()
                    failed -> hint("这个站没查到线路")
                    else -> ps.forEachIndexed { i, p ->
                        header(if (ps.size > 1) "站台 ${i + 1}" else "经过的线路")
                        items(p.lines) { l ->
                            ListItem(
                                headlineContent = { Text("${l.name}路", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("开往 ${l.endSn}") },
                                modifier = Modifier.clickable { board(l.lineId, l.targetOrder, p.sId) },
                            )
                        }
                    }
                }
            }
        }

        is BusStage.Line -> {
            val dir = s.dirs[s.dirIndex]
            var detail by remember(dir.lineId) { mutableStateOf<ChelaileApi.LineDetail?>(null) }
            var failed by remember(dir.lineId) { mutableStateOf(false) }
            LaunchedEffect(dir.lineId) {
                detail = withContext(Dispatchers.IO) { runCatching { ChelaileApi.lineDetail(dir.lineId) }.getOrNull() }
                failed = detail == null
            }
            PickerScaffold("${s.name}路 · 选上车站", { stage = BusStage.Search }) {
                if (s.dirs.size > 1) item {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.dirs.forEachIndexed { i, d ->
                            FilterChip(selected = i == s.dirIndex, onClick = { stage = s.copy(dirIndex = i) },
                                label = { Text("开往 ${d.endSn}") })
                        }
                    }
                }
                val d = detail
                when {
                    failed -> hint("线路详情加载失败")
                    d == null -> loading()
                    else -> items(d.stations) { st ->
                        ListItem(
                            headlineContent = { Text(st.name) },
                            leadingContent = { Text("${st.order}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { stage = BusStage.Alight(d, st) },
                        )
                    }
                }
            }
        }

        is BusStage.Alight -> PickerScaffold("在哪下车？", { stage = BusStage.Search }) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("${s.detail.name}路 · ${s.board.name} 上车 · 开往 ${s.detail.endSn}",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("选了下车站就能一键生成回程，也可以跳过", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { finish(s.detail, s.board, "") }) { Text("跳过") }
                }
                HorizontalDivider()
            }
            items(s.detail.stations.filter { it.order > s.board.order }) { st ->
                ListItem(
                    headlineContent = { Text(st.name) },
                    supportingContent = { Text("${st.order - s.board.order} 站") },
                    modifier = Modifier.clickable { finish(s.detail, s.board, st.name) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 地铁

/** 地铁没有实时数据：选站 + 填线路名、发车间隔和下车站 */
@Composable
fun MetroPicker(onBack: () -> Unit, onPicked: (Candidate) -> Unit) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<ChelaileApi.SearchStation>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<ChelaileApi.SearchStation?>(null) }
    var lineName by remember { mutableStateOf("") }
    var headway by remember { mutableStateOf(6) }
    var alight by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { result = null; return@LaunchedEffect }
        delay(350)
        searching = true
        result = withContext(Dispatchers.IO) { runCatching { ChelaileApi.search(q).stations }.getOrNull() }
            ?.filter { it.isSubway }
        searching = false
    }

    val c = chosen
    if (c == null) {
        PickerScaffold("添加地铁", onBack) {
            item { SearchField(query, "搜地铁站名") { query = it } }
            val r = result
            when {
                query.isBlank() -> hint("输入在哪个地铁站上车")
                searching && r == null -> loading()
                r == null -> hint("搜索失败，检查网络")
                r.isEmpty() -> hint("没有找到地铁站")
                else -> items(r) { st ->
                    ListItem(
                        headlineContent = { Text(st.name) },
                        leadingContent = { Icon(Icons.Default.LocationOn, null) },
                        modifier = Modifier.clickable { chosen = st },
                    )
                }
            }
        }
    } else {
        PickerScaffold("${c.name} 上车", { chosen = null }) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(lineName, { lineName = it }, label = { Text("线路，如 2号线") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(alight, { alight = it }, label = { Text("下车站（可选，用于生成回程）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Stepper("大约几分钟一班", headway, 1, 30) { headway = it }
                    Button(onClick = {
                        onPicked(Candidate(
                            type = Candidate.Type.METRO, name = lineName.trim().ifEmpty { "地铁" },
                            stopName = c.name, lat = c.lat, lng = c.lng, headwayMin = headway, alightName = alight.trim(),
                        ))
                    }, modifier = Modifier.fillMaxWidth()) { Text("添加") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 自定义通勤车

private val TIME_RE = Regex("(\\d{1,2})\\s*[:：.]\\s*(\\d{2})")

/** 自定义通勤车：名称、上车点、发车时刻 */
@Composable
fun ShuttleEditor(initial: Candidate?, onBack: () -> Unit, onDone: (Candidate) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "通勤车") }
    var stopName by remember { mutableStateOf(initial?.stopName ?: "") }
    var lat by remember { mutableStateOf(initial?.lat) }
    var lng by remember { mutableStateOf(initial?.lng) }
    var times by remember { mutableStateOf(initial?.departures?.sorted()?.joinToString(" ") { hhmm(it) } ?: "") }
    var locating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<ChelaileApi.SearchStation>?>(null) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) { result = null; return@LaunchedEffect }
        delay(350)
        result = withContext(Dispatchers.IO) { runCatching { ChelaileApi.search(q).stations }.getOrNull() }
    }
    val parsed = TIME_RE.findAll(times).mapNotNull { m ->
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }.distinct().sorted().toList()

    PickerScaffold(if (initial == null) "添加通勤车" else "编辑通勤车", onBack) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    times, { times = it }, label = { Text("发车时刻，如 07:20 07:40 08:00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    supportingText = { Text(if (parsed.isEmpty()) "还没识别到时刻" else "共 ${parsed.size} 班：" + parsed.joinToString(" ") { hhmm(it) }) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("上车点", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (lat != null && stopName.isNotEmpty()) "已选：$stopName" else if (lat != null) "已用当前位置" else "还没选",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        if (!locating) {
                            locating = true
                            scope.launch {
                                val l = Loc.current(ctx)
                                locating = false
                                if (l == null) Toast.makeText(ctx, "定位失败", Toast.LENGTH_SHORT).show()
                                else { lat = l.latitude; lng = l.longitude; if (stopName.isEmpty()) stopName = "通勤车上车点" }
                            }
                        }
                    }) { Text(if (locating) "定位中…" else "用我现在的位置") }
                }
                OutlinedTextField(stopName, { stopName = it }, label = { Text("上车点名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                SearchField(query, "搜一个离上车点近的站，借用它的位置") { query = it }
            }
        }
        result?.let { r ->
            items(r.take(10)) { st ->
                ListItem(
                    headlineContent = { Text(st.name) },
                    supportingContent = { Text(if (st.isSubway) "地铁站" else "公交站") },
                    modifier = Modifier.clickable { lat = st.lat; lng = st.lng; stopName = st.name; query = "" },
                )
            }
        }
        item {
            Button(
                onClick = {
                    val la = lat; val ln = lng
                    when {
                        parsed.isEmpty() -> Toast.makeText(ctx, "请填发车时刻", Toast.LENGTH_SHORT).show()
                        la == null || ln == null -> Toast.makeText(ctx, "请选上车点", Toast.LENGTH_SHORT).show()
                        else -> onDone(Candidate(
                            type = Candidate.Type.SHUTTLE, name = name.trim().ifEmpty { "通勤车" },
                            stopName = stopName.trim().ifEmpty { "通勤车上车点" }, lat = la, lng = ln, departures = parsed,
                        ))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("保存") }
        }
    }
}

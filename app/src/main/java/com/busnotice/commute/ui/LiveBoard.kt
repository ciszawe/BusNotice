package com.busnotice.commute.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.busnotice.commute.Candidate
import com.busnotice.commute.CommuteService
import com.busnotice.commute.LiveSnapshot
import com.busnotice.commute.Trip

/** 线路名缩短：纯数字线路（如「23路」「2号线」）只留数字，否则原样 */
fun shortLine(name: String): String {
    val m = Regex("^(地铁)?\\s*(\\d+)\\s*(路|号线|线)?$").find(name.trim())
    return m?.groupValues?.get(2) ?: name.trim().removeSuffix("路")
}

fun lineColor(c: Candidate?, fallbackName: String = ""): Color = when {
    c == null -> if (Candidate.looksLikeMetro(fallbackName)) Fuse.MetroRed else Fuse.BusGreen
    c.type == Candidate.Type.METRO -> Fuse.MetroRed
    c.type == Candidate.Type.SHUTTLE -> Fuse.Shuttle
    else -> Fuse.BusGreen
}

/** 彩色圆角线路牌 */
@Composable
fun LineBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp).widthIn(min = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
    }
}

private data class BigNum(val prefix: String, val num: String, val unit: String)

/** 「5分后走」「约3分」「待12分」「4站」「500米」→ 数字 + 单位；其它（即将到站 / 下车 / 换乘…）返回 null */
private fun parseBig(headline: String): BigNum? {
    val m = Regex("^(约|待)?\\s*(\\d+)\\s*((分|站|米|秒).*)$").find(headline.trim()) ?: return null
    return BigNum(m.groupValues[1], m.groupValues[2], m.groupValues[3])
}

private val Tnum = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun LiveBoard(s: LiveSnapshot, trip: Trip?, dark: Boolean) {
    val ctx = LocalContext.current
    val leg = trip?.legs?.getOrNull(s.legIndex)
    val cands = leg?.candidates.orEmpty()
    val shown = cands.filter { s.legTitle.contains(it.label()) }.take(3).ifEmpty { cands.take(1) }
    val main = shown.firstOrNull()
    val headText = when {
        s.riding && !main?.alightName.isNullOrEmpty() -> "到 ${main!!.alightName}"
        !main?.stopName.isNullOrEmpty() -> main!!.stopName
        else -> s.legTitle
    }

    Column(Modifier.fillMaxWidth().board(dark).padding(18.dp)) {
        // 顶部：线路牌 + 方向 | 行程名 + 分段条
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (shown.isEmpty()) LineBadge(shortLine(s.legTitle), lineColor(null, s.legTitle))
            shown.forEachIndexed { i, c ->
                if (i > 0) Spacer(Modifier.width(4.dp))
                LineBadge(shortLine(c.name.ifEmpty { c.label() }), lineColor(c))
            }
            Spacer(Modifier.width(8.dp))
            Text(headText, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(s.tripName, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(s.legCount.coerceIn(1, 6)) { i ->
                    val c = when {
                        i == s.legIndex -> Fuse.Yellow
                        i < s.legIndex -> Color.White.copy(alpha = 0.55f)
                        else -> Color.White.copy(alpha = 0.25f)
                    }
                    Box(Modifier.width(18.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c))
                }
            }
        }

        // 大号倒计时
        AnimatedContent(
            targetState = s.headline,
            transitionSpec = {
                (slideInVertically { it / 3 } + fadeIn()) togetherWith (slideOutVertically { -it / 3 } + fadeOut())
            },
            label = "headline",
        ) { h ->
            val big = parseBig(h)
            val glow = Shadow(Fuse.Yellow.copy(alpha = 0.45f), Offset.Zero, 24f)
            if (big != null) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
                    if (big.prefix.isNotEmpty()) {
                        Text(big.prefix, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp, end = 4.dp))
                    }
                    Text(big.num, color = Fuse.Yellow, fontSize = 80.sp, fontWeight = FontWeight.Bold,
                        style = Tnum.copy(shadow = glow, lineHeight = 80.sp))
                    Text(big.unit, color = Color.White, fontSize = 20.sp, maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
                }
            } else {
                Text(h, color = Fuse.Yellow, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = glow, lineHeight = 52.sp), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            }
        }

        // 详情：「下一站 XX」里的站名标黄
        val station = Regex("下一站\\s*(?:就是\\s*)?(\\S+)").find(s.detail)?.groups?.get(1)
        Text(
            buildAnnotatedString {
                if (station == null) append(s.detail) else {
                    append(s.detail.substring(0, station.range.first))
                    withStyle(SpanStyle(color = Fuse.Yellow)) { append(station.value) }
                    append(s.detail.substring(station.range.last + 1))
                }
            },
            color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )

        val passed = s.stopsPassed
        val total = s.stopsTotal
        if (passed != null && total != null && total > 0) {
            StopDots(passed.coerceIn(0, total), total)
        } else s.progress?.let { p ->
            Box(
                Modifier.padding(top = 16.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0, 100) / 100f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Fuse.Yellow))
            }
        }

        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!s.riding) {
                Pill("已上车", Fuse.Yellow, Color(0xFF111111), null, Modifier.weight(1f)) { CommuteService.instance?.onBoarded() }
            }
            Pill("结束", Color.White.copy(alpha = 0.12f), Color.White, Color.White.copy(alpha = 0.2f), Modifier.weight(1f)) {
                CommuteService.stop(ctx)
            }
        }
    }
}

/** 进程重启后、服务还没刷新出新状态时的占位看板：行程名 +「正在恢复…」，可以直接结束 */
@Composable
fun RestoringBoard(tripName: String, dark: Boolean) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().board(dark).padding(18.dp)) {
        Text(tripName, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("正在恢复…", color = Fuse.Yellow, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text("行程还在进行，马上刷新到最新状态", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 14.dp)) {
            Pill("结束", Color.White.copy(alpha = 0.12f), Color.White, Color.White.copy(alpha = 0.2f), Modifier.weight(1f)) {
                CommuteService.stop(ctx)
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, border: Color?, modifier: Modifier, onClick: () -> Unit) {
    var m = modifier.height(44.dp).clip(CircleShape).background(bg)
    if (border != null) m = m.border(1.dp, border, CircleShape)
    Box(m.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 站点圆点：已过白色、当前黄色带光、未到暗色、下车站黄色圆环 */
@Composable
private fun StopDots(passed: Int, total: Int) {
    val cur = passed.coerceAtMost(total - 1).coerceAtLeast(0)
    val dense = total > 14   // 站太多就不画中间的小点，只画线
    Column(Modifier.padding(top = 16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val pad = 8.dp.toPx()
            val y = size.height / 2
            val w = size.width - pad * 2
            fun x(i: Int) = pad + w * i / total
            val lineW = 3.dp.toPx()
            val dim = Color.White.copy(alpha = 0.2f)
            // 线
            if (cur > 0) drawLine(Color.White, Offset(x(0), y), Offset(x(cur), y), lineW, StrokeCap.Round)
            drawLine(dim, Offset(x(cur), y), Offset(x(total), y), lineW, StrokeCap.Round)
            if (cur < total) {
                drawLine(
                    Brush.horizontalGradient(listOf(Fuse.Yellow, Fuse.Yellow.copy(alpha = 0.2f)), x(cur), x(cur + 1)),
                    Offset(x(cur), y), Offset(x(cur + 1), y), lineW, StrokeCap.Round,
                )
            }
            // 点
            val r = 5.dp.toPx()
            for (i in 0 until total) {
                if (i == cur) continue
                if (dense && i != 0) continue
                drawCircle(if (i < cur) Color.White else Color.White.copy(alpha = 0.35f), r * (if (dense) 0.8f else 1f), Offset(x(i), y))
            }
            // 下车站：黄圈
            drawCircle(Fuse.Yellow, 6.dp.toPx(), Offset(x(total), y), style = Stroke(2.dp.toPx()))
            // 当前：黄点 + 光晕
            val c = Offset(x(cur), y)
            drawCircle(Brush.radialGradient(listOf(Fuse.Yellow.copy(alpha = 0.6f), Fuse.Yellow.copy(alpha = 0f)), c, 14.dp.toPx()), 14.dp.toPx(), c)
            drawCircle(Fuse.Yellow, 8.dp.toPx(), c)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val labelColor = Color.White.copy(alpha = 0.5f)
            Text("上车", color = labelColor, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart))
            Text("下车", color = labelColor, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterEnd))
            val f = cur.toFloat() / total
            if (f in 0.15f..0.85f) {
                // 「当前」居中到当前点下方（标签约 24dp 宽）
                val cx = 8.dp + (maxWidth - 16.dp) * f - 12.dp
                Text("当前", color = labelColor, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart).offset(x = cx))
            }
        }
    }
}

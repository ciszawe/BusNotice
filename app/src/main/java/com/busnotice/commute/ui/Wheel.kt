package com.busnotice.commute.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

// 滚轮选择器：两个会吸附的滚轮（时 00–23、分 00–59），中间一条玻璃高亮带，上下渐隐，每换一格轻震一下

private val ItemH = 48.dp
private const val VISIBLE = 5           // 可见 5 行，选中的在正中
private const val PAD = VISIBLE / 2     // 上下各垫 2 个空行，第一项和最后一项也能滚到正中

/** 单个滚轮：value 是选中的下标（0 until count），滚停时回调 onValue */
@Composable
fun WheelColumn(
    count: Int,
    value: Int,
    onValue: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    width: Dp = 84.dp,
) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value.coerceIn(0, count - 1))
    val fling = rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Start)
    val haptic = LocalHapticFeedback.current
    val onValueNow by rememberUpdatedState(onValue)
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { ItemH.toPx() }
    // 顶行下标 = 选中项下标（前面垫了 PAD 个空行）
    val selected by remember(state) {
        derivedStateOf {
            val i = state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
            i.coerceIn(0, count - 1)
        }
    }
    LaunchedEffect(state) {
        var first = true
        snapshotFlow { selected }.distinctUntilChanged().collect { v ->
            if (!first) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            first = false
            onValueNow(v)
        }
    }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = modifier.width(width).height(ItemH * VISIBLE)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // 上下渐隐
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.32f to Color.Black, 0.68f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        items(count + PAD * 2) { k ->
            val idx = k - PAD
            Box(
                Modifier.fillMaxWidth().height(ItemH)
                    .graphicsLayer {
                        // 离中心越远越淡、越小
                        val center = state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / itemPx + PAD
                        val d = abs(k - center).coerceAtMost(3f)
                        alpha = (1f - 0.28f * d).coerceAtLeast(0.15f)
                        scaleX = 1f - 0.07f * d; scaleY = scaleX
                    }
                    .let {
                        if (idx in 0 until count) it.clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                        ) { scope.launch { state.animateScrollToItem(idx) } } else it
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (idx in 0 until count) {
                    Text(label(idx), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = ink, style = TnumStyle)
                }
            }
        }
    }
}

/** 时 : 分 两个滚轮，后面垫一条玻璃高亮带；minute = 一天中的第几分钟 */
@Composable
fun TimeWheel(minute: Int, onMinute: (Int) -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    var h by remember { mutableIntStateOf((minute / 60).coerceIn(0, 23)) }
    var m by remember { mutableIntStateOf((minute % 60).coerceIn(0, 59)) }
    Box(modifier.fillMaxWidth().height(ItemH * VISIBLE), contentAlignment = Alignment.Center) {
        // 中间高亮带（玻璃）
        Box(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(ItemH + 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = if (dark) 0.10f else 0.65f))
                .border(1.dp, if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.95f), RoundedCornerShape(16.dp))
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            WheelColumn(24, h, { h = it; onMinute(h * 60 + m) }, { "%02d".format(it) })
            Text(":", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Fuse.ink(dark), modifier = Modifier.padding(horizontal = 6.dp))
            WheelColumn(60, m, { m = it; onMinute(h * 60 + m) }, { "%02d".format(it) })
        }
    }
}

/** 选时间的玻璃底部弹层：标题 + 滚轮 + 取消/确定胶囊（24 小时制） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeWheelSheet(title: String, initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    var picked by remember { mutableIntStateOf(initialMinute.coerceIn(0, 23 * 60 + 59)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Fuse.surface(dark), contentColor = ink,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
            Spacer(Modifier.height(12.dp))
            TimeWheel(picked, { picked = it })
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(onClick = onDismiss, secondary = true, modifier = Modifier.weight(1f)) { Text("取消") }
                PillButton(onClick = { onConfirm(picked) }, modifier = Modifier.weight(1f)) { Text("确定", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

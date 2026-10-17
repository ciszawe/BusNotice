package com.busnotice.commute.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

private data class Blob(val color: Color, val cx: Float, val cy: Float, val rx: Float, val ry: Float, val circle: Boolean = false)

// 位置按屏幕宽高比例；circle = 半径按屏幕高度比例
private val LightBlobs = listOf(
    Blob(Color(0xFFFFB8CD), 0.12f, 0.06f, 0.36f, 0.36f, circle = true),
    Blob(Color(0xFF9FC2FF), 0.98f, 0.26f, 0.38f, 0.38f, circle = true),
    Blob(Color(0xFFFF9EC0), 0.10f, 0.58f, 0.60f, 0.22f),
    Blob(Color(0xFF8FB4FF), 0.95f, 0.66f, 0.55f, 0.20f),
    Blob(Color(0xFF7FE3C0), 0.30f, 0.84f, 0.70f, 0.22f),
    Blob(Color(0xFFFFD27A), 0.90f, 0.96f, 0.50f, 0.18f),
)
private val DarkBlobs = listOf(
    Blob(Color(0xFF5A2F6E), 0.10f, 0.05f, 0.38f, 0.38f, circle = true),
    Blob(Color(0xFF24468A), 0.98f, 0.30f, 0.38f, 0.38f, circle = true),
    Blob(Color(0xFF7A2F62), 0.08f, 0.58f, 0.60f, 0.22f),
    Blob(Color(0xFF2A4FA0), 0.96f, 0.66f, 0.55f, 0.20f),
    Blob(Color(0xFF13695A), 0.30f, 0.84f, 0.70f, 0.22f),
    Blob(Color(0xFF7A5A1A), 0.92f, 0.96f, 0.50f, 0.18f),
)

private fun DrawScope.blob(b: Blob) {
    val w = size.width; val h = size.height
    val c = Offset(b.cx * w, b.cy * h)
    // 椭圆：先按竖直半径画圆形渐变，再横向拉伸
    val ry = b.ry * h
    val rx = if (b.circle) ry else b.rx * w
    val brush = Brush.radialGradient(
        0f to b.color, 0.45f to b.color.copy(alpha = 0.55f), 1f to b.color.copy(alpha = 0f),
        center = c, radius = ry,
    )
    scale(scaleX = rx / ry, scaleY = 1f, pivot = c) { drawCircle(brush, radius = ry, center = c) }
}

/** 整屏的柔和彩色渐变底，放在 Scaffold 后面 */
@Composable
fun GradientBackground(dark: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(if (dark) Fuse.DarkBg else Fuse.LightBg)
        (if (dark) DarkBlobs else LightBlobs).forEach { blob(it) }
    }
}

val GlassShape = RoundedCornerShape(24.dp)

/** 浅玻璃：半透明白色渐变 + 白色细边 + 顶部一道高光（不做真模糊） */
fun Modifier.glass(dark: Boolean, shape: Shape = GlassShape): Modifier = this
    .clip(shape)
    .background(
        Brush.linearGradient(
            if (dark) listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f))
            else listOf(Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.38f)),
            start = Offset.Zero, end = Offset.Infinite,
        ),
        shape,
    )
    .drawBehind {
        val hl = 18.dp.toPx().coerceAtMost(size.height)
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.10f else 0.45f), Color.White.copy(alpha = 0f)),
                startY = 0f, endY = hl,
            ),
            size = Size(size.width, hl),
        )
    }
    .border(1.dp, Color.White.copy(alpha = if (dark) 0.20f else 0.95f), shape)

val BoardShape = RoundedCornerShape(28.dp)

/** 烟玻璃看板：深色半透明 + 右上角一团黄色光晕 */
fun Modifier.board(dark: Boolean): Modifier = this
    .clip(BoardShape)
    .background(
        Brush.linearGradient(
            if (dark) listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))
            else listOf(Color(0xE01E1C30), Color(0xEB12121E)),
            start = Offset.Zero, end = Offset.Infinite,
        ),
        BoardShape,
    )
    .drawBehind {
        val c = Offset(size.width - 10.dp.toPx(), 30.dp.toPx())
        val r = 110.dp.toPx()
        drawCircle(
            Brush.radialGradient(0f to Fuse.Yellow.copy(alpha = 0.35f), 0.65f to Fuse.Yellow.copy(alpha = 0f), 1f to Fuse.Yellow.copy(alpha = 0f), center = c, radius = r),
            radius = r, center = c,
        )
        val hl = 16.dp.toPx().coerceAtMost(size.height)
        drawRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0f)), endY = hl),
            size = Size(size.width, hl),
        )
    }
    .border(1.dp, Color.White.copy(alpha = 0.18f), BoardShape)

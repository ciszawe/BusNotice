package com.busnotice.commute.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF3D6BE0), primaryContainer = Color(0xFFDCE5FF), onPrimaryContainer = Color(0xFF001849),
    secondary = Color(0xFF586178), tertiary = Color(0xFFFF8A3D),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF), primaryContainer = Color(0xFF1F4BB8), onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFC0C6DD), tertiary = Color(0xFFFFB68C),
)

/** 跟随系统深色模式；Android 12+ 跟随壁纸取色 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 首页「烟玻璃看板 + 浅玻璃列表」用到的固定配色 */
object Fuse {
    val Yellow = Color(0xFFFFD23F)
    val BusGreen = Color(0xFF1F9D55)
    val MetroRed = Color(0xFFD7263D)
    val Shuttle = Color(0xFF5B5BD6)
    val Amber = Color(0xFFE08A00)
    val Night = Color(0xFF1E1C30)
    val LightBg = Color(0xFFF1EEFF)
    val DarkBg = Color(0xFF0F0F18)
    val LightInk = Color(0xFF1B1B2B)
    val DarkInk = Color(0xFFF1F1F6)
    val LightDim = Color(0xFF5A5A6A)
    val DarkDim = Color(0xFFA9A9BD)

    fun ink(dark: Boolean) = if (dark) DarkInk else LightInk
    fun dim(dark: Boolean) = if (dark) DarkDim else LightDim
    /** 主操作色：浅色模式深色胶囊，深色模式黄色胶囊（和新建行程按钮一致） */
    fun accent(dark: Boolean) = if (dark) Yellow else Night
    fun onAccent(dark: Boolean) = if (dark) Color(0xFF111111) else Color.White
    /** 对话框、菜单的底色 */
    fun surface(dark: Boolean) = if (dark) Color(0xFF22212F) else Color(0xFFF8F6FF)
    /** 列表分隔线、浅描边 */
    fun hairline(dark: Boolean) = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
}

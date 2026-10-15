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

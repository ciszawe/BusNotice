package com.busnotice.commute.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePickerColors
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 二级页面（编辑行程、选线路、通勤车、更新弹窗）共用的玻璃风格小部件，和首页同一套配色

val FieldShape = RoundedCornerShape(16.dp)
val DialogShape = RoundedCornerShape(28.dp)
val MenuShape = RoundedCornerShape(16.dp)
val TnumStyle = TextStyle(fontFeatureSettings = "tnum")

/** 二级页面骨架：渐变底 + 透明顶栏（返回 + 标题）+ 列表；顶栏在列表滚动后出现半透明底，内容不会画到状态栏下面 */
@Composable
fun GlassPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    horizontalPadding: Dp = 16.dp,
    content: LazyListScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val scrolled by remember(state) { derivedStateOf { state.canScrollBackward } }
    Box(Modifier.fillMaxSize()) {
        GradientBackground(dark)
        CompositionLocalProvider(LocalContentColor provides Fuse.ink(dark)) {
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(title, onBack, dark, scrolled, actions)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(
                        WindowInsets.navigationBars.union(WindowInsets.ime).union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    ),
                    state = state,
                    contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 6.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
fun GlassTopBar(title: String, onBack: () -> Unit, dark: Boolean, scrolled: Boolean, actions: @Composable RowScope.() -> Unit = {}) {
    val ink = Fuse.ink(dark)
    val bg by animateColorAsState(
        if (scrolled) (if (dark) Fuse.DarkBg else Fuse.LightBg).copy(alpha = 0.78f) else Color.Transparent, label = "topbar",
    )
    Column(Modifier.fillMaxWidth().background(bg)) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(64.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(onClick = onBack, dark = dark) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
        if (scrolled) HorizontalDivider(thickness = 1.dp, color = Fuse.hairline(dark))
    }
}

/** 圆形半透明小按钮（返回、「⋯」） */
@Composable
fun RoundButton(onClick: () -> Unit, dark: Boolean, size: Dp = 40.dp, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Color.White.copy(alpha = if (dark) 0.10f else 0.6f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 主操作胶囊：浅色模式深色、深色模式黄色；secondary = 半透明白色带细边 */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = CircleShape,
        colors = if (secondary) ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = if (dark) 0.10f else 0.55f), contentColor = ink,
            disabledContainerColor = Color.White.copy(alpha = if (dark) 0.05f else 0.3f), disabledContentColor = ink.copy(alpha = 0.4f),
        ) else ButtonDefaults.buttonColors(
            containerColor = Fuse.accent(dark), contentColor = Fuse.onAccent(dark),
            disabledContainerColor = Fuse.accent(dark).copy(alpha = 0.35f), disabledContentColor = Fuse.onAccent(dark).copy(alpha = 0.6f),
        ),
        border = if (secondary) BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.95f)) else null,
        contentPadding = PaddingValues(horizontal = 20.dp),
        content = content,
    )
}

/** 选择胶囊：选中 = 深色/黄色实心，未选 = 半透明白色带细边 */
@Composable
fun GlassChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val dark = isSystemInDarkTheme()
    val ink = Fuse.ink(dark)
    val bg = if (selected) Fuse.accent(dark) else Color.White.copy(alpha = if (dark) 0.08f else 0.5f)
    val border = if (selected) Color.Transparent else if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.08f)
    Box(
        modifier.clip(CircleShape).background(bg).border(1.dp, border, CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Fuse.onAccent(dark) else ink.copy(alpha = if (enabled) 1f else 0.4f), maxLines = 1)
    }
}

/** 小节标题 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fuse.dim(dark), modifier = modifier)
}

/** 玻璃卡片 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    Column(modifier.fillMaxWidth().glass(dark).padding(padding), content = content)
}

/** 玻璃卡片里的一行（代替 ListItem） */
@Composable
fun GlassRow(
    headline: String,
    onClick: (() -> Unit)?,
    supporting: String? = null,
    bold: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(headline, fontSize = 16.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = Fuse.ink(dark))
            if (supporting != null) Text(supporting, fontSize = 13.sp, color = Fuse.dim(dark), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (supportingContent != null) Box(Modifier.padding(top = 4.dp)) { supportingContent() }
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

/** 一组行放进同一张玻璃卡，中间是很淡的分隔线 */
fun <T> LazyListScope.glassGroup(rows: List<T>, key: Any? = null, row: @Composable (T) -> Unit) {
    if (rows.isEmpty()) return
    item(key = key) {
        val dark = isSystemInDarkTheme()
        Column(Modifier.fillMaxWidth().glass(dark)) {
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 1.dp, color = Fuse.hairline(dark))
                row(r)
            }
        }
    }
}

/** 列表里的小节标题 */
fun LazyListScope.sectionHeader(text: String) = item {
    SectionLabel(text, Modifier.padding(start = 6.dp, top = 6.dp))
}

/** 适合玻璃的输入框：半透明填充、16dp 圆角、无下划线，聚焦时一圈主色描边 */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val dark = isSystemInDarkTheme()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        focused -> Fuse.accent(dark)
        dark -> Color.White.copy(alpha = 0.16f)
        else -> Color.Black.copy(alpha = 0.08f)
    }
    Column(modifier) {
        TextField(
            value = value, onValueChange = onValueChange, singleLine = singleLine,
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            keyboardOptions = keyboardOptions,
            shape = FieldShape, colors = glassFieldColors(dark), interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().border(if (focused) 1.5.dp else 1.dp, borderColor, FieldShape),
        )
        // 说明文字放在框外（玻璃框下面），和原来 supportingText 位置一致
        if (supportingText != null) {
            CompositionLocalProvider(LocalContentColor provides Fuse.dim(dark)) {
                Box(Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp)) {
                    androidx.compose.material3.ProvideTextStyle(TextStyle(fontSize = 12.sp)) { supportingText() }
                }
            }
        }
    }
}

@Composable
fun glassFieldColors(dark: Boolean): TextFieldColors {
    val ink = Fuse.ink(dark)
    val dim = Fuse.dim(dark)
    return TextFieldDefaults.colors(
        focusedTextColor = ink, unfocusedTextColor = ink,
        focusedContainerColor = Color.White.copy(alpha = if (dark) 0.12f else 0.75f),
        unfocusedContainerColor = Color.White.copy(alpha = if (dark) 0.07f else 0.5f),
        disabledContainerColor = Color.White.copy(alpha = if (dark) 0.04f else 0.3f),
        cursorColor = Fuse.accent(dark),
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
        focusedLabelColor = dim, unfocusedLabelColor = dim,
        focusedPlaceholderColor = dim, unfocusedPlaceholderColor = dim,
        focusedLeadingIconColor = dim, unfocusedLeadingIconColor = dim,
        focusedTrailingIconColor = dim, unfocusedTrailingIconColor = dim,
    )
}

/** 对话框里的文字按钮：确认用主色（浅色深、深色黄），其它用正文色 */
@Composable
fun DialogButton(text: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    val dark = isSystemInDarkTheme()
    val c = if (primary) (if (dark) Fuse.Yellow else Fuse.Night) else Fuse.dim(dark)
    TextButton(onClick = onClick, enabled = enabled, shape = CircleShape) {
        Text(text, color = if (enabled) c else c.copy(alpha = 0.4f), fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun glassTimePickerColors(dark: Boolean): TimePickerColors {
    val ink = Fuse.ink(dark)
    val soft = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    return TimePickerDefaults.colors(
        clockDialColor = soft,
        clockDialSelectedContentColor = Fuse.onAccent(dark),
        clockDialUnselectedContentColor = ink,
        selectorColor = Fuse.accent(dark),
        containerColor = Fuse.surface(dark),
        periodSelectorBorderColor = Fuse.hairline(dark),
        periodSelectorSelectedContainerColor = Fuse.accent(dark),
        periodSelectorUnselectedContainerColor = Color.Transparent,
        periodSelectorSelectedContentColor = Fuse.onAccent(dark),
        periodSelectorUnselectedContentColor = ink,
        timeSelectorSelectedContainerColor = Fuse.accent(dark),
        timeSelectorUnselectedContainerColor = soft,
        timeSelectorSelectedContentColor = Fuse.onAccent(dark),
        timeSelectorUnselectedContentColor = ink,
    )
}

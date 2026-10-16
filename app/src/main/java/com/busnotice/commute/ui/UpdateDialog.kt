package com.busnotice.commute.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.busnotice.commute.Updater
import kotlinx.coroutines.launch
import java.io.File

/** 打开 App 时检查新版本，有就弹窗；tick 变化（回到前台）时再查一次（3 小时节流） */
@Composable
fun UpdateDialog(tick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rel by remember { mutableStateOf<Updater.Release?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf(false) }
    var waitPerm by remember { mutableStateOf(false) }

    LaunchedEffect(tick) { if (rel == null) rel = Updater.check(ctx) }
    val forced = Updater.force.intValue
    LaunchedEffect(forced) {
        if (forced == 0) return@LaunchedEffect
        rel = Updater.check(ctx, force = true)
        if (rel == null) android.widget.Toast.makeText(ctx,
            if (Updater.lastOk) "已经是最新版 build-${Updater.currentBuild(ctx)}" else "连不上 GitHub，稍后再试",
            android.widget.Toast.LENGTH_SHORT).show()
    }
    // 去设置里打开「安装未知应用」后回来，自动接着安装
    LaunchedEffect(tick) { if (waitPerm) file?.let { waitPerm = !Updater.install(ctx, it) } }

    val r = rel ?: return
    val downloading = progress >= 0f && file == null && !error
    AlertDialog(
        onDismissRequest = { if (!downloading) rel = null },
        title = { Text("有新版本 build-${r.build}") },
        text = {
            Column {
                Text("当前 build-${Updater.currentBuild(ctx)}。" + if (error) "下载失败，网络不好时可以稍后再试。" else "")
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = {
                val have = file
                if (have != null) waitPerm = !Updater.install(ctx, have)
                else {
                    error = false; progress = 0f
                    scope.launch {
                        val f = Updater.download(ctx, r) { progress = it }
                        if (f == null) error = true else { file = f; waitPerm = !Updater.install(ctx, f) }
                    }
                }
            }) { Text(if (file != null) "安装" else if (downloading) "下载中…" else "更新") }
        },
        dismissButton = {
            if (!downloading) Row2(
                onLater = { rel = null },
                onSkip = { Updater.skip(ctx, r.build); rel = null },
            )
        },
    )
}

@Composable
private fun Row2(onLater: () -> Unit, onSkip: () -> Unit) {
    androidx.compose.foundation.layout.Row {
        TextButton(onClick = onSkip) { Text("跳过此版") }
        TextButton(onClick = onLater) { Text("稍后") }
    }
}

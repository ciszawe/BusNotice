package com.busnotice.commute

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.busnotice.commute.ui.App
import com.busnotice.commute.ui.AppTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START_TRIP = "startTrip"
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_BACKGROUND = "background"
    }

    /** 每次回到前台 +1，让界面重新检查权限、刷新下次预热时间 */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LiveNotifier.ensureChannels(this)
        Scheduler.rescheduleAll(this)
        setContent { AppTheme { App(resumeTick.intValue) } }
        handleStart(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleStart(intent)
    }

    /** 从兜底提醒点进来：在前台启动服务，系统允许 */
    private fun handleStart(i: android.content.Intent?) {
        val id = i?.getStringExtra(EXTRA_START_TRIP) ?: return
        i.removeExtra(EXTRA_START_TRIP)
        runCatching { CommuteService.start(this, id, manual = i.getBooleanExtra(EXTRA_MANUAL, false)) }
        // 从快捷开关点进来：启动完就退到后台，直接看胶囊
        if (i.getBooleanExtra(EXTRA_BACKGROUND, false)) moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        // 进 App 时如果行程还没结束、胶囊却没了，重新拉起
        CommuteService.resumeIfNeeded(this)
    }
}

package com.busnotice.commute

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.time.LocalDateTime

/**
 * 下拉快捷开关「通勤胶囊」：下班时间不固定时，出门前点一下就开始。
 * 再点一下关闭。选哪条行程：正处在时间窗里的优先，否则选开始时间离现在最近的。
 */
class QuickTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (CommuteService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = pick()?.name ?: "未设置行程"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (CommuteService.instance != null) {
            CommuteService.stop(this)
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        val t = pick() ?: return
        // 从前台 Activity 启动定位服务，系统才放行
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_TRIP, t.id)
            .putExtra(MainActivity.EXTRA_MANUAL, true)
            .putExtra(MainActivity.EXTRA_BACKGROUND, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 9, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }

    private fun pick(): Trip? {
        val trips = TripStore.all(this).filter { it.legs.isNotEmpty() }
        val now = LocalDateTime.now()
        trips.firstOrNull { it.enabled && Scheduler.inWindow(it, now) }?.let { return it }
        val m = now.hour * 60 + now.minute
        return trips.minByOrNull { minOf(Math.floorMod(it.startMinute - m, 1440), Math.floorMod(m - it.endMinute, 1440)) }
    }
}

package com.recorderx.app.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.recorderx.app.MainActivity
import com.recorderx.app.R

/**
 * The "pull down the shade, tap a tile" shortcut. Starting a recording needs
 * the system's MediaProjection consent dialog, which can only be shown from
 * a visible Activity -- a tile has no window of its own to show it from --
 * so tapping the tile while idle simply opens the app (one tap closer than
 * hunting for the launcher icon, and the user immediately sees Start
 * Recording). Tapping while a recording is already running/paused needs no
 * Activity at all and stops it directly, which is the shortcut this was
 * mainly requested for.
 */
class RecordingTileService : TileService() {

    private val stateListener: () -> Unit = { updateTile() }

    override fun onStartListening() {
        super.onStartListening()
        RecordingSessionState.addListener(stateListener)
        updateTile()
    }

    override fun onStopListening() {
        RecordingSessionState.removeListener(stateListener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (RecordingSessionState.phase) {
            RecordingSessionState.Phase.IDLE -> openApp()
            RecordingSessionState.Phase.RECORDING, RecordingSessionState.Phase.PAUSED ->
                startService(RecordingService.buildStopIntent(this))
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent-based overload is rejected on API 34+ targeting 34+;
            // PendingIntent is the only supported path there.
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when (RecordingSessionState.phase) {
            RecordingSessionState.Phase.IDLE -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label_idle)
                tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
            }
            RecordingSessionState.Phase.RECORDING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label_recording)
                tile.icon = Icon.createWithResource(this, R.drawable.ic_stop)
            }
            RecordingSessionState.Phase.PAUSED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label_paused)
                tile.icon = Icon.createWithResource(this, R.drawable.ic_stop)
            }
        }
        tile.updateTile()
    }
}

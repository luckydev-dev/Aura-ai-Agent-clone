package com.example.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class AuraTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isActive = AuraForegroundService.isCallActive.value
        if (isActive) {
            AuraForegroundService.endCall(this)
        } else {
            AuraForegroundService.startCall(this)
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isActive = AuraForegroundService.isCallActive.value
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isActive) "Aura Active" else "Aura Voice"
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher_round)
        tile.updateTile()
    }
}

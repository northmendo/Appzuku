package com.yn.shappky;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Manages the Quick Settings tile for starting and stopping the Shappky service
public class ShappkyQuickTile extends TileService {

    @Override
    public void onStartListening() {
       super.onStartListening();
       Tile tile = getQsTile();
        if (tile == null) return;
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_shappky));
        tile.setLabel("Shappky Service");
        boolean isServiceRunning = ShappkyService.isRunning();
        tile.setState(isServiceRunning ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
    
    // Called when the user taps the tile
    // Purpose: Toggle the service on or off and immediately update the tile appearance
    @Override
    public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        if (tile == null) return;

        // Request notification permission on Android 13+ if not granted
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        // Start or stop the service based on the current tile state
        if (tile.getState() == Tile.STATE_INACTIVE) {
            startForegroundService(new Intent(this, ShappkyService.class));
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("Running...");
        } else {
            stopService(new Intent(this, ShappkyService.class));
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Shappky Service");
        }
       tile.setIcon(Icon.createWithResource(this, R.drawable.ic_shappky));
       tile.updateTile();
    }
}
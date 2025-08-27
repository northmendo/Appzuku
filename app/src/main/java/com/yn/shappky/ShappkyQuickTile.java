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

    // Called when the Quick Settings panel is opened or the tile is added
    // Purpose: Keep the tile state in sync with the service status
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState(); // Update tile to match service state
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
            Intent intent = new Intent(this, ShappkyService.class);
            startForegroundService(intent);
        } else {
            Intent intent = new Intent(this, ShappkyService.class);
            stopService(intent);
        }
        
        updateTileState(); // Refresh the tile appearance
    }

    // Updates the tile's label, icon, and state based on whether the service is running
    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        // Set tile state and label based on service status
        if (ShappkyService.isRunning()) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("Running...");
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Shappky Service");
        }

        // Set the tile icon and apply the changes
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_shappky));
        tile.updateTile();
    }
}
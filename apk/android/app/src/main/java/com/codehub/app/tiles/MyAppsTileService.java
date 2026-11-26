package com.codehub.app.tiles;

import android.content.Intent;
import android.service.quicksettings.TileService;

import com.codehub.app.NativeMyAppsActivity;

public class MyAppsTileService extends TileService {
    @Override
    public void onClick() {
        super.onClick();
        try {
            Intent intent = new Intent(this, NativeMyAppsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivityAndCollapse(intent);
        } catch (Throwable ignored) {
        }
    }
}

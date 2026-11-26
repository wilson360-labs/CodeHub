package com.codehub.app.tiles;

import android.content.Intent;
import android.service.quicksettings.TileService;

public class CodeStreamTileService extends TileService {
    @Override
    public void onClick() {
        super.onClick();
        try {
            Intent intent = new Intent(this, com.tensei.ytxtube.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivityAndCollapse(intent);
        } catch (Throwable ignored) {
        }
    }
}

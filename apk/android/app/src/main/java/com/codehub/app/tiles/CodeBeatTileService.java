package com.codehub.app.tiles;

import android.content.Intent;
import android.service.quicksettings.TileService;

import com.codehub.app.MainActivity;

public class CodeBeatTileService extends TileService {
    @Override
    public void onClick() {
        super.onClick();
        open("codebeat");
    }

    private void open(String target) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("open_native", target);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivityAndCollapse(intent);
        } catch (Throwable ignored) {
        }
    }
}

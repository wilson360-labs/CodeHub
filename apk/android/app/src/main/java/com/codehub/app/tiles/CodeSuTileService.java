package com.codehub.app.tiles;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class CodeSuTileService extends TileService {
    @Override
    public void onClick() {
        super.onClick();
        feedback("Abriendo CodeSu…");
        try {
            Intent intent = new Intent(this, com.codehub.app.codesu.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivityAndCollapse(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "No se pudo abrir CodeSu", Toast.LENGTH_SHORT).show();
        }
    }

    private void feedback(String label) {
        try {
            Tile tile = getQsTile();
            if (tile != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.setSubtitle(label);
                tile.setState(Tile.STATE_ACTIVE);
                tile.updateTile();
            }
        } catch (Throwable ignored) {}
    }
}

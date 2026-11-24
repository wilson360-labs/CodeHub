package com.codehub.app.tiles;

import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.data.Killer;
import com.codehub.app.codesu.model.AppInfo;
import com.codehub.app.codesu.util.AppListHelper;

import java.util.List;

/** QS tile: cierra apps en segundo plano usando CodeSu/Killer. */
public class KillAppsTileService extends TileService {

    @Override
    public void onClick() {
        super.onClick();
        final Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("Cerrando apps");
            tile.updateTile();
        }

        App.executorService.execute(() -> {
            try {
                final List<AppInfo> targets = AppListHelper.getFilteredAppsList(App.context, true);
                final String label;
                if (targets.isEmpty()) {
                    label = "Todo limpio";
                } else {
                    boolean ok = Killer.killListOfApps(targets);
                    label = ok ? targets.size() + " cerradas" : "Falló";
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    Tile t = getQsTile();
                    if (t != null) {
                        t.setState(Tile.STATE_INACTIVE);
                        t.setLabel(label);
                        t.updateTile();
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            Tile r = getQsTile();
                            if (r != null) {
                                r.setState(Tile.STATE_INACTIVE);
                                r.setLabel("CodeHub Kill");
                                r.updateTile();
                            }
                        }, 2500);
                    }
                });
            } catch (Throwable ignored) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    Tile t = getQsTile();
                    if (t != null) {
                        t.setState(Tile.STATE_INACTIVE);
                        t.setLabel("CodeHub Kill");
                        t.updateTile();
                    }
                });
            }
        });
    }
}

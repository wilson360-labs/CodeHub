package com.codehub.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Build;
import android.widget.RemoteViews;

import java.util.List;

/**
 * Widget de acceso directo completo de CodeHub (pantalla de inicio).
 *
 * Rejilla 3x3 con los nueve destinos de HomeShortcuts. Cada celda usa el
 * mismo índice vectorial y el mismo deep link que los atajos dinámicos,
 * así el widget no tiene rutas propias que se puedan desincronizar.
 */
public class QuickAccessWidgetProvider extends AppWidgetProvider {

    private static final int[] CELLS = {
        R.id.quick_cell_wile, R.id.quick_cell_weather, R.id.quick_cell_apps,
        R.id.quick_cell_catalog, R.id.quick_cell_tools, R.id.quick_cell_codebeat,
        R.id.quick_cell_codestream, R.id.quick_cell_codesu, R.id.quick_cell_profile
    };
    private static final int[] ICONS = {
        R.id.quick_icon_wile, R.id.quick_icon_weather, R.id.quick_icon_apps,
        R.id.quick_icon_catalog, R.id.quick_icon_tools, R.id.quick_icon_codebeat,
        R.id.quick_icon_codestream, R.id.quick_icon_codesu, R.id.quick_icon_profile
    };
    private static final int[] LABELS = {
        R.id.quick_label_wile, R.id.quick_label_weather, R.id.quick_label_apps,
        R.id.quick_label_catalog, R.id.quick_label_tools, R.id.quick_label_codebeat,
        R.id.quick_label_codestream, R.id.quick_label_codesu, R.id.quick_label_profile
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            mgr.updateAppWidget(id, buildViews(context));
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick);
        List<HomeShortcuts.Option> options = HomeShortcuts.options();
        views.setTextViewText(R.id.quick_header_hint,
            context.getString(R.string.quick_access_count, options.size()));

        int count = Math.min(CELLS.length, options.size());
        for (int i = 0; i < count; i++) {
            HomeShortcuts.Option option = options.get(i);
            views.setImageViewResource(ICONS[i], option.icon);
            views.setTextViewText(LABELS[i], option.shortLabel);
            views.setContentDescription(CELLS[i], option.longLabel);
            views.setOnClickPendingIntent(CELLS[i],
                PendingIntent.getActivity(context, 210 + i, HomeShortcuts.intentFor(context, option), flags()));
        }
        return views;
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
    }
}

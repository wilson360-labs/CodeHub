package com.codehub.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Widget de accesos rápidos de CodeHub (pantalla de inicio).
 *
 * Los widgets son interfaces estáticos que no hacen trabajo en segundo
 * plano; mantienen la misma URL base del resto de la app.
 *
 * Pinta un grid 2x2 con las 4 secciones principales (WIL.E, Clima,
 * Catálogo Open Source y Herramientas), cada una con su icono del set
 * de shortcuts y un deep link del dominio propio que abre MainActivity
 * en la sección correcta.
 */
public class QuickAccessWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            mgr.updateAppWidget(id, buildViews(context));
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick);
        views.setOnClickPendingIntent(R.id.quick_cell_wile, openApp(context, 210,
            "https://wilson360-labs.vercel.app/index.html#open-to-work"));
        views.setOnClickPendingIntent(R.id.quick_cell_weather, openApp(context, 211,
            "https://wilson360-labs.vercel.app/index.html#weather-section"));
        views.setOnClickPendingIntent(R.id.quick_cell_catalog, openApp(context, 212,
            "https://wilson360-labs.vercel.app/opensource"));
        views.setOnClickPendingIntent(R.id.quick_cell_tools, openApp(context, 213,
            "https://wilson360-labs.vercel.app/tools"));
        return views;
    }

    private static PendingIntent openApp(Context context, int requestCode, String url) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("open_url", url);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(context, requestCode, intent, flags);
    }
}
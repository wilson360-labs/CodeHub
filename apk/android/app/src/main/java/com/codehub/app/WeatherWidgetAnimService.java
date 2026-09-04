package com.codehub.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Servicio de ANIMACIÓN del widget de clima: alimenta el AdapterViewFlipper
 * (R.id.wx_anim_emoji) con los frames del emoji según la condición actual
 * y el día/noche. El flip entre frames lo anima el PROPIO sistema en el
 * proceso del launcher (host del widget), así que el emoji "cobra vida"
 * sin gastar CPU ni batería de CodeHub.
 *
 * Los frames se construyen desde la última copia guardada en
 * SharedPreferences("codehub") — las mismas prefs que usa
 * WeatherWidgetProvider — por lo que no hay que esperar a un refresh.
 * Si el launcher no puede enlazar este servicio, el widget sigue
 * mostrando el emoji estático (widget_icon) porque el provider registra
 * ese id como "empty view" del flipper: nada se rompe.
 */
public class WeatherWidgetAnimService extends RemoteViewsService {

    public static final String PREFS = "codehub";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new EmojiFrameFactory(getApplicationContext(), intent);
    }

    static class EmojiFrameFactory implements RemoteViewsFactory {
        private final Context context;
        private final List<RemoteViews> frames = new ArrayList<>();

        EmojiFrameFactory(Context context, Intent intent) {
            this.context = context;
        }

        @Override public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            frames.clear();
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                int wcode = prefs.getInt("widget_wcode", -1);
                String[] sequence = framesFor(wcode);
                for (String emoji : sequence) {
                    RemoteViews frame = new RemoteViews(context.getPackageName(), R.layout.widget_emoji_frame);
                    frame.setTextViewText(R.id.widget_emoji_frame_text, emoji);
                    frames.add(frame);
                }
            } catch (Exception ignored) {
                // Nunca dejar el flipper sin frames: mínimo 1.
            }
            if (frames.isEmpty()) {
                RemoteViews frame = new RemoteViews(context.getPackageName(), R.layout.widget_emoji_frame);
                frame.setTextViewText(R.id.widget_emoji_frame_text, "🌤️");
                frames.add(frame);
            }
        }

        // Secuencia de emojis por condición + momento del día. Mismo
        // mapeo WMO que WeatherWidgetProvider.emojiFor (mantener en sintonía).
        private String[] framesFor(int wcode) {
            boolean night = isNight();
            if (wcode == 0)  return night ? new String[]{"🌙", "✨", "🌙"} : new String[]{"☀️", "🌤️", "☀️"};
            if (wcode >= 1 && wcode <= 2) return night ? new String[]{"🌙", "☁️", "🌙"} : new String[]{"🌤️", "⛅", "🌤️"};
            if (wcode == 3)  return new String[]{"☁️", "🌥️", "☁️"};
            if (wcode == 45 || wcode == 48) return new String[]{"🌫️", "🌁", "🌫️"};
            if (wcode >= 51 && wcode <= 57) return new String[]{"🌦️", "💧", "🌦️"};
            if (wcode >= 61 && wcode <= 67) return new String[]{"🌧️", "💧", "🌧️"};
            if (wcode >= 71 && wcode <= 77) return new String[]{"🌨️", "❄️", "🌨️"};
            if (wcode >= 80 && wcode <= 82) return new String[]{"🌧️", "☔", "🌧️"};
            if (wcode >= 85 && wcode <= 86) return new String[]{"🌨️", "🌨️", "❄️"};
            if (wcode >= 95)  return new String[]{"⛈️", "⚡", "⛈️"};
            return new String[]{"🌤️", "☀️", "🌤️"};
        }

        private boolean isNight() {
            try {
                int hour = Integer.parseInt(new SimpleDateFormat("HH", Locale.getDefault()).format(new java.util.Date()));
                return hour < 6 || hour >= 19;
            } catch (Exception e) { return false; }
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= frames.size()) return frames.get(0);
            return frames.get(position);
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public int getCount() { return frames.size(); }
        @Override public boolean hasStableIds() { return true; }
        @Override public long getItemId(int position) { return position; }
        @Override public void onDestroy() {}
    }
}
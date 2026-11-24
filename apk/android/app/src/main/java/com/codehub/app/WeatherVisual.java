package com.codehub.app;

/** Shared weather visual classification for NativeWeatherActivity and WeatherWidgetProvider. */
final class WeatherVisual {
    enum State { TROPICAL, SECO, TEMPLADO, CONTINENTAL, POLAR }

    static final class Snapshot {
        final State state;
        final int wcode;
        final boolean night;
        final float tempC;
        final int humidity;
        final float windKmh;
        final float visibilityM;

        Snapshot(State state, int wcode, boolean night, float tempC, int humidity, float windKmh, float visibilityM) {
            this.state = state;
            this.wcode = wcode;
            this.night = night;
            this.tempC = tempC;
            this.humidity = humidity;
            this.windKmh = windKmh;
            this.visibilityM = visibilityM;
        }
    }

    private WeatherVisual() {}

    static State classify(int wcode, float tempC, int humidity, float windKmh, float visibilityM) {
        boolean snow = (wcode >= 71 && wcode <= 77) || (wcode >= 85 && wcode <= 86);
        boolean rain = (wcode >= 51 && wcode <= 67) || (wcode >= 80 && wcode <= 82);
        boolean storm = wcode >= 95;
        boolean fog = wcode == 45 || wcode == 48;
        boolean clearish = wcode <= 3;
        boolean hot = !Float.isNaN(tempC) && tempC >= 28f;
        boolean warm = !Float.isNaN(tempC) && tempC >= 22f;
        boolean cold = !Float.isNaN(tempC) && tempC <= 8f;
        boolean freezing = !Float.isNaN(tempC) && tempC <= 0f;
        boolean humid = humidity >= 68;
        boolean dry = humidity >= 0 && humidity <= 45;
        boolean windy = !Float.isNaN(windKmh) && windKmh >= 28f;
        boolean lowVis = !Float.isNaN(visibilityM) && visibilityM > 0 && visibilityM < 4500f;

        if (freezing || snow || (cold && (snow || fog || wcode >= 71))) return State.POLAR;
        if (storm || (windy && (rain || cold || hot))) return State.CONTINENTAL;
        if ((humid && warm && (rain || storm || fog || wcode >= 51)) || (hot && humid && !dry)) return State.TROPICAL;
        if (dry && clearish && !rain && !snow && !fog && !Float.isNaN(tempC) && tempC >= 18f) return State.SECO;
        if (cold && !humid) return State.CONTINENTAL;
        if (lowVis && cold) return State.POLAR;
        return State.TEMPLADO;
    }

    static String label(State state) {
        switch (state) {
            case TROPICAL: return "Tropical";
            case SECO: return "Seco";
            case TEMPLADO: return "Templado";
            case CONTINENTAL: return "Continental";
            case POLAR: return "Polar";
            default: return "Templado";
        }
    }

    static int accent(State state) {
        switch (state) {
            case TROPICAL: return 0xFF9DB99E; // salvia húmeda
            case SECO: return 0xFFD98A67; // terracota sol
            case TEMPLADO: return 0xFFE2B48F; // hueso cálido
            case CONTINENTAL: return 0xFFC2B6A8; // piedra viento
            case POLAR: return 0xFFDCE7F2; // hielo suave
            default: return 0xFFD98A67;
        }
    }

    /** Animation phase 0..1 derived from wall clock, for widget frame updates. */
    static float phase(long nowMs, long periodMs) {
        long p = periodMs <= 0 ? 6000L : periodMs;
        return ((nowMs % p) / (float) p);
    }
}

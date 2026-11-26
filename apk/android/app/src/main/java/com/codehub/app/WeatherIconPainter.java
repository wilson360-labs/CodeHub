package com.codehub.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Transparent, high-contrast weather illustrations shared by the forecast and widget. */
final class WeatherIconPainter {
    private WeatherIconPainter() {}

    static Bitmap bitmap(int code, boolean night, int sizePx, float phase) {
        int size = Math.max(32, sizePx);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(size / 100f, size / 100f);
        draw(canvas, code, night, phase);
        return bitmap;
    }

    static void draw(Canvas canvas, int code, boolean night, float phase) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        boolean fog = code == 45 || code == 48;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || (code >= 85 && code <= 86);
        boolean storm = code >= 95;
        boolean clear = code == 0 || code < 0;
        boolean partlyCloudy = code == 1 || code == 2;

        if (clear || partlyCloudy) {
            if (night) drawMoon(canvas, paint, partlyCloudy ? 65f : 50f, partlyCloudy ? 35f : 42f);
            else drawSun(canvas, paint, partlyCloudy ? 66f : 50f, partlyCloudy ? 35f : 42f, phase);
        }
        if ((!clear && code != 0) || partlyCloudy) {
            drawCloud(canvas, paint, storm ? 0xFFF2F5F8 : 0xFFF5F4EE, partlyCloudy ? .9f : 1f);
        }
        if (fog) drawFog(canvas, paint);
        else if (rain) drawRain(canvas, paint, phase);
        else if (snow) drawSnow(canvas, paint, phase);
        else if (storm) drawLightning(canvas, paint);
    }

    private static void drawSun(Canvas canvas, Paint paint, float x, float y, float phase) {
        canvas.save();
        canvas.rotate(phase * 10f, x, y);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(0xFFFFC857);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4d;
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            canvas.drawLine(x + dx * 21f, y + dy * 21f, x + dx * 29f, y + dy * 29f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFC857);
        canvas.drawCircle(x, y, 17f, paint);
        paint.setColor(0xFFFFE6A3);
        canvas.drawCircle(x - 4f, y - 5f, 11f, paint);
        canvas.restore();
    }

    private static void drawMoon(Canvas canvas, Paint paint, float x, float y) {
        canvas.save();
        canvas.translate(x - 50f, y - 42f);
        Path crescent = new Path();
        crescent.moveTo(59f, 13f);
        crescent.cubicTo(24f, 16f, 17f, 68f, 55f, 84f);
        crescent.cubicTo(39f, 64f, 39f, 36f, 59f, 13f);
        crescent.close();
        paint.setColor(0xFFFFE4A0);
        canvas.drawPath(crescent, paint);
        paint.setColor(0xFFFFF2CA);
        canvas.drawCircle(72f, 27f, 2.5f, paint);
        canvas.drawCircle(76f, 58f, 1.8f, paint);
        canvas.restore();
    }

    private static void drawCloud(Canvas canvas, Paint paint, int color, float scale) {
        canvas.save();
        canvas.scale(scale, scale, 50f, 53f);
        Path cloud = new Path();
        cloud.moveTo(23f, 70f);
        cloud.cubicTo(12f, 70f, 8f, 62f, 10f, 53f);
        cloud.cubicTo(11f, 44f, 19f, 39f, 29f, 39f);
        cloud.cubicTo(31f, 26f, 41f, 19f, 52f, 20f);
        cloud.cubicTo(65f, 20f, 74f, 29f, 76f, 42f);
        cloud.cubicTo(87f, 39f, 94f, 47f, 92f, 57f);
        cloud.cubicTo(91f, 66f, 84f, 70f, 76f, 70f);
        cloud.close();
        paint.setColor(0x44000000);
        canvas.save();
        canvas.translate(0f, 3f);
        canvas.drawPath(cloud, paint);
        canvas.restore();
        paint.setColor(color);
        canvas.drawPath(cloud, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f);
        paint.setColor(0xAAFFFFFF);
        canvas.drawPath(cloud, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private static void drawRain(Canvas canvas, Paint paint, float phase) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(0xFF63C9E8);
        for (int i = 0; i < 3; i++) {
            float y = 77f + ((phase * 8f + i * 4f) % 8f);
            canvas.drawLine(37f + i * 14f, y, 33f + i * 14f, y + 8f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private static void drawSnow(Canvas canvas, Paint paint, float phase) {
        paint.setColor(0xFFEAF5FF);
        for (int i = 0; i < 3; i++) {
            float x = 36f + i * 14f;
            float y = 82f + ((phase * 5f + i * 2f) % 5f);
            canvas.drawCircle(x, y, 3f, paint);
        }
    }

    private static void drawLightning(Canvas canvas, Paint paint) {
        Path bolt = new Path();
        bolt.moveTo(55f, 65f);
        bolt.lineTo(43f, 82f);
        bolt.lineTo(52f, 82f);
        bolt.lineTo(47f, 96f);
        bolt.lineTo(66f, 73f);
        bolt.lineTo(57f, 73f);
        bolt.close();
        paint.setColor(0xFFFFD45E);
        canvas.drawPath(bolt, paint);
    }

    private static void drawFog(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(0xFFB8C9D5);
        canvas.drawLine(24f, 77f, 76f, 77f, paint);
        canvas.drawLine(18f, 86f, 70f, 86f, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}

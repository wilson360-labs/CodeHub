package com.codehub.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Shared native weather artwork for the forecast screen and launcher widget. */
final class WeatherIconPainter {
    private static final RectF CARD = new RectF(3f, 3f, 97f, 97f);

    private WeatherIconPainter() {}

    static Bitmap bitmap(int code, boolean night, int sizePx, float phase) {
        int size = Math.max(48, sizePx);
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
        boolean partly = code >= 1 && code <= 2;
        boolean clear = code == 0 || code < 0;

        int top = night ? 0xFF35246D : 0xFF53B9F2;
        int bottom = night ? 0xFF171D53 : 0xFF2475D5;
        if (fog) { top = 0xFF7996B4; bottom = 0xFF536C91; }
        else if (storm) { top = 0xFF7358CF; bottom = 0xFF38258F; }
        else if (rain || snow) { top = 0xFF6966D8; bottom = 0xFF393A9E; }
        else if (code == 3) { top = 0xFF668CC9; bottom = 0xFF405A96; }
        else if (clear && !night) { top = 0xFF58C7EF; bottom = 0xFF2582D9; }

        paint.setShader(new LinearGradient(0f, 0f, 100f, 100f, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(CARD, 20f, 20f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(0x44FFFFFF);
        canvas.drawRoundRect(CARD, 20f, 20f, paint);
        paint.setStyle(Paint.Style.FILL);

        if (night && (clear || partly)) {
            drawMoon(canvas, paint, partly ? 64f : 50f, partly ? 34f : 43f);
            drawStar(canvas, paint, 29f, 31f, 3.2f);
            drawStar(canvas, paint, 79f, 27f, 2.5f);
        } else if (clear || partly) {
            drawSun(canvas, paint, partly ? 67f : 50f, partly ? 34f : 45f, phase);
        }

        if (!clear || code == 3 || partly || night && partly) {
            drawCloud(canvas, paint, storm ? 0xFFF0F3FF : 0xFFF8FBFF, partly || night && partly ? 0.84f : 1f);
        }
        if (fog) drawFog(canvas, paint);
        else if (rain) drawRain(canvas, paint, phase);
        else if (snow) drawSnow(canvas, paint, phase);
        else if (storm) drawLightning(canvas, paint, phase);
    }

    private static void drawSun(Canvas canvas, Paint paint, float x, float y, float phase) {
        canvas.save();
        canvas.rotate(phase * 18f, x, y);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setColor(0xFFFFE69A);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4d;
            float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
            canvas.drawLine(x + dx * 19f, y + dy * 19f, x + dx * 26f, y + dy * 26f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(x - 12, y - 14, x + 12, y + 14,
            0xFFFFF5B8, 0xFFFFB73D, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, 16f, paint);
        paint.setShader(null);
        canvas.restore();
    }

    private static void drawMoon(Canvas canvas, Paint paint, float x, float y) {
        paint.setColor(0xFFFFE9A8);
        canvas.drawCircle(x, y, 17f, paint);
        paint.setColor(0xFF55439B);
        canvas.drawCircle(x + 9f, y - 7f, 15f, paint);
    }

    private static void drawStar(Canvas canvas, Paint paint, float x, float y, float radius) {
        paint.setColor(0xFFFFF1C4);
        canvas.drawCircle(x, y, radius, paint);
    }

    private static void drawCloud(Canvas canvas, Paint paint, int color, float scale) {
        canvas.save();
        canvas.scale(scale, scale, 50f, 52f);
        Path cloud = new Path();
        cloud.moveTo(23f, 69f);
        cloud.cubicTo(13f, 69f, 9f, 62f, 9f, 54f);
        cloud.cubicTo(9f, 45f, 16f, 39f, 26f, 38f);
        cloud.cubicTo(29f, 25f, 39f, 18f, 51f, 18f);
        cloud.cubicTo(65f, 18f, 75f, 28f, 76f, 41f);
        cloud.cubicTo(86f, 39f, 93f, 46f, 93f, 55f);
        cloud.cubicTo(93f, 63f, 87f, 69f, 77f, 69f);
        cloud.close();
        paint.setColor(0x445E75B4);
        canvas.save(); canvas.translate(0f, 3f); canvas.drawPath(cloud, paint); canvas.restore();
        paint.setShader(new LinearGradient(0f, 19f, 0f, 71f, color, 0xFFD4E8FF, Shader.TileMode.CLAMP));
        canvas.drawPath(cloud, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.2f); paint.setColor(0x99FFFFFF);
        canvas.drawPath(cloud, paint); paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private static void drawRain(Canvas canvas, Paint paint, float phase) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        for (int i = 0; i < 3; i++) {
            paint.setColor(i == 1 ? 0xFFB9F3FF : 0xFF75D8FF);
            float y = 76f + ((phase * 8f + i * 5f) % 8f);
            canvas.drawLine(35f + i * 15f, y, 31f + i * 15f, y + 7f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private static void drawSnow(Canvas canvas, Paint paint, float phase) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.7f);
        paint.setColor(0xFFE6F8FF);
        for (int i = 0; i < 3; i++) {
            float x = 34f + i * 16f, y = 80f + ((phase * 6f + i * 3f) % 6f);
            canvas.drawLine(x - 4f, y, x + 4f, y, paint);
            canvas.drawLine(x, y - 4f, x, y + 4f, paint);
            canvas.drawLine(x - 3f, y - 3f, x + 3f, y + 3f, paint);
            canvas.drawLine(x + 3f, y - 3f, x - 3f, y + 3f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private static void drawLightning(Canvas canvas, Paint paint, float phase) {
        Path bolt = new Path();
        bolt.moveTo(55f, 62f); bolt.lineTo(42f, 80f); bolt.lineTo(51f, 80f);
        bolt.lineTo(46f, 94f); bolt.lineTo(66f, 72f); bolt.lineTo(56f, 72f); bolt.close();
        paint.setColor(0xFFFFE47A); paint.setShadowLayer(5f, 0f, 1f, 0x99FFE47A);
        canvas.drawPath(bolt, paint); paint.clearShadowLayer();
        if (phase < .1f) { paint.setColor(0x66FFFFFF); canvas.drawRoundRect(12f, 12f, 88f, 88f, 20f, 20f, paint); }
    }

    private static void drawFog(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4.5f);
        paint.setColor(0xFFDDEBFA);
        canvas.drawLine(24f, 76f, 76f, 76f, paint);
        canvas.drawLine(18f, 84f, 68f, 84f, paint);
        paint.setColor(0x99C5DAEF); canvas.drawLine(32f, 91f, 80f, 91f, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}

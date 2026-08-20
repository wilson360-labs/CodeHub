package com.codehub.app;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Envía reportes de crash/errores de la app al backend, que los reenvía
 * al chat de Telegram del admin (POST /api/crash-report). Nunca debe
 * lanzar una excepción hacia el llamador: un fallo al reportar un crash
 * no puede convertirse en otro crash.
 */
public class CrashReporter {

    private static final String BACKEND     = "https://codehub-98s6.onrender.com";
    private static final String APP_VERSION = "1.2.0";

    /** Reporte SÍNCRONO con timeout corto — solo desde el hilo que va a morir (crash fatal). */
    public static void reportFatal(Context ctx, Thread thread, Throwable t) {
        send(ctx, buildBody(ctx, true, thread != null ? thread.getName() : null, t), 4000);
    }

    /** Reporte en background — excepciones atrapadas que no matan la app (try/catch en onCreate, etc). */
    public static void reportCaught(final Context ctx, final String tag, final Throwable t) {
        new Thread(new Runnable() {
            @Override public void run() { send(ctx, buildBody(ctx, false, tag, t), 10000); }
        }).start();
    }

    /** Reporte en background — errores JS del sitio dentro del WebView (window.onerror / unhandledrejection). */
    public static void reportJs(final Context ctx, final String message, final String source,
                                 final String line, final String col, final String stack) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("fatal", false);
                    body.put("tag", "javascript@" + safe(source) + ":" + safe(line) + ":" + safe(col));
                    body.put("exceptionClass", "JavaScriptError");
                    body.put("message", message);
                    body.put("stackTrace", (stack != null && !stack.isEmpty())
                        ? stack
                        : (safe(source) + ":" + safe(line) + ":" + safe(col)));
                    fillCommonFields(ctx, body);
                    send(ctx, body, 10000);
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    /** Al abrir la app: si quedó un crash.log de una sesión anterior sin poder enviarse, lo reintenta. */
    public static void flushPendingLog(final Context ctx) {
        new Thread(new Runnable() {
            @Override public void run() {
                File f = new File(ctx.getFilesDir(), "crash.log");
                try {
                    if (!f.exists() || f.length() == 0) return;
                    StringBuilder sb = new StringBuilder();
                    BufferedReader br = new BufferedReader(new FileReader(f));
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    br.close();

                    String log = sb.toString();
                    if (log.length() > 3200) log = log.substring(log.length() - 3200);

                    JSONObject body = new JSONObject();
                    body.put("fatal", true);
                    body.put("tag", "pending-log-flush");
                    body.put("exceptionClass", "PendingCrashLog");
                    body.put("message", "Log de crash recuperado al reabrir la app (no se pudo enviar en el momento del crash)");
                    body.put("stackTrace", log);
                    fillCommonFields(ctx, body);

                    boolean ok = send(ctx, body, 10000);
                    if (ok) //noinspection ResultOfMethodCallIgnored
                        f.delete();
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    // ── internos ─────────────────────────────────────────────────

    private static JSONObject buildBody(Context ctx, boolean fatal, String tag, Throwable t) {
        JSONObject body = new JSONObject();
        try {
            body.put("fatal", fatal);
            body.put("tag", tag);
            body.put("exceptionClass", t.getClass().getName());
            body.put("message", t.getMessage());
            body.put("stackTrace", stackTraceToString(t));
            fillCommonFields(ctx, body);
        } catch (Throwable ignored) {}
        return body;
    }

    private static void fillCommonFields(Context ctx, JSONObject body) throws Exception {
        String versionName = APP_VERSION;
        try { versionName = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        body.put("appVersion", versionName);
        body.put("platform", "android");
        body.put("deviceModel", Build.MANUFACTURER + " " + Build.MODEL);
        body.put("androidVersion", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        body.put("timestamp", System.currentTimeMillis());
    }

    private static boolean send(Context ctx, JSONObject body, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BACKEND + "/api/crash-report").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Throwable ignored) {
            // Nunca dejar que el reporte de crash cause otro crash.
            return false;
        }
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

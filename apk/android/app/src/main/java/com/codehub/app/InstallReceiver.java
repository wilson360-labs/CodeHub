package com.codehub.app;

import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;

import java.io.File;

public class InstallReceiver extends BroadcastReceiver {
    private static final String UPDATE_CHANNEL = "codehub_apk_updates";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || DownloadManager.ACTION_DOWNLOAD_COMPLETE == null) return;
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;

        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (downloadId == -1) return;

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor cursor = dm.query(query);
        if (cursor == null || !cursor.moveToFirst()) {
            if (cursor != null) cursor.close();
            return;
        }

        int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
        int uriIdx   = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
        int titleIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
        if (statusIdx < 0 || uriIdx < 0) { cursor.close(); return; }

        int status = cursor.getInt(statusIdx);
        String uriString = cursor.getString(uriIdx);
        String title = titleIdx >= 0 ? cursor.getString(titleIdx) : null;
        cursor.close();

        if (status != DownloadManager.STATUS_SUCCESSFUL) return;
        if (uriString == null) return;

        // ACTION_DOWNLOAD_COMPLETE es un broadcast del sistema: se dispara
        // para CUALQUIER descarga hecha con DownloadManager en el device
        // (incluidas las de Chrome u otras apps), no solo las nuestras.
        // Filtramos por extensión .apk / título "CodeHub" para no intentar
        // "instalar" un PDF o una foto que el usuario bajó del navegador.
        boolean looksLikeApk = uriString.toLowerCase().endsWith(".apk")
            || (title != null && title.toLowerCase().contains("codehub"));
        if (!looksLikeApk) return;

        Uri fileUri = Uri.parse(uriString);

        // file:// scheme — direct path
        if ("file".equals(fileUri.getScheme())) {
            notifyInstallApk(context, new File(fileUri.getPath()), downloadId);
            return;
        }

        // content:// scheme (API 29+ scoped storage) — copy to app-private dir
        try {
            ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(fileUri, "r");
            if (pfd == null) return;

            File tmpApk = new File(context.getFilesDir(), "CodeHub-update.apk");
            java.io.InputStream in = new java.io.FileInputStream(pfd.getFileDescriptor());
            java.io.OutputStream out = new java.io.FileOutputStream(tmpApk);
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) > 0) { out.write(buf, 0, read); }
            out.close();
            in.close();
            pfd.close();

            notifyInstallApk(context, tmpApk, downloadId);
        } catch (Exception e) {
            // Fallback: try the old path
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File apk = new File(downloads, "CodeHub-update.apk");
            if (apk.exists()) {
                notifyInstallApk(context, apk, downloadId);
            }
        }
    }

    private void notifyInstallApk(Context context, File apkFile, long downloadId) {
        if (apkFile == null || !apkFile.isFile()) return;
        // Android 13+: if notifications are disabled, DownloadManager's own
        // completed-download notification remains available as the fallback.
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && manager.getNotificationChannel(UPDATE_CHANNEL) == null) {
            manager.createNotificationChannel(new NotificationChannel(
                UPDATE_CHANNEL, "Actualizaciones de CodeHub", NotificationManager.IMPORTANCE_DEFAULT));
        }

        try {
            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", apkFile);
            Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent installPendingIntent = PendingIntent.getActivity(
                context, (int) (downloadId & 0x7fffffff), installIntent, flags);

            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, UPDATE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_real)
                .setContentTitle("Actualización de CodeHub lista")
                .setContentText("Toca para abrir el instalador del sistema.")
                .setCategory(NotificationCompat.CATEGORY_SYSTEM)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(installPendingIntent);
            manager.notify((int) (downloadId & 0x7fffffff), notification.build());
        } catch (Exception ignored) {
            // DownloadManager keeps its completed-download notification visible.
        }
    }
}

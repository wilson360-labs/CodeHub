package com.codehub.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;

public class InstallReceiver extends BroadcastReceiver {

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
        if (statusIdx < 0 || uriIdx < 0) { cursor.close(); return; }

        int status = cursor.getInt(statusIdx);
        String uriString = cursor.getString(uriIdx);
        cursor.close();

        if (status != DownloadManager.STATUS_SUCCESSFUL) return;
        if (uriString == null) return;

        Uri fileUri = Uri.parse(uriString);

        // file:// scheme — direct path
        if ("file".equals(fileUri.getScheme())) {
            installApk(context, new File(fileUri.getPath()));
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

            installApk(context, tmpApk);
        } catch (Exception e) {
            // Fallback: try the old path
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File apk = new File(downloads, "CodeHub-update.apk");
            if (apk.exists()) {
                installApk(context, apk);
            }
        }
    }

    private void installApk(Context context, File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", apkFile);
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}

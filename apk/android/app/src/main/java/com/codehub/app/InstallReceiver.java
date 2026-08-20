package com.codehub.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

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
        int mimeIdx  = cursor.getColumnIndex(DownloadManager.COLUMN_MIME_TYPE);
        if (statusIdx < 0 || uriIdx < 0) { cursor.close(); return; }

        int status = cursor.getInt(statusIdx);
        String uriString = cursor.getString(uriIdx);
        String mime = cursor.getString(mimeIdx);
        cursor.close();

        if (status != DownloadManager.STATUS_SUCCESSFUL) return;
        if (uriString == null) return;

        Uri fileUri = Uri.parse(uriString);
        if (fileUri.getScheme() != null && fileUri.getScheme().equals("file")) {
            installApk(context, new File(fileUri.getPath()));
        } else {
            // Content URI — convert to file path
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

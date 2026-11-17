package com.codehub.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Historial de descargas del sistema (DownloadManager) con acciones:
 *  abrir, instalar APK, reintentar y limpiar. Abierto desde el panel de
 *  dispositivo del WebView (CodeHubBridge.openDownloads). */
public class DownloadsActivity extends Activity {

    private DownloadManager dm;
    private ListView listView;
    private TextView emptyText;
    private final List<DownloadEntry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SystemBars.fit(this);

        dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        int d = (int) getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF080B11);
        root.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));

        TextView title = new TextView(this);
        title.setText("Descargas de CodeHub");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Toca para abrir/instalar · mantén pulsado para borrar");
        subtitle.setTextColor(0xFF9AA0B8);
        subtitle.setTextSize(13);
        root.addView(subtitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button clearBtn = new Button(this);
        clearBtn.setText("Limpiar todo");
        clearBtn.setAllCaps(false);
        clearBtn.setTextColor(0xFF0A0A12);
        clearBtn.setBackgroundColor(0xFF6366F1);
        clearBtn.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
        clearBtn.setOnClickListener(v -> confirmClearAll());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, (int) (44 * d));
        btnLp.topMargin = (int) (12 * d);
        btnLp.bottomMargin = (int) (8 * d);
        root.addView(clearBtn, btnLp);

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setBackgroundColor(0xFF0E0E1A);
        listView.setCacheColorHint(0x00000000);
        root.addView(listView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyText = new TextView(this);
        emptyText.setText("Aún no hay descargas en el sistema");
        emptyText.setTextColor(0xFF9AA0B8);
        emptyText.setTextSize(14);
        emptyText.setGravity(Gravity.CENTER);
        root.addView(emptyText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        emptyText.setVisibility(View.GONE);

        listView.setOnItemClickListener((AdapterView<?> parent, View v, int pos, long id) -> {
            DownloadEntry e = entries.get(pos);
            actionFor(e);
        });

        listView.setOnItemLongClickListener((AdapterView<?> parent, View v, int pos, long id) -> {
            confirmDelete(entries.get(pos));
            return true;
        });

        setContentView(root);
        SystemBars.applyInsets(this);
        loadDownloads();
    }

    // ── Carga ─────────────────────────────────────────────

    private void loadDownloads() {
        if (dm == null) {
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText("DownloadManager no disponible");
            return;
        }
        entries.clear();
        Cursor cursor = null;
        try {
            cursor = dm.query(new DownloadManager.Query());
            if (cursor != null && cursor.moveToFirst()) {
                int idIdx   = cursor.getColumnIndex(DownloadManager.COLUMN_ID);
                int tIdx    = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
                int sIdx    = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int byIdx   = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                int totIdx  = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                int uriIdx  = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
                int srcIdx  = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
                int tsIdx   = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP);
                do {
                    DownloadEntry e = new DownloadEntry();
                    e.id = idIdx >= 0 ? cursor.getLong(idIdx) : -1;
                    e.title = tIdx >= 0 ? cursor.getString(tIdx) : null;
                    e.status = sIdx >= 0 ? cursor.getInt(sIdx) : -1;
                    e.bytes = byIdx >= 0 ? cursor.getLong(byIdx) : 0;
                    e.total = totIdx >= 0 ? cursor.getLong(totIdx) : 0;
                    e.localUri = uriIdx >= 0 ? cursor.getString(uriIdx) : null;
                    e.srcUri = srcIdx >= 0 ? cursor.getString(srcIdx) : null;
                    e.time = tsIdx >= 0 ? cursor.getLong(tsIdx) : 0;
                    if (e.title == null || e.title.isEmpty()) {
                        e.title = e.localUri != null ? e.localUri : "Descarga " + e.id;
                    }
                    entries.add(e);
                } while (cursor.moveToNext());
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        render();
    }

    private void render() {
        if (entries.isEmpty()) {
            listView.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
            return;
        }
        listView.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        listView.setAdapter(new ArrayAdapter<DownloadEntry>(this, android.R.layout.simple_list_item_2, entries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                DownloadRow row = convertView instanceof DownloadRow
                    ? (DownloadRow) convertView : new DownloadRow(DownloadsActivity.this);
                DownloadEntry e = getItem(position);
                row.title.setText(e.title != null ? e.title : "Descarga");
                row.status.setText(statusLine(e));
                row.uninstall.setVisibility(e.status == DownloadManager.STATUS_SUCCESSFUL && isApkDownload(e)
                        ? View.VISIBLE : View.GONE);
                row.uninstall.setOnClickListener(v -> inspectInstalledApk(e));
                return row;
            }
        });
    }

    private String statusLine(DownloadEntry e) {
        StringBuilder sb = new StringBuilder();
        switch (e.status) {
            case DownloadManager.STATUS_SUCCESSFUL:
                sb.append("\u2705 Completada");
                break;
            case DownloadManager.STATUS_PENDING:
                sb.append("\u23F3 Pendiente");
                break;
            case DownloadManager.STATUS_RUNNING:
                sb.append("\u25B6 Descargando");
                break;
            case DownloadManager.STATUS_PAUSED:
                sb.append("\u23F8 Pausada");
                break;
            case DownloadManager.STATUS_FAILED:
                sb.append("\u274C Fallida — toca para reintentar");
                break;
            default:
                sb.append("Estado " + e.status);
        }
        if (e.total > 0) {
            sb.append(" · ").append(fmtSize(e.bytes)).append(" / ").append(fmtSize(e.total));
        }
        if (e.time > 0) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault());
            sb.append(" · ").append(sdf.format(new java.util.Date(e.time)));
        }
        return sb.toString();
    }

    private String fmtSize(long b) {
        if (b >= 1073741824L) return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
        if (b >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return Math.max(1, b / 1024L) + " KB";
    }

    // ── Acciones ────────────────────────────────────────────

    private void actionFor(DownloadEntry e) {
        if (e.status == DownloadManager.STATUS_FAILED || e.status == DownloadManager.STATUS_PAUSED) {
            retry(e);
            return;
        }
        if (e.status != DownloadManager.STATUS_SUCCESSFUL) {
            toast("La descarga aún no está completa");
            return;
        }
        if (e.localUri == null) {
            toast("No se pudo localizar el archivo");
            return;
        }
        Uri u = Uri.parse(e.localUri);
        boolean isApk = e.localUri.toLowerCase().endsWith(".apk") || (e.title != null && e.title.toLowerCase().contains("codehub"));
        if (isApk) {
            installApk(e, u);
        } else {
            openFile(e, u);
        }
    }

    private void retry(DownloadEntry e) {
        if (dm == null) return;
        if (e.srcUri == null || e.srcUri.isEmpty()) {
            toast("No hay URL de origen para reintentar");
            return;
        }
        try {
            dm.remove(e.id);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(e.srcUri));
            String name = fileBaseName(e.srcUri);
            if (name == null || name.isEmpty()) {
                name = e.title != null && !e.title.isEmpty() ? e.title.replaceAll("[^a-zA-Z0-9._-]", "_") : "download";
            }
            if (name.isEmpty()) name = "download";
            if (name.length() > 80) name = name.substring(name.length() - 80);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setTitle(name);
            req.setDescription("Descargando desde CodeHub");
            dm.enqueue(req);
            toast("Descarga reencolada");
            loadDownloads();
        } catch (Exception ex) {
            toast("No se pudo reintentar: " + ex.getMessage());
        }
    }

    private String fileBaseName(String url) {
        try {
            Uri u = Uri.parse(url);
            String path = u.getPath();
            if (path == null) return "";
            String seg = path.substring(path.lastIndexOf('/') + 1);
            return seg.replaceAll("[^a-zA-Z0-9._-]", "_");
        } catch (Exception e) {
            return "";
        }
    }

    private void installApk(DownloadEntry e, Uri u) {
        try {
            File apkFile = toRealFile(u);
            if ("content".equals(u.getScheme())) {
                apkFile = copyToPrivate(u);
            }
            if (apkFile == null || !apkFile.exists()) { toast("No se pudo acceder al APK"); return; }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apkFile);
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            } else {
                intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ex) {
            toast("No se pudo instalar: " + ex.getMessage());
        }
    }

    private boolean isApkDownload(DownloadEntry entry) {
        String value = ((entry.localUri == null ? "" : entry.localUri) + " "
                + (entry.title == null ? "" : entry.title)).toLowerCase(java.util.Locale.ROOT);
        return value.contains(".apk") || value.contains("application/vnd.android.package-archive");
    }

    /** Resolves the package from a downloaded APK and delegates uninstall to Android. */
    private void inspectInstalledApk(DownloadEntry entry) {
        if (entry.localUri == null) { toast("No se pudo localizar el APK descargado"); return; }
        new Thread(() -> {
            String packageName = null;
            File temporary = null;
            String failure = null;
            try {
                Uri source = Uri.parse(entry.localUri);
                File apk = toRealFile(source);
                if (apk == null && "content".equals(source.getScheme())) {
                    temporary = copyToPrivate(source);
                    apk = temporary;
                }
                if (apk == null || !apk.isFile()) throw new IllegalStateException("El APK descargado ya no está disponible.");
                PackageInfo archive;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    archive = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
                            PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA));
                } else {
                    //noinspection deprecation
                    archive = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_META_DATA);
                }
                if (archive == null || archive.packageName == null)
                    throw new IllegalStateException("Android no pudo leer el paquete de este APK.");
                packageName = archive.packageName;
            } catch (Exception error) {
                failure = error.getMessage() == null ? "No se pudo revisar el APK." : error.getMessage();
            } finally {
                if (temporary != null) temporary.delete();
            }

            final String resolvedPackage = packageName;
            final String failureMessage = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (failureMessage != null) { toast(failureMessage); return; }
                if (resolvedPackage == null) { toast("No se pudo identificar la app del APK."); return; }
                if (resolvedPackage.equals(getPackageName())) {
                    toast("Ese APK pertenece a CodeHub; no se ofrece desinstalarla desde su propio historial.");
                    return;
                }
                try {
                    getPackageManager().getPackageInfo(resolvedPackage, 0);
                    confirmUninstall(resolvedPackage, entry.title);
                } catch (PackageManager.NameNotFoundException notInstalled) {
                    toast("La app de este APK no está instalada en el dispositivo.");
                }
            });
        }, "CodeHub-apk-package-check").start();
    }

    private void confirmUninstall(String packageName, String label) {
        new AlertDialog.Builder(this)
                .setTitle("Desinstalar aplicación")
                .setMessage("Se abrirá el instalador de Android para desinstalar "
                        + (label == null || label.trim().isEmpty() ? packageName : label)
                        + ". Android solicitará confirmación; CodeHub no puede desinstalarla en silencio.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName)));
                    } catch (Exception error) {
                        toast("Android no pudo abrir la desinstalación de esta app.");
                    }
                }).show();
    }

    private void openFile(DownloadEntry e, Uri u) {
        try {
            File f = toRealFile(u);
            Uri share;
            if (f != null && f.exists()) {
                share = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            } else {
                share = u;
            }
            String mime = getMimeFor(e.localUri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(share, mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
            } catch (Exception ex) {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(share, "*/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        } catch (Exception ex) {
            toast("No se pudo abrir: " + ex.getMessage());
        }
    }

    /** file:// → File directo; content:// → copia a storage privado. */
    private File toRealFile(Uri u) {
        if (!"file".equals(u.getScheme())) return null;
        return new File(u.getPath());
    }

    private File copyToPrivate(Uri u) {
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(u, "r");
            if (pfd == null) return null;
            File tmp = new File(getFilesDir(), "downloaded-" + System.currentTimeMillis());
            InputStream in = new java.io.FileInputStream(pfd.getFileDescriptor());
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
            out.close();
            in.close();
            pfd.close();
            return tmp;
        } catch (Exception e) {
            return null;
        }
    }

    private String getMimeFor(String uri) {
        String lower = uri != null ? uri.toLowerCase() : "";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp"))
            return "image/*";
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")) return "video/*";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")) return "audio/*";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".txt") || lower.endsWith(".json")) return "text/plain";
        return "*/*";
    }

    private void confirmDelete(DownloadEntry e) {
        new AlertDialog.Builder(this)
            .setTitle("Eliminar descarga")
            .setMessage("¿Borrar \"" + e.title + "\"? (también quita el archivo del sistema)")
            .setPositiveButton("Borrar", (d, w) -> {
                if (dm != null) dm.remove(e.id);
                loadDownloads();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void confirmClearAll() {
        if (entries.isEmpty()) { toast("No hay descargas para limpiar"); return; }
        new AlertDialog.Builder(this)
            .setTitle("Vaciar historial")
            .setMessage("Se eliminarán del sistema todas las descargas listadas.")
            .setPositiveButton("Vaciar", (d, w) -> {
                if (dm == null) return;
                for (DownloadEntry e : entries) {
                    try { dm.remove(e.id); } catch (Exception ignored) {}
                }
                loadDownloads();
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    static class DownloadEntry {
        long id;
        String title;
        int status;
        long bytes;
        long total;
        String localUri;
        String srcUri;
        long time;
    }

    private final class DownloadRow extends LinearLayout {
        final TextView title;
        final TextView status;
        final Button uninstall;

        DownloadRow(Context context) {
            super(context);
            float density = getResources().getDisplayMetrics().density;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, (int) (8 * density), 0, (int) (8 * density));

            LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(VERTICAL);
            title = new TextView(context);
            title.setTextSize(15);
            title.setTextColor(0xFFFFFFFF);
            title.setMaxLines(1);
            status = new TextView(context);
            status.setTextSize(12);
            status.setTextColor(0xFF9AA0B8);
            textColumn.addView(title, new LinearLayout.LayoutParams(-1, -2));
            textColumn.addView(status, new LinearLayout.LayoutParams(-1, -2));
            addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1f));

            uninstall = new Button(context);
            uninstall.setText("Desinstalar");
            uninstall.setTextSize(11);
            uninstall.setAllCaps(false);
            uninstall.setMinHeight((int) (38 * density));
            uninstall.setPadding((int) (7 * density), 0, (int) (7 * density), 0);
            addView(uninstall, new LinearLayout.LayoutParams(-2, -2));
        }
    }
}

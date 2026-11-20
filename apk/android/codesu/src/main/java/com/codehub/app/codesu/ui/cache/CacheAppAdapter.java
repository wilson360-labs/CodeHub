package com.codehub.app.codesu.ui.cache;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.codehub.app.codesu.R;
import com.codehub.app.codesu.model.CacheAppInfo;
import com.codehub.app.codesu.util.CacheCleaner;

final class CacheAppAdapter extends RecyclerView.Adapter<CacheAppAdapter.Holder> {
    private final List<CacheAppInfo> apps = new ArrayList<>();
    private final Map<String, LiveResult> liveResults = new HashMap<>();

    void submit(List<CacheAppInfo> updated) {
        apps.clear();
        apps.addAll(updated);
        liveResults.clear();
        notifyDataSetChanged();
    }

    List<CacheAppInfo> getApps() { return new ArrayList<>(apps); }

    /** Resultado real de una app mientras corre el tasker (lista "live"). */
    void setLiveResult(String packageName, String status, long afterBytes) {
        LiveResult previous = liveResults.get(packageName);
        if (previous != null && previous.status.equals(status) && previous.afterBytes == afterBytes) return;
        liveResults.put(packageName, new LiveResult(status, afterBytes));
        for (int i = 0; i < apps.size(); i++) {
            if (packageName.equals(apps.get(i).packageName)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View row = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.codesu_cache_item, parent, false);
        return new Holder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        CacheAppInfo app = apps.get(position);
        holder.icon.setImageDrawable(app.icon);
        holder.name.setText(app.label);
        holder.packageName.setText((app.protectedApp ? "Protegida · " : app.systemApp ? "Sistema · " : "App · ")
                + app.packageName);
        boolean recommended = CacheCleaner.isDeepCleanCandidate(app);
        holder.advice.setText(recommended ? "Recomendable · limpieza segura" : app.protectedApp
                || CacheCleaner.isProtected(app.packageName) ? "No recomendable · app protegida"
                : app.systemApp && android.os.Build.VERSION.SDK_INT >= 36
                ? "Protegida · sistema Android 16"
                : "No recomendable · menos de 1 MB");
        holder.advice.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                recommended ? R.color.codesu_badge_recommended : R.color.codesu_badge_removed));
        holder.itemView.setAlpha(app.protectedApp ? 0.72f : 1f);
        holder.size.setText(formatBytes(app.cacheBytes));
        LiveResult live = liveResults.get(app.packageName);
        if (live == null) return;
        if ("cleared".equals(live.status)) {
            holder.advice.setText("Limpia · caché borrada");
            holder.advice.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                    R.color.codesu_badge_recommended));
            holder.size.setText(live.afterBytes >= 0L ? formatBytes(live.afterBytes)
                    : formatBytes(app.cacheBytes));
        } else if ("skipped".equals(live.status)) {
            holder.advice.setText("Omitida · sin caché suficiente");
            holder.advice.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                    R.color.codesu_text_muted));
        } else if ("failed".equals(live.status)) {
            holder.advice.setText("Fallida · la app no respondió");
            holder.advice.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                    R.color.codesu_badge_removed));
        }
    }

    @Override
    public int getItemCount() { return apps.size(); }

    static String formatBytes(long bytes) {
        if (bytes < 1024L) return "<1 KB";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static final class LiveResult {
        final String status;
        final long afterBytes;

        LiveResult(String status, long afterBytes) {
            this.status = status;
            this.afterBytes = afterBytes;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final TextView advice;
        final TextView size;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.codesu_cache_app_icon);
            name = itemView.findViewById(R.id.codesu_cache_app_name);
            packageName = itemView.findViewById(R.id.codesu_cache_app_package);
            advice = itemView.findViewById(R.id.codesu_cache_app_advice);
            size = itemView.findViewById(R.id.codesu_cache_app_size);
        }
    }
}

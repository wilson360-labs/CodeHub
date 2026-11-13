package com.codehub.app.codesu.ui.cache;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import com.codehub.app.codesu.R;
import com.codehub.app.codesu.model.CacheAppInfo;

final class CacheAppAdapter extends RecyclerView.Adapter<CacheAppAdapter.Holder> {
    private final List<CacheAppInfo> apps = new ArrayList<>();

    void submit(List<CacheAppInfo> updated) {
        apps.clear();
        apps.addAll(updated);
        notifyDataSetChanged();
    }

    List<CacheAppInfo> getApps() { return new ArrayList<>(apps); }

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
        holder.itemView.setAlpha(app.protectedApp ? 0.66f : 1f);
        holder.size.setText(formatBytes(app.cacheBytes));
    }

    @Override
    public int getItemCount() { return apps.size(); }

    static String formatBytes(long bytes) {
        if (bytes < 1024L) return "<1 KB";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView packageName;
        final TextView size;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.codesu_cache_app_icon);
            name = itemView.findViewById(R.id.codesu_cache_app_name);
            packageName = itemView.findViewById(R.id.codesu_cache_app_package);
            size = itemView.findViewById(R.id.codesu_cache_app_size);
        }
    }
}

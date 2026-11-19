package com.codehub.app.codesu.ui.debloater;

import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.codehub.app.codesu.R;
import com.codehub.app.codesu.databinding.CodesuDebloaterItemBinding;

import java.util.ArrayList;
import java.util.List;

final class DebloaterAdapter extends RecyclerView.Adapter<DebloaterAdapter.Holder> {
    interface Listener {
        void onSelectionChanged(DebloaterAppInfo app);
        void onStateAction(DebloaterAppInfo app);
        void onRemoveAction(DebloaterAppInfo app);
        void onInfoAction(DebloaterAppInfo app);
    }

    private final List<DebloaterAppInfo> apps = new ArrayList<>();
    private final Listener listener;

    DebloaterAdapter(Listener listener) {
        this.listener = listener;
    }

    void submit(List<DebloaterAppInfo> updated) {
        apps.clear();
        apps.addAll(updated);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        CodesuDebloaterItemBinding binding = CodesuDebloaterItemBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        DebloaterAppInfo app = apps.get(position);
        holder.bind(app);
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    final class Holder extends RecyclerView.ViewHolder {
        private final CodesuDebloaterItemBinding binding;

        Holder(CodesuDebloaterItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(DebloaterAppInfo app) {
            PackageManager pm = binding.getRoot().getContext().getPackageManager();
            binding.codesuDebloaterItemName.setText(app.label);
            binding.codesuDebloaterItemPackage.setText(app.packageName);
            try {
                binding.codesuDebloaterItemIcon.setImageDrawable(app.appInfo.loadIcon(pm));
            } catch (Exception ignored) {
                binding.codesuDebloaterItemIcon.setImageResource(R.drawable.codesu_ic_info);
            }

            if (app.description == null || app.description.isEmpty()) {
                binding.codesuDebloaterItemDescription.setVisibility(View.GONE);
            } else {
                binding.codesuDebloaterItemDescription.setVisibility(View.VISIBLE);
                binding.codesuDebloaterItemDescription.setText(app.description);
            }

            renderBadges(app);

            binding.codesuDebloaterItemState.setText(app.removed ? "Restaurar"
                    : app.isEnabledApp() ? "Deshabilitar" : "Reactivar");
            binding.codesuDebloaterItemRemove.setText(app.removed ? "Reinstalar" : "Retirar");

            binding.codesuDebloaterItemCheck.setOnCheckedChangeListener(null);
            binding.codesuDebloaterItemCheck.setChecked(app.selected);
            binding.codesuDebloaterItemCheck.setOnCheckedChangeListener((button, checked) -> {
                app.selected = checked;
                listener.onSelectionChanged(app);
            });
            binding.getRoot().setOnClickListener(v -> {
                app.selected = !app.selected;
                binding.codesuDebloaterItemCheck.setChecked(app.selected);
                listener.onSelectionChanged(app);
            });
            binding.codesuDebloaterItemState.setOnClickListener(v -> listener.onStateAction(app));
            binding.codesuDebloaterItemRemove.setOnClickListener(v -> listener.onRemoveAction(app));
            binding.codesuDebloaterItemInfo.setOnClickListener(v -> listener.onInfoAction(app));
        }

        private void renderBadges(DebloaterAppInfo app) {
            LinearLayout badges = binding.codesuDebloaterItemBadges;
            badges.removeAllViews();
            addBadge(badges, "SISTEMA", R.color.codesu_badge_system);
            if (app.removed) {
                addBadge(badges, "RETIRADA", R.color.codesu_badge_removed);
            } else if (app.isDisabledApp()) {
                addBadge(badges, "DESACTIVADA", R.color.codesu_badge_disabled);
            }
            int categoryColor;
            if (DebloaterCatalog.RECOMMENDED.equals(app.category)) {
                categoryColor = R.color.codesu_badge_recommended;
            } else if (DebloaterCatalog.ADVANCED.equals(app.category)) {
                categoryColor = R.color.codesu_badge_advanced;
            } else if (DebloaterCatalog.EXPERT.equals(app.category)) {
                categoryColor = R.color.codesu_badge_expert;
            } else {
                categoryColor = R.color.codesu_badge_system;
            }
            addBadge(badges, DebloaterCatalog.label(app.category), categoryColor);
        }

        private void addBadge(LinearLayout parent, String label, int colorRes) {
            TextView badge = new TextView(parent.getContext());
            badge.setText(label);
            badge.setTextSize(9f);
            badge.setTextColor(Color.WHITE);
            badge.setPadding(dp(5), dp(2), dp(5), dp(2));
            GradientDrawable background = new GradientDrawable();
            background.setColor(ContextCompat.getColor(parent.getContext(), colorRes));
            background.setCornerRadius(dp(8));
            badge.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(4);
            parent.addView(badge, params);
        }

        private int dp(int value) {
            return (int) (value * binding.getRoot().getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}

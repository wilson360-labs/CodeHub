package com.codehub.app.codesu.ui.engine;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.codehub.app.codesu.R;
import com.codehub.app.codesu.databinding.FragmentCodeEngineBinding;
import com.codehub.app.codesu.util.CapsuleToast;
import com.codehub.app.codesu.util.CodeEngine;
import com.codehub.app.codesu.util.GameList;
import com.codehub.app.codesu.util.ShizukuPlusUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Panel de los cinco perfiles de CodeEngine y su detección automática. */
public class CodeEngineFragment extends Fragment {

    private FragmentCodeEngineBinding binding;
    private boolean updating;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentCodeEngineBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.engineProfiles.setOnCheckedChangeListener((group, checkedId) -> {
            if (updating) return;
            String profile = profileFor(checkedId);
            if (profile == null) return;
            CodeEngine.setBaseProfile(requireContext(), profile);
            refresh();
            CapsuleToast.showInfo(requireActivity(),
                    "CodeEngine · " + CodeEngine.label(profile));
        });
        binding.engineAuto.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            CodeEngine.setAuto(requireContext(), checked);
            refresh();
        });
        binding.engineDoze.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            CodeEngine.setDozeAllowed(requireContext(), checked);
            refresh();
        });
        binding.engineDnd.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (checked && !CodeEngine.hasDndAccess(requireContext())) {
                updating = true;
                binding.engineDnd.setChecked(false);
                updating = false;
                openDndSettings();
                return;
            }
            CodeEngine.setDndAllowed(requireContext(), checked);
            refresh();
        });
        binding.engineDndPermission.setOnClickListener(v -> openDndSettings());
        binding.engineApplyButton.setOnClickListener(v -> {
            String profile = CodeEngine.currentProfile(requireContext());
            String keep = CodeEngine.REASON_GAME.equals(CodeEngine.overrideReason(requireContext()))
                    ? CodeEngine.foregroundGamePackage(requireContext()) : null;
            CodeEngine.apply(requireContext(), profile, "aplicación manual", keep);
            refresh();
            CapsuleToast.showInfo(requireActivity(), "Perfil reaplicado");
        });
        binding.engineGameAdd.setOnClickListener(v -> {
            String input = binding.engineGameInput.getText() == null ? ""
                    : binding.engineGameInput.getText().toString();
            String pkg = input.trim();
            if (GameList.isGame(requireContext(), pkg)) {
                binding.engineGameInput.setText("");
                CapsuleToast.showInfo(requireActivity(), "Ya está en la lista: " + pkg);
            } else if (GameList.add(requireContext(), pkg)) {
                binding.engineGameInput.setText("");
                refreshGames();
                CapsuleToast.showInfo(requireActivity(), "Juego agregado: " + pkg);
            } else {
                CapsuleToast.showInfo(requireActivity(), "Paquete no válido: " + pkg);
            }
        });
        binding.engineGameRemove.setOnClickListener(v -> {
            String input = binding.engineGameInput.getText() == null ? ""
                    : binding.engineGameInput.getText().toString();
            String pkg = input.trim();
            binding.engineGameInput.setText("");
            if (GameList.remove(requireContext(), pkg)) {
                refreshGames();
                CapsuleToast.showInfo(requireActivity(), "Juego quitado: " + pkg);
            } else {
                CapsuleToast.showInfo(requireActivity(), "No estaba en la lista");
            }
        });
        binding.engineGameReset.setOnClickListener(v -> {
            GameList.reset(requireContext());
            refreshGames();
            CapsuleToast.showInfo(requireActivity(), "Lista de Uperf restaurada");
        });
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    private void refresh() {
        if (binding == null || !isAdded()) return;
        updating = true;
        String active = CodeEngine.currentProfile(requireContext());
        binding.engineStatus.setText("Perfil base: "
                + CodeEngine.label(CodeEngine.baseProfile(requireContext()))
                + " · activo: " + CodeEngine.label(active));
        checkProfile(active);
        binding.engineAuto.setChecked(CodeEngine.isAuto(requireContext()));
        binding.engineDoze.setChecked(CodeEngine.isDozeAllowed(requireContext()));
        binding.engineDnd.setChecked(CodeEngine.isDndAllowed(requireContext()));
        binding.engineDndPermission.setVisibility(
                CodeEngine.hasDndAccess(requireContext()) ? View.GONE : View.VISIBLE);
        binding.engineShizukuStatus.setText(ShizukuPlusUtils.checkShizuku()
                ? "Shizuku: Activo · los ajustes se escriben y restauran"
                : "Shizuku: inactivo · los ajustes del sistema no se cambiarán");
        String result = CodeEngine.lastResult(requireContext());
        long at = CodeEngine.lastAppliedAt(requireContext());
        String when = at > 0L ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(at)) : "—";
        String reason = CodeEngine.overrideReason(requireContext());
        String detail = reason.isEmpty() ? "" : " · origen: " + reason;
        binding.engineResult.setText("Última aplicación: " + when + detail
                + (result == null || result.isEmpty() ? "" : "\n" + result));
        refreshGames();
        updating = false;
    }

    private void refreshGames() {
        if (binding == null || !isAdded()) return;
        java.util.List<String> games = GameList.sorted(requireContext());
        StringBuilder text = new StringBuilder();
        for (String game : games) {
            if (text.length() > 0) text.append('\n');
            text.append(game);
        }
        binding.engineGamesList.setText(games.isEmpty()
                ? "Lista vacía · agrega el paquete de tu juego."
                : text.toString() + "\n\n" + games.size() + " juegos en la lista.");
    }

    private void checkProfile(String profile) {
        int id;
        if (CodeEngine.PROFILE_ULTRA.equals(profile))
            id = R.id.engine_profile_ultra;
        else if (CodeEngine.PROFILE_SAVER.equals(profile))
            id = R.id.engine_profile_saver;
        else if (CodeEngine.PROFILE_ADAPTIVE.equals(profile))
            id = R.id.engine_profile_adaptive;
        else if (CodeEngine.PROFILE_PERFORMANCE.equals(profile))
            id = R.id.engine_profile_performance;
        else
            id = R.id.engine_profile_balanced;
        binding.engineProfiles.check(id);
    }

    private void openDndSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        } catch (RuntimeException ignored) {
            CapsuleToast.showInfo(requireActivity(), "Este dispositivo no permite cambiar no molestar");
        }
    }

    private static String profileFor(int id) {
        if (id == R.id.engine_profile_ultra) return CodeEngine.PROFILE_ULTRA;
        if (id == R.id.engine_profile_saver) return CodeEngine.PROFILE_SAVER;
        if (id == R.id.engine_profile_adaptive) return CodeEngine.PROFILE_ADAPTIVE;
        if (id == R.id.engine_profile_balanced) return CodeEngine.PROFILE_BALANCED;
        if (id == R.id.engine_profile_performance) return CodeEngine.PROFILE_PERFORMANCE;
        return null;
    }
}

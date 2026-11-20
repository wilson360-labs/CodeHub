package com.codehub.app.codesu.ui.engine;

import android.os.Bundle;
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
        binding.engineApplyButton.setOnClickListener(v -> {
            String profile = CodeEngine.currentProfile(requireContext());
            String keep = CodeEngine.REASON_GAME.equals(CodeEngine.overrideReason(requireContext()))
                    ? CodeEngine.foregroundGamePackage(requireContext()) : null;
            CodeEngine.apply(requireContext(), profile, "aplicación manual", keep);
            refresh();
            CapsuleToast.showInfo(requireActivity(), "Perfil reaplicado");
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
        updating = false;
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

    private static String profileFor(int id) {
        if (id == R.id.engine_profile_ultra) return CodeEngine.PROFILE_ULTRA;
        if (id == R.id.engine_profile_saver) return CodeEngine.PROFILE_SAVER;
        if (id == R.id.engine_profile_adaptive) return CodeEngine.PROFILE_ADAPTIVE;
        if (id == R.id.engine_profile_balanced) return CodeEngine.PROFILE_BALANCED;
        if (id == R.id.engine_profile_performance) return CodeEngine.PROFILE_PERFORMANCE;
        return null;
    }
}

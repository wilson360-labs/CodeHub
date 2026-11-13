package com.codehub.app.codesu.ui.Settings;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.SeekBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.MainActivity;
import com.codehub.app.codesu.R;
import com.codehub.app.codesu.databinding.FragmentSettingsBinding;
import com.codehub.app.codesu.util.AutoKillHelper;
import com.codehub.app.codesu.util.BackupManager;
import com.codehub.app.codesu.util.CapsuleToast;
import rikka.shizuku.Shizuku;

public class SettingsFragment extends Fragment {

    private static final int SHIZUKU_PERMISSION_REQUEST = 7653;

    private FragmentSettingsBinding binding;
    private SettingsViewModel settingsViewModel;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST || !isAdded() || binding == null) return;
                updateShizukuStatus();
                CapsuleToast.showInfo(requireActivity(), grantResult == PackageManager.PERMISSION_GRANTED
                        ? "Shizuku permission granted" : "Shizuku permission denied");
            };

    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        settingsViewModel = new ViewModelProvider(this).get(SettingsViewModel.class);
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setObservers();
        setListeners();
        getParentFragmentManager().setFragmentResultListener(
                BackupPreviewDialogFragment.RESULT_KEY,
                getViewLifecycleOwner(),
                (requestKey, result) -> settingsViewModel.reloadSettings()
        );
    }

    @Override
    public void onStart() {
        super.onStart();
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        updateShizukuStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateShizukuStatus();
    }

    @Override
    public void onStop() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        super.onStop();
    }

    private void updateShizukuStatus() {
        if (binding == null || !isAdded()) return;
        boolean available = false;
        boolean granted = false;
        try {
            available = Shizuku.pingBinder();
            granted = available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException ignored) {
            // Shizuku can become unavailable between pingBinder and permission check.
        }
        if (!available) {
            binding.shizukuStatus.setText("Service stopped. Start Shizuku, then return here.");
            binding.shizukuStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.codesu_text_primary));
            binding.shizukuPermissionButton.setText("Open Shizuku");
        } else if (granted) {
            binding.shizukuStatus.setText("Service running · CodeSu permission granted");
            binding.shizukuStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.codesu_accent_text));
            binding.shizukuPermissionButton.setText("Authorized");
        } else {
            binding.shizukuStatus.setText("Service running · CodeSu permission required");
            binding.shizukuStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark));
            binding.shizukuPermissionButton.setText("Grant access");
        }
    }

    private void requestShizukuAccess() {
        if (!Shizuku.pingBinder()) {
            Intent launch = requireContext().getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) startActivity(launch);
            else CapsuleToast.showInfo(requireActivity(), "Install and start Shizuku first");
            updateShizukuStatus();
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                updateShizukuStatus();
                return;
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
        } catch (RuntimeException e) {
            binding.shizukuStatus.setText("Could not request permission. Restart Shizuku and retry.");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void setObservers() {
        settingsViewModel.getThemeMode().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (integer == 0)
                    binding.themeSettings.check(binding.autoTheme.getId());
                else if (integer == 1)
                    binding.themeSettings.check(binding.lightTheme.getId());
                else if (integer == 2)
                    binding.themeSettings.check(binding.darkTheme.getId());
            }
        });
        settingsViewModel.getKillerMode().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (integer == 1)
                    binding.killerModeSettings.check(binding.rootMode.getId());
                else if (integer == 2)
                    binding.killerModeSettings.check(binding.shizukuMode.getId());
                else if (integer == 3)
                    binding.killerModeSettings.check(binding.shizukuPlusMode.getId());
            }
        });
        settingsViewModel.getKillLevel().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (integer == 0)
                    binding.killLevelSettings.check(binding.killLevelSoft.getId());
                else if (integer == 1)
                    binding.killLevelSettings.check(binding.killLevelDeep.getId());
                else if (integer == 2)
                    binding.killLevelSettings.check(binding.killLevelKiller.getId());
            }
        });
        settingsViewModel.getListMode().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (integer == 0)
                    binding.listModeSettings.check(binding.userMode.getId());
                else if (integer == 1)
                    binding.listModeSettings.check(binding.launcherMode.getId());
                else if (integer == 2)
                    binding.listModeSettings.check(binding.systemMode.getId());
            }
        });
        settingsViewModel.getSelectionMode().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (integer == 0)
                    binding.selectionModeSettings.check(binding.excludeMode.getId());
                else if (integer == 1)
                    binding.selectionModeSettings.check(binding.includeMode.getId());
            }
        });
        settingsViewModel.getHideKillMyApps().observe(getViewLifecycleOwner(), binding.hideKillMyApps::setChecked);
        settingsViewModel.getHideDefaultLauncher().observe(getViewLifecycleOwner(), binding.hideDefaultLauncher::setChecked);
        settingsViewModel.getHideDefaultAlarm().observe(getViewLifecycleOwner(), binding.hideDefaultAlarm::setChecked);
        settingsViewModel.getHideDefaultKeyboard().observe(getViewLifecycleOwner(), binding.hideDefaultKeyboard::setChecked);
        settingsViewModel.getHideDefaultDialer().observe(getViewLifecycleOwner(), binding.hideDefaultDialer::setChecked);
        settingsViewModel.getHideDefaultSMS().observe(getViewLifecycleOwner(), binding.hideDefaultSms::setChecked);
        settingsViewModel.getHideCriticalSystemApps().observe(getViewLifecycleOwner(), binding.hideCriticalSystemApps::setChecked);
        settingsViewModel.getShowAppsPkgName().observe(getViewLifecycleOwner(), binding.showPkgname::setChecked);
        settingsViewModel.getClickToAppInfo().observe(getViewLifecycleOwner(), binding.clickToAppInfo::setChecked);
        settingsViewModel.getLongClickToMenu().observe(getViewLifecycleOwner(), binding.longClickToMenu::setChecked);
        settingsViewModel.getShowScrollAnimation().observe(getViewLifecycleOwner(), binding.showScrollAnimation::setChecked);
        settingsViewModel.getScreenOffAutoKill().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean)
                    AutoKillHelper.enableScreenOffKill();
                else
                    AutoKillHelper.disableScreenOffKill();

                binding.screenOffAutoKill.setChecked(aBoolean);
                binding.screenOffAutoKillDelayLayout.setVisibility(aBoolean ? View.VISIBLE : View.GONE);
            }
        });
        settingsViewModel.getScreenOffAutoKillDelay().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (binding.screenOffAutoKillDelaySeekbar.getProgress() != integer)
                    binding.screenOffAutoKillDelaySeekbar.setProgress(integer);
                if (integer == 0)
                    binding.screenOffAutoKillDelayText.setText("Delay: Off");
                else if (integer == 1)
                    binding.screenOffAutoKillDelayText.setText("Delay: 1 Minute");
                else
                    binding.screenOffAutoKillDelayText.setText("Delay: " + integer + " Minutes");

                if (settingsViewModel.getScreenOffAutoKill().getValue())
                    AutoKillHelper.enableScreenOffKill();
            }
        });
        settingsViewModel.getScheduledAutoKill().observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean)
                    AutoKillHelper.enableScheduledKill();
                else
                    AutoKillHelper.disableScheduledKill();

                binding.scheduledAutoKill.setChecked(aBoolean);
                binding.scheduledAutoKillTimeLayout.setVisibility(aBoolean ? View.VISIBLE : View.GONE);
            }
        });
        settingsViewModel.getScheduledAutoKillTime().observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (binding.scheduledAutoKillTimeSeekbar.getProgress() != integer)
                    binding.scheduledAutoKillTimeSeekbar.setProgress(integer);
                StringBuilder time = new StringBuilder("Around: ");
                time.append(((integer / 2) < 10) ? ("0" + (integer / 2) + ":") : ((integer / 2) + ":"));
                time.append(((integer % 2) == 0) ? "00" : "30");
                binding.scheduledAutoKillTimeText.setText(time);
                if (settingsViewModel.getScheduledAutoKill().getValue())
                    AutoKillHelper.enableScheduledKill();
            }
        });
    }

    private void setListeners() {
        binding.shizukuPermissionButton.setOnClickListener(v -> requestShizukuAccess());
        binding.themeSettings.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull RadioGroup group, int checkedId) {
                if (checkedId == binding.autoTheme.getId())
                    settingsViewModel.setThemeMode(0);
                else if (checkedId == binding.lightTheme.getId())
                    settingsViewModel.setThemeMode(1);
                else if (checkedId == binding.darkTheme.getId())
                    settingsViewModel.setThemeMode(2);
            }
        });

        binding.killerModeSettings.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull RadioGroup group, int checkedId) {
                if (checkedId == binding.rootMode.getId())
                    settingsViewModel.setKillerMode(1);
                else if (checkedId == binding.shizukuMode.getId())
                    settingsViewModel.setKillerMode(2);
                else if (checkedId == binding.shizukuPlusMode.getId())
                    settingsViewModel.setKillerMode(3);
            }
        });

        binding.killLevelSettings.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull RadioGroup group, int checkedId) {
                if (checkedId == binding.killLevelSoft.getId())
                    settingsViewModel.setKillLevel(0);
                else if (checkedId == binding.killLevelDeep.getId())
                    settingsViewModel.setKillLevel(1);
                else if (checkedId == binding.killLevelKiller.getId())
                    settingsViewModel.setKillLevel(2);
            }
        });

        binding.listModeSettings.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull RadioGroup group, int checkedId) {
                if (checkedId == binding.userMode.getId())
                    settingsViewModel.setListMode(0);
                else if (checkedId == binding.launcherMode.getId())
                    settingsViewModel.setListMode(1);
                else if (checkedId == binding.systemMode.getId())
                    settingsViewModel.setListMode(2);
            }
        });

        binding.selectionModeSettings.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(@NonNull RadioGroup group, int checkedId) {
                if (checkedId == binding.excludeMode.getId())
                    settingsViewModel.setSelectionMode(0);
                else if (checkedId == binding.includeMode.getId())
                    settingsViewModel.setSelectionMode(1);
            }
        });

        binding.hideKillMyApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setHideKillMyApps(binding.hideKillMyApps.isChecked());
            }
        });

        binding.hideDefaultLauncher.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setHideDefaultLauncher(binding.hideDefaultLauncher.isChecked());
            }
        });

        binding.hideDefaultAlarm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setHideDefaultAlarm(binding.hideDefaultAlarm.isChecked());
            }
        });

        binding.hideDefaultKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setHideDefaultKeyboard(binding.hideDefaultKeyboard.isChecked());
            }
        });

        binding.hideDefaultDialer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setHideDefaultDialer(binding.hideDefaultDialer.isChecked());
            }
        });

        binding.hideDefaultSms.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setHideDefaultSMS(binding.hideDefaultSms.isChecked());
            }
        });

        binding.hideCriticalSystemApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setHideCriticalSystemApps(binding.hideCriticalSystemApps.isChecked());
            }
        });

        binding.showPkgname.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setShowAppsPkgName(binding.showPkgname.isChecked());
            }
        });

        binding.clickToAppInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setClickToAppInfo(binding.clickToAppInfo.isChecked());
            }
        });

        binding.longClickToMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setLongClickToMenu(binding.longClickToMenu.isChecked());
            }
        });

        binding.showScrollAnimation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setShowScrollAnimation(binding.showScrollAnimation.isChecked());
            }
        });

        binding.screenOffAutoKill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean isChecked = binding.screenOffAutoKill.isChecked();
                if (isChecked) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        binding.screenOffAutoKill.setChecked(false);
                        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                        return;
                    }
                }
                settingsViewModel.setScreenOffAutoKill(isChecked);
            }
        });

        binding.screenOffAutoKillDelaySeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser)
                    settingsViewModel.setScreenOffAutoKillDelay(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        binding.scheduledAutoKill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settingsViewModel.setScheduledAutoKill(binding.scheduledAutoKill.isChecked());
            }
        });

        binding.scheduledAutoKillTimeSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser)
                    settingsViewModel.setScheduledAutoKillTime(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        binding.backupButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                createBackupLauncher.launch(BackupManager.createDefaultFileName());
            }
        });

        binding.restoreButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openBackupLauncher.launch(new String[]{BackupManager.MIME_TYPE});
            }
        });
    }

    private final ActivityResultLauncher<String> requestNotificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted)
                    settingsViewModel.setScreenOffAutoKill(true);
                else
                    CapsuleToast.showInfo(getActivity(), "Notification permission is required to keep the auto-kill service running");
            });

    private final ActivityResultLauncher<String> createBackupLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE),
                    uri -> {
                        if (uri == null)
                            return;

                        Context appContext = requireContext().getApplicationContext();
                        App.executorService.execute(() -> {
                            String message = "";
                            try {
                                BackupManager.writeBackup(appContext, uri);
                                message = "Backup saved successfully";
                            } catch (Exception e) {
                                message = "Backup failed: " + e.getMessage();
                            } finally {
                                String finalMessage = message;
                                App.handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        CapsuleToast.showInfo(getActivity(), finalMessage);
                                    }
                                });
                            }
                        });
                    }
            );

    private final ActivityResultLauncher<String[]> openBackupLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            BackupPreviewDialogFragment.newInstance(uri)
                                    .show(getParentFragmentManager(), "backup_preview");
                        }
                    }
            );
}

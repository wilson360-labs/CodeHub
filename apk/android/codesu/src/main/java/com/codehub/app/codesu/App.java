package com.codehub.app.codesu;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.room.Room;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.codehub.app.codesu.data.Database;
import com.codehub.app.codesu.util.AutoKillHelper;

/**
 * CodeSu vive dentro de CodeHub (com.codehub.app): ya no es una Application
 * propia. {@link CodeHubApp} llama a {@link #init(Context)} desde su onCreate
 * para inicializar la Room DB, las settings, el executor y los servicios de
 * auto-kill de este módulo.
 */
public class App {

    public static final String THEME_MODE = "themeMode";
    public static final String KILLER_MODE = "killerMode";
    public static final String KILLER_LEVEL = "killLevel";
    public static final String LIST_MODE = "listMode";
    public static final String SELECTION_MODE = "selectionMode";
    public static final String HIDE_KILL_MY_APPS = "hideKillMyApps";
    public static final String HIDE_DEFAULT_LAUNCHER = "hideDefaultLauncher";
    public static final String HIDE_DEFAULT_ALARM = "hideDefaultAlarm";
    public static final String HIDE_DEFAULT_KEYBOARD = "hideDefaultKeyboard";
    public static final String HIDE_DEFAULT_DIALER = "hideDefaultDialer";
    public static final String HIDE_DEFAULT_SMS = "hideDefaultSMS";
    public static final String HIDE_CRITICAL_SYSTEM_APPS = "hideCriticalSystemApps";
    public static final String SHOW_PKG_NAME = "showPkgName";
    public static final String CLICK_TO_APP_INFO = "clickToAppInfo";
    public static final String LONG_CLICK_TO_MENU = "longClickToMenu";
    public static final String SHOW_SCROLL_ANIMATION = "showScrollAnimation";
    public static final String SCREEN_OFF_AUTO_KILL = "screenOffAutoKill";
    public static final String SCREEN_OFF_AUTO_KILL_DELAY = "screenOffAutoKillDelay";
    public static final String SCREEN_OFF_AUTO_KILL_START_TIME = "screenOffAutoKillStartTime";
    public static final String SCHEDULED_AUTO_KILL = "scheduledTimeAutoKill";
    public static final String SCHEDULED_AUTO_KILL_HOUR = "scheduledTimeAutoKillHour";
    public static final String SCHEDULED_AUTO_KILL_MINUTE = "scheduledTimeAutoKillMinute";
    public static final String SCHEDULED_AUTO_KILL_DATE = "scheduledTimeAutoKillDate";

    public static Database database;
    public static SharedPreferences settings;
    public static Context context;
    public static ExecutorService executorService;
    public static Handler handler;
    public static boolean isFirstRun;

    /** Inicializa el módulo CodeSu dentro del proceso de CodeHub. Idempotente. */
    public static void init(Context appContext) {
        if (database != null)
            return;
        context = appContext.getApplicationContext();
        database = Room.databaseBuilder(context, Database.class, "database").build();
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        executorService = Executors.newSingleThreadExecutor();
        handler = new Handler(Looper.getMainLooper());
        isFirstRun = settings.getBoolean("isFirstRun", true);
        if (isFirstRun)
            settings.edit().putBoolean("isFirstRun", false).apply();
        initAutoKillServices();
    }

    public static void setAppThemeMode() {
        int themeMode = App.settings.getInt(App.THEME_MODE, 0);
        if (themeMode == 0)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        else if (themeMode == 1)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        else if (themeMode == 2)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    }

    public static void initAutoKillServices() {
        if (settings.getBoolean(SCREEN_OFF_AUTO_KILL, false)) {
            boolean hasNotificationPermission = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasNotificationPermission = ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED;
            }
            if (hasNotificationPermission)
                AutoKillHelper.enableScreenOffKill();
            else
                AutoKillHelper.disableScreenOffKill();
        } else
            AutoKillHelper.disableScreenOffKill();
        if (settings.getBoolean(SCHEDULED_AUTO_KILL, false))
            AutoKillHelper.initializeScheduledKill();
        else
            AutoKillHelper.disableScheduledKill();
    }
}
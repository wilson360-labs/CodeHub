package com.codehub.app;

import android.os.Bundle;
import android.os.Process;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Small Shizuku UserService that runs only validated app-stop operations as shell. */
@Keep
public final class CodeSuUserService extends ICodeSuRemoteService.Stub {
    /** Increase whenever this UserService implementation changes so Shizuku replaces stale code. */
    public static final int USER_SERVICE_VERSION = 4;
    public static final int LEVEL_BACKGROUND = 0;
    public static final int LEVEL_FORCE_STOP = 1;
    public static final int LEVEL_KILLER = 2;
    public static final int LEVEL_HIBERNATE = 3;
    public static final int LEVEL_UNHIBERNATE = 4;
    private static final String OWN_PACKAGE = "com.codehub.app";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    // Shizuku's UserService runs with the shell UID. Never terminate its host package.
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final long COMMAND_TIMEOUT_MS = 15_000L;
    private static final long PACKAGE_INDEX_TTL_MS = 30_000L;
    private static volatile PackageIndex cachedPackageIndex;

    private static final class PackageIndex {
        final Map<Integer, LinkedHashSet<String>> byUid;
        final Set<String> packageNames;
        final long loadedAt;

        PackageIndex(Map<Integer, LinkedHashSet<String>> byUid, Set<String> packageNames) {
            this.byUid = byUid;
            this.packageNames = packageNames;
            this.loadedAt = System.currentTimeMillis();
        }
    }

    public CodeSuUserService() {
        // Shizuku creates UserService classes by reflection in its shell process.
    }

    /** Shizuku's reserved lifecycle hook, called when the remote service is removed. */
    @Keep
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String diagnostics() {
        return "uid=" + Process.myUid() + ";pid=" + Process.myPid();
    }

    /** Return only packages with a live process, using the shell identity supplied by Shizuku. */
    @Override
    public String[] runningPackages() {
        try {
            PackageIndex packageIndex = packageIndex();
            LinkedHashSet<String> running = new LinkedHashSet<>();
            File[] entries = new File("/proc").listFiles();
            if (entries == null) throw new IllegalStateException("No se pudo consultar /proc.");
            int readableProcesses = 0;
            for (File entry : entries) {
                if (!entry.isDirectory() || !entry.getName().matches("[0-9]+")) continue;
                int uid = readProcessUid(entry);
                if (uid >= 0) readableProcesses++;
                if (uid < 0) continue;
                String processName = readProcessName(entry);
                int separator = processName.indexOf(':');
                String baseName = separator >= 0 ? processName.substring(0, separator) : processName;
                if (packageIndex.packageNames.contains(baseName) && isAllowedTarget(baseName)) {
                    running.add(baseName);
                }
                LinkedHashSet<String> names = packageIndex.byUid.get(uid);
                if (names == null) continue;
                for (String name : names) {
                    if (!isAllowedTarget(name)) continue;
                    if (processName.equals(name) || processName.startsWith(name + ":")) {
                        running.add(name);
                    }
                }
                // Do not infer every package sharing a UID as running when the process name
                // cannot be mapped; that makes the live list report inactive apps as active.
            }
            if (readableProcesses == 0) throw new IllegalStateException("Shizuku no tiene acceso a los UID de /proc en este dispositivo.");
            return running.toArray(new String[0]);
        } catch (Exception error) {
            throw new IllegalStateException("Shizuku no pudo leer los procesos activos: " + error.getMessage(), error);
        }
    }

    private static PackageIndex packageIndex() throws Exception {
        PackageIndex current = cachedPackageIndex;
        long now = System.currentTimeMillis();
        if (current != null && now - current.loadedAt < PACKAGE_INDEX_TTL_MS) return current;
        synchronized (CodeSuUserService.class) {
            current = cachedPackageIndex;
            now = System.currentTimeMillis();
            if (current != null && now - current.loadedAt < PACKAGE_INDEX_TTL_MS) return current;
            cachedPackageIndex = readPackageIndex();
            return cachedPackageIndex;
        }
    }

    private static PackageIndex readPackageIndex() throws Exception {
        String output = readCommand(new String[]{"pm", "list", "packages", "-U"});
        Map<Integer, LinkedHashSet<String>> result = new HashMap<>();
        Set<String> packageNames = new HashSet<>();
        for (String line : output.split("\\r?\\n")) {
            if (!line.startsWith("package:")) continue;
            String row = line.substring("package:".length()).trim();
            int uidAt = row.lastIndexOf(" uid:");
            String name = (uidAt > 0 ? row.substring(0, uidAt) : row).trim();
            if (name.isEmpty()) continue;
            packageNames.add(name);
            if (uidAt <= 0) continue;
            int uid;
            try { uid = Integer.parseInt(row.substring(uidAt + 5).trim()); }
            catch (NumberFormatException ignored) { continue; }
            result.computeIfAbsent(uid, ignored -> new LinkedHashSet<>()).add(name);
        }
        if (packageNames.isEmpty()) throw new IllegalStateException("pm no devolvió paquetes instalados.");
        return new PackageIndex(result, packageNames);
    }

    private static int readProcessUid(File processDirectory) {
        File status = new File(processDirectory, "status");
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(status))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("Uid:")) continue;
                String[] parts = line.substring(4).trim().split("\\s+");
                return parts.length == 0 ? -1 : Integer.parseInt(parts[0]);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static String readProcessName(File processDirectory) {
        File cmdline = new File(processDirectory, "cmdline");
        try (FileInputStream input = new FileInputStream(cmdline)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) > 0) bytes.write(buffer, 0, count);
            String rawName = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            int separator = rawName.indexOf('\0');
            return separator >= 0 ? rawName.substring(0, separator) : rawName;
        } catch (Exception ignored) { return ""; }
    }

    @Override
    public Bundle stopPackages(String[] packageNames, int level) {
        ArrayList<String> stopped = new ArrayList<>();
        ArrayList<String> failed = new ArrayList<>();
        long rssBeforeKb = 0L;
        long releasedRssKb = 0L;
        if (packageNames != null) {
            for (String packageName : packageNames) {
                if (!isAllowedTarget(packageName)
                        || (level < LEVEL_BACKGROUND || level > LEVEL_UNHIBERNATE)) {
                    failed.add(packageName == null ? "(desconocido)" : packageName);
                    continue;
                }
                try {
                    long packageRssBeforeKb = packageResidentMemoryKb(packageName);
                    if (level == LEVEL_HIBERNATE || level == LEVEL_UNHIBERNATE) {
                        String state = level == LEVEL_HIBERNATE ? "true" : "false";
                        String output = readCommand(new String[]{"cmd", "app_hibernation", "set-state", packageName, state});
                        if (output.toLowerCase(java.util.Locale.ROOT).contains("error")) failed.add(packageName);
                        else {
                            stopped.add(packageName);
                            if (level == LEVEL_HIBERNATE) {
                                rssBeforeKb += packageRssBeforeKb;
                                releasedRssKb += Math.max(0L, packageRssBeforeKb - waitForPackageMemoryKb(packageName));
                            }
                        }
                        continue;
                    }
                    String command = level == LEVEL_BACKGROUND ? "kill" : "force-stop";
                    if (level == LEVEL_KILLER && Process.myUid() == 0) {
                        for (String pid : processIdsForPackage(packageName)) {
                            Runtime.getRuntime().exec(new String[]{"/system/bin/kill", "-9", pid}).waitFor();
                        }
                    }
                    int exitCode = runAm(command, packageName);
                    if (exitCode == 0) {
                        stopped.add(packageName);
                        rssBeforeKb += packageRssBeforeKb;
                        releasedRssKb += Math.max(0L, packageRssBeforeKb - waitForPackageMemoryKb(packageName));
                    } else failed.add(packageName);
                } catch (Throwable error) {
                    failed.add(packageName);
                }
            }
        }
        Bundle result = new Bundle();
        result.putStringArrayList("stopped", stopped);
        result.putStringArrayList("failed", failed);
        result.putString("identity", diagnostics());
        result.putLong("rss_before_kb", rssBeforeKb);
        result.putLong("released_rss_kb", releasedRssKb);
        return result;
    }

    private static boolean isAllowedTarget(String packageName) {
        return packageName != null
                && PACKAGE_PATTERN.matcher(packageName).matches()
                && !OWN_PACKAGE.equals(packageName)
                && !packageName.startsWith(OWN_PACKAGE + ".")
                && !SHIZUKU_PACKAGE.equals(packageName)
                && !SHELL_PACKAGE.equals(packageName);
    }

    private static int runAm(String operation, String packageName) throws Exception {
        java.lang.Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/am", operation, packageName});
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CountDownLatch stderrDone = new CountDownLatch(1);
        Thread drainError = new Thread(() -> {
            try (InputStream input = process.getErrorStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) >= 0) stderr.write(buffer, 0, count);
            } catch (Throwable ignored) {
            } finally {
                stderrDone.countDown();
            }
        }, "CodeSu-stderr");
        Thread drainOutput = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                while (input.read(buffer) >= 0) { /* drain command output */ }
            } catch (Throwable ignored) {
            }
        }, "CodeSu-stdout");
        drainError.start();
        drainOutput.start();
        FutureTask<Integer> waiter = new FutureTask<>(process::waitFor);
        new Thread(waiter, "CodeSu-wait-am").start();
        final int exitCode;
        try {
            exitCode = waiter.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            process.destroy();
            waiter.cancel(true);
            throw new IllegalStateException("timeout ejecutando am " + operation);
        }
        stderrDone.await(1, TimeUnit.SECONDS);
        drainOutput.join(1000L);
        if (exitCode != 0) {
            String detail = stderr.toString("UTF-8").trim();
            throw new IllegalStateException(detail.isEmpty() ? "am salió con código " + exitCode : detail);
        }
        return exitCode;
    }

    private static ArrayList<String> processIdsForPackage(String packageName) {
        ArrayList<String> matches = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return matches;
        for (File entry : entries) {
            String pid = entry.getName();
            if (!entry.isDirectory() || !pid.matches("[0-9]+")) continue;
            String processName = readProcessName(entry);
            if (processName.equals(packageName) || processName.startsWith(packageName + ":")) matches.add(pid);
        }
        return matches;
    }

    /** Sum the resident set of the target package's processes, using the Shizuku shell access to /proc. */
    private static long packageResidentMemoryKb(String packageName) {
        long totalKb = 0L;
        for (String pid : processIdsForPackage(packageName)) {
            File status = new File("/proc/" + pid + "/status");
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(status))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("VmRSS:")) continue;
                    String[] parts = line.substring("VmRSS:".length()).trim().split("\\s+");
                    if (parts.length > 0) totalKb += Long.parseLong(parts[0]);
                    break;
                }
            } catch (Exception ignored) { }
        }
        return totalKb;
    }

    private static long waitForPackageMemoryKb(String packageName) {
        android.os.SystemClock.sleep(150L);
        return packageResidentMemoryKb(packageName);
    }

    private static String readCommand(String[] command) throws Exception {
        java.lang.Process process = Runtime.getRuntime().exec(command);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            } catch (Exception ignored) { }
        }, "CodeSu-pm-output");
        drain.start();
        FutureTask<Integer> waiter = new FutureTask<>(process::waitFor);
        new Thread(waiter, "CodeSu-pm-wait").start();
        int exitCode;
        try {
            exitCode = waiter.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            process.destroy();
            waiter.cancel(true);
            throw new IllegalStateException("pm list packages agotó el tiempo.");
        }
        drain.join(1_000L);
        if (exitCode != 0) throw new IllegalStateException("pm list packages salió con código " + exitCode);
        return output.toString("UTF-8");
    }
}

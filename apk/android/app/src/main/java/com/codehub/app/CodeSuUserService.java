package com.codehub.app;

import android.os.Bundle;
import android.os.Process;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Small Shizuku UserService that runs only validated app-stop operations as shell. */
@Keep
public final class CodeSuUserService extends ICodeSuRemoteService.Stub {
    public static final int LEVEL_BACKGROUND = 0;
    public static final int LEVEL_FORCE_STOP = 1;
    private static final String OWN_PACKAGE = "com.codehub.app";
    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    private static final long COMMAND_TIMEOUT_MS = 15_000L;

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

    @Override
    public Bundle stopPackages(String[] packageNames, int level) {
        ArrayList<String> stopped = new ArrayList<>();
        ArrayList<String> failed = new ArrayList<>();
        if (packageNames != null) {
            for (String packageName : packageNames) {
                if (!isAllowedTarget(packageName) || (level != LEVEL_BACKGROUND && level != LEVEL_FORCE_STOP)) {
                    failed.add(packageName == null ? "(desconocido)" : packageName);
                    continue;
                }
                String command = level == LEVEL_BACKGROUND ? "kill" : "force-stop";
                try {
                    int exitCode = runAm(command, packageName);
                    if (exitCode == 0) stopped.add(packageName); else failed.add(packageName);
                } catch (Throwable error) {
                    failed.add(packageName);
                }
            }
        }
        Bundle result = new Bundle();
        result.putStringArrayList("stopped", stopped);
        result.putStringArrayList("failed", failed);
        result.putString("identity", diagnostics());
        return result;
    }

    private static boolean isAllowedTarget(String packageName) {
        return packageName != null
                && PACKAGE_PATTERN.matcher(packageName).matches()
                && !OWN_PACKAGE.equals(packageName)
                && !SHIZUKU_PACKAGE.equals(packageName);
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
}

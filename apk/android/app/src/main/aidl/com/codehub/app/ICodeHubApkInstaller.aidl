package com.codehub.app;

interface ICodeHubApkInstaller {
    void shutdown();
    void beginInstall(long apkSize);
    void appendInstallChunk(in byte[] chunk);
    String commitInstall(String relaunchComponent);
    void abortInstall();
}

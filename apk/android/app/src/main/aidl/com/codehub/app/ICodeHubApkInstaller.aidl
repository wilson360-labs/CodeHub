package com.codehub.app;

interface ICodeHubApkInstaller {
    void destroy() = 16777114;
    void beginInstall(long apkSize);
    void appendInstallChunk(in byte[] chunk);
    String commitInstall(String relaunchComponent);
    void abortInstall();
}

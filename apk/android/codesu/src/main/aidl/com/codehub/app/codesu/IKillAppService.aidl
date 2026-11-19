package com.codehub.app.codesu;

interface IKillAppService {

    boolean killApp(String pkgName, int level);

    int killListOfApps(in List<String> packages, int level);

    int clearAppCache(String pkgName);

    String clearAppCacheDetailed(String pkgName);

    String clearAppCacheDetailedWithEstimate(String pkgName, long estimatedCacheBytes);

    void cancelCurrentCacheClear();

    String manageSystemApp(String packageName, int action);

    String clearAppCacheBinder(String pkgName);

    String clearAppCacheShell(String pkgName, String user, long estimatedCacheBytes);

    String freeStorageAndNotify();

    String trimCaches(long bytes);
}

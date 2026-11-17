package com.codehub.app.codesu;

interface IKillAppService {

    boolean killApp(String pkgName, int level);

    int killListOfApps(in List<String> packages, int level);

    int clearAppCache(String pkgName);

    String clearAppCacheDetailed(String pkgName);

    String clearAppCacheDetailedWithEstimate(String pkgName, long estimatedCacheBytes);
}

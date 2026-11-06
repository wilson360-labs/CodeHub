package com.codehub.app;

import android.os.Bundle;

interface ICodeSuRemoteService {
    String diagnostics();
    String[] runningPackages();
    Bundle stopPackages(in String[] packageNames, int level);
}

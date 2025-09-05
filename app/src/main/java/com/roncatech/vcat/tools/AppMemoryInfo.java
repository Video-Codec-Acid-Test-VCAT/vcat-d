package com.roncatech.vcat.tools;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;

/** Reports memory used by this app (in bytes). */
public final class AppMemoryInfo {
    private AppMemoryInfo() {}

    /** Returns memory used by this process in bytes (approximate PSS). */
    public static long getBytes(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        Debug.MemoryInfo[] infos = am.getProcessMemoryInfo(new int[]{Process.myPid()});
        if (infos == null || infos.length == 0) return 0L;
        int kb = infos[0].getTotalPss(); // KiB
        return (kb <= 0) ? 0L : (long) kb * 1024L; // convert to bytes
    }
}

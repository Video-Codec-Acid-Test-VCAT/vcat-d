package com.roncatech.extension_dav1d;

/** Loads the JNI shim once. */
public final class Dav1dLibrary {
    private static volatile boolean loaded;
    private Dav1dLibrary() {}
    public static synchronized void load() {
        if (!loaded) {
            System.loadLibrary("dav1d_jni");
            loaded = true;
        }
    }
}

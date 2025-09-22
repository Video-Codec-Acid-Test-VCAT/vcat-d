package com.roncatech.extension_dav1d;

import android.view.Surface;
import java.nio.ByteBuffer;

public final class NativeDav1d {
    static {
        // If libdav1d_jni has DT_NEEDED on libdav1d, loading the JNI lib is enough.
        // Loading dav1d first is harmless; ignore if it isn't needed.
        try { System.loadLibrary("dav1d"); } catch (Throwable ignored) {}
        System.loadLibrary("dav1d_jni");
    }

    // Creates a decoder context; returns 0 on failure.
    public static native long nativeCreate(int frameThreads, int tileThreads);

    // Flushes decoder state (drains/clears internal queues).
    public static native void nativeFlush(long ctx);

    // Destroys the decoder context and frees resources.
    public static native void nativeClose(long ctx);

    static native boolean nativeHasCapacity(long ctx);
    static native void    nativeSignalEof(long ctx);

    // Queues one compressed sample (direct ByteBuffer required).
    // Returns 0 on success; negative errno on error (e.g. -22 for EINVAL).
    public static native int nativeQueueInput(
            long ctx, ByteBuffer buffer, int offset, int size, long ptsUs);

    // Attempts to dequeue a decoded frame.
    // Returns 0 if no frame yet; otherwise a non-zero native handle.
    // outWidthHeight[0]=w, [1]=h; outPtsUs[0]=pts.
    public static native long nativeDequeueFrame(
            long ctx, int[] outWidthHeight, long[] outPtsUs);

    // Renders a decoded frame to the given Surface (RGBA8888 blit).
    // Returns 0 on success; negative on error.
    public static native int nativeRenderToSurface(
            long ctx, long nativePic, Surface surface);

    // Releases a previously dequeued native picture handle.
    public static native void nativeReleasePicture(long ctx, long nativePic);

    // Get the dav1d decoder version.
    public static native String dav1dGetVersion();

    private NativeDav1d() {}
}

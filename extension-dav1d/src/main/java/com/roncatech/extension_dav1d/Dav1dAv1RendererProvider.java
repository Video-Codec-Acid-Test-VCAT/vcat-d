package com.roncatech.extension_dav1d;

import android.content.Context;
import android.os.Handler;

import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

public interface Dav1dAv1RendererProvider {
    /** Stable id used by user config, e.g. "dav1d" or "mediacodec". */
    String id();

    /** Quick availability probe (no heavy work). Return false if unavailable on this device/build. */
    boolean isAvailable(Context context);

    /** Build the renderer or throw IllegalStateException for strict fail. */
    Renderer build(long allowedJoiningTimeMs,
                   Handler eventHandler,
                   VideoRendererEventListener eventListener);
}

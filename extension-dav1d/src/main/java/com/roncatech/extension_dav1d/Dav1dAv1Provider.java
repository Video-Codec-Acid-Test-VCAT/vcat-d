package com.roncatech.extension_dav1d;

import android.content.Context;
import android.os.Handler;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

public final class Dav1dAv1Provider implements Dav1dAv1RendererProvider {
    private final int frameThreads, tileThreads;

    public Dav1dAv1Provider(int frameThreads, int tileThreads) {
        this.frameThreads = Math.max(1, frameThreads);
        this.tileThreads  = Math.max(1, tileThreads);
    }

    @Override public String id() { return "dav1d"; }

    @Override public boolean isAvailable(Context context) {
        try {
            Dav1dLibrary.load(); // will throw UnsatisfiedLinkError if lib missing
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Renderer build(long joinMs, Handler h, VideoRendererEventListener l) {
        // DRM hard-fail lives in Dav1dVideoRenderer#createDecoder()
        return new Dav1dVideoRenderer(joinMs, h, l, frameThreads, tileThreads);
    }
}

package com.roncatech.vcat.video;

import android.content.Context;
import android.os.Handler;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.extension_dav1d.Dav1dAv1RendererProvider;

import java.util.List;

public final class MediaCodecDav1dAv1Provider implements Dav1dAv1RendererProvider {
    private static final int MAX_DROPPED = 50;
    private final MediaCodecSelector selector;

    public MediaCodecDav1dAv1Provider(MediaCodecSelector selector) {
        this.selector = selector != null ? selector : MediaCodecSelector.DEFAULT;
    }

    @Override public String id() { return "mediacodec"; }

    @Override public boolean isAvailable(Context context) {
        try {
            List<MediaCodecInfo> infos =
                    selector.getDecoderInfos(MimeTypes.VIDEO_AV1, /*secureDecodersOnly=*/false, /*tunneling=*/false);
            return infos != null && !infos.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Renderer build(long joinMs, Handler h, VideoRendererEventListener l) {
        return new MediaCodecVideoRenderer(
                /* context = */ null, // not used by constructor overload in some 2.x; use the one you use elsewhere
                selector,
                joinMs,
                /* enableDecoderFallback = */ false,
                h, l,
                MAX_DROPPED);
    }
}

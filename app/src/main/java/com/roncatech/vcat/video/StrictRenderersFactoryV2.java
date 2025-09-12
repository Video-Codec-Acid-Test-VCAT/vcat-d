package com.roncatech.vcat.video;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.util.Supplier;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.mediacodec.DefaultMediaCodecAdapterFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.extension_dav1d.Dav1dAv1Provider;
import com.roncatech.extension_dav1d.Dav1dAv1RendererProvider;
import com.roncatech.vcat.models.SharedViewModel;
import com.roncatech.vcat.tools.VideoDecoderEnumerator;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class StrictRenderersFactoryV2 extends DefaultRenderersFactory {

    private final SharedViewModel viewModel;
    private final MediaCodecSelector customSelector;

    public StrictRenderersFactoryV2(Context ctx,
                                    SharedViewModel viewModel) {
        super(ctx);
        this.viewModel = viewModel;

        this.customSelector = (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            String decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(mimeType);

            List<MediaCodecInfo> infos = MediaCodecUtil.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
            );

            if (decoderName != null && !decoderName.isEmpty()) {
                List<MediaCodecInfo> filtered = new ArrayList<>();
                for (MediaCodecInfo info : infos) {
                    if (info.name.equalsIgnoreCase(decoderName)) {
                        filtered.add(info);
                    }
                }
                return filtered;
            }
            return infos;
        };
    }

    @Override
    protected void buildVideoRenderers(
            Context context,
            int extensionRendererMode,
            MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            long allowedVideoJoiningTimeMs,
            ArrayList<Renderer> out
    )  {

        String av1Decoder = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.AV1);
        // Always add MediaCodec renderer for fallback/default
        out.add(new MediaCodecVideoRenderer(
                context,
                customSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
        ));

        // Conditionally add Libdav1d renderer only for AV1 and if libdav1d is desired
        if ("vcat.dav1d".equalsIgnoreCase(av1Decoder)) {
            try {
                Dav1dAv1Provider provider = new Dav1dAv1Provider(this.viewModel.getRunConfig().threads, 4);

                if(provider == null){
                    throw new IllegalStateException("Unable to instantiate Dav1dAv1Provider");
                }
                if (!provider.isAvailable(context)) {
                    throw new IllegalStateException("Selected AV1 decoder not available for vcat.dav1d");
                }
                out.add(provider.build(allowedVideoJoiningTimeMs, eventHandler, eventListener));
            } catch (Exception e) {
                Log.w("RenderersFactory", "Libdav1dVideoRenderer not available: " + e.getMessage());
            }
        }
    }
}

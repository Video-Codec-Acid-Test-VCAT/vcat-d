package com.roncatech.vcat.video;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.vcat.models.SharedViewModel;
import com.roncatech.vcat.tools.VideoDecoderEnumerator;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StrictRenderersFactoryV1 extends DefaultRenderersFactory {

    private final SharedViewModel viewModel;
    private final MediaCodecSelector customSelector;

    public StrictRenderersFactoryV1(Context ctx, SharedViewModel viewModel){
        super(ctx);
        this.viewModel = viewModel;
        this.customSelector = (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            String decoderName = null;

            switch (mimeType) {
                case MimeTypes.VIDEO_H264:
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.H264);
                    break;
                case MimeTypes.VIDEO_H265:
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.H265);
                    break;
                case MimeTypes.VIDEO_AV1:
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.AV1);

                    if ("dav1d".equalsIgnoreCase(decoderName)) {
                        Log.d("Decoder", "Skipping MediaCodec decoders for AV1 (dav1d selected)");
                        return Collections.emptyList(); // Disables AV1 for MediaCodecVideoRenderer
                    }
                    break;
                case MimeTypes.VIDEO_VP9:
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.VP9);
                    break;
                case "video/vvc":
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.VVC);
                    break;
            }

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
        };;
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
    ) {
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
        if ("dav1d".equalsIgnoreCase(av1Decoder)) {
            try {
                Class<?> av1RendererClass = Class.forName("com.google.android.exoplayer2.ext.av1.Libdav1dVideoRenderer");
                Constructor<?> constructor = av1RendererClass.getConstructor(
                        long.class, Handler.class, VideoRendererEventListener.class, int.class, int.class, int.class, int.class
                );
                Renderer libav1Renderer = (Renderer) constructor.newInstance(
                        allowedVideoJoiningTimeMs,
                        eventHandler,
                        eventListener,
                        MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                        StrictRenderersFactoryV1.this.viewModel.getRunConfig().threads,
                        4,
                        4
                );
                Log.d("RenderersFactory", "Add dav1dRenderer");
                out.add(libav1Renderer);
            } catch (Exception e) {
                Log.w("RenderersFactory", "Libdav1dVideoRenderer not available: " + e.getMessage());
            }
        }
    }
}

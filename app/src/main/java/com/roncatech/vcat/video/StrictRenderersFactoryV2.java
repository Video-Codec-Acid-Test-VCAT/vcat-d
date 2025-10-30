package com.roncatech.vcat.video;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.video.DecoderVideoRenderer;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.libvcat.dav1d.Dav1dAv1Provider;
import com.roncatech.libvcat.vvdec.VvdecProvider;
import com.roncatech.vcat.models.SharedViewModel;
import com.roncatech.vcat.tools.VideoDecoderEnumerator;

import java.util.ArrayList;
import java.util.List;

public final class StrictRenderersFactoryV2 extends DefaultRenderersFactory {
    private static final String TAG = "RenderersFactory";

    private final SharedViewModel viewModel;
    private final MediaCodecSelector customSelector;

    public static final String VCAT_DAV1D = "vcat.dav1d";
    public static final String VCAT_VVDEC = "vcat.vvdec";

    @Nullable private DecoderVideoRenderer dav1dRenderer;
    @Nullable private DecoderVideoRenderer vvdecRenderer;

    public StrictRenderersFactoryV2(Context ctx, SharedViewModel viewModel) {
        super(ctx);
        this.viewModel = viewModel;

        // Respect the user-selected codec name if present; otherwise return full list.
        this.customSelector = (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            String selected = viewModel.getRunConfig().decoderCfg.getDecoder(mimeType);
            List<MediaCodecInfo> infos = MediaCodecUtil.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

            if (selected != null && !selected.isEmpty()) {
                List<MediaCodecInfo> filtered = new ArrayList<>();
                for (MediaCodecInfo info : infos) {
                    if (info.name.equalsIgnoreCase(selected)) filtered.add(info);
                }
                Log.i(TAG, "MediaCodecSelector for " + mimeType + " -> " + selected
                        + " (found=" + filtered.size() + ")");
                return filtered;
            }
            Log.i(TAG, "MediaCodecSelector for " + mimeType + " -> default list (count=" + infos.size() + ")");
            return infos;
        };
    }

    @Override
    protected void buildVideoRenderers(
            Context context,
            int extensionRendererMode,
            MediaCodecSelector ignoredMediaCodecSelector,
            boolean enableDecoderFallback,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            long allowedVideoJoiningTimeMs,
            ArrayList<Renderer> out
    )  {
        final String selAv1 = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.AV1);
        final String selVvc = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.VVC);

        // 1) Prefer extensions first so they can claim their formats before MediaCodec.
        // --- vvdec (VVC) ---
        try {
            boolean wantVvdec = VCAT_VVDEC.equalsIgnoreCase(selVvc) || selVvc == null || selVvc.isEmpty();
            if (wantVvdec && false) {
                VvdecProvider vvdec = new VvdecProvider(this.viewModel.getRunConfig().threads);
                if (vvdec.isAvailable(context)) {
                    Renderer r = vvdec.build(allowedVideoJoiningTimeMs, eventHandler, eventListener);
                    out.add(r);
                    if (r instanceof DecoderVideoRenderer) {
                        this.vvdecRenderer = (DecoderVideoRenderer) r;
                    }
                    Log.i(TAG, "Added vvdec renderer.");
                } else {
                    Log.i(TAG, "vvdec not available (skipped).");
                }
            } else {
                Log.i(TAG, "vvdec explicitly not selected (selVvc=" + selVvc + ").");
            }
        } catch (Throwable t) {
            Log.w(TAG, "vvdec renderer not added", t);
        }

        // --- dav1d (AV1) ---
        try {
            boolean wantDav1d = VCAT_DAV1D.equalsIgnoreCase(selAv1) || selAv1 == null || selAv1.isEmpty();
            if (wantDav1d) {
                Dav1dAv1Provider dav1d = new Dav1dAv1Provider(this.viewModel.getRunConfig().threads, /*tileThreads=*/4);
                if (dav1d.isAvailable(context)) {
                    Renderer r = dav1d.build(allowedVideoJoiningTimeMs, eventHandler, eventListener);
                    out.add(r);
                    if (r instanceof DecoderVideoRenderer) this.dav1dRenderer = (DecoderVideoRenderer) r;
                    Log.i(TAG, "Added dav1d renderer.");
                } else {
                    Log.i(TAG, "dav1d not available (skipped).");
                }
            } else {
                Log.i(TAG, "dav1d explicitly not selected (selAv1=" + selAv1 + ").");
            }
        } catch (Throwable t) {
            Log.w(TAG, "dav1d renderer not added", t);
        }

        // 2) Always add MediaCodec as a fallback/default (after extensions).
        out.add(new MediaCodecVideoRenderer(
                context,
                customSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
        ));
        Log.i(TAG, "Added MediaCodecVideoRenderer.");
    }
}

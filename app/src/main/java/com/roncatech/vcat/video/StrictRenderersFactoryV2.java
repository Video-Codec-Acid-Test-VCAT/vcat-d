package com.roncatech.vcat.video;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.video.DecoderVideoRenderer;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.libvcat.decoder.VcatDecoderManager;
import com.roncatech.vcat.decoder_plugin_api.VcatDecoderPlugin;
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
        // --- VVC decoder (vvdec or any other registered VVC plugin) ---
        try {
            String vvcDecoderId = (selVvc == null || selVvc.isEmpty()) ? VCAT_VVDEC : selVvc;
            VcatDecoderPlugin vvc = VcatDecoderManager.getInstance().getDecoder(vvcDecoderId);
            if (vvc != null) {
                out.add(vvc.createVideoRenderer(context, allowedVideoJoiningTimeMs, eventHandler, eventListener, this.viewModel.getRunConfig().threads));
            } else {
                Log.w(TAG, "VVC decoder not found in registry: " + vvcDecoderId);
            }
        } catch (DecoderException e) {
            Log.e(TAG, "VVC renderer not added", e);
        }

        // --- dav1d (AV1) ---
        try {
            String av1DecoderId = (selAv1 == null || selAv1.isEmpty()) ? VCAT_DAV1D : selAv1;
            VcatDecoderPlugin dav1d = VcatDecoderManager.getInstance().getDecoder(av1DecoderId);
            if (dav1d != null) {
                Renderer davidRenderer = dav1d.createVideoRenderer(context, allowedVideoJoiningTimeMs, eventHandler, eventListener, this.viewModel.getRunConfig().threads);
                out.add(davidRenderer);
                Log.i(TAG, "Added AV1 renderer: " + av1DecoderId);
            } else {
                Log.w(TAG, "AV1 decoder not found in registry: " + av1DecoderId);
            }
        } catch (DecoderException e) {
            Log.w(TAG, "dav1d renderer not added", e);
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

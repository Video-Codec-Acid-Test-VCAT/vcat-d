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
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.libvcat.decoder.VcatDecoderManager;
import com.roncatech.vcat.decoder_plugin_api.VcatDecoderPlugin;
import com.roncatech.vcat.models.SharedViewModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class StrictRenderersFactoryV2 extends DefaultRenderersFactory {
    private static final String TAG = "RenderersFactory";

    private final SharedViewModel viewModel;
    private final MediaCodecSelector customSelector;


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
        // 1) Software plugin decoders first so they can claim their formats before MediaCodec.
        //    Enumerate all distinct MIME types from registered plugins — no hardcoded codec list.
        LinkedHashSet<String> pluginMimeTypes = new LinkedHashSet<>();
        for (VcatDecoderPlugin p : VcatDecoderManager.getInstance().getDecoders()) {
            pluginMimeTypes.add(p.getMimeType());
        }
        for (String mimeType : pluginMimeTypes) {
            String selected = viewModel.getRunConfig().decoderCfg.getDecoder(mimeType);
            addPluginRenderers(context, allowedVideoJoiningTimeMs, eventHandler, eventListener,
                    mimeType, selected, out);
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

    /**
     * Adds software plugin renderers for {@code mimeType} to {@code out}.
     * If {@code selectedId} is non-empty, looks up that specific plugin only.
     * If empty, adds all registered plugins for the MIME type — supporting any
     * number of third-party decoder plugins without naming them here.
     */
    private void addPluginRenderers(
            Context context,
            long allowedVideoJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            String mimeType,
            String selectedId,
            ArrayList<Renderer> out) {

        List<VcatDecoderPlugin> candidates;
        if (selectedId != null && !selectedId.isEmpty()) {
            VcatDecoderPlugin plugin = VcatDecoderManager.getInstance().getDecoder(selectedId);
            if (plugin == null) {
                Log.w(TAG, "Decoder not found in registry: " + selectedId);
                return;
            }
            candidates = java.util.Collections.singletonList(plugin);
        } else {
            candidates = VcatDecoderManager.getInstance().getDecodersForMimeType(mimeType);
            if (candidates.isEmpty()) {
                Log.i(TAG, "No registered plugin for " + mimeType + ", skipping.");
                return;
            }
        }

        for (VcatDecoderPlugin plugin : candidates) {
            try {
                out.add(plugin.createVideoRenderer(context, allowedVideoJoiningTimeMs,
                        eventHandler, eventListener,
                        this.viewModel.getRunConfig().threads));
                Log.i(TAG, "Added plugin renderer: " + plugin.getId());
            } catch (DecoderException e) {
                Log.e(TAG, "Plugin renderer not added: " + plugin.getId(), e);
            }
        }
    }
}

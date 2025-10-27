/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting VCAT itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

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
import com.google.android.exoplayer2.video.DecoderVideoRenderer;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.libvcat.Dav1dAv1Provider;
import com.roncatech.libvcat.Dav1dAv1RendererProvider;
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

    public final static String vcatDav1dName = "vcat.dav1d";

    @Nullable private DecoderVideoRenderer dav1dRenderer;

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


        // Conditionally add Libdav1d renderer only for AV1 and if libdav1d is desired
        if (vcatDav1dName.equalsIgnoreCase(av1Decoder)) {
            try {
                Dav1dAv1Provider provider = new Dav1dAv1Provider(this.viewModel.getRunConfig().threads, 4);

                if (!provider.isAvailable(context)) {
                    throw new IllegalStateException(
                            "Selected AV1 decoder not available for vcat.dav1d"
                    );
                }

                if(provider == null){
                    throw new IllegalStateException("Unable to instantiate Dav1dAv1Provider");
                }
                if (!provider.isAvailable(context)) {
                    throw new IllegalStateException("Selected AV1 decoder not available for vcat.dav1d");
                }
                Renderer r = provider.build(allowedVideoJoiningTimeMs, eventHandler, eventListener);
                out.add(r);
                if (r instanceof DecoderVideoRenderer) this.dav1dRenderer = (DecoderVideoRenderer) r;
                android.util.Log.i("RenderersFactory", "Added dav1d-only renderer and returning.");
            } catch (Exception e) {
                Log.w("RenderersFactory", "Libdav1dVideoRenderer not available: " + e.getMessage());
            }
        }

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
    }
}

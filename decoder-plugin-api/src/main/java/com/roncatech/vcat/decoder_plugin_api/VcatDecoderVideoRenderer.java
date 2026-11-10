/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2026 vcat-d authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of vcat-d.
 *
 * vcat-d is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * vcat-d is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All vcat-d artwork is owned exclusively by RoncaTech LLC. Use of vcat-d logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting vcat-d itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.decoder_plugin_api;

import android.os.Handler;
import android.util.Log;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.video.DecoderVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

/**
 * The renderer every VCAT decoder plugin extends: one frame-drop policy for all decoders, owned
 * by the host.
 *
 * <p>A plugin supplies decoding (createDecoder, renderOutputBufferToSurface, supportsFormat,
 * ...); this class decides when a decoded frame is too late to show and when to skip ahead to a
 * keyframe. Both are final so no plugin can diverge -- they used to be overridden in each of six
 * plugin renderers, each hard-coding 50 ms and 500 ms.
 *
 * <p>Thresholds come from the run ({@link RendererConfig}) in frame intervals, converted with the
 * stream's frame rate ({@link Format#frameRate}); a stream with no frame rate falls back to
 * 30 fps, which reproduces the old millisecond thresholds exactly.
 *
 * <p>shouldForceRenderOutputBuffer is deliberately not overridden. ExoPlayer's default only
 * force-renders a late frame when more than 100 ms have passed since the last render, so late
 * frames are dropped and playback stays on the clock. A plugin that force-rendered every late
 * frame made a decoder below real time look better: vvdec 3.0.0 played at 0.64x real time on
 * CPH2625 reporting 0 drops.
 */
public abstract class VcatDecoderVideoRenderer extends DecoderVideoRenderer {
    private static final String TAG = "VcatRenderer";

    private final RendererConfig config;
    private long dropThresholdUs;
    private long skipThresholdUs;

    /** Diagnostics for the skip threshold; see shouldDropBuffersToKeyframe. */
    private long lastVeryLateLogMs;
    private int veryLateCount;

    protected VcatDecoderVideoRenderer(
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int maxDroppedFramesToNotify,
            RendererConfig config) {
        super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
        this.config = config != null ? config : RendererConfig.withThreads(1);
        applyFrameRate(Format.NO_VALUE);
    }

    /** The run conditions this renderer was created with. */
    protected final RendererConfig getRendererConfig() {
        return config;
    }

    @Override
    protected void onInputFormatChanged(FormatHolder formatHolder) throws ExoPlaybackException {
        super.onInputFormatChanged(formatHolder);
        Format format = formatHolder.format;
        applyFrameRate(format != null ? format.frameRate : Format.NO_VALUE);
    }

    private void applyFrameRate(float frameRate) {
        boolean known = RendererConfig.isKnownFrameRate(frameRate);
        dropThresholdUs = config.dropThresholdUs(frameRate);
        skipThresholdUs = config.skipThresholdUs(frameRate);
        Log.i(TAG, "drop policy: frameRate="
                + (known ? frameRate : "unknown, using " + RendererConfig.FALLBACK_FRAME_RATE)
                + " maxFrameDelay=" + config.maxFrameDelay + " (" + dropThresholdUs / 1000.0 + " ms)"
                + " skipToKeyframeDelay=" + config.skipToKeyframeDelay
                + " (" + skipThresholdUs / 1000.0 + " ms)");
    }

    @Override
    protected final boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        return earlyUs < -dropThresholdUs;
    }

    @Override
    protected final boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs) {
        boolean veryLate = earlyUs < -skipThresholdUs;
        if (veryLate) {
            // Telemetry reports how many frames a skip discarded, not how late the renderer
            // believed it was when it asked. Logging earlyUs distinguishes a decoder that really
            // is seconds late from a discontinuity in the timestamps it is compared against.
            // Rate-limited to one line a second so a genuine stall cannot flood the log.
            long nowMs = elapsedRealtimeUs / 1000L;
            if (nowMs - lastVeryLateLogMs > 1000L) {
                lastVeryLateLogMs = nowMs;
                Log.w(TAG, "VERY-LATE earlyUs=" + earlyUs + " (" + (earlyUs / 1000L) + " ms late)"
                        + " consecutive=" + (++veryLateCount));
            }
        }
        return veryLate;
    }
}

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

/**
 * What the host tells a decoder plugin about the run when it creates its renderer: the run
 * conditions that govern decoding and frame dropping.
 *
 * <p>Frame buffering and drop policy belong to the host, not to each plugin: every plugin's
 * renderer extends {@link VcatDecoderVideoRenderer}, which applies the thresholds here the same
 * way for every decoder. Before this, each plugin hard-coded its own (50 ms / 500 ms) and a run
 * condition had no way to reach them.
 *
 * <p>Thresholds are in <b>frame intervals</b> (1 / the stream's frame rate), not milliseconds,
 * so they mean the same at every frame rate: a fixed 50 ms is 1.2 frames at 24 fps but 3 at
 * 60 fps.
 */
public final class RendererConfig {
    /** Default for {@link #maxFrameDelay}: 1.5 intervals = the old 50 ms at 30 fps. */
    public static final double DEFAULT_MAX_FRAME_DELAY = 1.5;
    /** Default for {@link #skipToKeyframeDelay}: 15 intervals = the old 500 ms at 30 fps. */
    public static final double DEFAULT_SKIP_TO_KEYFRAME_DELAY = 15.0;

    /** Decoder threads (Conditions: Threads). */
    public final int threads;

    /**
     * A decoded frame more than this many frame intervals late is dropped instead of shown
     * (Conditions: Max Delay).
     */
    public final double maxFrameDelay;

    /**
     * When the renderer is more than this many frame intervals behind, it skips the input ahead
     * to the next keyframe instead of decoding frames that will only be dropped.
     */
    public final double skipToKeyframeDelay;

    public RendererConfig(int threads, double maxFrameDelay, double skipToKeyframeDelay) {
        this.threads = threads;
        this.maxFrameDelay = maxFrameDelay > 0 ? maxFrameDelay : DEFAULT_MAX_FRAME_DELAY;
        this.skipToKeyframeDelay =
                skipToKeyframeDelay > 0 ? skipToKeyframeDelay : DEFAULT_SKIP_TO_KEYFRAME_DELAY;
    }

    /** Frame rate assumed when a stream has none; reproduces the old ms thresholds exactly. */
    public static final float FALLBACK_FRAME_RATE = 30f;

    /** Whether {@code frameRate} is usable for converting frame intervals to time. */
    public static boolean isKnownFrameRate(float frameRate) {
        return frameRate > 0 && frameRate <= 480;
    }

    /** {@link #maxFrameDelay} in microseconds at {@code frameRate} (fallback if unknown). */
    public long dropThresholdUs(float frameRate) {
        return framesToUs(maxFrameDelay, frameRate);
    }

    /** {@link #skipToKeyframeDelay} in microseconds at {@code frameRate} (fallback if unknown). */
    public long skipThresholdUs(float frameRate) {
        return framesToUs(skipToKeyframeDelay, frameRate);
    }

    private static long framesToUs(double frames, float frameRate) {
        float fps = isKnownFrameRate(frameRate) ? frameRate : FALLBACK_FRAME_RATE;
        return Math.round(frames * 1_000_000.0 / fps);
    }

    public static RendererConfig withThreads(int threads) {
        return new RendererConfig(threads, DEFAULT_MAX_FRAME_DELAY, DEFAULT_SKIP_TO_KEYFRAME_DELAY);
    }

    @Override public String toString() {
        return "RendererConfig{threads=" + threads + ", maxFrameDelay=" + maxFrameDelay
                + ", skipToKeyframeDelay=" + skipToKeyframeDelay + "}";
    }
}

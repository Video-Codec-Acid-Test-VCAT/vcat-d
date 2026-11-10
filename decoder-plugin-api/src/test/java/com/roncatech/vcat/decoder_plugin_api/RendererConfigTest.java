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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The drop thresholds are in frame intervals, so the same Max Delay means the same thing at
 * every frame rate -- unlike the fixed 50 ms it replaced, which was 1.2 frames at 24 fps,
 * 1.5 at 30 and 3 at 60.
 */
public class RendererConfigTest {

    private static RendererConfig delay(double frames) {
        return new RendererConfig(2, frames, RendererConfig.DEFAULT_SKIP_TO_KEYFRAME_DELAY);
    }

    @Test public void defaultIsTheOld50msAt30fps() {
        RendererConfig c = RendererConfig.withThreads(2);
        assertEquals(1.5, c.maxFrameDelay, 0);
        assertEquals(50_000, c.dropThresholdUs(30f));
        assertEquals(500_000, c.skipThresholdUs(30f));
    }

    @Test public void thresholdScalesWithTheFrameInterval() {
        RendererConfig c = delay(1.5);
        assertEquals(62_500, c.dropThresholdUs(24f));   // 1.5 x 41.67 ms
        assertEquals(60_000, c.dropThresholdUs(25f));   // 1.5 x 40 ms
        assertEquals(50_000, c.dropThresholdUs(30f));   // 1.5 x 33.33 ms
        assertEquals(25_000, c.dropThresholdUs(60f));   // 1.5 x 16.67 ms
        assertEquals(50_050, c.dropThresholdUs(29.97f), 1);
    }

    @Test public void otherDelays() {
        assertEquals(33_333, delay(1.0).dropThresholdUs(30f));
        assertEquals(80_000, delay(2.0).dropThresholdUs(25f));
        assertEquals(125_000, delay(3.0).dropThresholdUs(24f));
    }

    @Test public void unknownFrameRateFallsBackTo30fps() {
        assertEquals(50_000, delay(1.5).dropThresholdUs(-1f));      // Format.NO_VALUE
        assertEquals(50_000, delay(1.5).dropThresholdUs(0f));
        assertEquals(50_000, delay(1.5).dropThresholdUs(1_000_000f)); // microsecond time base
    }

    @Test public void nonPositiveDelaysUseTheDefaults() {
        RendererConfig c = new RendererConfig(2, 0, -1);
        assertEquals(RendererConfig.DEFAULT_MAX_FRAME_DELAY, c.maxFrameDelay, 0);
        assertEquals(RendererConfig.DEFAULT_SKIP_TO_KEYFRAME_DELAY, c.skipToKeyframeDelay, 0);
    }
}

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

package com.roncatech.vcat.models;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class ConditionsSummaryTest {

    private static JsonObject tc(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void currentLogShowsAllFour() {
        JsonObject c = tc("{\"threads\":4,\"maxFrameDelay\":1.5,\"displayRefreshHz\":120,"
                + "\"screenBrightness\":25}");
        assertEquals("Threads: 4   Max Delay: 1.5 frames\nRefresh: 120 Hz   Brightness: 25%",
                ConditionsSummary.describe(c));
    }

    @Test
    public void conditionsALogPredatesReadNotRecorded() {
        // A log from before Max Delay and Refresh existed must not borrow today's defaults.
        JsonObject c = tc("{\"threads\":2,\"screenBrightness\":50}");
        assertEquals("n/a", ConditionsSummary.maxDelay(c));
        assertEquals("n/a", ConditionsSummary.refresh(c));
        assertEquals("2", ConditionsSummary.threads(c));
        assertEquals("50%", ConditionsSummary.brightness(c));
    }

    @Test
    public void noHeaderReadsNotRecordedEverywhere() {
        assertEquals("Threads: n/a   Max Delay: n/a\nRefresh: n/a   Brightness: n/a",
                ConditionsSummary.describe(null));
    }

    @Test
    public void wholeFrameDelaysDropTheDecimal() {
        assertEquals("1 frame", ConditionsSummary.frameDelayLabel(1.0));
        assertEquals("2 frames", ConditionsSummary.frameDelayLabel(2.0));
        assertEquals("1.25 frames", ConditionsSummary.frameDelayLabel(1.25));
    }
}

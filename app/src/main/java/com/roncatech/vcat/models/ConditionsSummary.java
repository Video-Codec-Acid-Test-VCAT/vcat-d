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

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The run conditions a log recorded, as the Logs details show them: Threads, Max Delay, Refresh
 * and Brightness.
 *
 * <p>Reads the header's raw {@code test_conditions} object rather than a deserialized
 * {@link RunConfig}, so a condition a log predates reads "n/a" instead of today's default. See
 * {@link SessionHeader#testConditionsJson}.
 */
public final class ConditionsSummary {

    public static final String NOT_RECORDED = "n/a";

    private ConditionsSummary() {}

    /** "Threads: 2   Max Delay: 1.5 frames" on one line, "Refresh: 60 Hz   Brightness: 25%" on the next. */
    public static String describe(@Nullable JsonObject testConditions) {
        return "Threads: " + threads(testConditions)
                + "   Max Delay: " + maxDelay(testConditions)
                + "\nRefresh: " + refresh(testConditions)
                + "   Brightness: " + brightness(testConditions);
    }

    public static String threads(@Nullable JsonObject tc) {
        Double v = number(tc, "threads");
        return v == null ? NOT_RECORDED : String.valueOf(v.intValue());
    }

    public static String maxDelay(@Nullable JsonObject tc) {
        Double v = number(tc, "maxFrameDelay");
        // 0 is what the field reads before the host fills it in; never a real run condition.
        return (v == null || v <= 0) ? NOT_RECORDED : frameDelayLabel(v);
    }

    public static String refresh(@Nullable JsonObject tc) {
        Double v = number(tc, "displayRefreshHz");
        return (v == null || v <= 0) ? NOT_RECORDED : v.intValue() + " Hz";
    }

    public static String brightness(@Nullable JsonObject tc) {
        Double v = number(tc, "screenBrightness");
        return v == null ? NOT_RECORDED : v.intValue() + "%";
    }

    /** "1.5 frames", "1 frame": the Max Delay choices as the user reads them. */
    public static String frameDelayLabel(double frames) {
        String n = (frames == Math.rint(frames)) ? String.valueOf((int) frames)
                : String.valueOf(frames);
        return n + (frames == 1.0 ? " frame" : " frames");
    }

    @Nullable
    private static Double number(@Nullable JsonObject tc, String key) {
        if (tc == null) return null;
        JsonElement e = tc.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        return e.getAsDouble();
    }
}

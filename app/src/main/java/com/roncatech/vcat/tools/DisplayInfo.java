/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
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

package com.roncatech.vcat.tools;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import com.roncatech.vcat.models.RunConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Display capabilities that affect a run: which refresh rates can actually be requested. */
public final class DisplayInfo {
    private static final String TAG = "DisplayInfo";

    /**
     * Rates offered in Test Conditions, in the order shown. A rate is only offered if the display
     * reports a mode for it, so a 60 Hz-only panel never shows 120.
     *
     * <p>30 is included because it matches 30 fps content exactly and minimises the display's
     * standing power, which is the largest uncontrolled term in a cross-device battery comparison.
     * It is not the default: at 30 Hz each frame gets exactly one vsync, so a frame ready a
     * millisecond late misses its window and is counted as a drop for presentation reasons rather
     * than decode. 60 keeps ~16 ms of slack.
     */
    private static final int[] CANDIDATE_RATES = {30, 60, 90, 120};

    /** Match tolerance in Hz — panels report values like 60.000004 and 120.00001. */
    private static final float MATCH_TOLERANCE_HZ = 1.0f;

    private DisplayInfo() {}

    /**
     * Refresh rates this device can be asked for, always including
     * {@link RunConfig#DEFAULT_REFRESH_HZ} so the default is never unselectable.
     */
    public static List<Integer> supportedRefreshRates(Context ctx) {
        Set<Integer> out = new LinkedHashSet<>();
        float[] reported = reportedRates(ctx);

        for (int candidate : CANDIDATE_RATES) {
            for (float r : reported) {
                if (Math.abs(r - candidate) <= MATCH_TOLERANCE_HZ) {
                    out.add(candidate);
                    break;
                }
            }
        }

        // A display that reports nothing usable must still allow the default to be chosen; the
        // request is advisory anyway, so asking for 60 on such a device is harmless.
        if (out.isEmpty() || !out.contains(RunConfig.DEFAULT_REFRESH_HZ)) {
            out.add(RunConfig.DEFAULT_REFRESH_HZ);
        }

        List<Integer> sorted = new ArrayList<>(out);
        sorted.sort(Integer::compare);
        return sorted;
    }

    /** True if {@code hz} is one of {@link #supportedRefreshRates}. */
    public static boolean supports(Context ctx, int hz) {
        return supportedRefreshRates(ctx).contains(hz);
    }

    /**
     * The rate the display is actually running at now. The requested rate is advisory — the system
     * may refuse it, and LTPO panels move on their own — so this is what a run should record.
     */
    public static float currentRefreshRate(Context ctx) {
        Display display = defaultDisplay(ctx);
        return display != null ? display.getRefreshRate() : 0f;
    }

    private static float[] reportedRates(Context ctx) {
        Display display = defaultDisplay(ctx);
        if (display == null) {
            Log.w(TAG, "No default display; offering the default rate only");
            return new float[0];
        }
        // Mode list rather than getSupportedRefreshRates(): the latter is deprecated and reports
        // only rates for the current resolution on some devices.
        Display.Mode[] modes = display.getSupportedModes();
        if (modes == null || modes.length == 0) {
            return new float[]{display.getRefreshRate()};
        }
        float[] rates = new float[modes.length];
        for (int i = 0; i < modes.length; i++) {
            rates[i] = modes[i].getRefreshRate();
        }
        return rates;
    }

    private static Display defaultDisplay(Context ctx) {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        return dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
    }
}

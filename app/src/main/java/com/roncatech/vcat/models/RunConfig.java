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

package com.roncatech.vcat.models;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.Map;
import java.util.Objects;

public class RunConfig {
    public int screenBrightness;
    public int threads;
    /**
     * Display refresh rate to request for playback, in Hz.
     *
     * <p>Defaults to 60 on every device. Left to the device default, panel rate is an uncontrolled
     * variable in a battery comparison: a 120 Hz-default phone pays a large standing display cost
     * for 30 fps content and looks worse than a 60 Hz phone for reasons unrelated to the decoder.
     * 60 is the lowest rate that is near-universally supported and still gives two vsyncs per
     * 30 fps frame, so a marginally-late frame is not counted as a drop for presentation reasons.
     *
     * <p>Only rates the display actually reports are offered (see
     * {@link com.roncatech.vcat.tools.DisplayInfo#supportedRefreshRates}); the request itself is
     * advisory, so the achieved rate is logged at playback start.
     */
    /**
     * Burn the frame number into each decoded frame, in the decoder rather than as a view on top,
     * so the number names the frame actually on screen.
     *
     * <p>Diagnostic only, and recorded in {@code test_conditions} so a log states whether it was
     * on: it costs a per-frame draw in the blit, so a run with it enabled is not a clean
     * performance measurement.
     */
    public boolean showFrameNumber;
    public int displayRefreshHz;

    /** Requested refresh rate when nothing else is chosen. See {@link #displayRefreshHz}. */
    public static final int DEFAULT_REFRESH_HZ = 60;
    public enum RunMode{ONCE, BATTERY, TIME};

    public enum VideoOrientation{
        // show all videos in vertical orientation
        VERTICAL("Vertical"),

        // show all videos in vertical orientation
        HORIZONTAL("Horizontal"),

        // show all videos matching the video orientation
        MATCH_VIDEO("Match Video"),

        // show all videos matching current device orientation
        MATCH_DEVICE("Match Device");

        private VideoOrientation(String label){this.label = label;}
        public final String label;

        public static VideoOrientation fromLabel(String label){
            for(VideoOrientation cur : VideoOrientation.values()){
                if (cur.label.equals(label)) {
                    return cur;
                }
            }
            return MATCH_VIDEO;
        }
    }
    public VideoOrientation videoOrientation;
    public RunMode runMode;
    public int runLimit; // battery %or total minutes

    public String runModeStr(){
        switch (runMode){
            case ONCE:
                return "Run Once";
            case BATTERY:
                return String.format("Until Battery <= %d%%", this.runLimit);
            case TIME:
                return String.format("Run for %d minutes", this.runLimit);
        }
        return "Run mode undefined";
    }

    public DecoderConfig decoderCfg = new DecoderConfig();

    public static final int defaultBattery = 15;
    public static final int defaultTime = 16*60;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RunConfig)) return false;
        RunConfig that = (RunConfig) o;

        if(this.runLimit != that.runLimit){return false;}
        if(this.runMode != that.runMode){return false;}
        if(this.threads != that.threads){return false;}
        if(this.screenBrightness != that.screenBrightness){return false;}
        if(this.videoOrientation != that.videoOrientation){return false;}
        if(this.showFrameNumber != that.showFrameNumber){return false;}
        if(this.displayRefreshHz != that.displayRefreshHz){return false;}

        return Objects.equals(this.decoderCfg, that.decoderCfg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                screenBrightness,
                threads,
                runMode,
                runLimit,
                showFrameNumber,
                displayRefreshHz,
                decoderCfg
        );
    }

    public RunConfig()
    {
        this.screenBrightness = 25;
        this.threads = 2;
        this.runMode = RunMode.BATTERY;
        this.runLimit = 15;
        this.decoderCfg = new DecoderConfig();
        this.videoOrientation = VideoOrientation.MATCH_VIDEO;
        this.showFrameNumber = false;
        this.displayRefreshHz = DEFAULT_REFRESH_HZ;
    }

    public RunConfig(final RunConfig copyFrom){
        this(
                copyFrom.screenBrightness,
                copyFrom.threads,
                copyFrom.runMode,
                copyFrom.runLimit,
                copyFrom.decoderCfg,
                copyFrom.videoOrientation);
        this.showFrameNumber = copyFrom.showFrameNumber;
        this.displayRefreshHz = copyFrom.displayRefreshHz;
    }

    // Constructor with parameters
    public RunConfig(int screenBrightness, int threads, RunMode runMode, int runLimit, DecoderConfig decoderCfg, VideoOrientation videoOrientation) {
        this.screenBrightness = screenBrightness;
        this.threads = threads;
        this.runMode = runMode;
        this.runLimit = runLimit;
        this.decoderCfg = new DecoderConfig(decoderCfg);
        this.videoOrientation = videoOrientation;
        this.showFrameNumber = false;
        this.displayRefreshHz = DEFAULT_REFRESH_HZ;
    }

    // Convert object to JSON string (for saving)
    public String toJson() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }

    // Convert JSON string back to object (for loading)
    public static RunConfig fromJson(String json) {
        if(json != null) {
            Gson gson = new Gson();
            RunConfig ret = gson.fromJson(json, RunConfig.class);
            // handle new fields, possibly not in persisted config
            if(ret.videoOrientation == null){
                ret.videoOrientation = VideoOrientation.MATCH_VIDEO;
            }

            return ret;
        }
        return new RunConfig();
    }

    // Static nested Comparator class
    public static class Comparator implements java.util.Comparator<RunConfig> {
        @Override
        public int compare(RunConfig config1, RunConfig config2) {

            // first compare the immediate member variables
            if(config1.runMode != config2.runMode){
                return Integer.compare(config1.runMode.ordinal(), config2.runMode.ordinal());
            }

            if(config1.runLimit != config2.runLimit){
                return Integer.compare(config1.runLimit, config2.runLimit);
            }


            if(config1.threads != config2.threads){
                return Integer.compare(config1.threads, config2.threads);
            }

            if(config1.screenBrightness != config2.screenBrightness){
                return Integer.compare(config1.screenBrightness, config2.screenBrightness);
            }

            if(config1.videoOrientation != config2.videoOrientation){
                return Integer.compare(config1.videoOrientation.ordinal(), config2.videoOrientation.ordinal());
            }

            if(config1.displayRefreshHz != config2.displayRefreshHz){
                return Integer.compare(config1.displayRefreshHz, config2.displayRefreshHz);
            }

            // now compare the decoder cfg
            return DecoderConfig.comparator.compare(config1.decoderCfg, config2.decoderCfg);
        }
    }

    public static final RunConfig.Comparator comparator = new RunConfig.Comparator();

    public Map<String, Object> toFieldMap(Context context) {
        Gson gson = new Gson();
        return gson.fromJson(
                gson.toJson(this),
                new TypeToken<Map<String, Object>>(){}.getType()
        );
    }

}


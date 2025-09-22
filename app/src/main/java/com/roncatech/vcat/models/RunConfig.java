package com.roncatech.vcat.models;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.Map;
import java.util.Objects;

public class RunConfig {
    public int screenBrightness;
    public int threads;
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

        return Objects.equals(this.decoderCfg, that.decoderCfg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                screenBrightness,
                threads,
                runMode,
                runLimit,
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
    }

    public RunConfig(final RunConfig copyFrom){
        this(
                copyFrom.screenBrightness,
                copyFrom.threads,
                copyFrom.runMode,
                copyFrom.runLimit,
                copyFrom.decoderCfg,
                copyFrom.videoOrientation);
    }

    // Constructor with parameters
    public RunConfig(int screenBrightness, int threads, RunMode runMode, int runLimit, DecoderConfig decoderCfg, VideoOrientation videoOrientation) {
        this.screenBrightness = screenBrightness;
        this.threads = threads;
        this.runMode = runMode;
        this.runLimit = runLimit;
        this.decoderCfg = new DecoderConfig(decoderCfg);
        this.videoOrientation = videoOrientation;
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


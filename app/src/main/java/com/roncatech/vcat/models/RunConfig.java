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
    public RunMode runMode;
    public int runLimit; // battery %or total minutes

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
    }

    public RunConfig(final RunConfig copyFrom){
        this(copyFrom.screenBrightness, copyFrom.threads, copyFrom.runMode, copyFrom.runLimit, copyFrom.decoderCfg);
    }

    // Constructor with parameters
    public RunConfig(int screenBrightness, int threads, RunMode runMode, int runLimit, DecoderConfig decoderCfg) {
        this.screenBrightness = screenBrightness;
        this.threads = threads;
        this.runMode = runMode;
        this.runLimit = runLimit;
        this.decoderCfg = new DecoderConfig(decoderCfg);
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
            return gson.fromJson(json, RunConfig.class);
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


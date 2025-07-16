package com.roncatech.vcat.models;

import android.content.Context;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Objects;

import com.roncatech.vcat.tools.DeviceInfo;
import com.roncatech.vcat.tools.CpuInfo;

import java.lang.reflect.Type;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import android.util.Log;

public class SessionHeader {
    private final static String TAG = "SessionHeader";
    @SerializedName("header_version")
    private final int headerVersion;

    @SerializedName("device_info")
    private final DeviceInfo deviceInfo;

    @SerializedName(("session_info"))
    private final SessionInfo sessionInfo;

    @SerializedName("test_conditions")
    private final RunConfig testConditions;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionHeader)) return false;
        SessionHeader that = (SessionHeader) o;

        if(this.headerVersion != that.headerVersion){return false;}

        if(!this.deviceInfo.equals(that.deviceInfo)){return false;}

        if(!this.testConditions.equals(that.testConditions)){return false;}

        return this.sessionInfo.equals(that.sessionInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.headerVersion, this.deviceInfo, this.testConditions, this.sessionInfo);
    }

    public SessionHeader(int headerVersion,
                         DeviceInfo deviceInfo,
                         RunConfig testConditions,
                         SessionInfo sessionInfo) {
        this.headerVersion  = headerVersion;
        this.deviceInfo     = deviceInfo;
        this.testConditions = testConditions;
        this.sessionInfo = sessionInfo;
    }

    public SessionHeader(Context context, RunConfig runCfg, SessionInfo sessioInfo){
        this.headerVersion = SessionInfo.getVersionCode(context);
        this.deviceInfo = new DeviceInfo(context);
        this.testConditions = new RunConfig(runCfg);
        this.sessionInfo = sessioInfo;
    }

    /** The numeric header version from the JSON. */
    public int getHeaderVersion() {
        return headerVersion;
    }

    /** Your fully‐populated DeviceInfo instance. */
    public DeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    /** The run configuration parsed from "test_conditions". */
    public RunConfig getTestConditions() {
        return testConditions;
    }

    public SessionInfo getSessionInfo(){
        return this.sessionInfo;
    }

    public static class Adapter
            implements JsonSerializer<SessionHeader>, JsonDeserializer<SessionHeader>
    {
        @Override
        public JsonElement serialize(SessionHeader src, Type typeOfSrc, JsonSerializationContext ctx) {
            JsonObject root = new JsonObject();
            root.addProperty("header_version", src.headerVersion);
            root.add("device_info",      ctx.serialize(src.deviceInfo, DeviceInfo.class));
            root.add("session_info",     ctx.serialize(src.sessionInfo, SessionInfo.class));
            root.add("test_conditions",  ctx.serialize(src.testConditions, RunConfig.class));
            return root;
        }

        @Override
        public SessionHeader deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx)
                throws JsonParseException
        {
            JsonObject o = json.getAsJsonObject();
            int hv = o.get("header_version").getAsInt();
            DeviceInfo di = ctx.deserialize(o.get("device_info"), DeviceInfo.class);
            SessionInfo si = ctx.deserialize(o.get("session_info"), SessionInfo.class);
            RunConfig tc = ctx.deserialize(o.get("test_conditions"), RunConfig.class);
            return new SessionHeader(hv, di, tc, si);
        }
    }

    public static final TypeAdapter<SessionHeader> TYPE_ADAPTER =
            new TypeAdapter<SessionHeader>() {
                @Override
                public void write(JsonWriter out, SessionHeader src) throws IOException {
                    out.beginObject();
                    out.name("header_version").value(src.headerVersion);
                    out.name("device_info");
                    SessionHeader.gson.getAdapter(DeviceInfo.class).write(out, src.deviceInfo);
                    out.name("session_info");
                    SessionHeader.gson.getAdapter(SessionInfo.class).write(out, src.sessionInfo);
                    out.name("test_conditions");
                    SessionHeader.gson.getAdapter(RunConfig.class).write(out, src.testConditions);
                    out.endObject();
                }
                @Override
                public SessionHeader read(JsonReader in) throws IOException {
                    JsonObject o;
                    try {
                        o = JsonParser.parseReader(in).getAsJsonObject();
                    } catch (JsonParseException e) {
                        // not even valid JSON
                        return null;
                    }
                    // 1) Check for header_version ≥ 32
                    JsonElement hvEl = o.get("header_version");
                    if (hvEl == null || hvEl.isJsonNull()) {
                        // no version field → old log
                        return null;
                    }

                    int hv;
                    try {
                        hv = hvEl.getAsInt();
                    } catch (ClassCastException | IllegalStateException e) {
                        // version exists but isn’t an int
                        return null;
                    }
                    if (hv < 32) {
                        // too old
                        return null;
                    }

                    // 2) Safe parse of the nested objects
                    try {
                        DeviceInfo di = SessionHeader.gson.fromJson(o.get("device_info"), DeviceInfo.class);
                        SessionInfo si = SessionHeader.gson.fromJson(o.get("session_info"), SessionInfo.class);
                        RunConfig tc = SessionHeader.gson.fromJson(o.get("test_conditions"), RunConfig.class);

                        // If any required section came back null, treat it as “no header”
                        if (di == null || si == null || tc == null) {
                            return null;
                        }

                        return new SessionHeader(hv, di, tc, si);
                    } catch (JsonParseException | NullPointerException e) {
                        // malformed subsections or unexpected null → bail out
                        return null;
                    }
                }
            };

    public static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(CpuInfo.class,    new CpuInfo.Adapter())
            .registerTypeAdapter(DeviceInfo.class, new DeviceInfo.Adapter())
            .registerTypeAdapter(SessionInfo.class, SessionInfo.TYPE_ADAPTER)
            .registerTypeAdapter(SessionHeader.class, TYPE_ADAPTER)            // ← here!
            .setPrettyPrinting()
            .create();

    /**
     * Reads the top‐of‐file JSON header from a VCAT telemetry CSV and
     * deserializes it to a SessionHeader.
     *
     * @param telemetryFile your .csv file with a JSON preamble
     * @return the parsed SessionHeader, or null if no valid JSON header was found
     */
    public static SessionHeader fromLogFile(File telemetryFile){
        try (BufferedReader r = new BufferedReader(new FileReader(telemetryFile))) {
            return fromLogFile(r);
        } catch (IOException e) {
            // I/O error reading file
            return null;
        }

    }
    public static SessionHeader fromLogFile(BufferedReader telemetryFileReader){
        StringBuilder json = new StringBuilder();
        int depth = 0;
        boolean started = false;

        try {
            String line;
            while ((line = telemetryFileReader.readLine()) != null) {
                line = line.trim();
                if (!started) {
                    if (line.startsWith("{")) {
                        started = true;
                    } else {
                        continue;
                    }
                }
                // accumulate
                json.append(line).append('\n');
                for (char c : line.toCharArray()) {
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                }
                // once we've closed the root object, stop
                if (started && depth == 0) {
                    break;
                }
            }
        } catch (IOException e) {
            // I/O error reading file
            return null;
        }

        if (!started) {
            // no JSON in file
            return null;
        }

        try {
            return gson.fromJson(json.toString(), SessionHeader.class);
        } catch (JsonParseException | NullPointerException e) {
            Log.e(TAG, "SessionHeader::fromLogFile: Exception parsing header: " + e.getLocalizedMessage());
            // malformed JSON
            return null;
        }
    }

}


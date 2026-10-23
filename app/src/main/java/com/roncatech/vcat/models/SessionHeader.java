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
import android.net.Uri;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.roncatech.vcat.tools.DeviceInfo;
import com.roncatech.vcat.tools.CpuInfo;
import com.roncatech.vcat.tools.PlaylistIdentity;
import com.roncatech.vcat.tools.RunEnvironment;

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

    /**
     * Identifies this telemetry file. Minted once when the header is written and never rewritten,
     * so a Resume — which appends to the existing file without a new header block — keeps the same
     * execution_id. Two runs of the same playlist are therefore distinguishable, which the playlist
     * id alone cannot do.
     */
    @SerializedName("execution_id")
    private final String executionId;

    @SerializedName("device_info")
    private final DeviceInfo deviceInfo;

    @SerializedName(("session_info"))
    private final SessionInfo sessionInfo;

    @SerializedName("test_conditions")
    private final RunConfig testConditions;

    /**
     * Ambient state at the start of the run — radios, charger, power-save. Recorded because any of
     * these silently invalidates a battery comparison and none were captured before, which made a
     * 20 h baseline and an 8 h run look like the same experiment.
     */
    @SerializedName("run_environment")
    private final RunEnvironment runEnvironment;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionHeader)) return false;
        SessionHeader that = (SessionHeader) o;

        if(this.headerVersion != that.headerVersion){return false;}

        if(!this.executionId.equals(that.executionId)){return false;}

        if(!this.deviceInfo.equals(that.deviceInfo)){return false;}

        if(!this.testConditions.equals(that.testConditions)){return false;}

        if(!Objects.equals(this.runEnvironment, that.runEnvironment)){return false;}

        return this.sessionInfo.equals(that.sessionInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.headerVersion, this.executionId, this.deviceInfo, this.testConditions,
                this.sessionInfo, this.runEnvironment);
    }

    public SessionHeader(int headerVersion,
                         String executionId,
                         DeviceInfo deviceInfo,
                         RunConfig testConditions,
                         SessionInfo sessionInfo) {
        this(headerVersion, executionId, deviceInfo, testConditions, sessionInfo, null);
    }

    public SessionHeader(int headerVersion,
                         String executionId,
                         DeviceInfo deviceInfo,
                         RunConfig testConditions,
                         SessionInfo sessionInfo,
                         RunEnvironment runEnvironment) {
        this.runEnvironment = runEnvironment;
        this.headerVersion  = headerVersion;
        this.executionId    = executionId != null ? executionId : PlaylistIdentity.NIL;
        this.deviceInfo     = deviceInfo;
        this.testConditions = testConditions;
        this.sessionInfo = sessionInfo;
    }

    public SessionHeader(Context context, String executionId, RunConfig runCfg, SessionInfo sessioInfo){
        this.runEnvironment = RunEnvironment.capture(context);
        this.headerVersion = SessionInfo.getVersionCode(context);
        this.executionId = executionId != null ? executionId : PlaylistIdentity.NIL;
        this.deviceInfo = new DeviceInfo(context);
        this.testConditions = new RunConfig(runCfg);
        this.sessionInfo = sessioInfo;
    }

    /** Ambient run state; null for logs written before the field existed. */
    public RunEnvironment getRunEnvironment() {
        return runEnvironment;
    }

    /** The uuid identifying this telemetry file; nil for logs written before the field existed. */
    public String getExecutionId() {
        return executionId;
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
            root.addProperty("execution_id", src.executionId);
            root.add("device_info",      ctx.serialize(src.deviceInfo, DeviceInfo.class));
            root.add("session_info",     ctx.serialize(src.sessionInfo, SessionInfo.class));
            root.add("test_conditions",  ctx.serialize(src.testConditions, RunConfig.class));
            if (src.runEnvironment != null) {
                root.add("run_environment", ctx.serialize(src.runEnvironment, RunEnvironment.class));
            }
            return root;
        }

        @Override
        public SessionHeader deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx)
                throws JsonParseException
        {
            JsonObject o = json.getAsJsonObject();
            int hv = o.get("header_version").getAsInt();
            String eid = readExecutionId(o);
            DeviceInfo di = ctx.deserialize(o.get("device_info"), DeviceInfo.class);
            SessionInfo si = ctx.deserialize(o.get("session_info"), SessionInfo.class);
            RunConfig tc = ctx.deserialize(o.get("test_conditions"), RunConfig.class);
            RunEnvironment env = ctx.deserialize(o.get("run_environment"), RunEnvironment.class);
            return new SessionHeader(hv, eid, di, tc, si, env);
        }
    }

    /**
     * Reads {@code execution_id}, falling back to the nil uuid. Logs written before the field
     * existed stay parseable — they simply have no execution identity.
     */
    private static String readExecutionId(JsonObject o) {
        JsonElement el = o.get("execution_id");
        if (el == null || el.isJsonNull()) {
            return PlaylistIdentity.NIL;
        }
        try {
            return el.getAsString();
        } catch (ClassCastException | IllegalStateException e) {
            return PlaylistIdentity.NIL;
        }
    }

    public static final TypeAdapter<SessionHeader> TYPE_ADAPTER =
            new TypeAdapter<SessionHeader>() {
                @Override
                public void write(JsonWriter out, SessionHeader src) throws IOException {
                    out.beginObject();
                    out.name("header_version").value(src.headerVersion);
                    out.name("execution_id").value(src.executionId);
                    out.name("device_info");
                    SessionHeader.gson.getAdapter(DeviceInfo.class).write(out, src.deviceInfo);
                    out.name("session_info");
                    SessionHeader.gson.getAdapter(SessionInfo.class).write(out, src.sessionInfo);
                    out.name("test_conditions");
                    SessionHeader.gson.getAdapter(RunConfig.class).write(out, src.testConditions);
                    if (src.runEnvironment != null) {
                        out.name("run_environment");
                        SessionHeader.gson.getAdapter(RunEnvironment.class).write(out, src.runEnvironment);
                    }
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

                    // Log version.  As this increases, older logs will not be supported
                    // unless back-version support is added
                    if (hv < 1) {
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

                        RunEnvironment env = SessionHeader.gson.fromJson(
                                o.get("run_environment"), RunEnvironment.class);
                        return new SessionHeader(hv, readExecutionId(o), di, tc, si, env);
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
            return null;
        }
    }

    public static SessionHeader fromLogFile(Context ctx, Uri uri) {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return fromLogFile(r);
        } catch (IOException e) {
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
            String jsonStr = json.toString();
            return gson.fromJson(jsonStr, SessionHeader.class);
        } catch (JsonParseException | NullPointerException e) {
            Log.e(TAG, "SessionHeader::fromLogFile: Exception parsing header: " + e.getLocalizedMessage());
            // malformed JSON
            return null;
        }
    }

}


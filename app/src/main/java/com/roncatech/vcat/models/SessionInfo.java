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

import static androidx.core.content.ContextCompat.getSystemService;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;

import com.roncatech.vcat.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.roncatech.vcat.tools.PlaylistIdentity;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public class SessionInfo {
    @SerializedName("battery")
    public final Battery battery;

    @SerializedName("playlist")
    public final Playlist playlist;

    @SerializedName("start_time")
    public final StartTime start_time;

    @SerializedName("vcat_version")
    public final String vcat_version;

    /**
     * Which variant of the host app produced the log: {@code "debug"} or {@code "release"}.
     *
     * <p>Worth recording because the two are not performance-equivalent. Release sets
     * {@code minifyEnabled false}, so the bytecode is identical and the only difference is the
     * debug variant's implicit {@code debuggable true} — which makes ART compile with
     * {@code --debuggable} and restrict optimisations such as inlining. That slows host Java, and
     * the whole ExoPlayer pipeline is host Java: the renderer's earliness check and drop
     * decision, the sample queues, the extractors, the media clock, and doSomeWork every 10 ms.
     * Only the decode itself is native and unaffected. {@code vcat_version} is the same string in
     * both variants, so without this a log cannot say which one produced it.
     */
    @SerializedName("vcat_build_type")
    public final String vcat_build_type;


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionInfo)) return false;
        SessionInfo that = (SessionInfo) o;
        if(!this.vcat_version.equals(that.vcat_version)){return false;}
        if(!Objects.equals(this.vcat_build_type, that.vcat_build_type)){return false;}
        if(!this.playlist.equals(that.playlist)){return false;}
        if(!this.battery.equals(that.battery)){return false;}

        return this.start_time.equals(that.start_time);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.vcat_version, this.vcat_build_type, this.playlist, this.battery, this.start_time);
    }

    /**
     * The playlist a run was launched from, recorded as identity plus display name.
     *
     * <p>This replaced a bare file-name string: a name is not unique across folders and changes
     * when a playlist is renamed, so it could not answer "which playlist produced this log". The
     * {@code id} is the playlist's {@code <identifier>} (see
     * {@link com.roncatech.vcat.tools.PlaylistIdentity}); {@code name} is kept for display only.
     */
    public static class Playlist {
        @SerializedName("id")
        public final String id;

        @SerializedName("name")
        public final String name;

        public Playlist(String id, String name) {
            this.id = id != null ? id : PlaylistIdentity.NIL;
            this.name = name != null ? name : "";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Playlist)) return false;
            Playlist that = (Playlist) o;
            return this.id.equals(that.id) && this.name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.id, this.name);
        }
    }

    public static class StartTime {
        public final String local_date;
        public final String local_time;
        public final long unix_time_ms;
        public StartTime(long unixTimeMs) {
            this.unix_time_ms = unixTimeMs;

            LocalDateTime now = Instant.ofEpochMilli(unixTimeMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();

            this.local_date = now.toLocalDate().toString();
            this.local_time = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        }

        public StartTime(long unix_time_ms, String local_date, String local_time){
            this.unix_time_ms = unix_time_ms;
            this.local_date = local_date;
            this.local_time = local_time;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StartTime)) return false;
            StartTime that = (StartTime) o;
            return this.unix_time_ms == that.unix_time_ms;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.local_date, this.local_time, this.unix_time_ms);
        }
    }

    public static class Battery {
        @SerializedName("capacity_ma")
        public final long capacity_ma;

        @SerializedName("initial_level_ma")
        public final long initial_level_ma;

        @SerializedName("initial_level_pct")
        public final double initial_level_pct;

        public Battery(Context context, double initial_level_pct, long capacity_ma) {
            BatteryManager batteryManager = (BatteryManager)getSystemService(context, BatteryManager.class);
            this.capacity_ma = capacity_ma;
            this.initial_level_ma  = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)/1000;
            this.initial_level_pct = initial_level_pct;
        }

        public Battery(int capacity, int levelMa, double levelPct) {
            this.capacity_ma = capacity;
            this.initial_level_ma = levelMa;
            this.initial_level_pct = levelPct;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Battery)) return false;
            Battery that = (Battery) o;
            return this.capacity_ma == that.capacity_ma &&
                    this.initial_level_ma == that.initial_level_ma &&
                    this.initial_level_pct == that.initial_level_pct;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.capacity_ma, this.initial_level_ma, this.initial_level_pct);
        }
    }

    public SessionInfo(Context context, long startTime, Playlist playlist, double initial_level_pct, long capacity_ma) {

        this.start_time = new StartTime(startTime);
        this.vcat_version = getAppVersion(context);
        this.vcat_build_type = BuildConfig.DEBUG ? "debug" : "release";
        this.battery = new Battery(context, initial_level_pct, capacity_ma);  // defaults to zero
        this.playlist = playlist;
    }

    public SessionInfo(StartTime start_Time, String vcat_version, Battery battery, Playlist playlist){
        this(start_Time, vcat_version, null, battery, playlist);
    }

    public SessionInfo(StartTime start_Time, String vcat_version, String vcat_build_type,
                       Battery battery, Playlist playlist){
        this.start_time = start_Time;
        this.vcat_version = vcat_version;
        this.vcat_build_type = vcat_build_type;
        this.battery = battery;
        this.playlist = playlist;
    }

    public static String getAppVersion(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static int getVersionCode(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            return (int) pi.getLongVersionCode();
        } catch (Exception e) {
            return -1;  // fallback
        }
    }

    /**
     * Reads either playlist shape: the current object form, or the pre-identifier bare string that
     * held just a file name. Logs written before playlists carried an id therefore still parse,
     * with a nil id — which is what keeps existing test results readable in the Results tab.
     */
    private static Playlist readPlaylist(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return new Playlist(PlaylistIdentity.NIL, "");
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            JsonElement idEl = o.get("id");
            JsonElement nameEl = o.get("name");
            return new Playlist(
                    idEl != null && !idEl.isJsonNull() ? idEl.getAsString() : PlaylistIdentity.NIL,
                    nameEl != null && !nameEl.isJsonNull() ? nameEl.getAsString() : "");
        }
        // Legacy: "playlist": "av2-1080p30.xspf"
        return new Playlist(PlaylistIdentity.NIL, el.getAsString());
    }

    public static final TypeAdapter<SessionInfo> TYPE_ADAPTER =
            new TypeAdapter<SessionInfo>() {
                @Override
                public void write(JsonWriter out, SessionInfo src) throws IOException {
                    out.beginObject();

                    // 1) battery
                    out.name("battery");
                    out.beginObject();
                    out.name("capacity_ma").value(src.battery.capacity_ma);
                    out.name("initial_level_ma").value(src.battery.initial_level_ma);
                    out.name("initial_level_pct").value(src.battery.initial_level_pct);
                    out.endObject();

                    // 2) playlist — object form: {"id", "name"}
                    out.name("playlist");
                    out.beginObject();
                    out.name("id").value(src.playlist.id);
                    out.name("name").value(src.playlist.name);
                    out.endObject();

                    // 3) start_time
                    out.name("start_time");
                    out.beginObject();
                    out.name("local_date").value(src.start_time.local_date);
                    out.name("local_time").value(src.start_time.local_time);
                    out.name("unix_time_ms").value(src.start_time.unix_time_ms);
                    out.endObject();

                    // 4) vcat_version
                    out.name("vcat_version").value(src.vcat_version);
                    out.name("vcat_build_type").value(src.vcat_build_type);

                    out.endObject();
                }

                @Override
                public SessionInfo read(JsonReader in) throws IOException {
                    JsonObject o = JsonParser.parseReader(in).getAsJsonObject();

                    JsonObject batJ = o.getAsJsonObject("battery");
                    Battery bat = new Battery(
                            batJ.get("capacity_ma").getAsInt(),
                            batJ.get("initial_level_ma").getAsInt(),
                            batJ.get("initial_level_pct").getAsDouble()
                    );

                    Playlist play = readPlaylist(o.get("playlist"));

                    JsonObject stJ = o.getAsJsonObject("start_time");
                    StartTime st = new StartTime(
                            stJ.get("unix_time_ms").getAsLong(),
                            stJ.get("local_date").getAsString(),
                            stJ.get("local_time").getAsString()
                    );

                    String ver = o.get("vcat_version").getAsString();
                    // Absent in logs written before the field existed.
                    String build = o.has("vcat_build_type") && !o.get("vcat_build_type").isJsonNull()
                            ? o.get("vcat_build_type").getAsString() : null;

                    return new SessionInfo(st, ver, build, bat, play);
                }
            };

    /**
     * If you want a ready‐made Gson that uses this adapter plus pretty‐printing:
     */
    public static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(SessionInfo.class, TYPE_ADAPTER)
            .setPrettyPrinting()
            .create();

    // You can now replace your toJson()/fromJson(...) with:
    public String toJson() {
        return gson.toJson(this);
    }
    public static SessionInfo fromJson(String json) {
        return gson.fromJson(json, SessionInfo.class);
    }
}


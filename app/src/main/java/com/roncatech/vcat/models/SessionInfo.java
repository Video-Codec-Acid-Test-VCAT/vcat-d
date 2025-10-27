/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting VCAT itself. Any other use requires prior written permission
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

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
    public final String playlist;

    @SerializedName("start_time")
    public final StartTime start_time;

    @SerializedName("vcat_version")
    public final String vcat_version;


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionInfo)) return false;
        SessionInfo that = (SessionInfo) o;
        if(!this.vcat_version.equals(that.vcat_version)){return false;}
        if(!this.playlist.equals(that.playlist)){return false;}
        if(!this.battery.equals(that.battery)){return false;}

        return this.start_time.equals(that.start_time);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.vcat_version, this.playlist, this.battery, this.start_time);
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

    public SessionInfo(Context context, long startTime, String playlist, double initial_level_pct, long capacity_ma) {

        this.start_time = new StartTime(startTime);
        this.vcat_version = getAppVersion(context);
        this.battery = new Battery(context, initial_level_pct, capacity_ma);  // defaults to zero
        this.playlist = playlist;
    }

    public SessionInfo(StartTime start_Time, String vcat_version, Battery battery, String playlist){
        this.start_time = start_Time;
        this.vcat_version = vcat_version;
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

                    // 2) playlist
                    out.name("playlist").value(src.playlist);

                    // 3) start_time
                    out.name("start_time");
                    out.beginObject();
                    out.name("local_date").value(src.start_time.local_date);
                    out.name("local_time").value(src.start_time.local_time);
                    out.name("unix_time_ms").value(src.start_time.unix_time_ms);
                    out.endObject();

                    // 4) vcat_version
                    out.name("vcat_version").value(src.vcat_version);

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

                    String play = o.get("playlist").getAsString();

                    JsonObject stJ = o.getAsJsonObject("start_time");
                    StartTime st = new StartTime(
                            stJ.get("unix_time_ms").getAsLong(),
                            stJ.get("local_date").getAsString(),
                            stJ.get("local_time").getAsString()
                    );

                    String ver = o.get("vcat_version").getAsString();

                    return new SessionInfo(st, ver, bat, play);
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


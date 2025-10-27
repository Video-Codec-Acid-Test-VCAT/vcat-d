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

package com.roncatech.vcat.tools;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.lang.reflect.Type;

public class DeviceInfo {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceInfo)) return false;
        DeviceInfo that = (DeviceInfo) o;

        if(!this.soc.equals(that.soc)){return false;}
        if(!this.manufacturer.equals(that.manufacturer)){return false;}
        if(!this.model.equals(that.model)){return false;}
        if(!this.androidVersion.equals(that.androidVersion)){return false;}
        if(!this.socManufacturer.equals(that.socManufacturer)){return false;}
        if(!this.cpu.equals(that.cpu)){return false;}
        if(!this.displayResolution.equals(that.displayResolution))
            if(!this.memoryInfo.equals(that.memoryInfo)){return false;}

        return this.storageInfo.equals(that.storageInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                manufacturer,
                model,
                androidVersion,
                socManufacturer,
                soc,
                cpu,
                displayResolution,
                memoryInfo,
                storageInfo);
    }

    public DeviceInfo(Context context){
        boolean is64Bit = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;

        this.manufacturer = Build.MANUFACTURER;
        this.displayResolution = new DisplayResolution(context);
        this.model = Build.MODEL;
        this.socManufacturer = getSocManufacturerSafe();
        this.soc = getSocModelSafe();
        this.androidVersion = String.format("%s (%s)", Build.VERSION.RELEASE, is64Bit ? "64-bit" : "32-bit");
        this.cpu = new CpuInfo();

        this.memoryInfo = MemoryInfo.getMemory(context);
        this.storageInfo = MemoryInfo.getStorage();

    }

    public DeviceInfo(String manufacturer,
                      String model,
                      String socManufacturer,
                      String soc,
                      String androidVersion,
                      MemoryInfo memoryInfo,
                      MemoryInfo storageInfo,
                      CpuInfo cpu,
                      DisplayResolution displayResolution){
        this.manufacturer = manufacturer;
        this.model = model;
        this.socManufacturer = socManufacturer;
        this.soc = soc;
        this.androidVersion = androidVersion;
        this.memoryInfo = memoryInfo;
        this.storageInfo = storageInfo;
        this.displayResolution = displayResolution;
        this.cpu = cpu;
    }

    public final String manufacturer;
    public final String model;
    public final String socManufacturer;
    public final String soc;
    public final String androidVersion;
    public final CpuInfo cpu;

    public final DisplayResolution displayResolution;

    public final MemoryInfo memoryInfo;

    public final MemoryInfo storageInfo;

    public static class MemoryInfo {

        public static String getPrettyMemSize(long memSize){
            if(memSize > 1_000_000_000){
                return String.format("%d GB", memSize/1_000_000_000);
            } else if(memSize > 1_000_000){
                return String.format("%d MB", memSize/1_000_000);
            }
            else if(memSize > 1_000){
                return String.format("%d KB", memSize/1_000);
            }
            else{
                return String.format("%d Bytes", memSize);
            }
        }

        public final long total;
        public final long available;

        public static MemoryInfo getMemory(Context context){
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            activityManager.getMemoryInfo(memInfo);

            return new MemoryInfo(memInfo.totalMem, memInfo.availMem);
        }

        public static MemoryInfo getStorage(){
            File storageDir = Environment.getDataDirectory();
            StatFs stat = new StatFs(storageDir.getPath());
            long blockSize = stat.getBlockSizeLong();
            long total = stat.getBlockCountLong() * blockSize;
            long available = stat.getAvailableBlocksLong() * blockSize;

            return new MemoryInfo(total, available);
        }

        public MemoryInfo(long total, long available){
            this.total = total;
            this.available = available;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MemoryInfo)) return false;
            MemoryInfo that = (MemoryInfo) o;
            return this.total == that.total && this.available == that.available;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.total, this.available);
        }
    }

    public static class DisplayResolution {
        public final int width;
        public final int height;

        public DisplayResolution(int width, int height){
            this.width = width;
            this.height = height;

        }

        @Override
        public String toString(){
            return String.format("%dx%d", this.width, this.height);
        }

        public DisplayResolution(Context context) {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            this.height = metrics.heightPixels;
            this.width = metrics.widthPixels;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DisplayResolution)) return false;
            DisplayResolution that = (DisplayResolution) o;
            return this.height == that.height && this.width == that.width;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.height, this.width);
        }
    }

    /** GSON adapter for DeviceInfo */
    public static class Adapter
            implements JsonSerializer<DeviceInfo>, JsonDeserializer<DeviceInfo> {

        private static final Pattern CORE_PATTERN =
                Pattern.compile("^(.+) \\(0x([0-9a-fA-F]+)\\): (\\d+) MHx$");

        @Override
        public JsonElement serialize(DeviceInfo src, Type typeOfSrc, JsonSerializationContext ctx) {
            JsonObject root = new JsonObject();
            root.addProperty("manufacturer", src.manufacturer);
            root.addProperty("model", src.model);
            root.addProperty("soc_manufacturer", src.socManufacturer);
            root.addProperty("soc", src.soc);
            root.addProperty("android_version", src.androidVersion);

            // cpu
            JsonElement cpuJson = ctx.serialize(src.cpu);
            root.add("cpu", cpuJson);

            // display_resolution
            root.add("display_resolution", ctx.serialize(src.displayResolution));

            // memory
            JsonObject memJ = new JsonObject();
            memJ.addProperty("total", formatBytes(src.memoryInfo.total));
            memJ.addProperty("available", formatBytes(src.memoryInfo.available));
            root.add("memory", memJ);

            // storage
            JsonObject stgJ = new JsonObject();
            stgJ.addProperty("total", formatBytes(src.storageInfo.total));
            stgJ.addProperty("available", formatBytes(src.storageInfo.available));
            root.add("storage", stgJ);

            return root;
        }

        @Override
        public DeviceInfo deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx)
                throws JsonParseException {
            JsonObject o = json.getAsJsonObject();
            String manufacturer    = o.get("manufacturer").getAsString();
            String model           = o.get("model").getAsString();
            String socMan          = o.get("soc_manufacturer").getAsString();
            String soc             = o.get("soc").getAsString();
            String androidVersion  = o.get("android_version").getAsString();

            CpuInfo cpu = ctx.deserialize(o.get("cpu"), CpuInfo.class);
            DisplayResolution disp = ctx.deserialize(o.get("display_resolution"), DisplayResolution.class);

            JsonObject memJ = o.getAsJsonObject("memory");
            long total = parseBytes(memJ.get("total").getAsString());
            long avail = parseBytes(memJ.get("available").getAsString());
            MemoryInfo mem = new MemoryInfo(total, avail);

            JsonObject storageJ = o.getAsJsonObject("storage");
            total = parseBytes(storageJ.get("total").getAsString());
            avail = parseBytes(storageJ.get("available").getAsString());
            MemoryInfo storage = new MemoryInfo(total, avail);

            return new DeviceInfo(
                    manufacturer, model, socMan, soc,
                    androidVersion, mem, storage, cpu, disp
            );
        }

        // helper to format and parse bytes
        private static String formatBytes(long bytes) {
            double gb = bytes / (1024.0 * 1024.0 * 1024.0);
            return String.format(Locale.US, "%.2f GB", gb);
        }
        private static long parseBytes(String s) {
            String[] parts = s.split(" ");
            double val = Double.parseDouble(parts[0]);
            return (long)(val * 1024 * 1024 * 1024);
        }
    }

    //==================== legacy to be removed

    private static String formatBytesToGB(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        return String.format(Locale.US, "%.2f GB", gb);
    }

    private static String getSocManufacturerSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return Build.SOC_MANUFACTURER;
            } catch (Exception ignored) {}
        }

        // Fallback: Infer from SoC codename
        try {
            Process process = Runtime.getRuntime().exec("getprop ro.board.platform");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String socName = reader.readLine().trim().toLowerCase();

            if (socName.startsWith("sdm") || socName.startsWith("sm") || socName.startsWith("msm"))
                return "Qualcomm";
            if (socName.startsWith("mt"))
                return "MediaTek";
            if (socName.contains("exynos"))
                return "Samsung";
            if (socName.startsWith("rk"))
                return "Rockchip";
            if (socName.startsWith("sp") || socName.startsWith("sc"))
                return "Unisoc";

            return "Unknown (" + socName + ")";
        } catch (IOException e) {
            return "Unknown";
        }
    }

    private static String getSocModelSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return Build.SOC_MODEL;
            } catch (Exception ignored) {}
        }

        // Fallback: Return board/platform codename
        try {
            Process process = Runtime.getRuntime().exec("getprop ro.board.platform");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            return reader.readLine().trim();
        } catch (IOException e) {
            return "Unknown";
        }
    }

    public final static class CpuInfoUtil {
        private CpuInfoUtil() { /* no-op */ }

        /**
         * Reads /proc/cpuinfo and returns the first non-null value for
         * Hardware (ARM), Processor (older kernels), or model name (x86).
         * Falls back to android.os.Build.HARDWARE or "unknown".
         */
        public static String getCpuModel() {
            String model = scanCpuInfo("/proc/cpuinfo", new String[]{
                    "Hardware",    // most ARM kernels
                    "Processor",   // some older ARM kernels
                    "model name"   // x86
            });
            if (model != null) {
                return model;
            }

            // As a last resort (ARM only), Android exposes this too:
            if (android.os.Build.HARDWARE != null && !android.os.Build.HARDWARE.isEmpty()) {
                return android.os.Build.HARDWARE;
            }

            return "unknown";
        }

        private static String scanCpuInfo(String path, String[] keys) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    for (String key : keys) {
                        if (line.startsWith(key + ":")) {
                            String[] parts = line.split(":", 2);
                            if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                                return parts[1].trim();
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // file missing or unreadable
            }
            return null;
        }
    }

}


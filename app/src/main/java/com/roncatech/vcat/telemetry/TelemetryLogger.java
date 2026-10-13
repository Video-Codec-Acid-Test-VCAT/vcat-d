package com.roncatech.vcat.telemetry;

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

import static android.content.Context.BATTERY_SERVICE;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.roncatech.vcat.models.SessionHeader;
import com.roncatech.vcat.models.RunConfig;
import com.roncatech.vcat.tools.AppMemoryInfo;
import com.roncatech.vcat.tools.BatteryInfo;
import com.roncatech.vcat.models.SessionInfo;
import com.roncatech.vcat.tools.DeviceInfo;
import com.roncatech.vcat.tools.StorageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;

/**
 * Logs telemetry data to csv file, inserting empty column for missing fields.
 * columns are written in the order of the 'Colum' enumeration.
 */
public class TelemetryLogger {
    private static final String TAG = "TelemetryLogger";

    /**
     * Column definitions uses to generater header and write rows.  The header labels will be in the
     * same order as the Column enum values, and clumn data will align under the appropriate label
     */
    public enum Column {
        TEST_TIMESTAMP        ("test.timestamp"),
        TEST_DURATION_MS      ("test.duration_ms"),
        TEST_PLAYLIST_DURATION_MS("test.playlist.duration_ms"),
        TEST_FILE_DURATION_MS ("test.file_duration_ms"),
        TEST_TOTAL_ITERATIONS ("test.total_iterations"),
        TEST_FILENAME         ("test.filename"),
        BATTERY_CHARGE_COUNTER("battery.charge_counter"),
        BATTERY_MILLIAMPS     ("battery.milliamps"),
        BATTERY_TEMPERATURE   ("battery.temperature"),
        SYSTEM_THERMAL_STATUS ("system.thermal_status"),
        SYSTEM_THERMAL_STATUS_MAX("system.thermal_status_max"),
        SYSTEM_THERMAL_HEADROOM("system.thermal_headroom"),
        DISPLAY_REFRESH_HZ_MIN("display.refresh_hz_min"),
        DISPLAY_REFRESH_HZ_MAX("display.refresh_hz_max"),
        CPU_FREQ              ("cpu.freq"),           // a *list* of values (mean over the row)
        CPU_FREQ_RMS          ("cpu.freq_rms"),       // a *list* of values (RMS over the row)
        VIDEO_FRAMES_DROPPED  ("video.frames_dropped"),
        VIDEO_DROPPED_TO_KEYFRAME("video.dropped_to_keyframe"),
        VIDEO_FRAMES_RENDERED ("video.frames_rendered"),
        VIDEO_DROPPED_TO_KEYFRAME_EVENTS("video.dropped_to_keyframe_events"),
        VIDEO_RESOLUTION      ("video.resolution"),
        VIDEO_BITRATE         ("video.bitrate"),
        VIDEO_CODEC_NAME      ("video.codec_name"),
        VIDEO_FRAMERATE       ("video.framerate"),
        VIDEO_DECODER_NAME    ("video.decoder_name"),
        CPU_USAGE_TOTAL       ("cpu.usage.total"),
        BATTERY_LEVEL         ("battery.level"),
        TEST_RESTART          ("test.restart"),
        TEST_END_OF_CUR_FILE  ("test.end_of_cur_file"),
        TEST_SYSTEM_MEMORY ("test.memory.system"),
        TEST_VCAT_MEMORY("test.memory.vcat");

        private final String name;
        Column(String name) { this.name = name; }
        public String getName() { return name; }
    }


    private final Context ctx;
    private final String csvFileName;
    private DocumentFile csvDocFile;
    private final int numCpus;
    private final CpuUsageSampler cpuSampler;
    /** Resume: ms spent stopped, subtracted from logged timestamps. 0 for a normal start. */
    private final long timestampOffsetMs;

    public static class VideoInfo{
        public final String fileName;
        public final String width;
        public final String height;
        public final String mimeType;
        public final String bitrate ;
        public final String codec;
        public final String decoderName;
        public final float fps;

        public static final VideoInfo empty = new VideoInfo();

        private VideoInfo(){
            this.fileName = this.width = this.height = this.mimeType = this.bitrate = this.codec =
                    this.decoderName = "empty";
            this.fps = -1;
        }

        public VideoInfo(String fileName, String width, String height, String mimeType, String bitrate,
                         String codec, String decoderName, float fps){
            this.fileName = fileName;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
            this.bitrate = bitrate;
            this.codec = codec;
            this.decoderName = decoderName;
            this.fps = fps;
        }

    }

    public TelemetryLogger(Context ctx, String csvFileName){
        this(ctx, csvFileName, 0L);
    }

    /**
     * @param timestampOffsetMs on Resume, the accumulated stopped-gap in ms; subtracted from
     *                          {@code test.timestamp} so the logged clock continues from where the
     *                          prior run left off instead of jumping by the time spent stopped.
     *                          0 for a normal start.
     */
    public TelemetryLogger(Context ctx, String csvFileName, long timestampOffsetMs){
        this.ctx = ctx.getApplicationContext();
        this.csvFileName = csvFileName;
        this.timestampOffsetMs = timestampOffsetMs;
        this.numCpus = getTotalCpus();
        this.cpuSampler = new CpuUsageSampler(this.numCpus);
    }

    private DocumentFile getOrCreateCsvDocFile() {
        if (csvDocFile != null) return csvDocFile;
        DocumentFile dir = StorageManager.getFolder(ctx, StorageManager.VCATFolder.TEST_RESULTS);
        if (dir == null) {
            Log.e(TAG, "TEST_RESULTS folder not available");
            return null;
        }
        DocumentFile existing = dir.findFile(csvFileName);
        if (existing != null && existing.isFile()) {
            csvDocFile = existing;
        } else {
            csvDocFile = dir.createFile("text/csv", csvFileName);
        }
        return csvDocFile;
    }

    private void writeRow(String row){
        DocumentFile docFile = getOrCreateCsvDocFile();
        if (docFile == null) {
            Log.e(TAG, "Cannot write row — CSV DocumentFile is null");
            return;
        }
        try (OutputStream os = ctx.getContentResolver().openOutputStream(docFile.getUri(), "wa")) {
            if (os == null) {
                Log.e(TAG, "openOutputStream returned null for " + docFile.getUri());
                return;
            }
            os.write((row + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "Error writing csv data: " + e.getLocalizedMessage());
        }
    }

    /**
     * Write the header to the csv log file.  Header labels are in the same order as the Column enum values.
     * However, for cpu frequency, there will be n column headers, one per cpu core.
     */
    public void writeCsvHeader(){

        List<String> hdr = new ArrayList<>();

        for (Column col : Column.values()) {
            if (isPerCpuColumn(col)) {
                for(int i = 0; i < this.numCpus; ++i){
                    hdr.add(col.getName()+i);
                }
            }
            else{
                hdr.add(col.getName());
            }
        }

        writeRow(String.join(",", hdr));
    }

    /**
     * Generate a row of empty data values to be used when writing a row.  This
     * ensures that all cells will be populated, even if with ""
     *
     * @param cpuCount
     * @return
     */
    private static Map<Column,Object> initRow(int cpuCount) {
        Map<Column,Object> row = new EnumMap<>(Column.class);

        for (Column col : Column.values()) {
            if (isPerCpuColumn(col)) {
                row.put(col, emptyFreqs(cpuCount));
            } else {
                // default empty string for all other columns
                row.put(col, "");
            }
        }

        return row;
    }

    /**
     * Log one Row, all cells will be populated even if only with ""
     * @param ct the Context to be used when collecting data
     * @param testStartMs when the test began. {@code test.duration_ms} is measured from here and
     *     never resets, so it states how far into the run a row is.
     * @param iterationStartMs when the current playlist iteration began.
     *     {@code test.playlist.duration_ms} is measured from here, so it restarts on every loop;
     *     with a single-file playlist every file end is also a loop, which makes it equal to
     *     {@code test.file_duration_ms}.
     * @param fileStartMs when the current file began. {@code test.file_duration_ms} is measured
     *     from here, giving the position within the clip.
     * @param totalIterations how many times the playlist has completed. Together with the two
     *     durations this makes a looping run readable: without it, rows from the tenth pass are
     *     indistinguishable from the first.
     * @param vi the video information for the video columns
     * @param frameDrops number of frame drops
     * @param droppedToKeyframe frames discarded from the source to reach a keyframe since the
     *     previous row, matching the per-interval convention of {@code frameDrops}. A count of
     *     frames rather than of events, so a stall shows the size of the block that was thrown
     *     away. These frames are never decoded at all, so unlike an ordinary late frame they
     *     shrink the decode workload: any row above zero marks an interval where the run did
     *     less work than the clip demanded, and its throughput is not comparable with a clean
     *     one. They are also counted inside {@code frameDrops}, which totals both kinds.
     * @param framesRendered frames actually put on screen since the previous row. Recorded so a
     *     row can be checked rather than trusted: {@code framesRendered + frameDrops} should
     *     account for the interval's elapsed time at the clip's frame rate, and a row that
     *     cannot be closed that way is reporting a counting error, not a decoder result. Both
     *     come from the same {@code DecoderCounters} of the same player, so they are directly
     *     comparable.
     * @param droppedToKeyframeEvents how many skip-to-keyframe operations produced
     *     {@code droppedToKeyframe}, as opposed to how many frames they discarded. Recorded
     *     because the frame count alone cannot be sanity-checked: a single skip is bounded by
     *     what is in the sample queue, and {@code DefaultLoadControl} caps that at
     *     {@code DEFAULT_MAX_BUFFER_MS} (50 s), so frames-per-event above roughly
     *     50 s x frame rate is arithmetically impossible and marks a counting error rather than
     *     a stall.
     * @param thermalStatusMax highest {@code PowerManager} thermal status observed since the
     *     previous row, from a registered listener rather than a point sample. The plain
     *     {@code system.thermal_status} column reads whatever happened to be true at the instant
     *     the row was written, so a throttling episode shorter than the telemetry interval is
     *     invisible -- which is how a 6.5 h run came back uniformly zero while stalling five
     *     times.
     * @param thermalHeadroom highest {@code getThermalHeadroom(0)} seen since the previous row,
     *     where 1.0 means at the throttling threshold. This is the column to read: the status
     *     code is quantised and, on the CPH2625, unusable. Its vendor HAL defines SKIN
     *     thresholds as {@code [NaN, 43.0, 45.0, 41.0, ...]} -- SEVERE at 41 C, *below* LIGHT at
     *     43 and MODERATE at 45 -- so the device jumps 0 to 3 and can never report the
     *     intermediate states. Headroom is continuous and shows the approach to the trip.
     *     {@code NaN} if the platform declines to answer (it rate-limits to roughly 1 Hz).
     * @param refreshHzMin lowest panel refresh rate observed since the previous row.
     * @param refreshHzMax highest panel refresh rate observed since the previous row. Recorded
     *     as a range because {@code preferredRefreshRate} is advisory — the system may refuse
     *     it, and an LTPO panel changes rate on its own regardless. A configured condition that
     *     is never measured is an assumption, and frame-drop figures cannot be read without
     *     knowing what the display was actually doing. When both match
     *     {@code runConfig.displayRefreshHz} the condition held for the whole interval; any
     *     other combination says it did not.
     * @param freqMean per-core mean frequency over the interval, or {@code null} to fall back to
     *     a single instantaneous read. Previously every row carried one point sample per core,
     *     which could not distinguish a core held at 1800 MHz from one alternating 600/2500.
     * @param freqRms per-core RMS frequency over the same samples. Recorded alongside the mean
     *     rather than instead of it: RMS alone is ambiguous — steady 1800 MHz and alternating
     *     600/2500 MHz give 1800 and 1818 — but together they yield the spread for free, since
     *     {@code sigma = sqrt(rms^2 - mean^2)} (0 and 950 MHz for those two cases).
     * @param isResume true if the test was resumed
     */
    public void logTelemetryRow(Context ct, long testStartMs, long iterationStartMs,
                                long fileStartMs, int totalIterations, VideoInfo vi,
                                Integer frameDrops, Integer droppedToKeyframe,
                                Integer framesRendered, Integer droppedToKeyframeEvents,
                                Integer thermalStatusMax, Float thermalHeadroom,
                                Float refreshHzMin, Float refreshHzMax,
                                Double[] freqMean, Double[] freqRms,
                                boolean isResume,
                                boolean isEndOfCurFile){
        Map<Column, Object> row = initRow(this.numCpus);

        // Two clocks, deliberately: the logged timestamp is shifted back by the resume gap so the
        // series is continuous across a stop/resume, while duration is measured from the raw clock
        // against the already-gap-shifted effective start (TestStatus.startTest). Both therefore
        // reduce to (logged timestamp − original start) — do not subtract the offset twice.
        long rawNow = System.currentTimeMillis();
        Long curTime = rawNow - this.timestampOffsetMs;
        row.put(Column.TEST_TIMESTAMP, curTime.toString());
        row.put(Column.TEST_DURATION_MS, Long.toString(rawNow - testStartMs));
        row.put(Column.TEST_PLAYLIST_DURATION_MS, Long.toString(rawNow - iterationStartMs));
        row.put(Column.TEST_FILE_DURATION_MS, Long.toString(rawNow - fileStartMs));
        row.put(Column.TEST_TOTAL_ITERATIONS, Integer.toString(totalIterations));
        row.put(Column.TEST_RESTART, Boolean.toString(isResume));
        row.put(Column.TEST_FILENAME, vi.fileName);
        row.put(Column.TEST_END_OF_CUR_FILE, Boolean.toString(isEndOfCurFile));

        BatteryManager batteryManager = (BatteryManager)ct.getSystemService(BATTERY_SERVICE);
        Long chargeCount = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long level = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = ct.registerReceiver(null, ifilter);
        Log.i(TAG, "Battery Changed Receiver registered in getLogsInCsv() with null receiver");

        Double batteryInMilliamps = (double) chargeCount / level * 100.0 / 1000.0;
        Double batteryTemperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0;
        Double batLevelPcnt = BatteryInfo.getBatteryLevel(ct) / 100.0;

        row.put(Column.BATTERY_CHARGE_COUNTER, chargeCount.toString());
        row.put(Column.BATTERY_MILLIAMPS, batteryInMilliamps.toString());
        row.put(Column.BATTERY_TEMPERATURE, batteryTemperature.toString());

        PowerManager powerManager = (PowerManager) ct.getSystemService(Context.POWER_SERVICE);
        Integer thermalCode = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalCode = powerManager.getCurrentThermalStatus();
        }

        row.put(Column.SYSTEM_THERMAL_STATUS, thermalCode.toString());
        row.put(Column.SYSTEM_THERMAL_STATUS_MAX,
                thermalStatusMax == null ? "" : thermalStatusMax.toString());
        row.put(Column.DISPLAY_REFRESH_HZ_MIN,
                (refreshHzMin == null || refreshHzMin <= 0f)
                        ? "" : String.format(Locale.US, "%.2f", refreshHzMin));
        row.put(Column.DISPLAY_REFRESH_HZ_MAX,
                (refreshHzMax == null || refreshHzMax <= 0f)
                        ? "" : String.format(Locale.US, "%.2f", refreshHzMax));
        row.put(Column.SYSTEM_THERMAL_HEADROOM,
                (thermalHeadroom == null || thermalHeadroom.isNaN())
                        ? "" : String.format(Locale.US, "%.3f", thermalHeadroom));

        // Fall back to an instantaneous read only when the sampler has nothing yet -- the very
        // first row, before the 2 s tick has fired.
        Double[] cpuFreqs = (freqMean != null && freqMean.length == this.numCpus)
                ? freqMean : getCurrentCpuFreq(this.numCpus);
        String[] strCpuFreqs = new String[cpuFreqs.length];
        for (int i = 0; i < cpuFreqs.length; ++i) {
            strCpuFreqs[i] = String.format(Locale.US, "%.1f", cpuFreqs[i] == null ? 0.0 : cpuFreqs[i]);
        }
        row.put(Column.CPU_FREQ, strCpuFreqs);

        if (freqRms != null && freqRms.length == this.numCpus) {
            String[] strRms = new String[freqRms.length];
            for (int i = 0; i < freqRms.length; ++i) {
                strRms[i] = String.format(Locale.US, "%.1f", freqRms[i] == null ? 0.0 : freqRms[i]);
            }
            row.put(Column.CPU_FREQ_RMS, strRms);
        }
        row.put(Column.VIDEO_FRAMES_DROPPED, frameDrops.toString());
        row.put(Column.VIDEO_DROPPED_TO_KEYFRAME, droppedToKeyframe.toString());
        row.put(Column.VIDEO_FRAMES_RENDERED, framesRendered.toString());
        row.put(Column.VIDEO_DROPPED_TO_KEYFRAME_EVENTS, droppedToKeyframeEvents.toString());
        row.put(Column.VIDEO_DECODER_NAME, vi.decoderName);
        row.put(Column.VIDEO_RESOLUTION, vi.width + "x" + vi.height);
        row.put(Column.VIDEO_BITRATE, vi.bitrate);
        row.put(Column.VIDEO_CODEC_NAME, vi.codec);
        row.put(Column.VIDEO_FRAMERATE, Float.toString(vi.fps));

        // vcat's own CPU utilization (0–100% of total device capacity) from /proc/self/stat,
        // sampled once per telemetry interval so each row's delta spans exactly one interval.
        double cpuUsageTotal = this.cpuSampler.sample();
        row.put(Column.CPU_USAGE_TOTAL, String.format(Locale.US, "%.1f", cpuUsageTotal));
        row.put(Column.BATTERY_LEVEL, batLevelPcnt.toString());

        Long vcatMemUsed = AppMemoryInfo.getBytes(ct);
        DeviceInfo.MemoryInfo sysMemInfo = DeviceInfo.MemoryInfo.getMemory(ct);
        Long sysMemUsed = sysMemInfo.total - sysMemInfo.available;
        row.put(Column.TEST_VCAT_MEMORY, vcatMemUsed.toString());
        row.put(Column.TEST_SYSTEM_MEMORY, sysMemUsed.toString());


        // build row string in same order as column defiinitions
        List<String> values = new ArrayList<>();
        for (Column col : Column.values()) {
            if (isPerCpuColumn(col)) {
                String[] freqs = emptyFreqs(this.numCpus);
                Object o = row.getOrDefault(col, freqs);

                if (o instanceof String[]) {
                    freqs = (String[]) o;
                }
                else{
                    Log.e(TAG, "Unexpected CPU Freqencies value.  Expected String[], got " + (o==null?"null":o.getClass()));
                    // use default empty set
                }

                for (String f : freqs) {
                    values.add(f);
                }
            } else {
                // All other values are Strings
                values.add(row.getOrDefault(col, "").toString());
            }
        }

        String rowStr = String.join(",", values);
        writeRow(rowStr);
    }

    /** Columns that expand to one field per CPU (cpu.freq0..N). */
    private static boolean isPerCpuColumn(Column col) {
        return col == Column.CPU_FREQ || col == Column.CPU_FREQ_RMS;
    }

    private static String[] emptyFreqs(int totalCpus){
        String[] cpuFrequencies = new String[totalCpus];
        for(int i = 0; i < totalCpus; ++i){
            cpuFrequencies[i] = "";
        }

        return cpuFrequencies;
    }

    /**
     * Get current CPU Frequencies
     * @param totalCpus the # of CPU's being tracked (0-totalCpus)
     * @return Double [totalCpus] with current freqs
     */
    public static Double[] getCurrentCpuFreq(int totalCpus) {
        Double[] cpuFrequencies = new Double[totalCpus];

        for (int i = 0; i < totalCpus; i++) {
            String scalingPath = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
            String cpuInfoPath = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_cur_freq";
            String validPath = null;
            for (String path : new String[]{scalingPath, cpuInfoPath}) {
                File freqFile = new File(path);
                if (freqFile.exists() && freqFile.canRead()) {
                    validPath = path;
                    break;
                }
            }
            // If no valid path is found, add a default value and continue
            if (validPath == null) {
                cpuFrequencies[i] = -1.0; // Indicate no valid path
                continue;
            }
            // Read the sysfs file directly. This used to shell out via
            // Runtime.exec("cat " + path), which forks a process per core per call -- tolerable
            // once every five minutes, but this is now sampled every 2 s, where it would be
            // ~173,000 forks over a twelve-hour run and would perturb the measurement itself.
            try (BufferedReader reader = new BufferedReader(new FileReader(validPath), 32)) {
                String line = reader.readLine();
                if (line != null) {
                    cpuFrequencies[i] = Float.parseFloat(line.trim()) / 1000.0; // kHz -> MHz
                } else {
                    cpuFrequencies[i] = 0.0;
                }
            } catch (Exception e) {
                cpuFrequencies[i] = 0.0;
            }
        }
        return cpuFrequencies;
    }

    public static int getTotalCpus() {
        int cpuCount = 0;

        try {
            File cpuDir = new File("/sys/devices/system/cpu/");
            File[] files = cpuDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().matches("cpu[0-9]+")) {
                        cpuCount++;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage());
        }

        return cpuCount;
    }

    /**
     * CPU utilization sampler for the vcat process, backed by {@code /proc/self/stat}.
     *
     * <p>This deliberately does NOT use the system-wide {@code /proc/stat}: SELinux denies
     * {@code untrusted_app} read access to it on modern Android, so every read failed and the
     * column was silently pinned to 0.0. A process's own {@code /proc/self} entries carry the
     * process's own domain label and AOSP policy grants {@code allow domain self:file
     * r_file_perms}, so {@code /proc/self/stat} is readable on every AOSP-derived device with no
     * kernel config dependency.
     *
     * <p>Fields 14 ({@code utime}) and 15 ({@code stime}) are cumulative clock ticks for the whole
     * thread group — including threads that have already exited — so in-process software decoders
     * (dav1d, vvdec) are fully accounted for. Work performed by a hardware decoder runs in a
     * separate vendor process and is therefore NOT included in this figure.
     *
     * <p>A percentage requires two samples an interval apart, so the previous read is retained and
     * a delta computed against it:
     * <pre>
     *   deltaCpuSeconds  = (utime + stime now − prev) / TICKS_PER_SEC
     *   deltaWallSeconds = (elapsedRealtimeNanos now − prev) / 1e9
     *   usage            = 100 * deltaCpuSeconds / (deltaWallSeconds * numCpus)
     * </pre>
     * The result is 0–100 as a fraction of total device capacity across all cores, matching the
     * scale this column used previously. Wall time comes from
     * {@link SystemClock#elapsedRealtimeNanos()} — monotonic, and unaffected by wall-clock
     * adjustments during a long run.
     *
     * <p>Per-core values are not available: the per-core {@code cpuN} lines live in the same
     * unreadable {@code /proc/stat}, and the cgroup cpuacct fallbacks are gone on current Android.
     */
    public static class CpuUsageSampler {
        private static final String TAG = "CpuUsageSampler";

        /** USER_HZ — the unit of the utime/stime fields in /proc/self/stat. */
        private static final long TICKS_PER_SEC = clockTicksPerSecond();

        /**
         * Intervals shorter than this can't be measured meaningfully against a 10ms tick, so the
         * previous value is reported instead. Telemetry normally ticks every 30s–5min, but rows
         * are also logged out-of-band (end of file, resume), which can land two samples close
         * together.
         */
        private static final long MIN_INTERVAL_NANOS = 250_000_000L;

        private final int numCpus;

        /** Cumulative utime+stime in ticks at the previous sample; -1 until primed. */
        private long prevTicks = -1L;
        /** Monotonic timestamp captured alongside prevTicks. */
        private long prevNanos = 0L;
        /** Last computed utilization, reported when an interval is too short to measure. */
        private double lastUsage = 0.0;

        public CpuUsageSampler(int numCpus) {
            this.numCpus = Math.max(1, numCpus);
            // Prime immediately so the first logged row spans construction→first row and reports
            // a real value rather than a placeholder 0.0.
            sample();
        }

        /**
         * Read {@code /proc/self/stat} and compute this process's CPU utilization (0–100% of total
         * device capacity) since the previous call. Should be invoked once per telemetry interval
         * so each delta spans exactly one interval.
         */
        public synchronized double sample() {
            long ticks = readSelfCpuTicks();
            // Timestamp as close to the read as possible so the delta pairs with the tick delta.
            long nanos = SystemClock.elapsedRealtimeNanos();

            if (ticks < 0) return lastUsage;

            if (prevTicks >= 0) {
                long deltaNanos = nanos - prevNanos;
                if (deltaNanos < MIN_INTERVAL_NANOS) {
                    // Leave prev untouched so the next sample spans a measurable window.
                    return lastUsage;
                }
                long deltaTicks = ticks - prevTicks;
                if (deltaTicks >= 0) {
                    double cpuSeconds  = (double) deltaTicks / TICKS_PER_SEC;
                    double wallSeconds = deltaNanos / 1_000_000_000.0;
                    double usage = 100.0 * cpuSeconds / (wallSeconds * numCpus);
                    // Tick quantization can nudge a fully-saturated interval just past 100.
                    lastUsage = Math.min(100.0, usage);
                }
            }

            prevTicks = ticks;
            prevNanos = nanos;
            return lastUsage;
        }

        /**
         * @return cumulative utime+stime for this process in clock ticks, or -1 if unreadable.
         */
        private static long readSelfCpuTicks() {
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/stat"), 512)) {
                String line = br.readLine();
                if (line == null) return -1L;

                // Field 2 (comm) is parenthesised and may itself contain spaces or ')', so field
                // splitting has to start after the LAST ')'. The first token after it is field 3.
                int close = line.lastIndexOf(')');
                if (close < 0 || close + 2 >= line.length()) return -1L;

                String[] f = line.substring(close + 2).split("\\s+");
                // f[0] == field 3 (state), so field N is at f[N - 3]: utime 14, stime 15.
                if (f.length < 13) return -1L;
                return Long.parseLong(f[11]) + Long.parseLong(f[12]);
            } catch (IOException | NumberFormatException e) {
                Log.e(TAG, "Failed to read /proc/self/stat: " + e.getLocalizedMessage());
                return -1L;
            }
        }

        /** @return sysconf(_SC_CLK_TCK), falling back to the universal Android value of 100. */
        private static long clockTicksPerSecond() {
            try {
                long hz = Os.sysconf(OsConstants._SC_CLK_TCK);
                if (hz > 0) return hz;
            } catch (Throwable t) {
                Log.w(TAG, "sysconf(_SC_CLK_TCK) failed, assuming 100: " + t.getLocalizedMessage());
            }
            return 100L;
        }
    }

    /**
     * Writes the JSON header block that opens a telemetry file.
     *
     * <p>The {@code execution_id} is minted here, which is precisely what makes it a per-file id:
     * a Resume appends to an existing file and skips this method entirely, so the resumed rows stay
     * under the execution_id the file was created with.
     *
     * @param playlistId the playlist's {@code <identifier>} id
     * @param playlistName the playlist file name, recorded for display only
     */
    public void writeHeaderRows(Context ct, String playlistId, String playlistName, RunConfig runCfg, long startTime){

        List<String> rows = new ArrayList<>();

        double batLevelPct = BatteryInfo.getBatteryLevel(ct) / 100.0;
        long capacityMA = (long) BatteryInfo.getBatteryDesignCapacity(ct);
        SessionInfo si = new SessionInfo(ct, startTime,
                new SessionInfo.Playlist(playlistId, playlistName), batLevelPct, capacityMA);

        SessionHeader sh = new SessionHeader(ct, UUID.randomUUID().toString(), runCfg, si);

        rows.add(SessionHeader.gson.toJson(sh, SessionHeader.class).toString());

        for(String row : rows){
            writeRow(row);
        }

        writeRow("");
        writeRow("");
    }
}



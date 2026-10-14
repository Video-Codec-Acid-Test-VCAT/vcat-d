/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roncatech.vcat.models;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Index of completed runs, kept beside the logs as {@code test_results/test_summary.json}.
 *
 * <p>Browsing the logs folder meant reading file names that carry only an epoch timestamp, so
 * finding "the 12-hour vvdec run" required opening files one at a time. Each entry here holds
 * what the list needs to be browsable — date, duration, codec, decoder — plus the same three
 * indicators the detail dialog colours, so the list can show status without parsing a log.
 *
 * <p>Entries are appended in completion order and never reordered; the list view sorts for
 * display. Reconciliation on opening the results panel drops entries whose log has been deleted
 * and backfills any log that has no entry, so a summary deleted or arriving from another device
 * repairs itself rather than needing to be regenerated.
 */
public final class TestSummary {

    private static final String TAG = "TestSummary";
    public static final String FILE_NAME = "test_summary.json";

    /** One completed run. */
    public static final class Entry {
        /** Log file name, e.g. {@code vcatd_log_1792004254558.csv}. The reconciliation key. */
        @SerializedName("file")           public String file;
        @SerializedName("start_time_ms")  public long   startTimeMs;
        @SerializedName("duration_sec")   public long   durationSec;
        @SerializedName("codec")          public String codec;
        @SerializedName("decoder")        public String decoder;
        @SerializedName("resolution")     public String resolution;
        /** Highest {@code system.thermal_status} seen. Detail dialog: green <1, yellow <3. */
        @SerializedName("max_thermal_status") public int    maxThermalStatus;
        /** Highest {@code battery.temperature} in C. Green <35, yellow <45. */
        @SerializedName("max_battery_temp_c") public double maxBatteryTempC;
        @SerializedName("frames_dropped") public long   framesDropped;
        /** Drops per second over the run. Green <0.25, yellow <1. */
        @SerializedName("drops_per_sec")  public double dropsPerSec;

        public Entry() { }
    }

    /**
     * Serialises every read-modify-write of the summary.
     *
     * <p>A run ending and the results panel opening can overlap, and both rewrite the file. On a
     * real device that produced two files -- {@code test_summary.json} and
     * {@code test_summary (1).json}, identical, same mtime -- because both callers resolved the
     * file as absent and created it. SAF has no atomic create-or-open, so the mutual exclusion
     * has to be here.
     */
    private static final Object LOCK = new Object();

    private TestSummary() { }

    /**
     * Finds the summary by listing the directory rather than via
     * {@link DocumentFile#findFile(String)}.
     *
     * <p>findFile() queries a cached tree and returns null for a file that exists but was created
     * after the DocumentFile was obtained -- which is exactly the case here, since the directory
     * handle is taken before the first run writes a summary. A null answer then leads to
     * createFile() and a duplicate.
     */
    @Nullable
    private static DocumentFile findSummary(DocumentFile dir) {
        if (dir == null) return null;
        DocumentFile[] kids = dir.listFiles();
        if (kids == null) return null;
        for (DocumentFile f : kids) {
            if (FILE_NAME.equals(f.getName())) return f;
        }
        return null;
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Builds an entry from an already-written log.
     *
     * <p>Deliberately derived from the file rather than from counters held during the run: the
     * same code then produces an entry whether it is appended at completion or backfilled during
     * reconciliation, so the two can never disagree.
     *
     * @return the entry, or null if the log cannot be parsed.
     */
    @Nullable
    public static Entry fromLog(Context ctx, Uri logUri, String fileName) {
        try {
            TestResult res = TestResult.fromLogFile(ctx, logUri);
            if (res == null || res.getTelemetryRows().isEmpty()) return null;

            List<Map<String, String>> rows = res.getTelemetryRows();
            Entry e = new Entry();
            e.file = fileName;

            Map<String, String> first = rows.get(0);
            Map<String, String> last  = rows.get(rows.size() - 1);

            e.startTimeMs = parseLong(first.get("test.timestamp"));
            // test.duration_ms is cumulative and never resets, so the last row is the run length.
            // Older logs called it test.duration; older still have neither, in which case the
            // span between the first and last row timestamps is the run. Without the fallbacks a
            // legacy log indexes as "0s", which is worse than no entry because it looks real.
            long durMs = parseLong(last.get("test.duration_ms"));
            if (durMs <= 0) durMs = parseLong(last.get("test.duration"));
            if (durMs <= 0) {
                long endMs = parseLong(last.get("test.timestamp"));
                if (endMs > e.startTimeMs && e.startTimeMs > 0) durMs = endMs - e.startTimeMs;
            }
            e.durationSec = durMs > 0 ? durMs / 1000 : 0;

            e.codec      = firstNonEmpty(rows, "video.codec_name");
            e.decoder    = firstNonEmpty(rows, "video.decoder_name");
            e.resolution = firstNonEmpty(rows, "video.resolution");

            int maxThermal = 0;
            double maxTemp = 0;
            long drops = 0;
            for (Map<String, String> row : rows) {
                maxThermal = Math.max(maxThermal, (int) parseLong(row.get("system.thermal_status")));
                // thermal_status_max exists only on newer logs; take it when present since it
                // catches transitions the point sample misses.
                maxThermal = Math.max(maxThermal, (int) parseLong(row.get("system.thermal_status_max")));
                maxTemp    = Math.max(maxTemp, parseDouble(row.get("battery.temperature")));
                drops     += parseLong(row.get("video.frames_dropped"));
            }
            e.maxThermalStatus = maxThermal;
            e.maxBatteryTempC  = maxTemp;
            e.framesDropped    = drops;
            e.dropsPerSec      = e.durationSec > 0 ? (double) drops / e.durationSec : 0;
            return e;
        } catch (Exception ex) {
            Log.w(TAG, "could not summarise " + fileName + ": " + ex.getMessage());
            return null;
        }
    }

    /** Reads the summary, or an empty list if absent or unreadable. */
    public static List<Entry> load(Context ctx, DocumentFile dir) {
        DocumentFile f = findSummary(dir);
        if (f == null || !f.exists()) return new ArrayList<>();
        try (InputStream in = ctx.getContentResolver().openInputStream(f.getUri())) {
            if (in == null) return new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<Entry> list = gson().fromJson(r, new TypeToken<List<Entry>>() {}.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            // A corrupt summary must not block the logs list; reconciliation rebuilds it.
            Log.w(TAG, "could not read " + FILE_NAME + ", rebuilding: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Writes the summary, creating it if needed. Rewrites whole: the file is small. */
    public static boolean save(Context ctx, DocumentFile dir, List<Entry> entries) {
        if (dir == null) return false;
        synchronized (LOCK) {
        try {
            DocumentFile f = findSummary(dir);
            if (f == null) f = dir.createFile("application/json", FILE_NAME);
            if (f == null) return false;
            // "wt" truncates; without it a shorter payload leaves a tail of the previous content
            // and the file no longer parses.
            try (OutputStream out = ctx.getContentResolver().openOutputStream(f.getUri(), "wt")) {
                if (out == null) return false;
                out.write(gson().toJson(entries).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not write " + FILE_NAME, e);
            return false;
        }
        }
    }

    /** Appends one entry, replacing any existing entry for the same file. */
    public static void append(Context ctx, DocumentFile dir, Entry entry) {
        if (entry == null || entry.file == null) return;
        synchronized (LOCK) {
            List<Entry> all = load(ctx, dir);
            all.removeIf(e -> entry.file.equals(e.file));
            all.add(entry);
            save(ctx, dir, all);
        }
    }

    /**
     * Brings the summary in line with the folder.
     *
     * <p>Drops entries whose log is gone — the common case, since deleting logs is how the folder
     * is managed — and backfills logs that have no entry, which covers logs copied in from
     * elsewhere and runs that ended before this file existed. Existing entries keep their
     * position so the file stays in completion order.
     *
     * @return the reconciled list, newest first for display.
     */
    public static List<Entry> reconcile(Context ctx, DocumentFile dir, Map<String, Uri> logsByName) {
        synchronized (LOCK) {
        List<Entry> existing = load(ctx, dir);
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry e : existing) {
            if (e != null && e.file != null && logsByName.containsKey(e.file)) {
                byName.put(e.file, e);          // keep, in original order
            }
        }
        boolean changed = byName.size() != existing.size();

        for (Map.Entry<String, Uri> log : logsByName.entrySet()) {
            if (byName.containsKey(log.getKey())) continue;
            Entry built = fromLog(ctx, log.getValue(), log.getKey());
            if (built != null) {
                byName.put(log.getKey(), built);   // appended at the bottom
                changed = true;
            }
        }

        List<Entry> out = new ArrayList<>(byName.values());
        if (changed) save(ctx, dir, out);
        return out;
        }
    }

    private static String firstNonEmpty(List<Map<String, String>> rows, String key) {
        for (Map<String, String> r : rows) {
            String v = r.get(key);
            if (v != null && !v.trim().isEmpty() && !"none".equals(v)) return v.trim();
        }
        return "";
    }

    private static long parseLong(@Nullable String s) {
        if (s == null) return 0;
        try { return (long) Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    private static double parseDouble(@Nullable String s) {
        if (s == null) return 0;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    /** "3h 42m" / "12m 05s" — compact enough for a list row. */
    public static String formatDuration(long seconds) {
        if (seconds <= 0) return "--";
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%dh %02dm", h, m);
        return String.format(Locale.US, "%dm %02ds", m, s);
    }
}

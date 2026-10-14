/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.roncatech.vcat.tools;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * CPU cluster layout, read from cpufreq rather than assumed.
 *
 * <p>Telemetry used to treat cpu0-3 as little and cpu4-7 as big. That holds on this CPH2625 and
 * breaks on anything with three clusters (1 prime + 3 big + 4 little is common), where it would
 * silently average a prime core together with the big ones and report a frequency no core ever
 * ran at. Clusters are therefore derived from {@code cpufreq/policyN/related_cpus}, which is the
 * kernel's own grouping, and ranked by {@code cpu_capacity} so the names mean something.
 *
 * <p>Read once at startup: the layout cannot change during a run, and the files are cheap but not
 * free.
 */
public final class CpuTopology {

    private static final String TAG = "CpuTopology";
    private static final String CPU_ROOT = "/sys/devices/system/cpu";

    /** One cpufreq policy: the cores that share a clock domain. */
    public static final class Cluster {
        /** Core indices in this cluster, ascending. */
        public final int[] cpus;
        /** Hardware ceiling in MHz from {@code cpuinfo_max_freq}, or -1 if unreadable. */
        public final int maxFreqMhz;
        /** Scheduler capacity of the first core, or -1. Used to rank clusters, not to compute. */
        public final int capacity;
        /** {@code sm} / {@code lg} for two clusters, {@code prime} for the top of three or more. */
        public final String name;
        /** cpufreq policy directory, source of this cluster's {@code stats/time_in_state}. */
        public final String policyPath;

        Cluster(int[] cpus, int maxFreqMhz, int capacity, String name, String policyPath) {
            this.cpus = cpus;
            this.maxFreqMhz = maxFreqMhz;
            this.capacity = capacity;
            this.name = name;
            this.policyPath = policyPath;
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder(name).append(":[");
            for (int i = 0; i < cpus.length; i++) { if (i > 0) sb.append(','); sb.append(cpus[i]); }
            return sb.append("] max=").append(maxFreqMhz).append("MHz cap=").append(capacity).toString();
        }
    }

    private static List<Cluster> clusters;
    private static Boolean scalingMaxReadable;

    private CpuTopology() {}

    /** Clusters ordered weakest to strongest. Empty if cpufreq is not exposed at all. */
    public static synchronized List<Cluster> clusters() {
        if (clusters == null) clusters = detect();
        return clusters;
    }

    /**
     * Whether {@code scaling_max_freq} can be read, which decides how throttling is detected.
     *
     * <p>It is the current cap, so a cap below {@code cpuinfo_max_freq} is throttling observed
     * directly. On the CPH2625 it is denied by SELinux even to adb shell, so the device falls
     * back to inferring throttling from the frequencies actually reached. The distinction has to
     * reach the report: inferred intervals are a weaker claim than measured ones.
     */
    public static synchronized boolean isScalingMaxReadable() {
        if (scalingMaxReadable == null) {
            scalingMaxReadable = false;
            for (Cluster c : clusters()) {
                if (c.cpus.length > 0 && readInt(scalingMaxPath(c.cpus[0])) > 0) {
                    scalingMaxReadable = true;
                    break;
                }
            }
        }
        return scalingMaxReadable;
    }

    /** Current cap in MHz for a core, or -1 when unreadable. */
    public static int scalingMaxMhz(int cpu) {
        int khz = readInt(scalingMaxPath(cpu));
        return khz > 0 ? khz / 1000 : -1;
    }

    private static String scalingMaxPath(int cpu) {
        return CPU_ROOT + "/cpu" + cpu + "/cpufreq/scaling_max_freq";
    }

    private static List<Cluster> detect() {
        List<Cluster> found = new ArrayList<>();
        File policyDir = new File(CPU_ROOT + "/cpufreq");
        File[] policies = policyDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("policy"));

        if (policies != null && policies.length > 0) {
            for (File p : policies) {
                int[] cpus = readCpuList(new File(p, "related_cpus"));
                if (cpus.length == 0) continue;
                int maxKhz = readInt(new File(p, "cpuinfo_max_freq").getPath());
                if (maxKhz <= 0) maxKhz = readInt(CPU_ROOT + "/cpu" + cpus[0] + "/cpufreq/cpuinfo_max_freq");
                int cap = readInt(CPU_ROOT + "/cpu" + cpus[0] + "/cpu_capacity");
                found.add(new Cluster(cpus, maxKhz > 0 ? maxKhz / 1000 : -1, cap, "?", p.getPath()));
            }
        }

        if (found.isEmpty()) {
            logSafe(false, "cpufreq policies not readable; cluster-aware metrics unavailable");
            return Collections.emptyList();
        }

        // Rank weakest first, by capacity where available and by clock otherwise.
        found.sort((a, b) -> {
            if (a.capacity > 0 && b.capacity > 0 && a.capacity != b.capacity) {
                return Integer.compare(a.capacity, b.capacity);
            }
            return Integer.compare(a.maxFreqMhz, b.maxFreqMhz);
        });

        List<Cluster> named = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            Cluster c = found.get(i);
            named.add(new Cluster(c.cpus, c.maxFreqMhz, c.capacity, nameFor(i, found.size()), c.policyPath));
        }
        logSafe(true, "clusters: " + named + " scaling_max readable=" + isScalingMaxReadable());
        return named;
    }

    /**
     * {@code sm}/{@code lg} for the two-cluster case the reports already use; for three or more
     * the top one becomes {@code prime} and any in between are numbered, so adding a cluster
     * never renames an existing column.
     */
    private static String nameFor(int index, int total) {
        if (total == 1) return "all";
        if (total == 2) return index == 0 ? "sm" : "lg";
        if (index == 0) return "sm";
        if (index == total - 1) return "prime";
        return total == 3 ? "lg" : String.format(Locale.US, "mid%d", index);
    }

    /**
     * Cumulative time per frequency bin for a cluster, from {@code stats/time_in_state}.
     *
     * <p>This is the kernel's own accounting in 10 ms jiffies, not a sample. Differencing it
     * between two telemetry rows gives the exact time the cluster spent at every frequency over
     * that interval, which is strictly better than polling {@code scaling_cur_freq}: no
     * sampling error, and short excursions between polls are counted rather than missed.
     *
     * <p>It also makes throttling observable without {@code scaling_max_freq}. A cluster free to
     * reach its top bin and under load will register time there; a capped one registers exactly
     * zero above the cap, so the highest occupied bin is the ceiling that was actually in force.
     *
     * @return bin frequency in MHz to jiffies, or an empty map if unreadable.
     */
    public static java.util.Map<Integer, Long> readTimeInState(Cluster c) {
        java.util.Map<Integer, Long> out = new java.util.LinkedHashMap<>();
        File f = new File(c.policyPath, "stats/time_in_state");
        if (!f.exists() || !f.canRead()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f), 4096)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 2) continue;
                try {
                    out.put(Integer.parseInt(parts[0]) / 1000, Long.parseLong(parts[1]));
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** Whether {@code time_in_state} is readable, which decides whether f_rms can be exact. */
    public static synchronized boolean isTimeInStateReadable() {
        for (Cluster c : clusters()) {
            if (!readTimeInState(c).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Logs without requiring the Android framework.
     *
     * <p>This class is reached from DeviceInfo's serializer, which JVM unit tests exercise, and
     * there {@code android.util.Log} throws "not mocked". Detection itself works fine off-device
     * -- it just finds no cpufreq and returns an empty list -- so the logging should not be what
     * breaks the test.
     */
    private static void logSafe(boolean info, String msg) {
        try {
            if (info) Log.i(TAG, msg); else Log.w(TAG, msg);
        } catch (Throwable ignored) {
            // Not on a device; the caller handles the empty result.
        }
    }

    private static int[] readCpuList(File f) {
        String s = readLine(f.getPath());
        if (s == null) return new int[0];
        List<Integer> out = new ArrayList<>();
        for (String tok : s.trim().split("[\\s,]+")) {
            if (tok.isEmpty()) continue;
            try {
                if (tok.contains("-")) {              // ranges appear in some kernels
                    String[] r = tok.split("-");
                    for (int i = Integer.parseInt(r[0]); i <= Integer.parseInt(r[1]); i++) out.add(i);
                } else {
                    out.add(Integer.parseInt(tok));
                }
            } catch (NumberFormatException ignored) { }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static int readInt(String path) {
        String s = readLine(path);
        if (s == null) return -1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static String readLine(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f), 64)) {
            return r.readLine();
        } catch (Exception e) {
            return null;     // denied by SELinux on some vendors; the caller reports unavailable
        }
    }
}

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

package com.roncatech.vcat.video;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.PlayerControlView;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.ui.PlayerView;

import com.google.android.exoplayer2.video.VideoSize;
import com.roncatech.vcat.models.TestStatus;
import com.roncatech.vcat.models.RunConfig;
import com.roncatech.vcat.models.ResumeInfo;
import com.roncatech.vcat.models.SharedViewModel;
import com.roncatech.vcat.service.PlayerCommandBus;
import com.roncatech.vcat.telemetry.TelemetryLogger;
import com.roncatech.vcat.tools.BatteryInfo;
import androidx.documentfile.provider.DocumentFile;
import com.roncatech.vcat.models.TestSummary;
import com.roncatech.vcat.tools.StorageManager;
import com.roncatech.vcat.tools.CpuTopology;
import com.roncatech.vcat.tools.DisplayInfo;
import com.roncatech.vcat.tools.PlaylistIdentity;
import com.roncatech.vcat.tools.UriUtils;
import com.roncatech.vcat.tools.VideoDecoderEnumerator;
import com.roncatech.vcat.tools.XspfParser;
import com.roncatech.vcat.R;
import com.roncatech.vcat.parsers.ivf.VcatIvfExtractor;
import com.roncatech.vcat.parsers.mp4.VcatMp4Extractor;

import java.util.List;
import java.util.Locale;

public class FullScreenPlayerActivity extends AppCompatActivity implements PlayerCommandBus.Listener {

    private static final String TAG = "FullScreenPlayerActivity";

    private SharedViewModel viewModel;
    private ExoPlayer exoPlayer;

    private PlayerView playerView;
    LinearLayout buttonRow;
    TextView videoOverlay;

    private List<Uri> testClips;
    private int curFileIndex;
    /** Playlist passes completed. With a single-file playlist, every file end is a pass. */
    private int totalIterations = 0;
    /** Start of the current playlist pass; {@code test.duration_ms} is measured from here. */
    private long iterationStartMs = System.currentTimeMillis();
    /** Start of the current file; {@code test.file_duration_ms} is measured from here. */
    private long fileStartMs = System.currentTimeMillis();
    /** Frame-number overlay, and the state needed to turn a presentation time into an index. */
    private TextView frameNumberOverlay;
    private long frameOverlayFirstPtsUs = Long.MIN_VALUE;
    private long frameOverlayStepUs = 0;
    private long frameOverlayLastPtsUs = Long.MIN_VALUE;

    /**
     * First battery reading of the run, used to estimate time to completion.
     *
     * <p>The estimate is a whole-run average rather than a sliding window: battery level is only
     * reported to the nearest percent, so over a nine-hour discharge a single percent lasts about
     * six minutes and any short window is mostly quantisation noise. Discharge under a fixed
     * playback workload is close to linear, which is what makes the average usable.
     */
    private int runStartBatteryPct = -1;
    private long runStartBatteryMs = 0L;

    /**
     * Frames dropped so far this run, for the overlay's running average.
     *
     * <p>Accumulated from the same per-interval count the telemetry row uses, so the overlay and
     * the CSV cannot disagree. A cumulative rate rather than the current interval's: an instant
     * figure swings between 0 and hundreds as a stall starts and clears, which says nothing about
     * whether a run is healthy. Total over elapsed time is the number worth watching.
     */
    private long runTotalFrameDrops = 0L;

    /**
     * The last video identity actually resolved from the player.
     *
     * <p>The row written at teardown runs after the player has been released, so
     * {@link #getTlVideoInfo} finds no Format and would otherwise stamp that row "empty" --
     * giving the run a second, nameless entry in the results detail view alongside the real
     * clip. The row is worth keeping, since on a short clip a whole playthrough can exist only
     * in it, so it reuses the identity it had rather than losing it.
     */
    private TelemetryLogger.VideoInfo lastVideoInfo = null;

    /** When the last telemetry row was written, so teardown can tell if one is still owed. */
    private long lastTelemetryRowMs = 0;

    /**
     * Drop counters are sampled on their own short timer and accumulated here, rather than being
     * read from the player when a telemetry row is written.
     *
     * <p>The two cadences have to differ. Telemetry rows ramp from 30 s to 5 min, because polling
     * battery, thermal, eight CPU frequencies and memory every couple of seconds for a 17 h run
     * would make the log unusable. But the player's drop counters do not survive that long: a new
     * ExoPlayer and a fresh {@code DecoderCounters} are created for every clip, so anything not
     * read before the next clip starts is gone. With 13 s clips and minutes between rows, most of
     * it was.
     *
     * <p>Sampling at {@link #DROP_SAMPLE_INTERVAL_MS} captures the counters while they still
     * exist; the telemetry row then reports the accumulated total and clears it, so a row covers
     * its whole interval however long that interval is.
     */
    private static final long DROP_SAMPLE_INTERVAL_MS = 2000;
    private final Handler dropSamplerHandler = new Handler(Looper.getMainLooper());
    private int pendingFrameDrops = 0;
    private int pendingDroppedToKeyframe = 0;
    private int pendingFramesRendered = 0;
    private int pendingDroppedToKeyframeEvents = 0;

    /**
     * Thermal state accumulated between telemetry rows.
     *
     * <p>Both are maxima, not point samples. {@code getCurrentThermalStatus()} read once per row
     * misses any episode shorter than the interval, which is how a 6.5 h run came back uniformly
     * zero while stalling five times. The listener catches every transition instead.
     *
     * <p>Headroom is the column that actually carries information on this device. The CPH2625's
     * vendor HAL defines SKIN thresholds as {@code [NaN, 43.0, 45.0, 41.0, ...]} — SEVERE at
     * 41 °C, below LIGHT at 43 and MODERATE at 45 — so status can only ever read 0 or 3 and the
     * approach to a trip is invisible. Headroom is continuous, with 1.0 at the threshold.
     */
    /**
     * Panel refresh rate observed between telemetry rows, as a range.
     *
     * <p>{@code preferredRefreshRate} is advisory: the system may refuse it, and an LTPO panel
     * changes rate on its own regardless. Until now the achieved rate was written to logcat once
     * at startup and never to telemetry, so a finished run could not answer whether the
     * configured condition actually held — which makes its frame-drop figures unreadable.
     */
    /**
     * Per-core frequency accumulated between telemetry rows: running sum and sum of squares.
     *
     * <p>One instantaneous reading per row could not tell a core held at 1800 MHz from one
     * alternating 600/2500 — and that distinction is the whole difference between a decoder that
     * sits at the top OPP and one that spreads across the DVFS range. Sampling on the 2 s tick
     * gives ~30 readings per core per row; mean and RMS are emitted, and their difference gives
     * the spread via sigma = sqrt(rms^2 - mean^2).
     */
    private double[] freqSum = null;
    private double[] freqSumSq = null;
    private int freqSamples = 0;

    /** Accumulates one frequency reading per core; called from the 2 s sampler. */
    private void sampleCpuFreq() {
        Double[] f = TelemetryLogger.getCurrentCpuFreq(this.numCpusForFreq);
        if (this.freqSum == null || this.freqSum.length != f.length) {
            this.freqSum = new double[f.length];
            this.freqSumSq = new double[f.length];
            this.freqSamples = 0;
        }
        for (int i = 0; i < f.length; i++) {
            double v = (f[i] == null || f[i] < 0) ? 0.0 : f[i];
            this.freqSum[i] += v;
            this.freqSumSq[i] += v * v;
        }
        this.freqSamples++;
        accumulateClusterMax(f);
    }

    private int numCpusForFreq = TelemetryLogger.getTotalCpus();

    /**
     * Per-cluster frequency extremes, for throttle detection.
     *
     * <p>{@code clusterMaxThisRow} is the highest any core in the cluster reached since the last
     * telemetry row; {@code clusterMaxRun} is the highest it has reached at any point in the run.
     *
     * <p>Throttling is measured from {@code scaling_max_freq} where that is readable. It is not
     * on the CPH2625 — SELinux denies it even to adb shell — so it is otherwise inferred by
     * comparing the two: a cluster that has demonstrably reached F this run and now cannot get
     * near F is capped. Calibrating against what the workload actually achieved, rather than
     * against {@code cpuinfo_max_freq}, is what makes this usable: a decoder that never needs the
     * top bin would look permanently throttled against the hardware ceiling.
     */
    private int[] clusterMaxThisRow = null;
    private int[] clusterMaxRun = null;

    /** Fraction of its established peak a cluster may fall to before the interval counts as throttled. */
    private static final double THROTTLE_INFER_RATIO = 0.90;

    /**
     * Previous {@code time_in_state} snapshot per cluster, for differencing at each row.
     *
     * <p>The kernel counts, in 10 ms jiffies, how long each cluster spent at every frequency bin.
     * Differencing the counter between two rows gives the exact residency over that interval --
     * no sampling error, and excursions shorter than the 2 s poll are counted rather than missed.
     * Polling {@code scaling_cur_freq} can only ever approximate this.
     */
    private java.util.List<java.util.Map<Integer, Long>> prevTimeInState = null;

    /** A cluster is busy enough for its ceiling to mean something above this. */
    private static final double THROTTLE_BUSY_FLOOR_PCT = 5.0;

    /**
     * Drop rate above which a low ceiling means the decoder wanted more clock and could not get
     * it, rather than simply not needing it.
     *
     * <p>Residency shows the ceiling that was in force but cannot by itself separate "capped at
     * 1400" from "never needed more than 1400". Frame drops separate them, because a decoder
     * missing its deadline is by definition asking for more than it is getting. Measured across
     * two runs: dav1d spent 48 of 49 intervals below ~1950 MHz with <b>0.0%</b> dropped — healthy
     * and demand-limited — while vvdec's 19 sub-1950 intervals averaged <b>34.8%</b> dropped.
     * Ceiling alone would have called dav1d permanently throttled.
     */
    private static final double THROTTLE_DROP_FLOOR_PCT = 1.0;

    /** Per-interval residency metrics for one cluster. */
    private static final class ClusterResidency {
        double fRms = -1;        // time-weighted RMS, MHz
        int fMaxUsed = -1;       // highest bin with non-zero time, MHz
        double busyPct = -1;     // % of interval above the lowest bin
    }

    /**
     * Differences {@code time_in_state} since the previous row.
     *
     * <p>Returns one entry per cluster; fields stay -1 where the counter is unreadable, which is
     * what tells the report to fall back on the sampled series instead.
     */
    private ClusterResidency[] sampleResidency() {
        java.util.List<CpuTopology.Cluster> cl = CpuTopology.clusters();
        ClusterResidency[] out = new ClusterResidency[cl.size()];
        java.util.List<java.util.Map<Integer, Long>> now = new java.util.ArrayList<>(cl.size());

        for (int k = 0; k < cl.size(); k++) {
            out[k] = new ClusterResidency();
            java.util.Map<Integer, Long> cur = CpuTopology.readTimeInState(cl.get(k));
            now.add(cur);
            if (cur.isEmpty() || this.prevTimeInState == null || k >= this.prevTimeInState.size()) continue;

            java.util.Map<Integer, Long> prev = this.prevTimeInState.get(k);
            double sumSq = 0, total = 0, aboveMin = 0;
            int maxUsed = -1, minBin = Integer.MAX_VALUE;
            for (int bin : cur.keySet()) minBin = Math.min(minBin, bin);

            for (java.util.Map.Entry<Integer, Long> e : cur.entrySet()) {
                long d = e.getValue() - (prev.containsKey(e.getKey()) ? prev.get(e.getKey()) : 0L);
                if (d <= 0) continue;
                int mhz = e.getKey();
                sumSq += (double) mhz * mhz * d;
                total += d;
                if (mhz > maxUsed) maxUsed = mhz;
                if (mhz > minBin) aboveMin += d;
            }
            if (total > 0) {
                out[k].fRms = Math.sqrt(sumSq / total);
                out[k].fMaxUsed = maxUsed;
                out[k].busyPct = 100.0 * aboveMin / total;
            }
        }
        this.prevTimeInState = now;
        return out;
    }

    private void ensureClusterArrays() {
        int n = CpuTopology.clusters().size();
        if (this.clusterMaxThisRow == null || this.clusterMaxThisRow.length != n) {
            this.clusterMaxThisRow = new int[n];
            this.clusterMaxRun = new int[n];
        }
    }

    /** Folds one frequency reading per core into the per-cluster maxima. */
    private void accumulateClusterMax(Double[] perCore) {
        ensureClusterArrays();
        java.util.List<CpuTopology.Cluster> cl = CpuTopology.clusters();
        for (int k = 0; k < cl.size(); k++) {
            for (int cpu : cl.get(k).cpus) {
                if (cpu < 0 || cpu >= perCore.length || perCore[cpu] == null) continue;
                int mhz = (int) Math.round(perCore[cpu]);
                if (mhz > this.clusterMaxThisRow[k]) this.clusterMaxThisRow[k] = mhz;
                if (mhz > this.clusterMaxRun[k]) this.clusterMaxRun[k] = mhz;
            }
        }
    }

    /** Current cap per cluster in MHz, or -1 where unreadable. */
    private int[] clusterScalingMax() {
        java.util.List<CpuTopology.Cluster> cl = CpuTopology.clusters();
        int[] out = new int[cl.size()];
        java.util.Arrays.fill(out, -1);
        if (!CpuTopology.isScalingMaxReadable()) return out;
        for (int k = 0; k < cl.size(); k++) {
            int lowest = Integer.MAX_VALUE;
            for (int cpu : cl.get(k).cpus) {
                int m = CpuTopology.scalingMaxMhz(cpu);
                if (m > 0) lowest = Math.min(lowest, m);
            }
            out[k] = (lowest == Integer.MAX_VALUE) ? -1 : lowest;
        }
        return out;
    }

    /** 1 throttled, 0 not, -1 undetermined. */
    private int[] clusterThrottled(int[] scalingMax, ClusterResidency[] res, double dropPct) {
        ensureClusterArrays();
        java.util.List<CpuTopology.Cluster> cl = CpuTopology.clusters();
        int[] out = new int[cl.size()];
        java.util.Arrays.fill(out, -1);
        for (int k = 0; k < cl.size(); k++) {
            int hwMax = cl.get(k).maxFreqMhz;
            if (scalingMax[k] > 0 && hwMax > 0) {
                out[k] = scalingMax[k] < hwMax ? 1 : 0;          // measured
                continue;
            }
            // Inferred. Needs a baseline: until the cluster has shown what it can do, say nothing
            // rather than report every opening interval as throttled.
            // Preferred: the ceiling the kernel's own accounting shows was in force. A cluster
            // free to reach its top bin and doing work will register time there; a capped one
            // registers exactly zero above the cap. The busy floor is what stops an idle cluster
            // -- which also never reaches its top bin -- from reading as throttled.
            if (res != null && k < res.length && res[k].fMaxUsed > 0 && hwMax > 0
                    && res[k].busyPct >= THROTTLE_BUSY_FLOOR_PCT) {
                boolean belowCeiling = res[k].fMaxUsed < hwMax;
                // A low ceiling on its own is not throttling -- the decoder may simply not need
                // the top bin. It is throttling when the decoder is also missing its deadline.
                out[k] = (belowCeiling && dropPct >= THROTTLE_DROP_FLOOR_PCT) ? 1 : 0;
                continue;
            }
            // Last resort, from the sampled series: compare against the peak this cluster has
            // actually reached this run rather than against cpuinfo_max_freq, or a decoder that
            // never needs the top bin reads as permanently throttled.
            int peak = this.clusterMaxRun[k];
            int seen = this.clusterMaxThisRow[k];
            if (peak <= 0 || seen <= 0) continue;
            out[k] = (seen < peak * THROTTLE_INFER_RATIO) ? 1 : 0;
        }
        return out;
    }

    private float pendingRefreshMin = 0f;
    private float pendingRefreshMax = 0f;
    private boolean refreshMismatchLogged = false;
    /** Set once verifyTestConditions() has run, so the sampler does not warn mid mode-switch. */
    private boolean conditionsVerified = false;
    /** Reported rates are floats (120.00001 Hz); allow a little slack before calling it a miss. */
    private static final float REFRESH_TOLERANCE_HZ = 1.0f;
    /** The window brightness override we asked for, as a 0..1 fraction. */
    private float requestedBrightness = -1f;

    /**
     * Aborts the run if a configured test condition did not actually take effect.
     *
     * <p>A benchmark that silently runs under conditions other than the ones configured produces
     * numbers that look valid and are not. {@code preferredRefreshRate} in particular is only
     * advisory — the system may refuse it outright, and the panel may already be pinned to
     * another rate by a system-level setting. Failing loudly at the start costs one run; failing
     * silently costs every comparison the data is later used in.
     *
     * <p>Checked once, a few seconds into the first clip, because the mode switch is asynchronous.
     */
    private void verifyTestConditions() {
        // The check is posted three seconds out, which is long enough for the run to have ended
        // first -- a clip that fails to load tears the activity down well inside that window.
        // Showing a dialog then throws BadTokenException and takes the process with it, turning
        // a recoverable playback error into a crash. Worse, stopTestAndCleanup() restores the
        // original screen brightness, so a check running after it would compare against the
        // restored value and "fail" a condition that was in fact applied correctly.
        if (isFinishing() || isDestroyed() || this.exoPlayer == null) {
            Log.i(TAG, "Skipping condition check; the run already ended");
            return;
        }

        float achieved = DisplayInfo.currentRefreshRate(this);
        Log.i(TAG, "Display refresh: requested " + this.requestedRefreshHz
                + " Hz, achieved " + achieved + " Hz");

        if (this.requestedRefreshHz > 0 && achieved > 0
                && Math.abs(achieved - this.requestedRefreshHz) > REFRESH_TOLERANCE_HZ) {
            abortOnCondition("Display refresh rate",
                    String.format(Locale.US,
                            "Configured %d Hz, but the panel is running at %.2f Hz.\n\n"
                            + "preferredRefreshRate is advisory and the system declined it. "
                            + "Frame-drop and battery figures are not comparable across "
                            + "different refresh rates, so the run has been stopped.\n\n"
                            + "Check that %d Hz is not overridden by a system display setting "
                            + "(for example a forced high refresh rate mode).",
                            this.requestedRefreshHz, achieved, this.requestedRefreshHz));
            return;
        }

        // Brightness can only be checked at the window layer. There is no public API for the
        // achieved panel backlight: getAttributes() returns what we set, and
        // Settings.System.SCREEN_BRIGHTNESS is the system slider, which a window override
        // deliberately bypasses. So this catches the override failing to stick -- being reset to
        // BRIGHTNESS_OVERRIDE_NONE by a lifecycle event, say -- but cannot see thermal dimming
        // applied below the framework. Worth having because it is the failure that would
        // otherwise be silent; the rest still needs an eyeball.
        float appliedBrightness = getWindow().getAttributes().screenBrightness;
        if (this.requestedBrightness >= 0f
                && Math.abs(appliedBrightness - this.requestedBrightness) > 0.01f) {
            abortOnCondition("Screen brightness",
                    String.format(Locale.US,
                            "Configured %.0f%%, but the window override reads %s.\n\n"
                            + "Display power is a large part of total draw, so battery figures "
                            + "taken at a different brightness are not comparable.",
                            this.requestedBrightness * 100f,
                            appliedBrightness < 0f
                                    ? "no override (system default)"
                                    : String.format(Locale.US, "%.0f%%", appliedBrightness * 100f)));
            return;
        }

        this.conditionsVerified = true;
        Log.i(TAG, "Test conditions verified: " + this.requestedRefreshHz + " Hz, brightness "
                + Math.round(this.requestedBrightness * 100f) + "%");
    }

    /** Shows a blocking error and ends the run; the data collected so far is still flushed. */
    private void abortOnCondition(String what, String detail) {
        Log.e(TAG, "ABORT - test condition not applied: " + what + " - " + detail);
        // Second guard, independent of the caller's: a dialog on a window that is going away is
        // a BadTokenException, and this path exists to stop a run cleanly rather than crash it.
        if (isFinishing() || isDestroyed()) {
            stopTestAndCleanup();
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Test Condition Not Applied")
                .setMessage(what + "\n\n" + detail)
                .setPositiveButton(android.R.string.ok, null)
                .setCancelable(false)
                .show();
        dlg.setOnDismissListener(d -> stopTestAndCleanup());
    }

    /** Samples the panel's current refresh rate, keeping the range seen since the last row. */
    private void sampleRefreshRate() {
        float hz = DisplayInfo.currentRefreshRate(this);
        if (hz <= 0f) return;
        if (this.pendingRefreshMin <= 0f || hz < this.pendingRefreshMin) this.pendingRefreshMin = hz;
        if (hz > this.pendingRefreshMax) this.pendingRefreshMax = hz;
        // Warn once, and only after verifyTestConditions() has run. The panel takes a couple of
        // seconds to switch mode, so sampling before that reports the old rate and cries wolf.
        if (this.conditionsVerified && !this.refreshMismatchLogged && this.requestedRefreshHz > 0
                && Math.abs(hz - this.requestedRefreshHz) > 1.0f) {
            this.refreshMismatchLogged = true;
            Log.w(TAG, "REFRESH MISMATCH: configured " + this.requestedRefreshHz
                    + " Hz, panel reports " + hz + " Hz");
        }
    }

    private int pendingThermalStatusMax = 0;
    private float pendingThermalHeadroom = Float.NaN;
    @Nullable private PowerManager.OnThermalStatusChangedListener thermalListener = null;

    /** Registers the thermal listener; status changes are recorded as a max until the next row. */
    private void startThermalMonitoring() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || this.thermalListener != null) return;
        this.pendingThermalStatusMax = pm.getCurrentThermalStatus();
        this.thermalListener = status -> {
            if (status > this.pendingThermalStatusMax) this.pendingThermalStatusMax = status;
            Log.i(TAG, "THERMAL status -> " + status);
            // Write a row on the transition as well as on the timer. A throttling episode
            // shorter than the row interval would otherwise fall between samples, and those
            // episodes are exactly what the throttle analysis is looking for.
            //
            // Only once the timer has written a row, though. This callback can arrive at any
            // point after registration -- including before playback has set up the state
            // logTelemetry() reads -- and a thermal transition must never be able to take the
            // run down with it. lastTelemetryRowMs is the cheapest proof that the normal path
            // has already run successfully at least once.
            if (this.lastTelemetryRowMs > 0 && this.tl != null && this.exoPlayer != null
                    && !isFinishing() && !isDestroyed()) {
                try {
                    logTelemetry(false);
                } catch (Throwable t) {
                    // A diagnostic row is never worth crashing a multi-hour run for.
                    Log.e(TAG, "thermal-triggered telemetry row failed", t);
                }
            }
        };
        pm.addThermalStatusListener(getMainExecutor(), this.thermalListener);
    }

    private void stopThermalMonitoring() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && this.thermalListener != null) {
            pm.removeThermalStatusListener(this.thermalListener);
        }
        this.thermalListener = null;
    }

    /** Samples thermal headroom, keeping the worst value seen since the last row. */
    private void sampleThermalHeadroom() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        try {
            // forecastSeconds = 0 asks for "now". The platform rate-limits this to roughly 1 Hz
            // and returns NaN if asked more often, which the 2 s sampler stays clear of.
            // 10 s forecast rather than 0: the spec wants early warning of a trip, and the
            // platform's forecast is what gives the approach rather than the arrival.
            float h = pm.getThermalHeadroom(10);
            if (!Float.isNaN(h)
                    && (Float.isNaN(this.pendingThermalHeadroom) || h > this.pendingThermalHeadroom)) {
                this.pendingThermalHeadroom = h;
            }
        } catch (Exception e) {
            // Unsupported on some devices; leave the column blank rather than failing the row.
        }
    }

    /** Folds everything the player has recorded since the last call into the pending totals. */
    private void sampleDrops() {
        sampleThermalHeadroom();
        sampleRefreshRate();
        sampleCpuFreq();
        this.fd.reset();
        if (this.videoCounters != null) {
            this.videoCounters.ensureUpdated();

            // Drops are read from the counter, not from onDroppedVideoFrames. The callback is
            // batched at a 50-frame threshold and flushed again on renderer stop, so its
            // arrival time is decoupled from the drop; during a clip swap the outgoing player
            // was still attached and its flush landed in the incoming clip's row. The counter
            // belongs to one player and cannot be credited to another.
            int dropped = this.videoCounters.droppedBufferCount;
            if (dropped < this.lastDroppedBuffers) this.lastDroppedBuffers = 0;
            this.pendingFrameDrops += dropped - this.lastDroppedBuffers;
            this.lastDroppedBuffers = dropped;

            // Rendered frames make a row checkable: rendered + dropped should account for the
            // interval at the clip's frame rate, so a row that will not close is a counting
            // error rather than a decoder result.
            int rendered = this.videoCounters.renderedOutputBufferCount;
            if (rendered < this.lastRenderedFrames) this.lastRenderedFrames = 0;
            this.pendingFramesRendered += rendered - this.lastRenderedFrames;
            this.lastRenderedFrames = rendered;
            // Frames, not events. droppedInputBufferCount counts buffers discarded from the
            // source to reach a keyframe, and ExoPlayer only ever increments it there: an
            // ordinary late frame is reported as droppedInputBufferCount=0. So this is the size
            // of the block that was thrown away, which is what a stall should look like --
            // droppedToKeyframeCount would just read 1 however many frames went with it.
            int total = this.videoCounters.droppedInputBufferCount;
            // A fresh counter for a new clip restarts at zero.
            if (total < this.lastDroppedToKeyframe) {
                this.lastDroppedToKeyframe = 0;
            }
            int delta = total - this.lastDroppedToKeyframe;
            if (delta > 0) {
                Log.d(TAG, "SKIP-TO-KEYFRAME dropped " + delta + " source frames at file_ms="
                        + (System.currentTimeMillis() - this.fileStartMs)
                        + " (counter total=" + total + ")");
            }
            this.pendingDroppedToKeyframe += delta;
            this.lastDroppedToKeyframe = total;

            // The event count is what makes the frame count checkable. One skip can only
            // discard what is sitting in the sample queue, and DefaultLoadControl caps that at
            // 50 s, so a row claiming far more frames per event than 50 s of content is
            // reporting a counting error, not a stall.
            int events = this.videoCounters.droppedToKeyframeCount;
            if (events < this.lastDroppedToKeyframeEvents) this.lastDroppedToKeyframeEvents = 0;
            this.pendingDroppedToKeyframeEvents += events - this.lastDroppedToKeyframeEvents;
            this.lastDroppedToKeyframeEvents = events;
        }
    }

    private final Runnable dropSamplerRunnable = new Runnable() {
        @Override
        public void run() {
            sampleDrops();
            dropSamplerHandler.postDelayed(this, DROP_SAMPLE_INTERVAL_MS);
        }
    };

    // Session cur/max for the status overlay. Battery temp in °C; system value is the
    // PowerManager thermal status (0–6) — the only system-thermal metric available to an app.
    private double curBatteryTempC = 0, maxBatteryTempC = 0;
    private int curSysThermal = 0, maxSysThermal = 0;

    private TelemetryLogger tl;

    @Nullable private DecoderCounters videoCounters = null;
    /** Last sampled value of {@code droppedToKeyframeCount}, so rows can carry the delta. */
    private int lastDroppedToKeyframe = 0;
    /** Last sampled {@code droppedBufferCount} / {@code renderedOutputBufferCount} baselines. */
    private int lastDroppedBuffers = 0;
    private int lastRenderedFrames = 0;
    private int lastDroppedToKeyframeEvents = 0;
    private AnalyticsListener analyticsListener;

    private Player.Listener playbackStateListener;

    RenderersFactory renderersFactory;


    private static class FrameDrops{
        private int elapsedMs = 0;
        private int frameDrops = 0;

        private void reset(){this.elapsedMs = this.frameDrops = 0;}
    }

    private static final String emptyDecoder = "{none}";
    private String curDecoder = emptyDecoder;

    FrameDrops fd = new FrameDrops();

    private float originalWindowBrightness = -1;
    /** Refresh rate asked for this run; the achieved rate is checked once playback is up. */
    private int requestedRefreshHz = RunConfig.DEFAULT_REFRESH_HZ;
    private boolean refreshRateLogged = false;

    private boolean orientationCommittedForClip = false;

    private static String stateName(int s) {
        switch (s) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return "UNKNOWN(" + s + ")";
        }
    }

    private final Handler hb = new Handler(Looper.getMainLooper());
    private long lastPos = -1, lastBeatUptime = 0;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (exoPlayer != null) {
                long pos = exoPlayer.getCurrentPosition();
                int state = exoPlayer.getPlaybackState();
                boolean playing = exoPlayer.getPlayWhenReady() && state == Player.STATE_READY;

                // poll counters if we have them
                if (videoCounters != null) {
                    videoCounters.ensureUpdated();
                    Log.d(TAG, "COUNTERS rendered=" + videoCounters.renderedOutputBufferCount
                            + " dropped=" + videoCounters.droppedBufferCount
                            + " skipped=" + videoCounters.skippedOutputBufferCount);
                }

                long dPos = (lastPos < 0) ? 0 : (pos - lastPos);
                Log.d(TAG, "HB state=" + stateName(state) + " pos=" + pos + "ms Δpos=" + dPos + "ms");
                if (playing && lastPos >= 0 && dPos < 5 && (SystemClock.uptimeMillis() - lastBeatUptime) > 1500) {
                    Log.w(TAG, "HB STALL: READY+playing but position not advancing");
                }
                lastPos = pos;
                lastBeatUptime = SystemClock.uptimeMillis();
            }
            hb.postDelayed(this, 1000);
        }
    };

    private static boolean hasValidVideoSize(@Nullable VideoSize vs) {
        return vs != null && vs.width > 0 && vs.height > 0;
    }

    private boolean shouldStopTesting(){
        boolean shouldStop = false;

        int batteryLevel = BatteryInfo.getBatteryLevel(FullScreenPlayerActivity.this);
        RunConfig rc = FullScreenPlayerActivity.this.viewModel.getRunConfig();

        if(rc.runMode == RunConfig.RunMode.BATTERY && batteryLevel < rc.runLimit){
            Log.i(TAG, String.format("Stopping test because battery level %d is < run limit", batteryLevel));
            shouldStop = true;
        } else if(rc.runMode == RunConfig.RunMode.TIME){
            long elapsedTime = (System.currentTimeMillis() - FullScreenPlayerActivity.this.viewModel.curTestDetails.getStartTimeAsEpoch()) / (1000 * 60);
            if(elapsedTime >= rc.runLimit){
                Log.i(TAG, "Stopping test because test run has exceeded run limit time");
                shouldStop = true;
            }
        }
        else if(rc.runMode == RunConfig.RunMode.ONCE &&
                FullScreenPlayerActivity.this.curFileIndex +1 == FullScreenPlayerActivity.this.testClips.size()){
            Log.i(TAG, "Stopping test because playlist is complete");
            shouldStop = true;
        }

        return shouldStop;
    }

    private void advanceToNextClip(){
        if (++this.curFileIndex >= this.testClips.size()) {
            // Wrapped: the playlist has completed a pass. A single-file playlist wraps on every
            // file, which is what makes "iteration" and "file" coincide there.
            this.curFileIndex = 0;
            this.totalIterations++;
            this.iterationStartMs = System.currentTimeMillis();
        }
    }

    @Override
    public void onStopTest(){
        stopTestAndCleanup();
    }
    public void onToggleVideoInfo() {
        if (videoOverlay.getVisibility() == View.VISIBLE) {
            videoOverlay.setVisibility(View.GONE);
            return;
        }

        TelemetryLogger.VideoInfo vi = getTlVideoInfo(this.testClips.get(this.curFileIndex), this.viewModel.curTestDetails);

        // show it ABOVE the video + controller
        videoOverlay.setVisibility(View.VISIBLE);
        videoOverlay.bringToFront();
        videoOverlay.setElevation(1000f);

        // FIX: make sure it’s actually visible (don’t leave alpha at 0)
        // Simple on/off:
        videoOverlay.setAlpha(1f);

        // (If you prefer a fade-in, use this instead of the line above)
        // videoOverlay.setAlpha(0f);
        // videoOverlay.animate().alpha(1f).setDuration(150).start();

        // populate overlay text (your logTelemetry already sets it when overlay is VISIBLE)
        logTelemetry(false);
    }

    public void onPlayPause(){
        exoPlayer.setPlayWhenReady(!exoPlayer.getPlayWhenReady());
    }

    /**
     * Appends this run to {@code test_results/test_summary.json}.
     *
     * <p>Runs on a background thread: it re-reads the log it has just written, which is up to a
     * few hundred KB after a twelve-hour run, and the teardown path is on the main thread.
     * Summarising from the file rather than from counters held during the run means an entry
     * written here and one backfilled later by reconciliation are produced by the same code and
     * cannot disagree.
     *
     * <p>Failures are logged and swallowed. The index is a convenience; reconciliation will
     * backfill a missing entry next time the results panel is opened, and nothing about a
     * completed run should be put at risk to maintain it.
     */
    private void appendRunToSummary() {
        final DocumentFile log = (this.tl == null) ? null : this.tl.getCsvDocFile();
        if (log == null || !log.exists()) return;
        final String name = log.getName();
        if (name == null) return;
        final Uri uri = log.getUri();
        final Context appCtx = getApplicationContext();

        new Thread(() -> {
            try {
                DocumentFile dir = StorageManager.getFolder(appCtx, StorageManager.VCATFolder.TEST_RESULTS);
                if (dir == null) return;
                TestSummary.Entry e = TestSummary.fromLog(appCtx, uri, name);
                if (e != null) {
                    TestSummary.append(appCtx, dir, e);
                    Log.i(TAG, "summary: indexed " + name + " (" + e.codec + ", "
                            + TestSummary.formatDuration(e.durationSec) + ")");
                }
            } catch (Throwable t) {
                Log.w(TAG, "could not index run in test_summary.json", t);
            }
        }, "vcat-summary").start();
    }

    private void stopTestAndCleanup() {
        // 0) flush whatever has accrued since the last row.
        //
        // Only the STATE_ENDED path logs before stopping; the stop button, the ADB stop command
        // and both error dialogs come straight here, so their final interval used to be thrown
        // away. That matters most on short clips: the sampling timer ramps from 30 s to 5 min, so
        // with a 13 s file a whole playthrough can fall between periodic rows and exist only in
        // the row written at its end.
        //
        // Skipped when a row was just written, so the STATE_ENDED path does not emit a duplicate.
        if (this.tl != null && this.testClips != null && !this.testClips.isEmpty()
                && System.currentTimeMillis() - this.lastTelemetryRowMs > 500) {
            logTelemetry(true);
        }

        // 0b) index the finished run in test_summary.json, so the logs list can show date,
        // duration and codec without opening each file.
        appendRunToSummary();

        // 1) stop your periodic telemetry
        stopTelemetryTimer();
        // 2) reset the test details
        viewModel.curTestDetails.reset();
        // 3) tear down playback & exit
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }

        this.curDecoder = emptyDecoder;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        finish();
    }

    private com.google.android.exoplayer2.DefaultRenderersFactory getRendersFactory() {
        // forcedJoinMs = 500 (tweak as you like)
        return new StrictRenderersFactoryV2(this, this.viewModel);
    }

    private void initialize(){
        this.viewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        int testScreenBrightness = viewModel.getRunConfig().screenBrightness;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        this.originalWindowBrightness = lp.screenBrightness;

        float override = Math.max(0.01f, Math.min(testScreenBrightness / 100f, 1f));
        lp.screenBrightness = override;
        this.requestedBrightness = override;

        // Pin the panel refresh rate for the run. Left alone this is an uncontrolled variable in a
        // battery comparison: a 120 Hz-default device pays a large standing display cost for 30 fps
        // content and scores worse for reasons unrelated to the decoder. Use preferredRefreshRate
        // rather than preferredDisplayModeId so only the rate is pinned — a mode id would pin
        // resolution too and could silently change it.
        int requestedHz = viewModel.getRunConfig().displayRefreshHz;
        if (requestedHz <= 0) {
            requestedHz = RunConfig.DEFAULT_REFRESH_HZ;
        }
        if (!DisplayInfo.supports(this, requestedHz)) {
            Log.w(TAG, "Display does not report " + requestedHz + " Hz; falling back to "
                    + RunConfig.DEFAULT_REFRESH_HZ + " Hz");
            requestedHz = RunConfig.DEFAULT_REFRESH_HZ;
        }
        lp.preferredRefreshRate = requestedHz;
        this.requestedRefreshHz = requestedHz;

        this.renderersFactory = getRendersFactory();

        this.playbackStateListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    onPlaybackStarted();
                } else if (state == Player.STATE_ENDED) {
                    logTelemetry(true);
                    if (shouldStopTesting()) {
                        stopTestAndCleanup();
                    } else {
                        advanceToNextClip();
                        startClipWithFreshPlayer();
                    }
                }
            }

            final RunConfig.VideoOrientation mode = FullScreenPlayerActivity.this.viewModel.getRunConfig().videoOrientation;

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Throwable cause = error.getCause();
                if (cause instanceof MediaCodecRenderer.DecoderInitializationException) {
                    MediaCodecRenderer.DecoderInitializationException die =
                            (MediaCodecRenderer.DecoderInitializationException) cause;

                    // public fields on that exception:
                    String decoderName           = die.codecInfo.name;       // e.g. "c2.unisoc.av1.decoder"
                    boolean secureDecoderRequired = die.secureDecoderRequired;

                    String msg = new StringBuilder()
                            .append("Failed to init decoder:\n")
                            .append("  name: ").append(decoderName).append("\n")
                            .append(die.getMessage())
                            .toString();

                    AlertDialog dlg = new AlertDialog.Builder(FullScreenPlayerActivity.this)
                            .setTitle("Decoder Initialization Error")
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    dlg.setOnDismissListener(d->stopTestAndCleanup());

                } else {
                    // fallback for any other playback error
                    AlertDialog dlg = new AlertDialog.Builder(FullScreenPlayerActivity.this)
                            .setTitle("Playback Error")
                            .setMessage(error.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    dlg.setOnDismissListener(d->stopTestAndCleanup());
                }
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                Log.i(TAG, "Video size Changed" + videoSize.width + "x" + videoSize.height);
                if (!orientationCommittedForClip && hasValidVideoSize(videoSize)) {
                    maybeApplyOrientationForClip(mode, videoSize);
                }
            }

            @Override
            public void onMediaItemTransition(@Nullable com.google.android.exoplayer2.MediaItem mediaItem, int reason) {
                orientationCommittedForClip = false;
                if (mode != RunConfig.VideoOrientation.MATCH_VIDEO) {
                    maybeApplyOrientationForClip(mode, /*vs=*/null);
                }
            }
        };

        this.analyticsListener = new AnalyticsListener() {

            // ---- High-signal lifecycle / state ----
            @Override
            public void onPlaybackStateChanged(EventTime et, int state) {
                long pos = exoPlayer == null ? -1 : exoPlayer.getCurrentPosition();
                Log.d(TAG, "state=" + stateName(state) + " pos=" + pos);
            }

            @Override
            public void onIsPlayingChanged(EventTime et, boolean isPlaying) {
                Log.d(TAG, "isPlaying=" + isPlaying);
            }

            @Override
            public void onPlayerError(EventTime et, PlaybackException error) {
                Log.e(TAG, "playerError code=" + error.errorCode + " pos=" + exoPlayer.getCurrentPosition(), error);
            }

            // ---- Decoder / format info ----
            @Override
            public void onVideoDecoderInitialized(
                    EventTime et, String decoderName, long initializationDurationMs, long initializationDelayMs) {
                Log.i(TAG, "Video decoder initialized: " + decoderName
                        + " initMs=" + initializationDurationMs + " delayMs=" + initializationDelayMs);
                FullScreenPlayerActivity.this.curDecoder = decoderName;
            }

            @Override
            public void onVideoInputFormatChanged(
                    EventTime et, Format format, @Nullable DecoderReuseEvaluation reuse) {
                Log.i(TAG, "format " + format.sampleMimeType + " "
                        + format.width + "x" + format.height + " @" + format.frameRate
                        + " color=" + format.colorInfo);
            }
            @Override
            public void onVideoEnabled(EventTime et, DecoderCounters counters) {
                videoCounters = counters;                    // keep reference to poll later
                // A fresh DecoderCounters starts at zero, so the delta baselines follow it.
                lastDroppedToKeyframe = 0;
                lastDroppedBuffers = 0;
                lastRenderedFrames = 0;
                lastDroppedToKeyframeEvents = 0;
                Log.d(TAG, "videoEnabled: counters attached");
            }

            @Override
            public void onVideoDisabled(EventTime et, DecoderCounters counters) {
                counters.ensureUpdated();
                Log.i(TAG, "videoDisabled rendered=" + counters.renderedOutputBufferCount
                        + " dropped=" + counters.droppedBufferCount
                        + " skipped=" + counters.skippedOutputBufferCount);
                // Only detach the counter we are actually holding. During a clip swap the
                // outgoing player's teardown can arrive after the incoming player's
                // onVideoEnabled, and an unconditional null would drop the live reference and
                // stop collecting dropped_to_keyframe for the rest of that clip.
                if (videoCounters == counters) {
                    videoCounters = null;
                }
            }

            // ---- First frame / output target ----
            @Override
            public void onRenderedFirstFrame(EventTime et, Object output, long renderTimeMs) {
                String out = (output == null) ? "null" : output.getClass().getSimpleName();
                Log.i(TAG, "firstFrame output=" + out + " t=" + renderTimeMs + "ms");
            }

            @Override
            public void onVideoSizeChanged(EventTime et, VideoSize size) {
                Log.d(TAG, "videoSize=" + size.width + "x" + size.height
                        + " pxAspect=" + size.pixelWidthHeightRatio);
            }

            @Override
            public void onDroppedVideoFrames(EventTime et, int droppedFrameCount, long elapsedMs) {
                Log.w(TAG, "Dropped " + droppedFrameCount + " frames in " + elapsedMs + "ms");
                FullScreenPlayerActivity.this.fd.elapsedMs += elapsedMs;
                FullScreenPlayerActivity.this.fd.frameDrops += droppedFrameCount;
            }
        };
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_player);
        frameNumberOverlay = findViewById(R.id.frameNumberOverlay);
        initialize();

        startThermalMonitoring();

        playerView = findViewById(R.id.playerView);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Lookups for the overlay controls that live OUTSIDE the PlayerView
        LinearLayout buttonRow   = findViewById(R.id.buttonRow);
        View stopButton          = findViewById(R.id.stopButton);
        View infoButton          = findViewById(R.id.toggleVideoInfo);
        this.videoOverlay        = findViewById(R.id.videoOverlay);

        // Ensure the overlay is above the video surface and clickable
        buttonRow.bringToFront();
        buttonRow.setClickable(true);
        buttonRow.setFocusable(true);

        // Make PlayerView controller behavior explicit
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setControllerHideOnTouch(true);
        // Don't auto-reveal the controller when playback starts / each playlist item begins;
        // it should only appear on a screen touch.
        playerView.setControllerAutoShow(false);

        // Keep the overlay in lockstep with the controller visibility
        playerView.setControllerVisibilityListener(new PlayerControlView.VisibilityListener() {
            @Override
            public void onVisibilityChange(int visibility) {
                buttonRow.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE);
            }
        });

        // Start with the controller (and the synced overlay) hidden; a screen touch reveals it.
        playerView.hideController();

        // Wire button actions
        stopButton.setOnClickListener(v -> stopTestAndCleanup());
        infoButton.setOnClickListener(v -> {
            onToggleVideoInfo();
        });

        // setup telemetry file. On Resume, append to the prior run's CSV (no new header block);
        // otherwise create a fresh vcatd_log_<start>.csv with header rows.
        ResumeInfo resume = viewModel.resumeInfo;
        // Confirm the Resume targets the same playlist the prior run used, by identity. This
        // compared display names before playlists carried ids, and a bare name could neither
        // survive a rename nor distinguish same-named playlists in different folders.
        String playlistId = viewModel.curTestDetails.getPlaylistId();
        boolean resuming = resume != null && resume != ResumeInfo.empty
                && !PlaylistIdentity.isNilOrEmpty(playlistId)
                && playlistId.equals(resume.playlistId);
        // Effective start (set by TestStatus.startTest): "now" for Play, originalStart+gap for Resume.
        long startTime = viewModel.curTestDetails.getStartTimeAsEpoch();
        String telemetryFileName = resuming
                ? UriUtils.fileNameFromURI(resume.logFileUri)
                : "vcatd_log_" + startTime + ".csv";

        // On Resume, shift logged timestamps back by the stopped gap so a 1h run + 5h stopped + 1h
        // run reads as 2h of elapsed test time, not 7h.
        this.tl = new TelemetryLogger(this, telemetryFileName,
                resuming ? resume.logTimestampOffset : 0L);

        if (!resuming) {
            this.tl.writeHeaderRows(this, playlistId, viewModel.curTestDetails.getPlaylistFileName(),
                    this.viewModel.getRunConfig(), startTime);
            this.tl.writeCsvHeader();
        }
        // Consume the one-shot resume request so a subsequent normal Play doesn't re-resume.
        viewModel.resumeInfo = ResumeInfo.empty;

        testClips = XspfParser.parsePlaylist(this, Uri.parse(viewModel.curTestDetails.getPlaylist()));
        for (int i = 0; i < testClips.size(); i++) {
            testClips.set(i, UriUtils.resolveMediaUri(this, testClips.get(i)));
        }
        curFileIndex = 0;
        totalIterations = 0;
        iterationStartMs = System.currentTimeMillis();
        if (testClips.isEmpty()) {
            finish();  // nothing to play
            return;
        }

        if (curFileIndex < 0) curFileIndex = 0;
        startClipWithFreshPlayer();
    }

    private static String rendererTypeName(int t) {
        switch (t) {
            case com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO: return "VIDEO";
            case com.google.android.exoplayer2.C.TRACK_TYPE_AUDIO: return "AUDIO";
            case com.google.android.exoplayer2.C.TRACK_TYPE_TEXT: return "TEXT";
            case com.google.android.exoplayer2.C.TRACK_TYPE_METADATA: return "METADATA";
            case com.google.android.exoplayer2.C.TRACK_TYPE_CAMERA_MOTION: return "CAMERA_MOTION";
            case com.google.android.exoplayer2.C.TRACK_TYPE_NONE: return "NONE";
            default: return "UNKNOWN(" + t + ")";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopThermalMonitoring();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // Put this near your other private helpers
    private void startClipWithFreshPlayer() {

        // New file: restart its clock, and clear the drop counters so nothing from the previous
        // clip leaks into this one's first row. Both counters are reported per interval; this is
        // a boundary guard, not accumulation. It also re-bases dropped_to_keyframe, whose source
        // counter restarts at zero when the renderer is re-enabled and would otherwise go
        // negative on the first row of the new file.
        //
        // Detaching the counter is part of that re-basing, not a tidy-up. The outgoing player is
        // released after the swap, so its DecoderCounters stays alive and keeps its cumulative
        // droppedInputBufferCount for a while yet. Zeroing the baseline while still holding that
        // reference let the 2 s sampler read the old total against a baseline of zero and book
        // the whole previous file's drops a second time -- observed as spikes that matched the
        // file's own reported total exactly (6047 = 4559 + 1488). Nulling it here makes the
        // sampler skip until onVideoEnabled attaches the new player's counter.
        this.fileStartMs = System.currentTimeMillis();
        this.fd.reset();
        this.pendingFrameDrops = 0;
        this.pendingDroppedToKeyframe = 0;
        this.pendingFramesRendered = 0;
        this.pendingDroppedToKeyframeEvents = 0;
        this.lastDroppedToKeyframe = 0;
        this.lastDroppedBuffers = 0;
        this.lastRenderedFrames = 0;
        this.lastDroppedToKeyframeEvents = 0;
        this.videoCounters = null;

        orientationCommittedForClip = false;
        final RunConfig.VideoOrientation mode = viewModel.getRunConfig().videoOrientation;

        // Keep a handle to the old player so we can release it after swap
        ExoPlayer old = exoPlayer;

        // Detach and silence the outgoing player *before* the new one is wired up. It used to
        // keep its analytics listener until after the swap, so for the rest of this method it
        // was still playing, still reporting, and -- once setPlayer() moved the surface -- doing
        // so with no output while a second decoder spun up on the same cores. Every drop it
        // recorded in that window was credited to the incoming clip's first row. Pausing it
        // first also stops two 1080p decoders competing during the handover.
        if (old != null) {
            old.setPlayWhenReady(false);
            old.removeListener(this.playbackStateListener);
            if (this.analyticsListener != null) old.removeAnalyticsListener(this.analyticsListener);
        }


        // Build a new player using your existing RenderersFactory (dav1d, etc.)
        ExoPlayer newPlayer =
                new ExoPlayer.Builder(this, renderersFactory)
                        .setMediaSourceFactory(
                                new DefaultMediaSourceFactory(
                                        this,
                                        () -> new Extractor[] { new VcatIvfExtractor(), new VcatMp4Extractor(/* flags= */ 0) } // IVF (DKIF) first, then MP4
                                )
                        )
                        .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                        .build();        newPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

        // Frame-number overlay. Driven from the renderer rather than a timer: this fires on the
        // playback thread immediately before each frame is released to the surface, so the number
        // names the frame being presented. Works for every decoder, hardware included, which an
        // overlay drawn inside a decoder plugin could not.
        if (viewModel.getRunConfig().showFrameNumber && frameNumberOverlay != null) {
            frameOverlayFirstPtsUs = Long.MIN_VALUE;
            frameOverlayStepUs = 0;
            frameOverlayLastPtsUs = Long.MIN_VALUE;
            frameNumberOverlay.setVisibility(View.VISIBLE);
            newPlayer.setVideoFrameMetadataListener(
                    (presentationTimeUs, releaseTimeNs, fmt, mediaFormat) -> {
                        // Index from the presentation time, not a render count: a render count
                        // stays contiguous across dropped frames and so misnames every frame
                        // after the first drop. Cadence is the first forward gap observed.
                        if (frameOverlayFirstPtsUs == Long.MIN_VALUE) {
                            frameOverlayFirstPtsUs = presentationTimeUs;
                        } else if (frameOverlayStepUs == 0
                                && presentationTimeUs > frameOverlayLastPtsUs) {
                            frameOverlayStepUs = presentationTimeUs - frameOverlayLastPtsUs;
                        }
                        frameOverlayLastPtsUs = presentationTimeUs;
                        final long idx = frameOverlayStepUs > 0
                                ? Math.round((presentationTimeUs - frameOverlayFirstPtsUs)
                                        / (double) frameOverlayStepUs)
                                : 0L;
                        frameNumberOverlay.post(
                                () -> frameNumberOverlay.setText(Long.toString(idx)));
                    });
        } else if (frameNumberOverlay != null) {
            frameNumberOverlay.setVisibility(View.GONE);
        }

        // Always silent. The injected track is zeros anyway, so this is belt-and-braces: it keeps
        // playback inaudible if a container's own audio track is ever selected. Muting does not
        // stop the AudioTrack, so the media clock is unaffected.
        newPlayer.setVolume(0f);

        // Wire listeners you already use
        newPlayer.addListener(this.playbackStateListener);
        if (this.analyticsListener != null) {
            newPlayer.addAnalyticsListener(this.analyticsListener);
        }

        hb.removeCallbacks(heartbeat);
        hb.post(heartbeat);

        if (mode != RunConfig.VideoOrientation.MATCH_VIDEO) {
            maybeApplyOrientationForClip(mode, /*vs=*/null);
        }

        // Attach it to the PlayerView
        playerView.setPlayer(newPlayer);

        // Load & play the current clip
        Uri clip = this.testClips.get(this.curFileIndex);
        newPlayer.setMediaItem(MediaItem.fromUri(clip));
        newPlayer.prepare();
        newPlayer.play();

        // preferredRefreshRate is advisory — the system can refuse it, and LTPO panels move on
        // their own — so record what was actually achieved rather than what was asked for. Delayed
        // because the mode switch does not take effect synchronously with the request.
        if (!refreshRateLogged) {
            refreshRateLogged = true;
            // Delayed because preferredRefreshRate does not take effect synchronously with the
            // request; the panel needs a few frames to switch mode.
            playerView.postDelayed(this::verifyTestConditions, 3000);
        }

        // Make the new player current
        exoPlayer = newPlayer;

        int videoRenderers = 0;
        for (int i = 0; i < exoPlayer.getRendererCount(); i++) {
            int type = exoPlayer.getRendererType(i);
            Log.d(TAG, "renderer[" + i + "] type=" + rendererTypeName(type));
            if (type == com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO) videoRenderers++;
        }
        Log.d(TAG, "videoRendererCount=" + videoRenderers);

        // Release the old one after we've fully switched the view. Its listeners were already
        // detached above, so nothing it does on the way down reaches this clip's telemetry.
        if (old != null) {
            old.clearMediaItems();
            old.release();
        }
    }
    private void playCurClip() {
        Uri clip = this.testClips.get(this.curFileIndex);
        exoPlayer.setMediaItem(MediaItem.fromUri(clip));
        exoPlayer.prepare();
        exoPlayer.play();
    }


    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                // omit the LAYOUT flags if you don’t need content under the bars
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override protected void onStart() {
        super.onStart();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStop() {
        super.onStop();

        super.onStop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // restore screen brightness if set
        if(this.originalWindowBrightness > 0){
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = this.originalWindowBrightness;
            getWindow().setAttributes(lp);
        }

        if (isFinishing()) {
            if (exoPlayer != null) {
                exoPlayer.release();
                exoPlayer = null;
            }
            onPlaybackStopped();
        }
    }

    private final Handler telemetryHandler = new Handler(Looper.getMainLooper());
    private final Runnable telemetryRunnable = new Runnable() {
        @Override
        public void run() {
            long now     = System.currentTimeMillis();
            long startTs = viewModel.curTestDetails.getStartTimeAsEpoch();
            long elapsed = now - startTs;

            // 1) log your telemetry (you already have the method)
            logTelemetry(false);

            // 2) compute next delay
            long rampDuration = 60 * 60 * 1000L;  // 1 hour
            long minDelay    = 30 * 1000L;         //  30 seconds
            long maxDelay    = 1  * 60 * 1000L;    //  1 minute

            float ratio = Math.min(1f, (float) elapsed / rampDuration);
            long delay = minDelay + (long) ((maxDelay - minDelay) * ratio);

            // 3) schedule again
            telemetryHandler.postDelayed(this, delay);
        }
    };

    private void startTelemetryTimer() {
        // Drop any previous scheduling first. onPlaybackStarted() calls this at every clip start,
        // so without this each clip left another self-rescheduling runnable behind: rows got more
        // frequent the longer a run went on, and a looping playlist accumulated one timer per
        // playthrough.
        telemetryHandler.removeCallbacks(telemetryRunnable);
        // kick off immediately
        telemetryHandler.post(telemetryRunnable);
        dropSamplerHandler.removeCallbacks(dropSamplerRunnable);
        dropSamplerHandler.postDelayed(dropSamplerRunnable, DROP_SAMPLE_INTERVAL_MS);
    }

    private void stopTelemetryTimer() {
        telemetryHandler.removeCallbacks(telemetryRunnable);
        dropSamplerHandler.removeCallbacks(dropSamplerRunnable);
    }

    // call this after exoPlayer.play()
    private void onPlaybackStarted() {
        startTelemetryTimer();
    }

    // call this when playback ends or in onStop()
    private void onPlaybackStopped() {

    }

    /**
     * Plugin-SPI version reported by the decoder plugin registered for {@code mime}, so the
     * overlay can show whether the active decoder is on the new {@code VcatDecoder} SPI
     * ("0.1.0") or the legacy {@code VcatDecoderPlugin} SPI ("0.1"). Hardware-only codecs
     * (no registered plugin) show "n/a".
     */
    private String pluginApiForMime(String mime) {
        com.roncatech.vcat.decoder_plugin.VcatDecoderManager mgr =
                com.roncatech.vcat.decoder_plugin.VcatDecoderManager.getInstance();
        com.roncatech.vcat.decoder_plugin_api.VcatDecoder d = null;
        // Mirror StrictRenderersFactoryV2's selection so the overlay reflects the decoder actually
        // used: a user-selected decoder id wins; otherwise the first plugin registered for the MIME
        // (which is also what ExoPlayer picks). Avoids reporting the wrong plugin when several
        // register for the same MIME (e.g. vvdec + damo266d both handle video/vvc).
        String selectedId = this.viewModel.getRunConfig().decoderCfg.getDecoder(mime);
        if (selectedId != null && !selectedId.isEmpty()) {
            d = mgr.getDecoder(selectedId);
        }
        if (d == null) {
            java.util.List<com.roncatech.vcat.decoder_plugin_api.VcatDecoder> ds =
                    mgr.getDecodersForMimeType(mime);
            if (!ds.isEmpty()) d = ds.get(0);
        }
        return d == null ? "n/a (hardware)" : d.getPluginApiVersion();
    }

    private void logTelemetry(boolean endOfFile) {

        TelemetryLogger.VideoInfo vi = getTlVideoInfo(this.testClips.get(this.curFileIndex), this.viewModel.curTestDetails);

        // Both counts come from the 2 s sampler's store, not from the player: by now the clips
        // they were recorded on may have been torn down. One last sample folds in anything since
        // the previous tick, then the totals are taken and cleared, so each row reports its own
        // interval and a stall reads as a spike rather than raising every row after it.
        sampleDrops();
        int frameDrops = this.pendingFrameDrops;
        this.runTotalFrameDrops += frameDrops;
        int droppedToKeyframe = this.pendingDroppedToKeyframe;
        int framesRendered = this.pendingFramesRendered;
        int d2kEvents = this.pendingDroppedToKeyframeEvents;
        int thermalMax = this.pendingThermalStatusMax;
        float headroom = this.pendingThermalHeadroom;
        float refreshMin = this.pendingRefreshMin;
        float refreshMax = this.pendingRefreshMax;

        Double[] freqMean = null, freqRms = null;
        if (this.freqSamples > 0 && this.freqSum != null) {
            freqMean = new Double[this.freqSum.length];
            freqRms  = new Double[this.freqSum.length];
            for (int i = 0; i < this.freqSum.length; i++) {
                freqMean[i] = this.freqSum[i] / this.freqSamples;
                freqRms[i]  = Math.sqrt(this.freqSumSq[i] / this.freqSamples);
            }
        }
        ensureClusterArrays();
        int[] clFreqMax   = this.clusterMaxThisRow.clone();
        int[] clScalingMax = clusterScalingMax();
        ClusterResidency[] res = sampleResidency();
        // Drop rate for this interval, used to qualify a low ceiling.
        double dropPct = (framesRendered + frameDrops) > 0
                ? 100.0 * frameDrops / (framesRendered + frameDrops) : 0.0;
        int[] clThrottled  = clusterThrottled(clScalingMax, res, dropPct);
        double[] clFRms    = new double[res.length];
        int[]    clFMaxUsed = new int[res.length];
        double[] clBusyPct = new double[res.length];
        for (int k = 0; k < res.length; k++) {
            clFRms[k] = res[k].fRms; clFMaxUsed[k] = res[k].fMaxUsed; clBusyPct[k] = res[k].busyPct;
        }
        this.pendingFrameDrops = 0;
        this.pendingDroppedToKeyframe = 0;
        this.pendingFramesRendered = 0;
        this.pendingDroppedToKeyframeEvents = 0;
        // Re-base to the current status rather than zero: a sustained throttle must keep showing
        // on every row it spans, not just the one where it started.
        PowerManager pmNow = (PowerManager) getSystemService(Context.POWER_SERVICE);
        this.pendingThermalStatusMax = pmNow != null ? pmNow.getCurrentThermalStatus() : 0;
        this.pendingThermalHeadroom = Float.NaN;
        this.pendingRefreshMin = 0f;
        this.pendingRefreshMax = 0f;
        if (this.freqSum != null) {
            java.util.Arrays.fill(this.freqSum, 0.0);
            java.util.Arrays.fill(this.freqSumSq, 0.0);
        }
        this.freqSamples = 0;
        // Per-interval, like the drop counters: the cluster peak describes this row, not the run.
        if (this.clusterMaxThisRow != null) {
            java.util.Arrays.fill(this.clusterMaxThisRow, 0);
        }

        this.tl.logTelemetryRow(this, this.viewModel.curTestDetails.getStartTimeAsEpoch(),
                this.iterationStartMs, this.fileStartMs, this.totalIterations, vi,
                frameDrops, droppedToKeyframe, framesRendered, d2kEvents,
                thermalMax, headroom, refreshMin, refreshMax, freqMean, freqRms,
                clFreqMax, clScalingMax, clThrottled, clFRms, clFMaxUsed, clBusyPct,
                false, endOfFile);
        this.lastTelemetryRowMs = System.currentTimeMillis();

        if(!this.viewModel.curTestDetails.getCurrentTestVideo().getFileName().equals(vi.fileName)){
            // update test details
            this.viewModel.curTestDetails.setCurrentTestVideo(new TestStatus.CurrentTestVideo(vi.fileName,
                    vi.codec,
                    vi.decoderName,
                    vi.width + "x" + vi.height,
                    vi.mimeType,
                    vi.bitrate,
                    vi.fps));
        }

        // Track battery temp (°C) and system thermal status each tick, keeping the session max,
        // so the overlay's cur:max reflects the whole run regardless of when it's opened.
        if (runStartBatteryPct < 0) {
            runStartBatteryPct = (int) BatteryInfo.getBatteryLevel(this);
            runStartBatteryMs = System.currentTimeMillis();
        }

        curBatteryTempC = BatteryInfo.getBatteryTemperatureC(this);
        if (curBatteryTempC > maxBatteryTempC) maxBatteryTempC = curBatteryTempC;
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        curSysThermal = (pm != null) ? pm.getCurrentThermalStatus() : 0;
        if (curSysThermal > maxSysThermal) maxSysThermal = curSysThermal;

        // if video overlay is vidible, populate
        if (this.videoOverlay != null && this.videoOverlay.getVisibility() == View.VISIBLE) {
            this.videoOverlay.setText(String.format(Locale.US,
                    "Path: %s\n" +
                            "Resolution: %sx%s\n" +
                            "MIME Type: %s\n" +
                            "Bitrate: %s\n" +
                            "Codec: %s\n" +
                            "Decoder: %s\n" +
                            "Plugin API: %s\n" +
                            "Framerate: %.2f fps\n"+
                            "Display: %d%% %dHz (%.1fHz)\n" +
                            "Threads: %d\n" +
                            "Run Mode: %s\n" +
                            "Battery: %d%% %.1f:%.1f\n"+
                            "System Thermal Status: %d:%d\n"+
                            "Framedrops/sec: %.2f (%,d total)\n"+
                            "Run Dur: %s (est %s)",
                    UriUtils.fileNameFromURI(vi.fileName),
                    vi.width,
                    vi.height,
                    vi.mimeType,
                    vi.bitrate,
                    vi.codec,
                    vi.decoderName,
                    pluginApiForMime(vi.mimeType),
                    vi.fps,
                    this.viewModel.getRunConfig().screenBrightness,
                    // Configured first, for consistency with the other overlay lines, which all
                    // report run conditions. Achieved rate in parentheses because
                    // preferredRefreshRate is advisory and LTPO panels move on their own — when
                    // the two disagree, the run is not the run that was asked for.
                    this.viewModel.getRunConfig().displayRefreshHz,
                    DisplayInfo.currentRefreshRate(this),
                    // A run condition, not a decoder property, and one that materially changes
                    // the result: on CPH2625 raising it from 2 to 4 measured *worse*, because low
                    // delay confines the pool to tile parallelism and the extra threads land on
                    // the little cluster. Worth seeing on screen next to the decoder it applies to.
                    this.viewModel.getRunConfig().threads,
                    this.viewModel.getRunConfig().runModeStr(),
                    (int) BatteryInfo.getBatteryLevel(this),
                    curBatteryTempC, maxBatteryTempC,
                    curSysThermal, maxSysThermal,
                    runFrameDropsPerSec(), this.runTotalFrameDrops,
                    this.viewModel.curTestDetails.getElapsedSinceStartHms(),
                    estimateTimeRemaining()
            ));
        }
    }

    /**
     * Frames dropped per second over the whole run so far.
     *
     * <p>Zero until a second has elapsed, rather than dividing by a near-zero interval and
     * reporting a rate in the thousands for the first tick of every run.
     */
    private double runFrameDropsPerSec() {
        long startEpoch = this.viewModel.curTestDetails.getStartTimeAsEpoch();
        if (startEpoch < 1_000_000_000_000L) startEpoch *= 1000L;   // tolerate seconds
        long elapsedMs = System.currentTimeMillis() - startEpoch;
        if (elapsedMs < 1000L) return 0.0;
        return this.runTotalFrameDrops * 1000.0 / elapsedMs;
    }

    /** Formats a duration as hh:mm, clamped at zero. */
    private static String asHhMm(long ms) {
        if (ms < 0) ms = 0;
        long totalMin = ms / 60_000L;
        return String.format(Locale.US, "%02d:%02d", totalMin / 60L, totalMin % 60L);
    }

    /**
     * Estimated time until the run's stop condition is met, for the overlay.
     *
     * <p>{@code N/A} for a single pass, where completion depends on playlist length rather than
     * elapsed time. {@code --:--} while a battery estimate has no basis yet — the level has not
     * moved a whole percent since the run began, so there is no rate to extrapolate.
     */
    private String estimateTimeRemaining() {
        RunConfig rc = this.viewModel.getRunConfig();
        long startEpoch = this.viewModel.curTestDetails.getStartTimeAsEpoch();
        if (startEpoch < 1_000_000_000_000L) startEpoch *= 1000L;   // tolerate seconds
        long nowMs = System.currentTimeMillis();

        switch (rc.runMode) {
            case ONCE:
                return "N/A";
            case TIME:
                return asHhMm(rc.runLimit * 60_000L - (nowMs - startEpoch));
            case BATTERY: {
                int cur = (int) BatteryInfo.getBatteryLevel(this);
                int drained = runStartBatteryPct - cur;
                if (runStartBatteryPct < 0 || drained <= 0) {
                    return "--:--";
                }
                int remainingPct = cur - rc.runLimit;
                if (remainingPct <= 0) {
                    return asHhMm(0);
                }
                long elapsedMs = nowMs - runStartBatteryMs;
                return asHhMm((long) (remainingPct * (elapsedMs / (double) drained)));
            }
            default:
                return "--:--";
        }
    }

    private int getVideoBitrate(){
        int bitrate = com.google.android.exoplayer2.Format.NO_VALUE;

        com.google.android.exoplayer2.trackselection.TrackSelectionArray sels =
                exoPlayer.getCurrentTrackSelections();

        // find the video renderer index
        int vidIdx = -1;
        for (int i = 0; i < exoPlayer.getRendererCount(); i++) {
            if (exoPlayer.getRendererType(i) == com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO) {
                vidIdx = i; break;
            }
        }

        if (vidIdx >= 0) {
            com.google.android.exoplayer2.trackselection.TrackSelection sel = sels.get(vidIdx);
            if (sel != null && sel.length() > 0) {
                com.google.android.exoplayer2.Format f = sel.getFormat(0); // fallback heuristic
                if (f != null) {
                    bitrate = f.bitrate;
                }
            }
        }

        // Fall back to measuring it. Containers rarely carry an average-bitrate field -- VVC in
        // MP4 reports NO_VALUE here just as AV2 in IVF did -- and the overlay and the telemetry
        // column both read -1 as a result. File size over media duration is the same figure the
        // bitrate field would hold, and is exact for a single-track file.
        if (bitrate == com.google.android.exoplayer2.Format.NO_VALUE) {
            bitrate = measuredBitrate();
        }

        return bitrate;
    }

    /**
     * Average bitrate from source length and media duration, or {@code NO_VALUE}.
     *
     * <p>Only meaningful once the duration is known, so it reports nothing while the player is
     * still preparing rather than dividing by {@code TIME_UNSET}.
     */
    private int measuredBitrate() {
        if (this.exoPlayer == null
                || this.testClips == null
                || this.curFileIndex < 0
                || this.curFileIndex >= this.testClips.size()) {
            return com.google.android.exoplayer2.Format.NO_VALUE;
        }
        Uri uri = this.testClips.get(this.curFileIndex);
        if (uri == null) return com.google.android.exoplayer2.Format.NO_VALUE;
        long durMs = this.exoPlayer.getDuration();
        if (durMs == com.google.android.exoplayer2.C.TIME_UNSET || durMs <= 0) {
            return com.google.android.exoplayer2.Format.NO_VALUE;
        }
        androidx.documentfile.provider.DocumentFile f =
                androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri);
        long bytes = (f != null) ? f.length() : 0L;
        if (bytes <= 0) return com.google.android.exoplayer2.Format.NO_VALUE;
        double bps = bytes * 8.0 * 1000.0 / durMs;
        return (bps > 0 && bps <= Integer.MAX_VALUE)
                ? (int) Math.round(bps) : com.google.android.exoplayer2.Format.NO_VALUE;
    }

    public TelemetryLogger.VideoInfo getTlVideoInfo(
            Uri videoFileUri,
            TestStatus testStatus) {

        if(this.exoPlayer == null) {
            return lastKnownVideoInfo();
        }
        // Grab the video track’s Format from ExoPlayer
        Format fmt = exoPlayer.getVideoFormat();
        if (fmt == null) {
            // No video track info available yet
            return lastKnownVideoInfo();
        }


        // Width & height
        String width  = String.valueOf(fmt.width);
        String height = String.valueOf(fmt.height);

        // Bitrate → human-readable string
        // (assumes formatBitrate takes a String; adjust if you have a long overload)
        String bitrate = formatBitrate(String.valueOf(getVideoBitrate()));

        // Mime type and codec name
        String mime  = fmt.sampleMimeType;                     // e.g. "video/av1"
        String codec = mimeTypeToCodecName(mime);              // your mapping helper

        // Frame rate (may be <= 0 if unspecified in the container)
        float fps = fmt.frameRate > 0 ? fmt.frameRate : -1f;

        // Log the display name, not the raw URI: a SAF content:// URI is an opaque, percent-encoded
        // document id that makes test.filename unreadable in the results CSV and the overlay.
        TelemetryLogger.VideoInfo vi = new TelemetryLogger.VideoInfo(
                /* path     = */ UriUtils.fileNameFromURI(videoFileUri.toString()),
                /* width    = */ width,
                /* height   = */ height,
                /* mimeType = */ mime,
                /* bitrate  = */ bitrate,
                /* codec    = */ codec,
                /* decoder  = */ this.curDecoder,    // or whatever decoder name you track
                /* fps      = */ fps
        );

        this.lastVideoInfo = vi;
        return vi;
    }

    /**
     * The identity of the clip last seen, or {@code empty} before any has been resolved.
     *
     * <p>Only the opening rows of a run can legitimately be "empty" -- before the first Format
     * arrives there is genuinely nothing to report.
     */
    private TelemetryLogger.VideoInfo lastKnownVideoInfo() {
        return (this.lastVideoInfo != null)
                ? this.lastVideoInfo : TelemetryLogger.VideoInfo.empty;
    }

    public static String formatBitrate(String bitrateStr) {
        if (bitrateStr == null || bitrateStr.isEmpty()) {
            return "Unknown";
        }
        long bitrate;
        try {
            bitrate = Long.parseLong(bitrateStr);
        } catch (NumberFormatException e) {
            return "Unknown";
        }

        if (bitrate >= 1_000_000L) {
            // Mbps
            return String.format(Locale.US, "%.2f Mbps", bitrate / 1_000_000.0);
        } else if (bitrate >= 1_000L) {
            // Kbps
            return String.format(Locale.US, "%.2f Kbps", bitrate / 1_000.0);
        } else {
            // bps
            return bitrate + " bps";
        }
    }

    /**
     * Friendly name for a video MIME type, falling back to the raw type.
     *
     * <p>{@code video/av01} deliberately has no entry. It used to map to "AV1", which made dav1d
     * the odd one out in telemetry: every other software decoder reports its raw MIME type
     * ({@code video/av02}, {@code video/vvc}) because no mapping exists for it, so
     * {@code video.codec_name} read "AV1" for one decoder and a MIME type for the rest. Letting
     * AV1 fall through makes the column uniform across the plugins and keeps it machine-readable.
     */
    public static String mimeTypeToCodecName(String mimeType) {
        if (mimeType == null) return "Unknown";
        switch (mimeType) {
            case "video/avc":
                return "H.264";
            case "video/hevc":
                return "H.265";
            case "video/x-vnd.on2.vp8":
                return "VP8";
            case "video/x-vnd.on2.vp9":
                return "VP9";
            case "video/mpeg4":
            case "video/mp4v-es":
                return "MPEG-4 Part 2";
            case "video/3gpp":
                return "H.263";
            case "video/x-ms-wmv":
                return "WMV";
            default:
                // fallback to the raw mime type if you like
                return mimeType;
        }
    }

    // screen orientation
    private void maybeApplyOrientationForClip(RunConfig.VideoOrientation mode, @Nullable VideoSize vs) {
        switch (mode) {
            case MATCH_DEVICE: {
                setRequestedOrientation(SCREEN_ORIENTATION_FULL_SENSOR);
                orientationCommittedForClip = true;
                return;
            }
            case VERTICAL: {
                setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                orientationCommittedForClip = true;
                return;
            }
            case HORIZONTAL: {
                setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                orientationCommittedForClip = true;
                return;
            }
            case MATCH_VIDEO:
            default: {
                if (!hasValidVideoSize(vs)) return; // don't commit on 0×0
                int w = vs.width, h = vs.height;
                if ((vs.unappliedRotationDegrees % 180) == 90) { int t = w; w = h; h = t; }
                boolean portrait = h >= w;
                setRequestedOrientation(portrait
                        ? SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        : SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                orientationCommittedForClip = true;
            }
        }
    }

}


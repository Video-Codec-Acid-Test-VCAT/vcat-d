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
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
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

    /** Folds everything the player has recorded since the last call into the pending totals. */
    private void sampleDrops() {
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
        this.lastDroppedToKeyframe = 0;
        this.lastDroppedBuffers = 0;
        this.lastRenderedFrames = 0;
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
            playerView.postDelayed(() -> {
                float achieved = DisplayInfo.currentRefreshRate(this);
                Log.i(TAG, "Display refresh: requested " + requestedRefreshHz
                        + " Hz, achieved " + achieved + " Hz");
            }, 3000);
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
            hb.removeCallbacks(heartbeat);
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
            long maxDelay    = 5  * 60 * 1000L;    //  5 minutes

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
        int droppedToKeyframe = this.pendingDroppedToKeyframe;
        int framesRendered = this.pendingFramesRendered;
        this.pendingFrameDrops = 0;
        this.pendingDroppedToKeyframe = 0;
        this.pendingFramesRendered = 0;

        this.tl.logTelemetryRow(this, this.viewModel.curTestDetails.getStartTimeAsEpoch(),
                this.iterationStartMs, this.fileStartMs, this.totalIterations, vi,
                frameDrops, droppedToKeyframe, framesRendered, false, endOfFile);
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
                            "Run Mode: %s\n" +
                            "Battery: %d%% %.1f:%.1f\n"+
                            "System Thermal Status: %d:%d\n"+
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
                    this.viewModel.getRunConfig().runModeStr(),
                    (int) BatteryInfo.getBatteryLevel(this),
                    curBatteryTempC, maxBatteryTempC,
                    curSysThermal, maxSysThermal,
                    this.viewModel.curTestDetails.getElapsedSinceStartHms(),
                    estimateTimeRemaining()
            ));
        }
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

        return bitrate;
    }

    public TelemetryLogger.VideoInfo getTlVideoInfo(
            Uri videoFileUri,
            TestStatus testStatus) {

        if(this.exoPlayer == null) {
            return TelemetryLogger.VideoInfo.empty;
        }
        // Grab the video track’s Format from ExoPlayer
        Format fmt = exoPlayer.getVideoFormat();
        if (fmt == null) {
            // No video track info available yet
            return TelemetryLogger.VideoInfo.empty;
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

        return vi;
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

    public static String mimeTypeToCodecName(String mimeType) {
        if (mimeType == null) return "Unknown";
        switch (mimeType) {
            case "video/avc":
                return "H.264";
            case "video/hevc":
                return "H.265";
            case "video/av01":
                return "AV1";
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


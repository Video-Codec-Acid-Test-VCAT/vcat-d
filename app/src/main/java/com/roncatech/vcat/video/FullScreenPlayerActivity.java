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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import com.roncatech.vcat.models.SharedViewModel;
import com.roncatech.vcat.service.PlayerCommandBus;
import com.roncatech.vcat.telemetry.TelemetryLogger;
import com.roncatech.vcat.tools.BatteryInfo;
import com.roncatech.vcat.tools.UriUtils;
import com.roncatech.vcat.tools.VideoDecoderEnumerator;
import com.roncatech.vcat.tools.XspfParser;
import com.roncatech.vcat.R;
import com.roncatech.libvcat.extractor.mp4.VcatMp4Extractor;

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

    private TelemetryLogger tl;

    @Nullable private DecoderCounters videoCounters = null;
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
            this.curFileIndex = 0;
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

        // calculate size (fall back if height is not laid out yet)
        TelemetryLogger.VideoInfo vi = getTlVideoInfo(this.testClips.get(this.curFileIndex), this.viewModel.curTestDetails);
        int displayHeight = playerView.getHeight();
        if (displayHeight == 0) {
            displayHeight = playerView.getMeasuredHeight();
            if (displayHeight == 0) {
                // final fallback: just use MATCH_PARENT width if we can't compute yet
                displayHeight = ViewGroup.LayoutParams.MATCH_PARENT;
            }
        }
        double videoAspectRatio = -1;

        if(vi.width != null && !vi.width.isEmpty() && vi.height != null && !vi.height.isEmpty()){
            videoAspectRatio = Double.parseDouble(vi.width) / Double.parseDouble(vi.height);
        }
        int scaledVideoWidth = (displayHeight == ViewGroup.LayoutParams.MATCH_PARENT)
                ? ViewGroup.LayoutParams.MATCH_PARENT
                : (int) (displayHeight * videoAspectRatio);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                scaledVideoWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        videoOverlay.setLayoutParams(params);

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
                Log.d(TAG, "videoEnabled: counters attached");
            }

            @Override
            public void onVideoDisabled(EventTime et, DecoderCounters counters) {
                counters.ensureUpdated();
                Log.i(TAG, "videoDisabled rendered=" + counters.renderedOutputBufferCount
                        + " dropped=" + counters.droppedBufferCount
                        + " skipped=" + counters.skippedOutputBufferCount);
                videoCounters = null;
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

        // Keep the overlay in lockstep with the controller visibility
        playerView.setControllerVisibilityListener(new PlayerControlView.VisibilityListener() {
            @Override
            public void onVisibilityChange(int visibility) {
                buttonRow.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE);
            }
        });

        // Optional: start with controller visible so buttons appear initially
        playerView.showController();

        // Wire button actions
        stopButton.setOnClickListener(v -> stopTestAndCleanup());
        infoButton.setOnClickListener(v -> {
            onToggleVideoInfo();
        });

        // setup telemetry file
        long startTime = System.currentTimeMillis();
        String telemetryFileName = "logs_" + startTime + ".csv";

        this.tl = new TelemetryLogger(telemetryFileName);

        this.tl.writeHeaderRows(this, viewModel.curTestDetails.getPlaylistFileName(), this.viewModel.getRunConfig(), startTime);
        this.tl.writeCsvHeader();

        testClips  = XspfParser.parsePlaylist(this, Uri.parse(viewModel.curTestDetails.getPlaylist()));
        curFileIndex = 0;
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

        orientationCommittedForClip = false;
        final RunConfig.VideoOrientation mode = viewModel.getRunConfig().videoOrientation;

        // Keep a handle to the old player so we can release it after swap
        ExoPlayer old = exoPlayer;

        // Build a new player using your existing RenderersFactory (dav1d, etc.)
        ExoPlayer newPlayer =
                new ExoPlayer.Builder(this, renderersFactory)
                        .setMediaSourceFactory(
                                new DefaultMediaSourceFactory(
                                        this,
                                        () -> new Extractor[] { new VcatMp4Extractor(/* flags if any */) } // ONLY your extractor
                                )
                        )
                        .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                        .build();        newPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

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

        // Make the new player current
        exoPlayer = newPlayer;

        int videoRenderers = 0;
        for (int i = 0; i < exoPlayer.getRendererCount(); i++) {
            int type = exoPlayer.getRendererType(i);
            Log.d(TAG, "renderer[" + i + "] type=" + rendererTypeName(type));
            if (type == com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO) videoRenderers++;
        }
        Log.d(TAG, "videoRendererCount=" + videoRenderers);

        // Release the old one after we've fully switched the view
        if (old != null) {
            // Defensive: clear media & listeners before release
            old.clearMediaItems();
            old.removeListener(this.playbackStateListener);
            if (this.analyticsListener != null) old.removeAnalyticsListener(this.analyticsListener);
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
        // kick off immediately
        telemetryHandler.post(telemetryRunnable);
    }

    private void stopTelemetryTimer() {
        telemetryHandler.removeCallbacks(telemetryRunnable);
    }

    // call this after exoPlayer.play()
    private void onPlaybackStarted() {
        startTelemetryTimer();
    }

    // call this when playback ends or in onStop()
    private void onPlaybackStopped() {

    }

    private void logTelemetry(boolean endOfFile) {

        TelemetryLogger.VideoInfo vi = getTlVideoInfo(this.testClips.get(this.curFileIndex), this.viewModel.curTestDetails);

        int frameDrops = this.fd.frameDrops;
        this.fd.reset();

        this.tl.logTelemetryRow(this, this.viewModel.curTestDetails.getStartTimeAsEpoch(), vi, frameDrops, false, endOfFile);

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

        // if video overlay is vidible, populate
        if (this.videoOverlay != null && this.videoOverlay.getVisibility() == View.VISIBLE) {
            this.videoOverlay.setText(String.format(Locale.US,
                    "Path: %s\n" +
                            "Resolution: %sx%s\n" +
                            "MIME Type: %s\n" +
                            "Bitrate: %s\n" +
                            "Codec: %s\n" +
                            "Decoder: %s\n" +
                            "Framerate: %.2f fps\n"+
                            "Display: %d%%\n" +
                            "Run Mode: %s\n" +
                            "Battery Level: %d%%\n"+
                            "Run Duration: %s",
                    UriUtils.fileNameFromURI(vi.fileName),
                    vi.width,
                    vi.height,
                    vi.mimeType,
                    vi.bitrate,
                    vi.codec,
                    vi.decoderName,
                    vi.fps,
                    this.viewModel.getRunConfig().screenBrightness,
                    this.viewModel.getRunConfig().runModeStr(),
                    (int) BatteryInfo.getBatteryLevel(this),
                    this.viewModel.curTestDetails.getElapsedSinceStartHms()
            ));
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

        TelemetryLogger.VideoInfo vi = new TelemetryLogger.VideoInfo(
                /* path     = */ videoFileUri.toString(),
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


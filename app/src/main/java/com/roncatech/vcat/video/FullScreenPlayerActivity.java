package com.roncatech.vcat.video;

import android.app.AlertDialog;
import android.app.AsyncNotedAppOp;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;


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

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.lang.reflect.Constructor;

public class FullScreenPlayerActivity extends AppCompatActivity implements PlayerCommandBus.Listener {

    private static final String TAG = "FullScreenPlayerActivity";

    private SharedViewModel viewModel;
    private SimpleExoPlayer exoPlayer;

    private PlayerView playerView;
    LinearLayout buttonRow;
    TextView videoOverlay;

    private List<Uri> testClips;
    private int curFileIndex;

    private TelemetryLogger tl;

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

    private boolean shouldStopTesting(){
        boolean shouldStop = false;

        int batteryLevel = BatteryInfo.getBatteryLevel(FullScreenPlayerActivity.this);
        RunConfig rc = FullScreenPlayerActivity.this.viewModel.getRunConfig();

        if(rc.runMode == RunConfig.RunMode.BATTERY && batteryLevel < rc.runLimit){
            Log.i(TAG, String.format("Stopping test because battery level %d is < run limit", batteryLevel));
            shouldStop = true;
        } else if(rc.runMode == RunConfig.RunMode.TIME){
            long elapsedTime = System.currentTimeMillis() - FullScreenPlayerActivity.this.viewModel.curTestDetails.getStartTimeAsEpoch();
            if(elapsedTime > rc.runLimit){
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
        double videoAspectRatio = Double.parseDouble(vi.width) / Double.parseDouble(vi.height);
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
        finish();
    }


    // 2) Extract your frame‐drop listener
    private final AnalyticsListener frameDropListener = new AnalyticsListener() {
        @Override
        public void onDroppedVideoFrames(
                EventTime eventTime,
                int droppedFrameCount,
                long elapsedMs
        ) {
            Log.i(TAG, "Dropped " + droppedFrameCount + " frames over " + elapsedMs + " ms");
            fd.elapsedMs  += elapsedMs;
            fd.frameDrops += droppedFrameCount;
        }
    };

    MediaCodecSelector customSelector;

    private void initialize(){
        this.viewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        int testScreenBrightness = viewModel.getRunConfig().screenBrightness;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        this.originalWindowBrightness = lp.screenBrightness;

        float override = Math.max(0.01f, Math.min(testScreenBrightness / 100f, 1f));
        lp.screenBrightness = override;

        this.renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected void buildVideoRenderers(
                    Context context,
                    int extensionRendererMode,
                    MediaCodecSelector mediaCodecSelector,
                    boolean enableDecoderFallback,
                    Handler eventHandler,
                    VideoRendererEventListener eventListener,
                    long allowedVideoJoiningTimeMs,
                    ArrayList<Renderer> out
            ) {
                String av1Decoder = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.AV1);
                // Always add MediaCodec renderer for fallback/default
                out.add(new MediaCodecVideoRenderer(
                        context,
                        customSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                ));

                // Conditionally add Libdav1d renderer only for AV1 and if libdav1d is desired
                if ("dav1d".equalsIgnoreCase(av1Decoder)) {
                    try {
                        Class<?> av1RendererClass = Class.forName("com.google.android.exoplayer2.ext.av1.Libgav1VideoRenderer");
                        Constructor<?> constructor = av1RendererClass.getConstructor(
                                long.class, Handler.class, VideoRendererEventListener.class, int.class
                        );
                        Renderer libav1Renderer = (Renderer) constructor.newInstance(
                                allowedVideoJoiningTimeMs,
                                eventHandler,
                                eventListener,
                                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                        );
                        Log.d("RenderersFactory", "Add dav1dRenderer");
                        out.add(libav1Renderer);
                    } catch (Exception e) {
                        Log.w("RenderersFactory", "Libgav1VideoRenderer not available: " + e.getMessage());
                    }
                }
            }
        };

        this.customSelector = (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
            String decoderName = null;

            switch (mimeType) {
                case MimeTypes.VIDEO_H264:
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.H264);
                    break;
                case MimeTypes.VIDEO_H265:
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.H265);
                    break;
                case MimeTypes.VIDEO_AV1:
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.AV1);

                    if ("dav1d".equalsIgnoreCase(decoderName)) {
                        Log.d("Decoder", "Skipping MediaCodec decoders for AV1 (dav1d selected)");
                        return Collections.emptyList(); // Disables AV1 for MediaCodecVideoRenderer
                    }
                    break;
                case MimeTypes.VIDEO_VP9:
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.VP9);
                    break;
                case "video/vvc":
                    decoderName = viewModel.getRunConfig().decoderCfg.getDecoder(VideoDecoderEnumerator.MimeType.VVC);
                    break;
            }

            List<MediaCodecInfo> infos = MediaCodecUtil.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
            );

            if (decoderName != null && !decoderName.isEmpty()) {
                List<MediaCodecInfo> filtered = new ArrayList<>();
                for (MediaCodecInfo info : infos) {
                    if (info.name.equalsIgnoreCase(decoderName)) {
                        filtered.add(info);
                    }
                }
                return filtered;
            }
            return infos;
        };

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
        };

        this.analyticsListener = new AnalyticsListener() {
            @Override
            public void onDroppedVideoFrames(AnalyticsListener.EventTime eventTime, int droppedFrameCount, long elapsedMs) {
                // This is the number of frames dropped *since the last callback*,
                // and elapsedMs is the time window in which that drop occurred.

                Log.i(TAG, "Dropped " + droppedFrameCount
                        + " frames over " + elapsedMs + " ms");

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

    // Put this near your other private helpers
    private void startClipWithFreshPlayer() {
        // Keep a handle to the old player so we can release it after swap
        SimpleExoPlayer old = exoPlayer;

        // Build a new player using your existing RenderersFactory (dav1d, etc.)
        SimpleExoPlayer newPlayer = new SimpleExoPlayer.Builder(this, renderersFactory).build();
        newPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

        // Wire listeners you already use
        newPlayer.addListener(this.playbackStateListener);
        if (this.analyticsListener != null) {
            newPlayer.addAnalyticsListener(this.analyticsListener);
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

        // Release the old one after we've fully switched the view
        if (old != null) {
            // Defensive: clear media & listeners before release
            old.clearMediaItems();
            old.removeListener(this.playbackStateListener);
            if (this.analyticsListener != null) old.removeAnalyticsListener(this.analyticsListener);
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

    @Override
    protected void onStop() {
        super.onStop();

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
                            "Battery Level: %d%%",
                    UriUtils.fileNameFromURI(vi.fileName),
                    vi.width,
                    vi.height,
                    vi.mimeType,
                    vi.bitrate,
                    vi.codec,
                    vi.decoderName,
                    vi.fps,
                    (int) BatteryInfo.getBatteryLevel(this)
            ));
        }
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
        String bitrate = formatBitrate(String.valueOf(fmt.bitrate));

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


}


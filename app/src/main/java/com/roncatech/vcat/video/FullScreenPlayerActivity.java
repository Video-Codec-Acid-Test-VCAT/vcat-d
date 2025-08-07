package com.roncatech.vcat.video;

import android.app.AlertDialog;
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

public class FullScreenPlayerActivity extends AppCompatActivity implements PlayerCommandBus.Listener {

    private static final String TAG = "FullScreenPlayerActivity";

    private SharedViewModel viewModel;
    private SimpleExoPlayer exoPlayer;

    private PlayerView playerView;
    private ImageButton stopButton;
    private ImageButton videoInfoButton;
    LinearLayout buttonRow;
    TextView videoOverlay;

    private List<Uri> testClips;
    private int curFileIndex;

    private TelemetryLogger tl;

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
    public void onToggleVideoInfo(){
        if (videoOverlay.getVisibility() == View.VISIBLE) {
            videoOverlay.setVisibility(View.GONE);
        } else {

            // calculate size
            TelemetryLogger.VideoInfo vi = getTlVideoInfo(this.testClips.get(this.curFileIndex), this.viewModel.curTestDetails);
            int displayHeight = playerView.getHeight(); // or dm.heightPixels;
            double videoAspectRatio = Double.parseDouble(vi.width) / Double.parseDouble(vi.height);
            int scaledVideoWidth = (int) (displayHeight * videoAspectRatio);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    scaledVideoWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            );
            videoOverlay.setLayoutParams(params);
            videoOverlay.setVisibility(View.VISIBLE);
            logTelemetry(false);
        }

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

    private final Player.Listener playbackStateListener = new Player.Listener() {
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
                    playCurClip();
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

    MediaCodecSelector customSelector = (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
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

    RenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
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
            out.add(new MediaCodecVideoRenderer(
                    context,
                    customSelector,
                    allowedVideoJoiningTimeMs,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            ));
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        this.viewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        int testScreenBrightness = viewModel.getRunConfig().screenBrightness;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        this.originalWindowBrightness = lp.screenBrightness;

        float override = Math.max(0.01f, Math.min(testScreenBrightness / 100f, 1f));
        lp.screenBrightness = override;
        getWindow().setAttributes(lp);

        setContentView(R.layout.activity_fullscreen_player);

        Window window = getWindow();
        // Let us draw under the status and nav bars:
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        // … your existing immersive flags …
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // And force both bars to be black
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);

        hideSystemUi();

        this.playerView = findViewById(R.id.playerView);
        this.stopButton  = findViewById(R.id.stopButton);
        this.videoInfoButton = findViewById(R.id.toggleVideoInfo);
        this.buttonRow = findViewById(R.id.buttonRow);
        this.videoOverlay = findViewById(R.id.videoOverlay);

        exoPlayer  = new SimpleExoPlayer.Builder(this,renderersFactory).build();
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
        playerView.setPlayer(exoPlayer);

        // 1) Load and parse the playlist
        testClips  = XspfParser.parsePlaylist(this, Uri.parse(viewModel.curTestDetails.getPlaylist()));
        curFileIndex = 0;
        if (testClips.isEmpty()) {
            finish();  // nothing to play
            return;
        }

        // setup telemetry file
        long startTime = System.currentTimeMillis();
        String telemetryFileName = "logs_" + startTime + ".csv";

        this.tl = new TelemetryLogger(telemetryFileName);

        this.tl.writeHeaderRows(this, viewModel.curTestDetails.getPlaylistFileName(), this.viewModel.getRunConfig(), startTime);
        this.tl.writeCsvHeader();

        // 2) When one clip ends, play next (or loop)
        exoPlayer.addListener(this.playbackStateListener);

        exoPlayer.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onDroppedVideoFrames(AnalyticsListener.EventTime eventTime, int droppedFrameCount, long elapsedMs) {
                // This is the number of frames dropped *since the last callback*,
                // and elapsedMs is the time window in which that drop occurred.

                Log.i(TAG, "Dropped " + droppedFrameCount
                        + " frames over " + elapsedMs + " ms");

                FullScreenPlayerActivity.this.fd.elapsedMs += elapsedMs;
                FullScreenPlayerActivity.this.fd.frameDrops += droppedFrameCount;
            }

            @Override
            public void onVideoDecoderInitialized(
                    AnalyticsListener.EventTime eventTime,
                    String decoderName,
                    long initializationDurationMs,
                    long initializationDelayMs) {
                Log.i(TAG, "Video decoder initialized: " + decoderName);
                // Save or log decoderName as needed
                FullScreenPlayerActivity.this.curDecoder = decoderName;
            }

        });

        playerView.setControllerVisibilityListener(visibility -> {
            buttonRow.setVisibility(visibility);
        });

        stopButton.setOnClickListener(v -> {
            // your VCAT stop logic here:
            stopTestAndCleanup();
        });

        videoInfoButton.setOnClickListener(v -> {
            onToggleVideoInfo();
        });

        // register for external commands:
        PlayerCommandBus.get().setListener(this);

        // Listen for when the video size is known:
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if(videoSize.width == 0 && videoSize.height == 0){
                    return;
                }
                if (videoSize.height > videoSize.width) {
                    // lock to portrait and ignore the sensor entirely
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                } else {
                    // lock to landscape (not sensor-landscape)
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                }
            }
        });

        // 3) Kick off the first clip
        playCurClip();
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


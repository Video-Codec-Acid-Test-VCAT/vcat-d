package com.roncatech.extension_dav1d;

import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.decoder.CryptoConfig; // ExoPlayer 2.x package
import com.google.android.exoplayer2.decoder.Decoder;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.decoder.VideoDecoderOutputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.DecoderVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

public final class Dav1dVideoRenderer extends DecoderVideoRenderer {

    private final static String TAG = "Dav1dVideoRenderer";

    private static final int MAX_DROPPED_FRAMES_TO_NOTIFY = 50;

    private final int frameThreads;
    private final int tileThreads;

    private Dav1dDecoder decoder;

    public Dav1dVideoRenderer(
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int frameThreads,
            int tileThreads) {
        // NOTE: 4-arg super() is required in ExoPlayer 2.x
        super(allowedJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_FRAMES_TO_NOTIFY);
        this.frameThreads = Math.max(1, frameThreads);
        this.tileThreads  = Math.max(1, tileThreads);
    }

    @Override public String getName() { return "Dav1dVideoRenderer"; }


    @Override
    protected Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
    createDecoder(Format format, CryptoConfig cryptoConfig) throws Dav1dDecoderException {
        this.decoder = new Dav1dDecoder(
                frameThreads,
                tileThreads);
        return this.decoder;
    }

    @Override
    protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws Dav1dDecoderException {
        if (decoder == null) {
            throw new Dav1dDecoderException(
                    "Failed to render output buffer to surface: decoder is not initialized.");
        }
        decoder.renderToSurface((Dav1dOutputBuffer)outputBuffer, surface);
        outputBuffer.release();
    }

    private static String videoOutputModeStr(int outputMode){
        switch (outputMode){
            case C.VIDEO_OUTPUT_MODE_YUV:
                return "VIDEO_OUTPUT_MODE_YUV";
            case C.VIDEO_OUTPUT_MODE_SURFACE_YUV:
                return "VIDEO_OUTPUT_MODE_SURFACE_YUV";
        }

        return "VIDEO_OUTPUT_MODE_NONE";
    }
    @Override
    protected void setDecoderOutputMode(int outputMode) {

        Log.d(TAG,"setDecoderOutputMode="+outputMode);

        switch (outputMode) {
            case com.google.android.exoplayer2.C.VIDEO_OUTPUT_MODE_YUV:
            case com.google.android.exoplayer2.C.VIDEO_OUTPUT_MODE_SURFACE_YUV:
            case C.VIDEO_OUTPUT_MODE_NONE:
                // dav1d always outputs YUV planes. If your Dav1dDecoder exposes setOutputMode(),
                // set it to YUV; otherwise you can ignore this call.
                // example: decoder.setOutputMode(com.google.android.exoplayer2.C.VIDEO_OUTPUT_MODE_YUV);
                return;

            default:
                throw new IllegalArgumentException(
                        "Surface output mode (" + videoOutputModeStr(outputMode) + ") not supported by dav1d");
        }
    }

    @Override
    protected DecoderReuseEvaluation canReuseDecoder(String name, Format oldF, Format newF) {
        // Re-init on format change.
        return new DecoderReuseEvaluation(
                name,
                oldF,
                newF,
                DecoderReuseEvaluation.REUSE_RESULT_NO, // or just 0 if the constant isn't present
                0                                       // discardReasons bitmask
        );
    }

    @Override
    public int supportsFormat(Format format) {
        final String mime = format.sampleMimeType;
        if (!MimeTypes.VIDEO_AV1.equals(mime)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
        // dav1d path is clear-only (no DRM)
        if (format.drmInitData != null) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        }
        return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        // only drop if >50 ms late
        return earlyUs < -50_000;
    }

    @Override
    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs) {
        // only skip forward to a keyframe if we're ~0.5 s behind
        return earlyUs < -500_000;
    }

    @Override
    protected boolean shouldForceRenderOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        // render when due or slightly late
        return earlyUs <= 0;
    }


}

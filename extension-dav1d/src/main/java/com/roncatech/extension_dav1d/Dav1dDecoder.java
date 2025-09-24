package com.roncatech.extension_dav1d;

import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.DecoderOutputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import com.google.android.exoplayer2.decoder.VideoDecoderOutputBuffer;
import androidx.annotation.Nullable;

final class Dav1dDecoder
        extends SimpleDecoder<DecoderInputBuffer, Dav1dOutputBuffer, Dav1dDecoderException> {

    // Local copies of 2.x buffer flags to avoid Media3 suggestions.
    private static final int FLAG_DECODE_ONLY = 0x1;
    private static final int FLAG_END_OF_STREAM = 0x4;

    private static final int NUM_INPUT_BUFFERS = 8;
    private static final int NUM_OUTPUT_BUFFERS = 4;

    private final int frameThreads;
    private final int tileThreads;

    private long nativeCtx; // 0 when released
    private Format inputFormat;

    private boolean eosSignaled = false;

    Dav1dDecoder(int frameThreads, int tileThreads) throws Dav1dDecoderException {
        super(
                new DecoderInputBuffer[NUM_INPUT_BUFFERS],
                new Dav1dOutputBuffer[NUM_OUTPUT_BUFFERS]);

        this.frameThreads = Math.max(1, frameThreads);
        this.tileThreads  = Math.max(1, tileThreads);

        nativeCtx = NativeDav1d.nativeCreate(this.frameThreads, this.tileThreads);
        if (nativeCtx == 0) {
            throw new Dav1dDecoderException("nativeCreate failed");
        }
    }

    // Dav1dDecoder.java (add this)
    void setOutputSurface(@androidx.annotation.Nullable Surface surface) {
        if (nativeCtx == 0) return;
        NativeDav1d.nativeSetSurface(nativeCtx, surface); // caches/replaces ANativeWindow in native
    }


    @Override
    public String getName() {
        return "vcat-dav1d-" + NativeDav1d.dav1dGetVersion();
    }

    /** Called by the renderer on input format changes. */
    void setInputFormat(Format format) {
        this.inputFormat = format;
    }

    @Override
    protected Dav1dOutputBuffer createOutputBuffer() {
        return new Dav1dOutputBuffer(
                new VideoDecoderOutputBuffer.Owner() {
                    @Override
                    public void releaseOutputBuffer(DecoderOutputBuffer buffer) {
                        Dav1dDecoder.this.releaseOutputBuffer((Dav1dOutputBuffer) buffer);
                    }
                });
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected Dav1dDecoderException createUnexpectedDecodeException(Throwable error) {
        return new Dav1dDecoderException("Unexpected decode error", error);
    }

    @Override
    protected void releaseOutputBuffer(Dav1dOutputBuffer out) {
        if (out.nativePic != 0 && nativeCtx != 0) {
            NativeDav1d.nativeReleasePicture(nativeCtx, out.nativePic);
            out.nativePic = 0;
        }
        super.releaseOutputBuffer(out);
    }

    @Override
    public void release() {
        if (nativeCtx != 0) {
            NativeDav1d.nativeSetSurface(nativeCtx, null);
        }
        super.release();
        if (nativeCtx != 0) {
            NativeDav1d.nativeClose(nativeCtx);
            nativeCtx = 0;
        }
    }

    @Override
    protected Dav1dDecoderException decode(DecoderInputBuffer in, Dav1dOutputBuffer out, boolean reset) {
        if (nativeCtx == 0) return new Dav1dDecoderException("Decoder released");
        if (reset) { NativeDav1d.nativeFlush(nativeCtx); eosSignaled = false; }
        final boolean decodeOnly = in.isDecodeOnly();

        // EOS: signal, try one drain, else EOS flag
        if (in.isEndOfStream()) {
            NativeDav1d.nativeSignalEof(nativeCtx);
            eosSignaled = true;
            int[] wh = new int[2]; long[] pts = new long[1];
            long h = NativeDav1d.nativeDequeueFrame(nativeCtx, wh, pts);
            if (wh[0] == -1) return new Dav1dDecoderException("dav1d_get_picture failed: " + wh[1]);
            if (h != 0) {
                out.mode=C.VIDEO_OUTPUT_MODE_SURFACE_YUV; out.timeUs=pts[0];
                out.width=wh[0]; out.height=wh[1]; out.format=inputFormat; out.nativePic=h;
                if (decodeOnly) out.addFlag(FLAG_DECODE_ONLY);
                return null;
            }
            out.addFlag(FLAG_END_OF_STREAM);
            return null;
        }

        if (in.data == null) return new Dav1dDecoderException("Input buffer has no data");

        // If full: try one drain; if none, hold input (back-pressure)
        if (!NativeDav1d.nativeHasCapacity(nativeCtx)) {
            int[] wh = new int[2]; long[] pts = new long[1];
            long h = NativeDav1d.nativeDequeueFrame(nativeCtx, wh, pts);
            if (wh[0] == -1) return new Dav1dDecoderException("dav1d_get_picture failed: " + wh[1]);
            if (h != 0) {
                out.mode=C.VIDEO_OUTPUT_MODE_SURFACE_YUV; out.timeUs=pts[0];
                out.width=wh[0]; out.height=wh[1]; out.format=inputFormat; out.nativePic=h;
                if (decodeOnly) out.addFlag(FLAG_DECODE_ONLY);
                return null;
            }
            return null;
        }

        // Enqueue current input
        int rc = NativeDav1d.nativeQueueInput(nativeCtx, in.data, in.data.position(), in.data.remaining(), in.timeUs);
        if (rc == -11) { // EAGAIN: needs drain first
            int[] wh = new int[2]; long[] pts = new long[1];
            long h = NativeDav1d.nativeDequeueFrame(nativeCtx, wh, pts);
            if (wh[0] == -1) return new Dav1dDecoderException("dav1d_get_picture failed: " + wh[1]);
            if (h != 0) {
                out.mode=C.VIDEO_OUTPUT_MODE_SURFACE_YUV; out.timeUs=pts[0];
                out.width=wh[0]; out.height=wh[1]; out.format=inputFormat; out.nativePic=h;
                if (decodeOnly) out.addFlag(FLAG_DECODE_ONLY);
                return null;
            }
            return null;
        }
        if (rc != 0) return new Dav1dDecoderException("nativeQueueInput failed: " + rc);

        // After enqueue, try one non-blocking drain
        {
            int[] wh = new int[2]; long[] pts = new long[1];
            long h = NativeDav1d.nativeDequeueFrame(nativeCtx, wh, pts);
            if (wh[0] == -1) return new Dav1dDecoderException("dav1d_get_picture failed: " + wh[1]);
            if (h != 0) {
                out.mode=C.VIDEO_OUTPUT_MODE_SURFACE_YUV; out.timeUs=pts[0];
                out.width=wh[0]; out.height=wh[1]; out.format=inputFormat; out.nativePic=h;
                if (decodeOnly) out.addFlag(FLAG_DECODE_ONLY);
                return null;
            }
        }
        return null;
    }


    /** Called by the renderer to blit the decoded frame to a Surface. */
    void renderToSurface(Dav1dOutputBuffer out) throws Dav1dDecoderException {
        if (nativeCtx == 0 || out.nativePic == 0) return;
        int rc = NativeDav1d.nativeRenderToSurface(nativeCtx, out.nativePic);
        if (rc < 0) {
            throw new Dav1dDecoderException("nativeRenderToSurface failed: " + rc);
        }
    }

}

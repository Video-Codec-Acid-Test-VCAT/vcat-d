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
 * You should have received a copy of the GNU General Public License
 * along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.parsers.ivf;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Handler;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.upstream.DataReader;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.vcat.decoder_plugin.VcatDecoderManager;
import com.roncatech.vcat.decoder_plugin_api.ContainerParser;
import com.roncatech.vcat.decoder_plugin_api.IvfParserExtension;
import com.roncatech.vcat.decoder_plugin_api.VcatDecoder;
import com.roncatech.vcat.decoder_plugin_api.VideoConfiguration;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Phase-1 unit tests for {@link VcatIvfExtractor} (IVF framing) and {@link IvfFileHeader}. */
public class VcatIvfExtractorTest {

    private static final int AV01 = Util.getIntegerCodeForString("AV01");

    // The manager is a process-wide singleton (first-registration wins), so use a single shared
    // stub registered once, and reset its captured state before each test.
    private static final StubIvfPlugin STUB =
            new StubIvfPlugin("AV01", "test.stub.av1.ivf", "video/av01");
    /** AV2 files need an AV02 parser registered; keyframe detection itself is the host's. */
    private static final StubIvfPlugin STUB_AV2 =
            new StubIvfPlugin("AV02", "test.stub.av2.ivf", "video/av02");
    private static boolean registered;

    @Before
    public void setUp() {
        if (!registered) {
            VcatDecoderManager.getInstance().registerDecoder(STUB);
            VcatDecoderManager.getInstance().registerDecoder(STUB_AV2);
            registered = true;
        }
        STUB.reset();
        STUB_AV2.reset();
    }

    // ---------- sniff ----------

    @Test public void sniff_validHeader_returnsTrue() throws IOException {
        VcatIvfExtractor ex = new VcatIvfExtractor();
        assertTrue(ex.sniff(input(ivf("AV01", 30, 1, 1, new long[]{0}, new byte[][]{payload(10)}))));
    }

    @Test public void sniff_mp4Header_returnsFalse() throws IOException {
        byte[] mp4 = new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
        assertFalse(new VcatIvfExtractor().sniff(input(mp4)));
    }

    @Test public void sniff_truncatedInput_returnsFalse() throws IOException {
        assertFalse(new VcatIvfExtractor().sniff(input(new byte[]{'D', 'K'})));
    }

    @Test public void sniff_randomBytes_returnsFalse() throws IOException {
        assertFalse(new VcatIvfExtractor().sniff(input(new byte[]{9, 8, 7, 6, 5, 4})));
    }

    // ---------- read ----------

    @Test public void read_unknownFourCc_throwsParserException() {
        byte[] data = ivf("XXXX", 30, 1, 1, new long[]{0}, new byte[][]{payload(10)});
        ParserException e = assertThrows(ParserException.class,
                () -> extract(new VcatIvfExtractor(), input(data), new CapturingOutput()));
        assertTrue(e.getMessage().contains("XXXX"));
    }

    @Test public void read_knownFourCc_pluginResolved() throws IOException {
        byte[] first = payload(123);
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 1, new long[]{0}, new byte[][]{first})),
                new CapturingOutput());
        assertTrue(STUB.parseHeaderCalled);
        assertEquals(first.length, STUB.capturedFrameSize);
    }

    @Test public void read_firstFrameFlags_keyFrame() throws IOException {
        CapturingOutput out = new CapturingOutput();
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 2, new long[]{0, 1},
                        new byte[][]{payload(20), payload(20)})),
                out);
        assertTrue((out.track.flags.get(0) & C.BUFFER_FLAG_KEY_FRAME) != 0);
    }

    @Test public void read_secondFrameFlags_notKeyFrame() throws IOException {
        CapturingOutput out = new CapturingOutput();
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 2, new long[]{0, 1},
                        new byte[][]{payload(20), payload(20)})),
                out);
        assertEquals(0, (out.track.flags.get(1) & C.BUFFER_FLAG_KEY_FRAME));
    }

    @Test public void read_timestampConversion_correct() throws IOException {
        CapturingOutput out = new CapturingOutput();
        // timebase 1/30: raw ts 0 -> 0us, raw ts 1 -> 33333us
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 2, new long[]{0, 1},
                        new byte[][]{payload(8), payload(8)})),
                out);
        assertEquals(0L, (long) out.track.timesUs.get(0));
        assertEquals(33333L, (long) out.track.timesUs.get(1));
    }

    @Test public void read_seekMap_durationFromHeader() throws IOException {
        CapturingOutput out = new CapturingOutput();
        // 3600 displayed frames at 30/1 -> 120 s. TIME_UNSET here left the player with no
        // duration, so the file-size bitrate fallback could never work for IVF.
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 3600, new long[]{0}, new byte[][]{payload(8)})),
                out);
        assertEquals(120_000_000L, out.seekMap.getDurationUs());
    }

    @Test public void read_seekMap_durationUnsetWithoutFrameCount() throws IOException {
        CapturingOutput out = new CapturingOutput();
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 0, new long[]{0}, new byte[][]{payload(8)})),
                out);
        assertEquals(C.TIME_UNSET, out.seekMap.getDurationUs());
    }

    @Test public void read_multipleFrames_correctSampleCount() throws IOException {
        CapturingOutput out = new CapturingOutput();
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 3, new long[]{0, 1, 2},
                        new byte[][]{payload(11), payload(22), payload(33)})),
                out);
        assertEquals(3, out.track.samples.size());
    }

    @Test public void read_firstFramePayloadIntact_afterHeaderPeek() throws IOException {
        // The stub PEEKS the header; the extractor must still feed the full, unaltered first frame
        // payload as the first sample (read position preserved via resetPeekPosition).
        byte[] first = payload(200);
        CapturingOutput out = new CapturingOutput();
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 1, new long[]{0}, new byte[][]{first})),
                out);
        assertTrue(STUB.peeked);
        assertArrayEquals(first, out.track.samples.get(0));
    }

    // ---------- IvfFileHeader ----------

    @Test public void ivfFileHeader_parse_fieldsCorrect() {
        byte[] h = fileHeader("AV01", 30, 1, 300);
        IvfFileHeader p = IvfFileHeader.parse(h);
        assertEquals(AV01, p.fourCc);
        assertEquals(30, p.timeBaseRate);   // bytes 16-19
        assertEquals(1, p.timeBaseScale);   // bytes 20-23
        assertEquals(300, p.frameCount);
        // sanity: FourCC convention matches Util.getIntegerCodeForString (not the LE packing)
        assertNotEquals(0x31305641, p.fourCc);
    }

    // ---------- keyframes from the bitstream (IvfRandomAccess) ----------
    //
    // IVF has no keyframe flag. Without these, only frame 0 was flagged and the renderer's
    // skip-to-keyframe had nowhere to go. The fixtures and expected keyframes are vcat-d-ios's
    // (tests/ivf/fixtures, RendererDropPolicyTests), so both platforms agree on the keyframes.

    @Test public void keyframes_av1Fixture_areTheTwoGopStarts() throws IOException {
        List<Integer> flags = extractFlags("ivf/av1-640x360-2gop.ivf");
        assertEquals(120, flags.size());
        assertEquals(java.util.Arrays.asList(0, 60), keyframeIndices(flags));
    }

    @Test public void keyframes_av2Fixtures_onlyTheClosedLoopKeyFrame() throws IOException {
        for (String name : new String[]{"ivf/av2-640x360-ra.ivf", "ivf/av2-640x360-noaltref.ivf"}) {
            assertEquals(name, Collections.singletonList(0), keyframeIndices(extractFlags(name)));
        }
    }

    @Test public void keyframes_av1_shownKeyFrameWithSequenceHeader() throws IOException {
        assertEquals(java.util.Arrays.asList(0, 1), keyframeIndices(extractAv1(
                av1Tu(true, av1FrameHeader(false, 0, true)),
                av1Tu(true, av1FrameHeader(false, 0, true)))));
    }

    @Test public void keyframes_av1_needsTheSequenceHeader() throws IOException {
        assertEquals(Collections.singletonList(0), keyframeIndices(extractAv1(
                av1Tu(true, av1FrameHeader(false, 0, true)),
                av1Tu(false, av1FrameHeader(false, 0, true)))));
    }

    @Test public void keyframes_av1_interHiddenAndShowExistingAreNot() throws IOException {
        assertEquals(Collections.singletonList(0), keyframeIndices(extractAv1(
                av1Tu(true, av1FrameHeader(false, 0, true)),
                av1Tu(true, av1FrameHeader(false, 1, true)),    // inter frame
                av1Tu(true, av1FrameHeader(false, 0, false)),   // key frame, not shown
                av1Tu(true, av1FrameHeader(true, 0, true)))));  // show_existing_frame
    }

    @Test public void keyframes_av1_reducedStillPictureHeaderMakesEveryFrameKey() throws IOException {
        byte[] stillSeq = obuAv1(1, new byte[]{0x18, 0x00});    // still_picture, reduced header
        byte[] tu = concat(obuAv1(2, new byte[0]), stillSeq, obuAv1(6, new byte[]{0x40, 0}));
        assertEquals(java.util.Arrays.asList(0, 1), keyframeIndices(extractAv1(tu, tu)));
    }

    @Test public void keyframes_av2_closedLoopKeyFrameWithSequenceHeader() throws IOException {
        byte[] closedKf = concat(obuAv2(2), obuAv2(1), obuAv2(4));
        byte[] openKf = concat(obuAv2(2), obuAv2(1), obuAv2(5));
        byte[] kfNoSeq = concat(obuAv2(2), obuAv2(4));
        byte[] inter = concat(obuAv2(2), obuAv2(6));
        assertEquals(java.util.Arrays.asList(0, 1), keyframeIndices(extractAv2(
                closedKf, closedKf, openKf, kfNoSeq, inter)));
    }

    /**
     * The keyframe peek must not change how a truncated last frame ends: it is not a keyframe,
     * and the sample read then fails with EOFException, as the real SampleQueue makes it.
     */
    @Test public void keyframes_truncatedFrame_endsAsBefore() {
        byte[] tu = av1Tu(true, av1FrameHeader(false, 0, true));
        byte[] full = ivf("AV01", 30, 1, 2, new long[]{0, 1}, new byte[][]{tu, tu});
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 3);
        CapturingOutput out = new CapturingOutput();
        assertThrows(EOFException.class,
                () -> extract(new VcatIvfExtractor(), input(cut), out));
        assertEquals("only the complete first frame is emitted", 1, out.track.flags.size());
    }

    // ----- helpers for the keyframe tests -----

    private static List<Integer> keyframeIndices(List<Integer> flags) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < flags.size(); i++) {
            if ((flags.get(i) & C.BUFFER_FLAG_KEY_FRAME) != 0) out.add(i);
        }
        return out;
    }

    private List<Integer> extractFlags(String resource) throws IOException {
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
        if (in == null) throw new AssertionError("missing test resource " + resource);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        in.close();
        CapturingOutput out = new CapturingOutput();
        extract(new VcatIvfExtractor(), input(bo.toByteArray()), out);
        return out.track.flags;
    }

    private static List<Integer> extractAv1(byte[]... tus) throws IOException {
        return extractFrames("AV01", tus);
    }

    private static List<Integer> extractAv2(byte[]... tus) throws IOException {
        return extractFrames("AV02", tus);
    }

    private static List<Integer> extractFrames(String fourCc, byte[]... tus) throws IOException {
        long[] pts = new long[tus.length];
        for (int i = 0; i < pts.length; i++) pts[i] = i;
        CapturingOutput out = new CapturingOutput();
        extract(new VcatIvfExtractor(), input(ivf(fourCc, 30, 1, tus.length, pts, tus)), out);
        return out.track.flags;
    }

    /** An AV1 OBU with has_size set: header, leb128 size (payloads here are < 128 bytes), payload. */
    private static byte[] obuAv1(int type, byte[] payload) {
        byte[] o = new byte[2 + payload.length];
        o[0] = (byte) ((type << 3) | 0x02);
        o[1] = (byte) payload.length;
        System.arraycopy(payload, 0, o, 2, payload.length);
        return o;
    }

    /** First byte of an AV1 uncompressed header: show_existing_frame(1) frame_type(2) show_frame(1). */
    private static byte[] av1FrameHeader(boolean showExisting, int frameType, boolean show) {
        int b = (showExisting ? 0x80 : 0) | ((frameType & 3) << 5) | (show ? 0x10 : 0);
        return new byte[]{(byte) b, 0};
    }

    /** A temporal unit: temporal delimiter, an optional sequence header, then a frame OBU. */
    private static byte[] av1Tu(boolean withSequenceHeader, byte[] frameHeader) {
        byte[] seq = withSequenceHeader ? obuAv1(1, new byte[]{0x00, 0x00, 0x00}) : new byte[0];
        return concat(obuAv1(2, new byte[0]), seq, obuAv1(6, frameHeader));
    }

    /** An AV2 OBU as dav2d parses it: leb128 size covering a header byte (type << 2) and 2 payload bytes. */
    private static byte[] obuAv2(int type) {
        return new byte[]{3, (byte) (type << 2), 0, 0};
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] p : parts) bo.write(p, 0, p.length);
        return bo.toByteArray();
    }

    // ===================== helpers =====================

    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i + 1);
        return b;
    }

    private static byte[] fileHeader(String fourCc, int rate, int scale, int frameCount) {
        byte[] h = new byte[32];
        h[0] = 'D'; h[1] = 'K'; h[2] = 'I'; h[3] = 'F';
        h[6] = 32; // header length
        for (int i = 0; i < 4; i++) h[8 + i] = (byte) fourCc.charAt(i); // FourCC in order
        putLe32(h, 16, rate);   // time base rate (ticks/sec)
        putLe32(h, 20, scale);  // time base scale
        putLe32(h, 24, frameCount);
        return h;
    }

    private static byte[] ivf(String fourCc, int rate, int scale, int frameCount,
                              long[] pts, byte[][] payloads) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bo.write(fileHeader(fourCc, rate, scale, frameCount));
            for (int i = 0; i < payloads.length; i++) {
                byte[] fh = new byte[12];
                putLe32(fh, 0, payloads[i].length);
                putLe64(fh, 4, pts[i]);
                bo.write(fh);
                bo.write(payloads[i]);
            }
            return bo.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }

    private static void putLe64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    private static ByteArrayInput input(byte[] data) {
        return new ByteArrayInput(data);
    }

    private static void extract(VcatIvfExtractor ex, ByteArrayInput input, CapturingOutput out)
            throws IOException {
        ex.init(out);
        PositionHolder ph = new PositionHolder();
        int result = Extractor.RESULT_CONTINUE;
        while (result != Extractor.RESULT_END_OF_INPUT) {
            result = ex.read(input, ph);
            if (result == Extractor.RESULT_SEEK) input.setPosition((int) ph.position);
        }
    }

    /** Stub IVF plugin: PEEKS a few header bytes, returns a fixed config. No real codec parsing. */
    private static final class StubIvfPlugin implements VcatDecoder, IvfParserExtension {
        private final int fourCc;
        private final String id;
        private final String mime;
        boolean parseHeaderCalled = false;
        boolean peeked = false;
        int capturedFrameSize = -1;

        StubIvfPlugin(String fourCc, String id, String mime) {
            this.fourCc = Util.getIntegerCodeForString(fourCc);
            this.id = id;
            this.mime = mime;
        }

        void reset() { parseHeaderCalled = false; peeked = false; capturedFrameSize = -1; }

        @Override public int ivfFourCc() { return fourCc; }

        @Override public VideoConfiguration parseHeader(ExtractorInput input, int frameSize)
                throws IOException, InterruptedException {
            parseHeaderCalled = true;
            capturedFrameSize = frameSize;
            byte[] tmp = new byte[Math.min(frameSize, 4)];
            if (tmp.length > 0) {
                input.peekFully(tmp, 0, tmp.length); // peek only — read position must be preserved
                peeked = true;
            }
            VideoConfiguration.Builder b = new VideoConfiguration.Builder();
            b.mimeType = mime;
            b.width = 640;
            b.height = 360;
            return b.build();
        }

        @Override public List<ContainerParser> getSupportedContainerParsers() {
            return Collections.singletonList(this);
        }
        @Override public String getId() { return id; }
        @Override public String getDisplayName() { return "stub-av1-ivf"; }
        @Override public String getVersion() { return "0"; }
        @Override public String getMimeType() { return mime; }
        @Override public Renderer createVideoRenderer(
                Context context, long allowedJoiningTimeMs, Handler eventHandler,
                VideoRendererEventListener eventListener, int threads) throws DecoderException {
            return null;
        }
    }

    // ---- audio is never published ----

    /**
     * VCAT measures video decode, so no audio track reaches the player.
     *
     * <p>An audio path is not free: two matched CPH2625 battery runs differing only in whether a
     * silent clock track was injected discharged 100%-14% in 17.72 h without it against 11.02 h
     * with it. The clock now comes from ExoPlayer's standalone (system) clock instead, which
     * advances at wall-clock rate so a decoder below realtime drops frames rather than stretching
     * the timeline.
     */
    @Test public void noAudioTrackIsRegistered() throws IOException {
        CapturingOutput out = new CapturingOutput();
        extract(new VcatIvfExtractor(),
                input(ivf("AV01", 30, 1, 3, new long[]{0, 1, 2},
                        new byte[][]{payload(10), payload(10), payload(10)})),
                out);

        assertFalse("no audio track may be registered", out.audioRegistered);
        assertEquals(0, out.audioTrack.samples.size());
        assertEquals("video samples must still be produced", 3, out.track.samples.size());
    }

    private static final class CapturingOutput implements ExtractorOutput {
        /** The video track. Named {@code track} because most assertions are about video. */
        final CapturingTrackOutput track = new CapturingTrackOutput();
        /** Audio track, if one were ever registered. Nothing should register one. */
        final CapturingTrackOutput audioTrack = new CapturingTrackOutput();
        boolean audioRegistered;
        SeekMap seekMap;
        boolean tracksEnded;

        // Per-type outputs: returning one shared instance would interleave injected audio samples
        // into the video track's capture lists and invalidate index-based assertions.
        @Override public TrackOutput track(int id, int type) {
            if (type == C.TRACK_TYPE_AUDIO) {
                audioRegistered = true;
                return audioTrack;
            }
            return track;
        }
        @Override public void endTracks() { tracksEnded = true; }
        @Override public void seekMap(SeekMap seekMap) { this.seekMap = seekMap; }
    }

    /** Capturing TrackOutput: accumulates sample bytes + metadata. */
    private static final class CapturingTrackOutput implements TrackOutput {
        Format format;
        final List<byte[]> samples = new ArrayList<>();
        final List<Long> timesUs = new ArrayList<>();
        final List<Integer> flags = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();

        @Override public void format(Format format) { this.format = format; }

        @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput,
                                        int sampleDataPart) throws IOException {
            byte[] buf = new byte[length];
            int total = 0;
            while (total < length) {
                int r = input.read(buf, total, length - total);
                if (r == C.RESULT_END_OF_INPUT) {
                    // As ExoPlayer's SampleQueue: at the end of input with nothing read, return
                    // RESULT_END_OF_INPUT only if allowed, otherwise throw. Returning 0 here left
                    // a truncated frame looping forever in the extractor, which never happens
                    // against the real queue.
                    if (total == 0) {
                        if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                        throw new EOFException();
                    }
                    break;
                }
                total += r;
            }
            current.write(buf, 0, total);
            return total;
        }

        @Override public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            byte[] b = new byte[length];
            data.readBytes(b, 0, length);
            current.write(b, 0, length);
        }

        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset,
                                             TrackOutput.CryptoData cryptoData) {
            samples.add(current.toByteArray());
            current.reset();
            timesUs.add(timeUs);
            this.flags.add(flags);
            sizes.add(size);
        }
    }

    /** Minimal byte[]-backed ExtractorInput (read position + independent peek position). */
    private static final class ByteArrayInput implements ExtractorInput {
        private final byte[] data;
        private int readPos;
        private int peekPos;

        ByteArrayInput(byte[] data) { this.data = data; }

        void setPosition(int p) { readPos = p; peekPos = p; }

        @Override public int read(byte[] target, int offset, int length) {
            int avail = data.length - readPos;
            if (avail <= 0) return C.RESULT_END_OF_INPUT;
            int n = Math.min(length, avail);
            System.arraycopy(data, readPos, target, offset, n);
            readPos += n;
            peekPos = readPos;
            return n;
        }

        @Override public boolean readFully(byte[] target, int offset, int length, boolean allowEndOfInput)
                throws IOException {
            int avail = data.length - readPos;
            if (avail < length) {
                if (allowEndOfInput && avail == 0) return false;
                throw new EOFException();
            }
            System.arraycopy(data, readPos, target, offset, length);
            readPos += length;
            peekPos = readPos;
            return true;
        }

        @Override public void readFully(byte[] target, int offset, int length) throws IOException {
            readFully(target, offset, length, false);
        }

        @Override public int skip(int length) {
            int avail = data.length - readPos;
            if (avail <= 0) return C.RESULT_END_OF_INPUT;
            int n = Math.min(length, avail);
            readPos += n;
            peekPos = readPos;
            return n;
        }

        @Override public boolean skipFully(int length, boolean allowEndOfInput) throws IOException {
            int avail = data.length - readPos;
            if (avail < length) {
                if (allowEndOfInput && avail == 0) return false;
                throw new EOFException();
            }
            readPos += length;
            peekPos = readPos;
            return true;
        }

        @Override public void skipFully(int length) throws IOException { skipFully(length, false); }

        @Override public int peek(byte[] target, int offset, int length) {
            int avail = data.length - peekPos;
            if (avail <= 0) return C.RESULT_END_OF_INPUT;
            int n = Math.min(length, avail);
            System.arraycopy(data, peekPos, target, offset, n);
            peekPos += n;
            return n;
        }

        @Override public boolean peekFully(byte[] target, int offset, int length, boolean allowEndOfInput)
                throws IOException {
            int avail = data.length - peekPos;
            if (avail < length) {
                if (allowEndOfInput && avail == 0) return false;
                throw new EOFException();
            }
            System.arraycopy(data, peekPos, target, offset, length);
            peekPos += length;
            return true;
        }

        @Override public void peekFully(byte[] target, int offset, int length) throws IOException {
            peekFully(target, offset, length, false);
        }

        @Override public boolean advancePeekPosition(int length, boolean allowEndOfInput) throws IOException {
            int avail = data.length - peekPos;
            if (avail < length) {
                if (allowEndOfInput && avail == 0) return false;
                throw new EOFException();
            }
            peekPos += length;
            return true;
        }

        @Override public void advancePeekPosition(int length) throws IOException {
            advancePeekPosition(length, false);
        }

        @Override public void resetPeekPosition() { peekPos = readPos; }

        @Override public long getPeekPosition() { return peekPos; }

        @Override public long getPosition() { return readPos; }

        @Override public long getLength() { return data.length; }

        @Override public <E extends Throwable> void setRetryPosition(long position, E e) throws E {
            readPos = (int) position;
            peekPos = readPos;
            throw e;
        }
    }
}

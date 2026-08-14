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
    private static final StubIvfPlugin STUB = new StubIvfPlugin();
    private static boolean registered;

    @Before
    public void setUp() {
        if (!registered) {
            VcatDecoderManager.getInstance().registerDecoder(STUB);
            registered = true;
        }
        STUB.reset();
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
        boolean parseHeaderCalled = false;
        boolean peeked = false;
        int capturedFrameSize = -1;

        void reset() { parseHeaderCalled = false; peeked = false; capturedFrameSize = -1; }

        @Override public int ivfFourCc() { return AV01; }

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
            b.mimeType = "video/av01";
            b.width = 640;
            b.height = 360;
            return b.build();
        }

        @Override public List<ContainerParser> getSupportedContainerParsers() {
            return Collections.singletonList(this);
        }
        @Override public String getId() { return "test.stub.av1.ivf"; }
        @Override public String getDisplayName() { return "stub-av1-ivf"; }
        @Override public String getVersion() { return "0"; }
        @Override public String getMimeType() { return "video/av01"; }
        @Override public Renderer createVideoRenderer(
                Context context, long allowedJoiningTimeMs, Handler eventHandler,
                VideoRendererEventListener eventListener, int threads) throws DecoderException {
            return null;
        }
    }

    /** Capturing ExtractorOutput with a single track. */
    private static final class CapturingOutput implements ExtractorOutput {
        final CapturingTrackOutput track = new CapturingTrackOutput();
        SeekMap seekMap;
        boolean tracksEnded;

        @Override public TrackOutput track(int id, int type) { return track; }
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
                    if (total == 0 && allowEndOfInput) return C.RESULT_END_OF_INPUT;
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

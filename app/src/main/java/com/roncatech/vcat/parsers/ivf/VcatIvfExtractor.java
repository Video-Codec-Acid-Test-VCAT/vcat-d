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

package com.roncatech.vcat.parsers.ivf;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.video.ColorInfo;
import com.roncatech.vcat.decoder_plugin.VcatDecoderManager;
import com.roncatech.vcat.decoder_plugin_api.IvfParserExtension;
import com.roncatech.vcat.decoder_plugin_api.VideoConfiguration;

import java.io.IOException;

/**
 * ExoPlayer {@link Extractor} for the AOM IVF container. Parses IVF framing only — all
 * codec-specific sequence-header parsing is delegated to the registered {@link IvfParserExtension}
 * matched by the file-header FourCC (see {@link VcatDecoderManager#findIvfPlugin(int)}).
 *
 * <p>Sequence-header parsing uses peeking: the plugin's {@link IvfParserExtension#parseHeader} only
 * <em>peeks</em> the first frame payload, so the read position stays at the start of that payload.
 * After the call the extractor resets the peek position and feeds the full first frame as the first
 * sample — no buffering of the whole frame and no seek-back required.
 */
public final class VcatIvfExtractor implements Extractor {

    private static final int FILE_HEADER_SIZE = IvfFileHeader.SIZE; // 32
    private static final int FRAME_HEADER_SIZE = 12;               // 4-byte size + 8-byte pts

    private static final int STATE_FILE_HEADER = 0;
    private static final int STATE_FRAME_HEADER = 1;
    private static final int STATE_SEQUENCE_HEADER = 2;
    private static final int STATE_SAMPLE = 3;

    private final byte[] scratch = new byte[FRAME_HEADER_SIZE];


    private ExtractorOutput extractorOutput;
    private TrackOutput trackOutput;
    private IvfFileHeader fileHeader;
    private IvfParserExtension plugin;

    private int state = STATE_FILE_HEADER;

    /** Source length in bytes, or 0 when unknown. Used only for the average-bitrate estimate. */
    private long fileLength = 0L;
    private int currentFrameSize;
    private long currentPtsUs;
    private int sampleBytesRemaining;
    private boolean firstSample = true;
    private boolean formatEmitted = false;

    public VcatIvfExtractor() {
    }


    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        byte[] sig = new byte[4];
        try {
            input.peekFully(sig, 0, 4);
        } catch (IOException e) {
            return false; // truncated / unreadable → not IVF
        }
        return sig[0] == 'D' && sig[1] == 'K' && sig[2] == 'I' && sig[3] == 'F';
    }

    @Override
    public void init(ExtractorOutput output) {
        this.extractorOutput = output;
        this.trackOutput = output.track(0, C.TRACK_TYPE_VIDEO);
        output.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
        output.endTracks();
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        switch (state) {
            case STATE_FILE_HEADER:
                return readFileHeader(input);
            case STATE_FRAME_HEADER:
                return readFrameHeader(input);
            case STATE_SEQUENCE_HEADER:
                return parseSequenceHeader(input);
            case STATE_SAMPLE:
                return readSample(input);
            default:
                throw new IllegalStateException("Unexpected IVF extractor state: " + state);
        }
    }

    private int readFileHeader(ExtractorInput input) throws IOException {
        byte[] header = new byte[FILE_HEADER_SIZE];
        if (!input.readFully(header, 0, FILE_HEADER_SIZE, /* allowEndOfInput= */ true)) {
            return RESULT_END_OF_INPUT; // empty input
        }
        fileHeader = IvfFileHeader.parse(header);
        // Captured here for the average-bitrate estimate; C.LENGTH_UNSET on a non-seekable source,
        // in which case the bitrate is simply left unreported rather than guessed.
        long len = input.getLength();
        fileLength = (len != C.LENGTH_UNSET) ? len : 0L;
        plugin = VcatDecoderManager.getInstance().findIvfPlugin(fileHeader.fourCc);
        if (plugin == null) {
            throw ParserException.createForMalformedContainer(
                    "No IVF plugin registered for FourCC: " + fourCcToString(fileHeader.fourCc),
                    /* cause= */ null);
        }
        state = STATE_FRAME_HEADER;
        return RESULT_CONTINUE;
    }

    private int readFrameHeader(ExtractorInput input) throws IOException {
        if (!input.readFully(scratch, 0, FRAME_HEADER_SIZE, /* allowEndOfInput= */ true)) {
            return RESULT_END_OF_INPUT; // clean end between frames
        }
        currentFrameSize = le32(scratch, 0);
        currentPtsUs = toMicros(le64(scratch, 4));
        sampleBytesRemaining = currentFrameSize;
        // The first frame drives sequence-header parsing (Format emission) before it is emitted as
        // a sample; every subsequent frame goes straight to sample output.
        state = formatEmitted ? STATE_SAMPLE : STATE_SEQUENCE_HEADER;
        return RESULT_CONTINUE;
    }

    private int parseSequenceHeader(ExtractorInput input) throws IOException {
        // Read position is at the first byte of the first frame payload. The plugin PEEKS only.
        VideoConfiguration cfg;
        try {
            cfg = plugin.parseHeader(input, currentFrameSize);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted parsing IVF sequence header", e);
        }
        input.resetPeekPosition(); // discard peek; read position remains at payload start
        trackOutput.format(buildFormat(cfg));
        formatEmitted = true;
        state = STATE_SAMPLE;
        return RESULT_CONTINUE;
    }

    private int readSample(ExtractorInput input) throws IOException {
        while (sampleBytesRemaining > 0) {
            int appended = trackOutput.sampleData(input, sampleBytesRemaining, /* allowEndOfInput= */ false);
            if (appended == C.RESULT_END_OF_INPUT) {
                throw ParserException.createForMalformedContainer("Truncated IVF frame", null);
            }
            sampleBytesRemaining -= appended;
        }
        int flags = firstSample ? C.BUFFER_FLAG_KEY_FRAME : 0;
        trackOutput.sampleMetadata(currentPtsUs, flags, currentFrameSize, /* offset= */ 0, /* cryptoData= */ null);
        // Emit in lockstep with video so neither sample queue starves the player.
        firstSample = false;
        state = STATE_FRAME_HEADER;
        return RESULT_CONTINUE;
    }

    /**
     * Frame rate implied by the IVF time base, or 0 when it is not credible.
     *
     * <p>Rejects rates outside 1..480 fps, which is what separates the standard
     * frames-per-second convention from writers that put a microsecond time base in the same
     * fields and carry real timestamps.
     */
    private float ivfFrameRate() {
        if (fileHeader == null) return 0f;
        long rate = fileHeader.timeBaseRate;
        long scale = fileHeader.timeBaseScale;
        if (rate <= 0 || scale <= 0) return 0f;
        double fps = rate / (double) scale;
        if (fps < 1.0 || fps > 480.0) return 0f;
        return (float) fps;
    }

    private Format buildFormat(VideoConfiguration cfg) {
        Format.Builder b = new Format.Builder()
                .setSampleMimeType(cfg.mimeType)
                .setWidth(cfg.width)
                .setHeight(cfg.height);

        // Nominal frame rate from the IVF time base. This was left unset on the grounds that
        // rate/scale are not reliable, which is true of writers that use a microsecond time base
        // (1000000/1) and carry real timestamps -- those yield an absurd rate and are rejected by
        // the plausibility test below. For every AV1/AV2 clip under test the convention holds and
        // rate/scale is the frame rate exactly: 30/1, 50/1, 30000/1000.
        //
        // Leaving it unset is not free: Format.frameRate feeds the overlay and the telemetry
        // column, both of which read -1 for AV2 while MP4-carried VVC reported correctly.
        final float fps = ivfFrameRate();
        if (fps > 0f) {
            b.setFrameRate(fps);
        }

        // Average bitrate over the whole file. The header's frame count is *displayed* frames,
        // which is what the duration depends on -- an alt-ref clip has fewer packets than frames,
        // so counting packets would overstate the rate by the alt-ref ratio.
        if (fps > 0f && fileHeader.frameCount > 0 && fileLength > 0) {
            double durationSec = fileHeader.frameCount / (double) fps;
            if (durationSec > 0.0) {
                long bps = Math.round(fileLength * 8.0 / durationSec);
                if (bps > 0 && bps <= Integer.MAX_VALUE) {
                    b.setAverageBitrate((int) bps);
                }
            }
        }
        if (cfg.initializationData != null && !cfg.initializationData.isEmpty()) {
            b.setInitializationData(cfg.initializationData);
        }
        if (cfg.colorSpace != Format.NO_VALUE
                || cfg.colorRange != Format.NO_VALUE
                || cfg.colorTransfer != Format.NO_VALUE) {
            b.setColorInfo(
                    new ColorInfo.Builder()
                            .setColorSpace(cfg.colorSpace)
                            .setColorRange(cfg.colorRange)
                            .setColorTransfer(cfg.colorTransfer)
                            .build());
        }
        return b.build();
    }

    private long toMicros(long rawTimestamp) {
        // seconds = pts * scale / rate  (rate = ticks/sec)
        long rate = fileHeader.timeBaseRate == 0 ? 1 : fileHeader.timeBaseRate;
        return rawTimestamp * 1_000_000L * fileHeader.timeBaseScale / rate;
    }

    @Override
    public void seek(long position, long timeUs) {
        // IVF is unseekable; only a reset to the start (position == 0) is expected.
        if (position == 0) {
            state = STATE_FILE_HEADER;
            firstSample = true;
            formatEmitted = false;
            sampleBytesRemaining = 0;
        }
    }

    @Override
    public void release() {
        // No resources held.
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (b[off + i] & 0xFF) << (8 * i);
        }
        return v;
    }

    private static String fourCcToString(int fourCc) {
        return "" + (char) ((fourCc >> 24) & 0xFF)
                + (char) ((fourCc >> 16) & 0xFF)
                + (char) ((fourCc >> 8) & 0xFF)
                + (char) (fourCc & 0xFF);
    }
}

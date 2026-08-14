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
    private int currentFrameSize;
    private long currentPtsUs;
    private int sampleBytesRemaining;
    private boolean firstSample = true;
    private boolean formatEmitted = false;

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
        firstSample = false;
        state = STATE_FRAME_HEADER;
        return RESULT_CONTINUE;
    }

    private Format buildFormat(VideoConfiguration cfg) {
        // Frame rate is intentionally left unset: IVF's rate/scale don't reliably give nominal fps.
        Format.Builder b = new Format.Builder()
                .setSampleMimeType(cfg.mimeType)
                .setWidth(cfg.width)
                .setHeight(cfg.height);
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

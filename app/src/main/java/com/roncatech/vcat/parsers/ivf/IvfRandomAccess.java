/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2026 vcat-d authors and RoncaTech
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

import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.util.Util;

import java.io.IOException;

/**
 * Finds random-access points -- keyframes the decoder can restart from after a flush -- in IVF
 * frames, which carry no keyframe flag of their own.
 *
 * <p>Why the host does this: ExoPlayer's skip-to-keyframe ({@code DecoderVideoRenderer
 * .maybeDropBuffersToKeyframe}) can only skip to a sample flagged {@code BUFFER_FLAG_KEY_FRAME},
 * and gives up when no flagged sample lies ahead. VcatIvfExtractor flagged only the first frame,
 * so on IVF a decoder falling behind could never skip -- a 14.9 h CPH2625 run recorded 0
 * dropped-to-keyframe events in 444 loops -- while vcat-d-ios, which finds keyframes in the
 * bitstream, does skip. Frame buffering and drop policy belong to the host, not the plugins, so
 * the keyframe rules live here too, keyed by FourCC.
 *
 * <p>The rules are vcat-d-ios's {@code AV1RandomAccess}, rule for rule, so both platforms pick
 * the same keyframes; the tests use the same fixtures. A frame qualifies only if it also
 * carries a sequence header, so a decoder that was just flushed has everything it needs.
 *
 * <p>Works by peeking: OBU headers are read and payloads skipped with the input's peek
 * position, so the frame itself is never copied. The caller resets the peek position before
 * and after.
 */
final class IvfRandomAccess {
    static final int AV01 = Util.getIntegerCodeForString("AV01");
    static final int AV02 = Util.getIntegerCodeForString("AV02");

    private IvfRandomAccess() {}

    /** Whether this FourCC has keyframe rules here; for others only the first frame is a keyframe. */
    static boolean knows(int fourCc) {
        return fourCc == AV01 || fourCc == AV02;
    }

    /**
     * Whether the frame at the input's peek position is a random-access point. False for an
     * unknown FourCC, and for a malformed or truncated frame (the sample read reports those).
     */
    static boolean isRandomAccessPoint(int fourCc, ExtractorInput input, int frameSize)
            throws IOException {
        Peek p = new Peek(input, frameSize);
        if (fourCc == AV01) return isAv1RandomAccessPoint(p);
        if (fourCc == AV02) return isAv2RandomAccessPoint(p);
        return false;
    }

    /**
     * AV1 (low-overhead bitstream format): each OBU is a header byte ({@code forbidden(1) type(4)
     * extension(1) has_size(1) reserved(1)}), an extension byte if flagged, then a leb128 size if
     * {@code has_size}. A random-access point has a sequence header (type 1) and a shown key
     * frame: the first frame header (type 3, or 6 = header + tile data) has
     * {@code show_existing_frame == 0}, {@code frame_type == KEY_FRAME (0)} and {@code show_frame},
     * or the sequence header sets {@code reduced_still_picture_header} (every frame a key frame).
     */
    private static boolean isAv1RandomAccessPoint(Peek p) throws IOException {
        boolean hasSequenceHeader = false;
        boolean reducedStillPictureHeader = false;
        while (p.remaining > 0) {
            int header = p.readByte();
            if (header < 0) return false;
            int type = (header >> 3) & 0x0F;
            boolean hasExtension = ((header >> 2) & 1) == 1;
            boolean hasSize = ((header >> 1) & 1) == 1;
            if (hasExtension && p.readByte() < 0) return false;
            long size = p.remaining;
            if (hasSize) {
                size = p.readLeb128();
                if (size < 0) return false;
            }
            if (size > p.remaining) return false;

            if (type == 1 && size > 0) {
                hasSequenceHeader = true;
                // seq_profile(3) still_picture(1) reduced_still_picture_header(1)
                int b = p.readByte();
                if (b < 0) return false;
                reducedStillPictureHeader = ((b >> 3) & 1) == 1;
                if (!p.skip(size - 1)) return false;
            } else if (type == 3 || type == 6) {
                if (!hasSequenceHeader) return false;
                if (reducedStillPictureHeader) return true;
                if (size == 0) return false;
                int b = p.readByte();
                if (b < 0) return false;
                boolean showExistingFrame = (b >> 7) == 1;
                int frameType = (b >> 5) & 0x03;
                boolean showFrame = ((b >> 4) & 1) == 1;
                return !showExistingFrame && frameType == 0 && showFrame;
            } else if (!p.skip(size)) {
                return false;
            }
        }
        return false;
    }

    /**
     * AV2 (as dav2d parses it, {@code dav2d_parse_obus}): each OBU is a leb128 size, then a
     * header byte ({@code extension(1) type(5) tlayer_id(2)}) and an extension byte if flagged,
     * all counted in the size. A random-access point has a sequence header (type 1) and a
     * closed-loop key frame (type 4, {@code OBU_CLOSED_LOOP_KF}). Open-loop key frames (type 5)
     * have leading pictures that may reference earlier frames, so they don't qualify.
     */
    private static boolean isAv2RandomAccessPoint(Peek p) throws IOException {
        boolean hasSequenceHeader = false;
        boolean hasClosedLoopKeyFrame = false;
        while (p.remaining > 0) {
            long size = p.readLeb128();
            if (size <= 0 || size > p.remaining) return false;
            int header = p.readByte();
            if (header < 0) return false;
            int type = (header >> 2) & 0x1F;
            if (type == 1) hasSequenceHeader = true;
            if (type == 4) hasClosedLoopKeyFrame = true;
            if (!p.skip(size - 1)) return false;
        }
        return hasSequenceHeader && hasClosedLoopKeyFrame;
    }

    /** A cursor over one frame's bytes through the input's peek position, bounded by its size. */
    private static final class Peek {
        private final ExtractorInput input;
        private final byte[] one = new byte[1];
        long remaining;

        Peek(ExtractorInput input, int frameSize) {
            this.input = input;
            this.remaining = frameSize;
        }

        /** The next byte, or -1 at the end of the frame or of the input. */
        int readByte() throws IOException {
            if (remaining <= 0) return -1;
            if (!input.peekFully(one, 0, 1, /* allowEndOfInput= */ true)) return -1;
            remaining--;
            return one[0] & 0xFF;
        }

        boolean skip(long n) throws IOException {
            if (n < 0 || n > remaining) return false;
            if (n > 0 && !input.advancePeekPosition((int) n, /* allowEndOfInput= */ true)) {
                return false;
            }
            remaining -= n;
            return true;
        }

        /** An unsigned leb128 value (at most 8 bytes), or -1 if it is truncated or too long. */
        long readLeb128() throws IOException {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                int b = readByte();
                if (b < 0) return -1;
                value |= (long) (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) return value;
            }
            return -1;
        }
    }
}

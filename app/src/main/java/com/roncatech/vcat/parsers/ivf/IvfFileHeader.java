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

/**
 * Parsed contents of the 32-byte IVF file header. Host-internal container metadata only — used by
 * {@link VcatIvfExtractor} for plugin routing, timestamp conversion, and progress reporting. It is
 * never passed to plugin code; the plugin derives width/height/color from the bitstream.
 *
 * <p>Width and height are intentionally omitted: the IVF file header's dimensions are unreliable,
 * so they are exclusively the plugin's responsibility to extract from the sequence header.
 *
 * <p>Timestamps: seconds = {@code pts × timeBaseScale / timeBaseRate} (rate = ticks/sec). Nominal
 * fps is not reliably {@code rate/scale} (pts can increment by {@code scale > 1}), so no frame rate
 * is derived here — the extractor leaves {@code Format.frameRate} unset.
 *
 * <p>IVF file header layout (little-endian):
 * <pre>
 *   bytes  0- 3: signature "DKIF"
 *   bytes  4- 5: version (0)
 *   bytes  6- 7: header length (32)
 *   bytes  8-11: FourCC codec identifier
 *   bytes 12-13: width   (ignored)
 *   bytes 14-15: height  (ignored)
 *   bytes 16-19: time base rate  (ticks per second, e.g. 15360)
 *   bytes 20-23: time base scale (e.g. 1)
 *   bytes 24-27: frame count
 *   bytes 28-31: unused
 * </pre>
 */
public final class IvfFileHeader {

    /** Length of the IVF file header in bytes. */
    public static final int SIZE = 32;

    public final int fourCc;
    /** Time base rate — ticks per second (IVF bytes 16-19). */
    public final int timeBaseRate;
    /** Time base scale (IVF bytes 20-23). */
    public final int timeBaseScale;
    public final int frameCount;

    public IvfFileHeader(int fourCc, int timeBaseRate, int timeBaseScale, int frameCount) {
        this.fourCc = fourCc;
        this.timeBaseRate = timeBaseRate;
        this.timeBaseScale = timeBaseScale;
        this.frameCount = frameCount;
    }

    /** Parse from the raw 32-byte IVF file header. */
    public static IvfFileHeader parse(byte[] header) {
        // FourCC is packed big-endian to match Util.getIntegerCodeForString(), which is what
        // plugins return from ivfFourCc(). Rate/scale/frame-count are little-endian per the IVF spec.
        int fourCc = be32(header, 8);
        int rate = le32(header, 16);
        int scale = le32(header, 20);
        int frames = le32(header, 24);
        return new IvfFileHeader(fourCc, rate, scale, frames);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }
}

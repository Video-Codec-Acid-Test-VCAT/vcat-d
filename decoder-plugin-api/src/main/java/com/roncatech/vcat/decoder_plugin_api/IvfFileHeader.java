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

package com.roncatech.vcat.decoder_plugin_api;

/**
 * Parsed contents of the 32-byte IVF file header.
 * Container metadata only — used by the IVF extractor for routing,
 * frame rate, and frame count. Never passed to the decoder.
 *
 * IVF file header layout:
 *   bytes  0- 3: signature "DKIF"
 *   bytes  4- 5: version (0)
 *   bytes  6- 7: header length (32)
 *   bytes  8-11: FourCC codec identifier
 *   bytes 12-13: width
 *   bytes 14-15: height
 *   bytes 16-19: timebase numerator (frame rate denominator in IVF spec)
 *   bytes 20-23: timebase denominator (frame rate numerator in IVF spec)
 *   bytes 24-27: frame count
 *   bytes 28-31: unused
 */
public final class IvfFileHeader {

    public final int fourCc;
    public final int width;
    public final int height;
    public final int timebaseNumerator;
    public final int timebaseDenominator;
    public final int frameCount;
    public final float frameRate;

    public IvfFileHeader(
            int fourCc,
            int width,
            int height,
            int timebaseNumerator,
            int timebaseDenominator,
            int frameCount) {
        this.fourCc              = fourCc;
        this.width               = width;
        this.height              = height;
        this.timebaseNumerator   = timebaseNumerator;
        this.timebaseDenominator = timebaseDenominator;
        this.frameCount          = frameCount;
        this.frameRate           = timebaseNumerator > 0
                                   ? (float) timebaseDenominator / timebaseNumerator
                                   : 0f;
    }

    /** Parse directly from the raw 32-byte IVF file header. */
    public static IvfFileHeader parse(byte[] header) {
        // bytes 8-11: FourCC (little-endian)
        int fourCc = (header[8] & 0xFF)
                   | ((header[9]  & 0xFF) << 8)
                   | ((header[10] & 0xFF) << 16)
                   | ((header[11] & 0xFF) << 24);
        // bytes 12-13: width (little-endian)
        int width  = (header[12] & 0xFF) | ((header[13] & 0xFF) << 8);
        // bytes 14-15: height (little-endian)
        int height = (header[14] & 0xFF) | ((header[15] & 0xFF) << 8);
        // bytes 16-19: timebase numerator (little-endian)
        int tbNum  = (header[16] & 0xFF) | ((header[17] & 0xFF) << 8)
                   | ((header[18] & 0xFF) << 16) | ((header[19] & 0xFF) << 24);
        // bytes 20-23: timebase denominator (little-endian)
        int tbDen  = (header[20] & 0xFF) | ((header[21] & 0xFF) << 8)
                   | ((header[22] & 0xFF) << 16) | ((header[23] & 0xFF) << 24);
        // bytes 24-27: frame count (little-endian)
        int frames = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8)
                   | ((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
        return new IvfFileHeader(fourCc, width, height, tbNum, tbDen, frames);
    }
}

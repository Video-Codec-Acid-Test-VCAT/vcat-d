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
 * ContainerParser for IVF-wrapped codecs.
 *
 * The IVF extractor matches the file header FourCC against registered
 * parsers' ivfFourCc() values, then calls parseIvfStream() to produce a
 * VideoConfiguration for the decoder.
 *
 * getContainerMimeType() defaults to "video/ivf"; the codec MIME is the
 * owning decoder's VcatDecoder.getMimeType() (not duplicated here).
 */
public interface IvfDecoderPlugin extends ContainerParser {

    @Override
    default String getContainerMimeType() {
        return "video/ivf";
    }

    /**
     * The IVF FourCC this parser handles, as an int.
     * Use Util.getIntegerCodeForString(), e.g.:
     *   "AV01" → AV1
     *   "AV02" → AV2
     *   "MLVC" → MLVC
     */
    int ivfFourCc();

    /**
     * Build a VideoConfiguration from the IVF file header and the first
     * frame payload. The parser is responsible for extracting all
     * codec-level initialization data from the first frame payload
     * (e.g. AV1 sequence header OBU, MLVC sequence config) and
     * populating VideoConfiguration accordingly.
     *
     * Width and height may be read from ivfHeader or from the first
     * frame payload — whichever is authoritative for this codec.
     *
     * Color: parse color_config from the sequence header and set
     * VideoConfiguration.Builder.colorSpace / colorRange / colorTransfer from
     * it. If the stream signals "unspecified" (or the codec carries no color
     * info), leave those fields at Format.NO_VALUE so the renderer applies its
     * normal inference. Do NOT hard-code BT.709.
     *
     * @param ivfHeader       parsed IVF file header
     * @param firstFrameBytes raw bytes of the first IVF frame payload
     * @return populated VideoConfiguration
     */
    VideoConfiguration parseIvfStream(IvfFileHeader ivfHeader, byte[] firstFrameBytes);
}

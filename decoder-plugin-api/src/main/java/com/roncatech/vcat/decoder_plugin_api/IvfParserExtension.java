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

import com.google.android.exoplayer2.extractor.ExtractorInput;

import java.io.IOException;

/**
 * ContainerParser for IVF-wrapped codecs.
 *
 * The IVF extractor matches the file header FourCC against registered
 * parsers' ivfFourCc() values, then calls parseHeader() to produce a
 * VideoConfiguration for the decoder. The host does not pass any IVF file
 * header to the plugin — the plugin derives all configuration from the
 * bitstream alone.
 *
 * getContainerMimeType() defaults to "video/ivf"; the codec MIME is the
 * owning decoder's VcatDecoder.getMimeType() (not duplicated here).
 */
public interface IvfParserExtension extends ContainerParser {

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
     * Parse codec sequence-level configuration from a stream positioned at the
     * first byte of the first frame payload.
     *
     * The implementation reads only as many bytes as needed to extract the
     * sequence header (SPS/PPS or equivalent) — typically by peeking. It must
     * not consume beyond the sequence header: the extractor feeds the full
     * frame as the first sample afterwards. The plugin extracts all
     * codec-level initialization data (e.g. AV1 sequence header OBU, MLVC
     * sequence config), width/height, and color from the bitstream.
     *
     * Color: set VideoConfiguration.Builder.colorSpace / colorRange /
     * colorTransfer from the parsed sequence header. If the stream signals
     * "unspecified" (or the codec carries no color info), leave those fields
     * at Format.NO_VALUE so the renderer applies its normal inference. Do NOT
     * hard-code BT.709.
     *
     * @param input     stream positioned at the first byte of the first frame payload
     * @param frameSize total byte length of the first frame payload
     * @return populated VideoConfiguration
     */
    VideoConfiguration parseHeader(ExtractorInput input, int frameSize)
            throws IOException, InterruptedException;
}

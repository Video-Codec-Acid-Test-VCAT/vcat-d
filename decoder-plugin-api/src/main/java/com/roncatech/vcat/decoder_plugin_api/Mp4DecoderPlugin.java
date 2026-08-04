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
 * ContainerParser for non-standard codecs carried in MP4, parsed from the {@code stsd}
 * sample entry. MP4 analog of {@link IvfDecoderPlugin}.
 *
 * <p>The MP4 parser matches a track's sample-entry FourCC against registered parsers'
 * {@link #sampleEntry4ccCode()} values, then calls {@link #parseStsd(byte[])} to produce a
 * {@link VideoConfiguration} for the decoder.
 *
 * <p>{@link #getContainerMimeType()} defaults to {@code "video/mp4"}; the codec MIME is the
 * owning decoder's {@link VcatDecoder#getMimeType()} (not duplicated here).
 */
public interface Mp4DecoderPlugin extends ContainerParser {

    @Override
    default String getContainerMimeType() { return "video/mp4"; }

    /** FourCC of the MP4 sample entry this parser handles (e.g. {@code "vvc1"}). */
    int sampleEntry4ccCode();

    /** FourCC of the codec configuration box (e.g. {@code "vvcC"}). */
    int codecConfiguration4ccCode();

    /** Parse the {@code stsd} sample-entry bytes into a {@link VideoConfiguration}. */
    VideoConfiguration parseStsd(byte[] data);
}

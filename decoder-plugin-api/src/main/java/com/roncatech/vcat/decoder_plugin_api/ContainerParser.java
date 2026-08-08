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
 * Common supertype for container parsers, so a decoder can advertise which containers it can be
 * driven from ({@link VcatDecoder#getSupportedContainerParsers()}) and the host can route by
 * container MIME without {@code instanceof}. Implemented by {@link Mp4ParserExtension} (MP4) and
 * {@link IvfDecoderPlugin} (IVF), each of which defaults {@link #getContainerMimeType()} — so
 * implementers add no MIME accessor methods. The codec MIME is the owning decoder's
 * {@link VcatDecoder#getMimeType()} and is not duplicated here.
 */
public interface ContainerParser {

    /** Container MIME this parser handles, e.g. {@code "video/mp4"} or {@code "video/ivf"}. */
    String getContainerMimeType();
}

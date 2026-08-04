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

import android.content.Context;
import android.os.Handler;

import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import java.util.Collections;
import java.util.List;

/**
 * Android SPI for a single-codec decoder. Supersedes {@link VcatDecoderPlugin}.
 *
 * <p>A decoder declares its codec once ({@link #getMimeType()}) and the container parsers it can
 * be driven from ({@link #getSupportedContainerParsers()}), decoupling the codec from the
 * delivery container (MP4, IVF, …).
 */
public interface VcatDecoder {

    String getId();
    String getDisplayName();
    String getVersion();
    String getMimeType();

    List<ContainerParser> getSupportedContainerParsers();

    Renderer createVideoRenderer(
            Context context,
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int threads
    ) throws DecoderException;

    default boolean supports(String mime) {
        return getMimeType().equals(mime);
    }

    default List<String> getExtended(String key) {
        return Collections.emptyList();
    }
}

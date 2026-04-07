/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting VCAT itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.decoder_plugin_api;

import android.content.Context;
import android.os.Handler;

import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.google.android.exoplayer2.decoder.DecoderException;

import java.util.Collections;
import java.util.List;

/** Android SPI for single-codec decoder plugins (ExoPlayer video renderer). */
public interface VcatDecoderPlugin {
    String getId();               // e.g., "vvdec", "libdav1d-av1"
    String getDisplayName();      // e.g., "vvdec VVC Decoder"
    String getVersion();          // e.g., "1.0.0"

    /** Single codec MIME this plugin decodes, e.g. "video/vvc", "video/av01". */
    String getMimeType();

    /** Profiles supported for this codec, e.g. ["Main10"] or ["Main"]. */
    List<String> getSupportedProfiles();

    /**
     * Build and return the plugin's ExoPlayer video Renderer.
     *
     * @param context Android context.
     * @param allowedJoiningTimeMs ExoPlayer join time.
     * @param eventHandler Handler for renderer callbacks.
     * @param eventListener Renderer event listener.
     * @param threads Required worker thread count (>= 1).
     * @return Renderer instance, or null if unavailable.
     */
    Renderer createVideoRenderer(
            Context context,
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int threads
    ) throws DecoderException;

    /* --- helpers --- */

    default boolean supports(String mime) { return getMimeType().equals(mime); }

    default boolean supports(String mime, String profile) {
        return supports(mime) && getSupportedProfiles().contains(profile);
    }

    /** Optional extension hook (bitDepths, tiers, hdr formats, CPU features, etc.). */
    default List<String> getExtended(String key) { return Collections.emptyList(); }
}

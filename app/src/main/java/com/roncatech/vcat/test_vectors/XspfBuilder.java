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

package com.roncatech.vcat.test_vectors;

import com.roncatech.vcat.models.TestVectorManifests;
import com.roncatech.vcat.models.TestVectorMediaAsset;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

public class XspfBuilder {
    private static final String TAG = "XspfBuilder";

    /**
     * Builds an XSPF playlist content from the given manifest and resolved media assets,
     * returning the XML as a String.
     *
     * @param playlistManifest    the parsed playlist manifest
     * @param replacedVideoAssets map from each PlaylistAsset.uuid to its local media asset
     * @return the XSPF XML content as a String
     * @throws IOException on any I/O or missing asset
     */
    public static String buildPlaylistString(
            TestVectorManifests.PlaylistManifest playlistManifest,
            Map<UUID, TestVectorMediaAsset> replacedVideoAssets
    ) {
        StringBuilder sb = new StringBuilder();

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">\n")
                .append("  <title>")
                .append(escapeXml(playlistManifest.header.name))
                .append("</title>\n")
                .append("  <trackList>\n");

        for (TestVectorManifests.PlaylistAsset pAsset : playlistManifest.mediaAssets) {
            TestVectorMediaAsset tvAsset = replacedVideoAssets.get(pAsset.uuid);
            if (tvAsset == null) {
                throw new IllegalStateException("Missing media asset for: " + pAsset.name);
            }
            String location = "file://" + tvAsset.localPath.getAbsolutePath();

            sb.append("    <track>\n")
                    .append("      <location>")
                    .append(escapeXml(location))
                    .append("</location>\n")
                    .append("      <title>")
                    .append(escapeXml(pAsset.name))
                    .append("</title>\n")
                    .append("    </track>\n");
        }

        sb.append("  </trackList>\n")
                .append("</playlist>\n");

        return sb.toString();
    }


    /**
     * Escape basic XML special chars in text nodes.
     */
    private static String escapeXml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}

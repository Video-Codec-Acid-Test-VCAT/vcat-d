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

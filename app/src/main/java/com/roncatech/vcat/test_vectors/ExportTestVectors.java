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

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.roncatech.vcat.models.TestVectorManifests;
import com.roncatech.vcat.tools.XspfParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExportTestVectors {
    private static final String TAG = "ExportTestVectors";

    public interface ExportCallback {
        void onProgress(String message);
        void onSuccess(File exportFolder);
        void onError(String errorMessage);
    }

    /**
     * Exports a playlist as a complete test vector package.
     *
     * @param context       Android context
     * @param playlistFile  The .xspf playlist file to export
     * @param stagingFolder The destination folder path
     * @param vectorName    Name for the test vector
     * @param createdBy     Creator name
     * @param description   Description of the test vector
     * @param callback      Callback for progress and completion
     */
    public static void exportPlaylist(
            Context context,
            File playlistFile,
            String stagingFolder,
            String vectorName,
            String createdBy,
            String description,
            ExportCallback callback
    ) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            try {
                mainHandler.post(() -> callback.onProgress("Starting export..."));

                // Create export folder structure
                String safeName = vectorName.replaceAll("[^a-zA-Z0-9_.-]", "_");
                File exportRoot = new File(stagingFolder, safeName);
                File mediaFolder = new File(exportRoot, "media");
                File manifestFolder = new File(exportRoot, "manifest");

                if (!exportRoot.exists() && !exportRoot.mkdirs()) {
                    throw new IOException("Failed to create export folder: " + exportRoot);
                }
                if (!mediaFolder.exists() && !mediaFolder.mkdirs()) {
                    throw new IOException("Failed to create media folder: " + mediaFolder);
                }
                if (!manifestFolder.exists() && !manifestFolder.mkdirs()) {
                    throw new IOException("Failed to create manifest folder: " + manifestFolder);
                }

                // Parse the XSPF playlist to get media file URIs
                Uri playlistUri = Uri.fromFile(playlistFile);
                List<Uri> mediaUris = XspfParser.parsePlaylist(context, playlistUri);

                if (mediaUris.isEmpty()) {
                    throw new IOException("No media files found in playlist");
                }

                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                List<TestVectorManifests.PlaylistAsset> playlistAssets = new ArrayList<>();

                // Process each media file
                int fileIndex = 0;
                for (Uri mediaUri : mediaUris) {
                    fileIndex++;
                    String fileName = getFileName(mediaUri);
                    final int idx = fileIndex;
                    final int total = mediaUris.size();
                    mainHandler.post(() -> callback.onProgress(
                            "Processing file " + idx + "/" + total + ": " + fileName));

                    // Get source file
                    File sourceFile = new File(mediaUri.getPath());
                    if (!sourceFile.exists()) {
                        throw new IOException("Source file not found: " + sourceFile);
                    }

                    // Copy to media folder
                    File destFile = new File(mediaFolder, fileName);
                    copyFile(sourceFile, destFile);

                    // Calculate checksum
                    String checksum = calculateChecksum(destFile);
                    long fileSize = destFile.length();

                    // Create VideoManifest for this file
                    UUID videoUuid = UUID.randomUUID();
                    TestVectorManifests.Header videoHeader = new TestVectorManifests.Header(
                            fileName,
                            "Video file: " + fileName,
                            createdBy
                    );

                    TestVectorManifests.VideoAsset videoAsset = new TestVectorManifests.VideoAsset(
                            fileName,
                            "media/" + fileName,
                            checksum,
                            fileSize,
                            getMimeType(fileName),
                            null,  // durationMs - unknown
                            null,  // resolutionXY - unknown
                            null   // frameRate - unknown
                    );

                    TestVectorManifests.VideoManifest videoManifest = new TestVectorManifests.VideoManifest(
                            videoHeader,
                            videoAsset
                    );

                    // Write VideoManifest to manifest folder
                    String manifestFileName = fileName.replaceAll("\\.[^.]+$", "") + ".manifest.json";
                    File manifestFile = new File(manifestFolder, manifestFileName);
                    writeJsonFile(manifestFile, gson.toJson(videoManifest));

                    // Calculate manifest checksum
                    String manifestChecksum = calculateChecksum(manifestFile);
                    long manifestSize = manifestFile.length();

                    // Create PlaylistAsset reference to this video
                    TestVectorManifests.PlaylistAsset playlistAsset = new TestVectorManifests.PlaylistAsset(
                            fileName,
                            "manifest/" + manifestFileName,
                            manifestChecksum,
                            manifestSize,
                            videoUuid,
                            "Video: " + fileName
                    );
                    playlistAssets.add(playlistAsset);
                }

                mainHandler.post(() -> callback.onProgress("Creating playlist manifest..."));

                // Create PlaylistManifest
                TestVectorManifests.Header playlistHeader = new TestVectorManifests.Header(
                        vectorName,
                        description,
                        createdBy
                );
                TestVectorManifests.PlaylistManifest playlistManifest = new TestVectorManifests.PlaylistManifest(
                        playlistHeader,
                        playlistAssets
                );

                // Write PlaylistManifest
                String playlistManifestName = safeName + "_playlist.json";
                File playlistManifestFile = new File(manifestFolder, playlistManifestName);
                writeJsonFile(playlistManifestFile, gson.toJson(playlistManifest));

                mainHandler.post(() -> callback.onProgress("Creating catalog..."));

                // Create Catalog
                TestVectorManifests.Header catalogHeader = new TestVectorManifests.Header(
                        vectorName + " Catalog",
                        "Catalog for " + vectorName,
                        createdBy
                );

                // Create catalog reference to playlist
                String playlistManifestChecksum = calculateChecksum(playlistManifestFile);
                long playlistManifestSize = playlistManifestFile.length();
                TestVectorManifests.PlaylistAsset catalogPlaylistRef = new TestVectorManifests.PlaylistAsset(
                        vectorName,
                        "manifest/" + playlistManifestName,
                        playlistManifestChecksum,
                        playlistManifestSize,
                        UUID.randomUUID(),
                        description
                );

                List<TestVectorManifests.PlaylistAsset> catalogPlaylists = new ArrayList<>();
                catalogPlaylists.add(catalogPlaylistRef);
                TestVectorManifests.Catalog catalog = new TestVectorManifests.Catalog(
                        catalogHeader,
                        catalogPlaylists
                );

                // Write Catalog
                String catalogName = safeName + "_catalog.json";
                File catalogFile = new File(manifestFolder, catalogName);
                writeJsonFile(catalogFile, gson.toJson(catalog));

                mainHandler.post(() -> callback.onProgress("Creating catalog index..."));

                // Create CatalogIndex
                TestVectorManifests.Header indexHeader = new TestVectorManifests.Header(
                        vectorName + " Index",
                        "Catalog index for " + vectorName,
                        createdBy
                );

                String catalogChecksum = calculateChecksum(catalogFile);
                long catalogSize = catalogFile.length();
                TestVectorManifests.CatalogAsset catalogAsset = new TestVectorManifests.CatalogAsset(
                        vectorName + " Catalog",
                        "manifest/" + catalogName,
                        catalogChecksum,
                        catalogSize,
                        UUID.randomUUID(),
                        "Catalog for " + vectorName
                );

                List<TestVectorManifests.CatalogAsset> indexCatalogs = new ArrayList<>();
                indexCatalogs.add(catalogAsset);
                TestVectorManifests.CatalogIndex catalogIndex = new TestVectorManifests.CatalogIndex(
                        indexHeader,
                        indexCatalogs
                );

                // Write CatalogIndex to root
                File catalogIndexFile = new File(exportRoot, "catalog_index.json");
                writeJsonFile(catalogIndexFile, gson.toJson(catalogIndex));

                mainHandler.post(() -> callback.onSuccess(exportRoot));

            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError(msg));
            } finally {
                executor.shutdown();
            }
        });
    }

    private static String getFileName(Uri uri) {
        String path = uri.getPath();
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) {
            return "video/mp4";
        } else if (lower.endsWith(".webm")) {
            return "video/webm";
        } else if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        } else if (lower.endsWith(".avi")) {
            return "video/x-msvideo";
        } else if (lower.endsWith(".mov")) {
            return "video/quicktime";
        } else if (lower.endsWith(".ts")) {
            return "video/mp2t";
        }
        return "video/mp4";
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(dest);
             FileChannel inChannel = fis.getChannel();
             FileChannel outChannel = fos.getChannel()) {

            long size = inChannel.size();
            long pos = 0;
            while (pos < size) {
                pos += inChannel.transferTo(pos, size - pos, outChannel);
            }
        }
    }

    private static String calculateChecksum(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hashBytes = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    private static void writeJsonFile(File file, String json) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(json);
        }
    }
}

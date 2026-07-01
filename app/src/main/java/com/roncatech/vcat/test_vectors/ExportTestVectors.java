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

import androidx.documentfile.provider.DocumentFile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.roncatech.vcat.models.TestVectorManifests;
import com.roncatech.vcat.tools.XspfParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExportTestVectors {
    private static final String TAG = "ExportTestVectors";

    public interface ExportCallback {
        void onProgress(String message);
        void onSuccess(Uri exportUri);
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
            Uri playlistUri,
            Uri stagingUri,
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

                String safeName = vectorName.replaceAll("[^a-zA-Z0-9_.-]", "_");

                DocumentFile stagingRoot = DocumentFile.fromTreeUri(context, stagingUri);
                if (stagingRoot == null || !stagingRoot.isDirectory()) {
                    throw new IOException("Invalid staging folder: " + stagingUri);
                }

                DocumentFile exportRoot = stagingRoot.createDirectory(safeName);
                if (exportRoot == null) throw new IOException("Failed to create export folder");
                DocumentFile mediaFolder = exportRoot.createDirectory("media");
                if (mediaFolder == null) throw new IOException("Failed to create media folder");
                DocumentFile manifestFolder = exportRoot.createDirectory("manifest");
                if (manifestFolder == null) throw new IOException("Failed to create manifest folder");

                List<Uri> mediaUris = XspfParser.parsePlaylist(context, playlistUri);
                if (mediaUris.isEmpty()) throw new IOException("No media files found in playlist");

                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                List<TestVectorManifests.PlaylistAsset> playlistAssets = new ArrayList<>();

                int fileIndex = 0;
                for (Uri mediaUri : mediaUris) {
                    fileIndex++;
                    String fileName = getFileName(mediaUri);
                    final int idx = fileIndex;
                    final int total = mediaUris.size();
                    mainHandler.post(() -> callback.onProgress(
                            "Processing file " + idx + "/" + total + ": " + fileName));

                    // Copy to media folder
                    DocumentFile destDoc = mediaFolder.createFile(getMimeType(fileName), fileName);
                    if (destDoc == null) throw new IOException("Failed to create dest file: " + fileName);
                    copyUri(context, mediaUri, destDoc.getUri());

                    String checksum = calculateChecksum(context, destDoc.getUri());
                    long fileSize = destDoc.length();

                    UUID videoUuid = UUID.randomUUID();
                    TestVectorManifests.Header videoHeader = new TestVectorManifests.Header(
                            fileName, "Video file: " + fileName, createdBy);
                    TestVectorManifests.VideoAsset videoAsset = new TestVectorManifests.VideoAsset(
                            fileName, "media/" + fileName, checksum, fileSize,
                            getMimeType(fileName), null, null, null);
                    TestVectorManifests.VideoManifest videoManifest =
                            new TestVectorManifests.VideoManifest(videoHeader, videoAsset);

                    String manifestFileName = fileName.replaceAll("\\.[^.]+$", "") + ".manifest.json";
                    DocumentFile manifestDoc = manifestFolder.createFile("application/json", manifestFileName);
                    if (manifestDoc == null) throw new IOException("Failed to create manifest: " + manifestFileName);
                    writeJson(context, manifestDoc.getUri(), gson.toJson(videoManifest));

                    String manifestChecksum = calculateChecksum(context, manifestDoc.getUri());
                    long manifestSize = manifestDoc.length();

                    playlistAssets.add(new TestVectorManifests.PlaylistAsset(
                            fileName, "manifest/" + manifestFileName,
                            manifestChecksum, manifestSize, videoUuid, "Video: " + fileName));
                }

                mainHandler.post(() -> callback.onProgress("Creating playlist manifest..."));

                TestVectorManifests.PlaylistManifest playlistManifest =
                        new TestVectorManifests.PlaylistManifest(
                                new TestVectorManifests.Header(vectorName, description, createdBy),
                                playlistAssets);

                String playlistManifestName = safeName + "_playlist.json";
                DocumentFile pmDoc = manifestFolder.createFile("application/json", playlistManifestName);
                if (pmDoc == null) throw new IOException("Failed to create playlist manifest file");
                writeJson(context, pmDoc.getUri(), gson.toJson(playlistManifest));

                mainHandler.post(() -> callback.onProgress("Creating catalog..."));

                String pmChecksum = calculateChecksum(context, pmDoc.getUri());
                long pmSize = pmDoc.length();

                List<TestVectorManifests.PlaylistAsset> catalogPlaylists = new ArrayList<>();
                catalogPlaylists.add(new TestVectorManifests.PlaylistAsset(
                        vectorName, "manifest/" + playlistManifestName,
                        pmChecksum, pmSize, UUID.randomUUID(), description));
                TestVectorManifests.Catalog catalog = new TestVectorManifests.Catalog(
                        new TestVectorManifests.Header(vectorName + " Catalog", "Catalog for " + vectorName, createdBy),
                        catalogPlaylists);

                String catalogName = safeName + "_catalog.json";
                DocumentFile catDoc = manifestFolder.createFile("application/json", catalogName);
                if (catDoc == null) throw new IOException("Failed to create catalog file");
                writeJson(context, catDoc.getUri(), gson.toJson(catalog));

                mainHandler.post(() -> callback.onProgress("Creating catalog index..."));

                String catChecksum = calculateChecksum(context, catDoc.getUri());
                long catSize = catDoc.length();

                List<TestVectorManifests.CatalogAsset> indexCatalogs = new ArrayList<>();
                indexCatalogs.add(new TestVectorManifests.CatalogAsset(
                        vectorName + " Catalog", "manifest/" + catalogName,
                        catChecksum, catSize, UUID.randomUUID(), "Catalog for " + vectorName));
                TestVectorManifests.CatalogIndex catalogIndex = new TestVectorManifests.CatalogIndex(
                        new TestVectorManifests.Header(vectorName + " Index", "Catalog index for " + vectorName, createdBy),
                        indexCatalogs);

                DocumentFile indexDoc = exportRoot.createFile("application/json", "catalog_index.json");
                if (indexDoc == null) throw new IOException("Failed to create catalog index file");
                writeJson(context, indexDoc.getUri(), gson.toJson(catalogIndex));

                final Uri exportUri = exportRoot.getUri();
                mainHandler.post(() -> callback.onSuccess(exportUri));

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

    private static void copyUri(Context ctx, Uri src, Uri dst) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(src);
             OutputStream out = ctx.getContentResolver().openOutputStream(dst)) {
            if (in == null) throw new IOException("Cannot open input: " + src);
            if (out == null) throw new IOException("Cannot open output: " + dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String calculateChecksum(Context ctx, Uri uri)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open input for checksum: " + uri);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hashBytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void writeJson(Context ctx, Uri uri, String json) throws IOException {
        try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot open output for JSON: " + uri);
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }
}

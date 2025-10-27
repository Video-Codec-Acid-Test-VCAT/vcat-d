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
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import com.google.gson.Gson;
import com.roncatech.vcat.models.TestVectorMediaAsset;
import com.roncatech.vcat.models.TestVectorManifests;
import com.roncatech.vcat.tools.UriUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
public class DownloadTestVectors {
    private static final String TAG = "DownloadTestVectors";
    private static final OkHttpClient client = new OkHttpClient();

    public interface CatalogCallback {
        void onSuccess(TestVectorManifests.Catalog catalog, String resolvedCatalogUrl);
        void onError(String errorMessage);
    }

    public interface PlaylistCallback {
        void onSuccess(TestVectorManifests.PlaylistManifest playlist);
        void onError(String errorMessage);
        void onStatusUpdate(String statusMessage);
    }

    /**
     * Reads an InputStream fully into a UTF‑8 String, using readAllBytes() on API 33+
     * or a manual loop on older devices.
     */
    private static String readStreamFully(InputStream in) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API‑33+: optimized
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } else {
            // below API‑33: fallback to manual copy
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return out.toString("UTF-8");
        }
    }


    private static String downloadJson2(Context ctx, String url) throws IOException {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Request req = new Request.Builder().url(url).build();
            Response resp = client.newCall(req).execute();
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return resp.body().string();
        }
        else if (url.startsWith("file://")) {
            File f = new File(URI.create(url));
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        else if (url.startsWith("content://")) {
            // handle Android content URIs
            Uri uri = Uri.parse(url);
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open content URI: " + url);
                return readStreamFully(in);
            }
        }
        else {
            throw new IllegalArgumentException("Unsupported URL scheme: " + url);
        }
    }
    /**
     * file‐based catalog loader.  Accepts a Uri (content:// or file://) that points
     * directly at a catalog JSON file and parses it.
     */
    public static void downloadCatalogFromFile(
            Context context,
            Uri fileUri,
            CatalogCallback callback
    ) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());

        exec.execute(() -> {
            try {
                // open the document
                try (InputStream in = context.getContentResolver().openInputStream(fileUri)) {
                    if (in == null) throw new IOException("Cannot open " + fileUri);
                    String json = readStreamAsString(in);
                    TestVectorManifests.Catalog cat =
                            new Gson().fromJson(json, TestVectorManifests.Catalog.class);

                    // hand back the Uri.toString() as “resolved”
                    main.post(() -> callback.onSuccess(cat, fileUri.toString()));
                }
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage()));
            } finally {
                exec.shutdown();
            }
        });
    }


    private static String readStreamAsString(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)
        );
        StringBuilder sb = new StringBuilder();
        for (String line; (line = reader.readLine()) != null; ) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }



    /**
     * Downloads the VCAT test‐vector catalog from the given URL and returns a parsed
     * VcatTestVectorPlaylistCatalog via the CatalogCallback.
     */
    public static void downloadCatalogHttp(
            Context context,
            String catalogUrl,
            CatalogCallback callback
    ) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            try {
                String resolvedCatalogUrl;
                String json;

                if (catalogUrl.startsWith("http://") || catalogUrl.startsWith("https://")) {
                    // need to handle http special to get redirected url as base
                    Request request = new Request.Builder()
                            .url(catalogUrl)
                            .build();
                    Response response = client.newCall(request).execute();

                    if (!response.isSuccessful()) {
                        throw new IOException("Unexpected HTTP " + response.code());
                    }

                    json = response.body().string();


                     resolvedCatalogUrl = response.request().url().toString();
                }else{
                    json = downloadJson2(context, catalogUrl);
                    resolvedCatalogUrl = catalogUrl;
                }
                TestVectorManifests.Catalog catalog =
                        new Gson().fromJson(
                                json,
                                TestVectorManifests.Catalog.class
                        );

                mainHandler.post(() -> callback.onSuccess(catalog, resolvedCatalogUrl));
            } catch (Exception e) {
                Log.e(TAG, "Failed to download catalog", e);
                String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError(msg));
            }
        });
    }

    public static TestVectorManifests.PlaylistManifest downloadPlaylistSync(
            Context context,
            String baseUrl,
            TestVectorManifests.PlaylistAsset playlistAsset,
            Map<UUID, TestVectorMediaAsset> mediaAssetTable
    ) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<TestVectorManifests.PlaylistManifest> resultRef = new AtomicReference<>();
        final AtomicReference<Exception>     errorRef  = new AtomicReference<>();

        downloadPlaylist(
                context,
                baseUrl,
                playlistAsset,
                mediaAssetTable,
                new PlaylistCallback() {
                    @Override
                    public void onStatusUpdate(String status) {
                        // ignore in sync wrapper
                    }

                    @Override
                    public void onSuccess(TestVectorManifests.PlaylistManifest playlist) {
                        resultRef.set(playlist);
                        latch.countDown();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        errorRef.set(new IOException(errorMessage));
                        latch.countDown();
                    }
                }
        );

        // wait until either success or error
        latch.await();

        if (errorRef.get() != null) {
            throw errorRef.get();
        }
        return resultRef.get();
    }

    public static Future<?> downloadPlaylist(
            Context context,
            String baseUrl,
            TestVectorManifests.PlaylistAsset playlistAsset,
            Map<UUID, TestVectorMediaAsset> videoAssetTable,
            PlaylistCallback callback
    ) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        Future<?> future = executor.submit(() -> {
            try {

                String playlistUrl = UriUtils.resolveUri(context, baseUrl, playlistAsset.url).toString();

                // 1) Download & verify playlist manifest
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                mainHandler.post(() -> callback.onStatusUpdate("Downloading playlist manifest..."));
                String playlistJson = downloadJson2(context, playlistUrl);

                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                File playlistFile = saveStringToTempFile(playlistJson, context);
                if (!verifyChecksum(playlistFile, playlistAsset.checksum)) {
                    throw new IOException("Checksum failed for playlist: " + playlistAsset.name);
                }
                mainHandler.post(() -> callback.onStatusUpdate("Playlist manifest OK."));

                // parse playlist manifest
                TestVectorManifests.PlaylistManifest playlistManifest =
                        new Gson().fromJson(
                                playlistJson,
                                TestVectorManifests.PlaylistManifest.class
                        );

                List<TestVectorMediaAsset> mediaAssets = new ArrayList<>();

                // 2) For each referenced media_asset in the playlist...
                for (TestVectorManifests.PlaylistAsset ma : playlistManifest.mediaAssets) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

                    String mediaManifestUrl = UriUtils.resolveUri(context, baseUrl, ma.url).toString();

                    // a) Download & verify the media manifest JSON
                    mainHandler.post(() -> callback.onStatusUpdate("Downloading media manifest: " + ma.name));
                    String vmJson = downloadJson2(context, mediaManifestUrl);

                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    File vmFile = saveStringToTempFile(vmJson, context);
                    if (!verifyChecksum(vmFile, ma.checksum)) {
                        throw new IOException("Checksum failed for media manifest: " + ma.name);
                    }
                    mainHandler.post(() -> callback.onStatusUpdate("Media manifest OK: " + ma.name));

                    // b) Parse the single video manifest
                    TestVectorManifests.VideoManifest videoManifest =
                            new Gson().fromJson(vmJson, TestVectorManifests.VideoManifest.class);

                    mediaAssets.add(new TestVectorMediaAsset(videoManifest, vmFile));

                    TestVectorManifests.VideoAsset va = videoManifest.mediaAsset;

                    // c) Skip if already downloaded
                    if(videoAssetTable.containsKey(videoManifest.header.uuid))
                    {
                        continue;
                    }

                    // d) Download the actual video file
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                    String mediaAssetUrl = UriUtils.resolveUri(context, baseUrl, va.url).toString();
                    mainHandler.post(() -> callback.onStatusUpdate("Downloading video file: " + va.name));
                    File videoFile = downloadToTempFile(mediaAssetUrl, context);

                    // e) Verify its checksum
                    if (!verifyChecksum(videoFile, va.checksum)) {
                        throw new IOException("Checksum failed for video file: " + va.name);
                    }
                    mainHandler.post(() -> callback.onStatusUpdate("Video file OK: " + va.name));

                    // f) Add to the shared table
                    TestVectorMediaAsset vtv =
                            new TestVectorMediaAsset(videoManifest, videoFile);
                    videoAssetTable.put(UUID.fromString(videoManifest.header.uuid), vtv);
                }

                // 3) Success: return the playlist manifest (and its temp file)
                mainHandler.post(() -> callback.onSuccess(playlistManifest));

            } catch (InterruptedException ie) {
                mainHandler.post(() -> callback.onError("Download cancelled"));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                executor.shutdownNow();
            }
        });

        return future;
    }

    /**
     * Helper: write a small String into a temp file under cacheDir
     */
    private static File saveStringToTempFile(String content, Context ctx) throws IOException {
        File tmp = File.createTempFile("testvector_", ".json", ctx.getCacheDir());
        try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
            w.write(content);
        }
        return tmp;
    }

    // Save JSON content to a temporary file
    private static File saveToTempFile(String content, Context context) throws IOException {
        File tempFile = File.createTempFile("testvector_", ".json", context.getCacheDir());
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
            writer.write(content);
        }
        return tempFile;
    }

    // Verify checksum (dummy implementation, replace with real logic)
    public static boolean verifyChecksum(File file, String expectedChecksum) {
        try {
            String actualChecksum = getChecksum(file);  // Get checksum of the file
            return actualChecksum.equals(expectedChecksum);  // Compare with the expected checksum
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Method to calculate checksum using SHA-256
    private static String getChecksum(File file) throws IOException, NoSuchAlgorithmException {
        // Initialize SHA-256 message digest
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        // Read the file and update the digest
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] byteBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(byteBuffer)) != -1) {
                digest.update(byteBuffer, 0, bytesRead); // Update the digest with file data
            }
        }

        // Compute the final hash value (SHA-256)
        byte[] hashBytes = digest.digest();

        // Convert hash bytes to hexadecimal format
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b)); // Format each byte as two hex digits
        }

        return hexString.toString(); // Return the checksum as a hexadecimal string
    }

    private static File downloadToTempFile(String url, Context ctx) throws IOException {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();

        // --- 1) Figure out a sensible "originalName" suffix ---
        String originalName = null;
        if ("content".equalsIgnoreCase(scheme)) {
            // Query the provider for a DISPLAY_NAME (this is fast)
            try (Cursor c = ctx.getContentResolver().query(
                    uri,
                    new String[]{ OpenableColumns.DISPLAY_NAME },
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    originalName = c.getString(
                            c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        if (originalName == null || originalName.isEmpty()) {
            // fallback for file:// or http:// or if query failed
            String last = uri.getLastPathSegment();
            originalName = (last == null || last.isEmpty()) ? "download" : last;
        }

        // --- 2) Create a temp file with random prefix + originalName suffix ---
        String prefix = UUID.randomUUID().toString();
        String suffix = "_" + originalName;
        File tmp = File.createTempFile(prefix, suffix, ctx.getCacheDir());

        // --- 3) Copy contents as fast as possible ---
        if ("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
            // For SAF content:// and file://, use NIO transferTo if we can
            try (ParcelFileDescriptor pfd = ctx.getContentResolver()
                    .openFileDescriptor(uri, "r");
                 FileInputStream fis  = new FileInputStream(pfd.getFileDescriptor());
                 FileOutputStream fos = new FileOutputStream(tmp)) {

                FileChannel inChan  = fis.getChannel();
                FileChannel outChan = fos.getChannel();
                long size = inChan.size();
                long pos  = 0;
                while (pos < size) {
                    pos += inChan.transferTo(pos, size - pos, outChan);
                }
            }
        } else {
            // Anything else (e.g. http://), fall back to buffered streaming
            Request req  = new Request.Builder().url(url).build();
            Response resp = client.newCall(req).execute();
            if (!resp.isSuccessful()) {
                throw new IOException("Failed to download "+ url +": HTTP "+ resp.code());
            }

            try (InputStream in = resp.body().byteStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {

                byte[] buf = new byte[64*1024];  // larger buffer for speed
                int   r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.getFD().sync();
            }
        }

        return tmp;
    }


    private static InputStream openStreamForUri(Context ctx, Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            // fall back to OkHttp for HTTP downloads
            Request  req  = new Request.Builder().url(uri.toString()).build();
            Response resp = client.newCall(req).execute();
            if (!resp.isSuccessful()) {
                throw new IOException("Failed to download " + uri + ": HTTP " + resp.code());
            }
            return resp.body().byteStream();
        }
        else if ("file".equals(scheme)) {
            return new FileInputStream(new File(uri.getPath()));
        }
        else if ("content".equals(scheme)) {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("Unable to open content URI: " + uri);
            }
            return in;
        }
        else {
            throw new IOException("Unsupported URI scheme: " + scheme);
        }
    }



}

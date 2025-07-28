package com.roncatech.vcat.test_vectors;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.roncatech.vcat.models.TestVectorMediaAsset;
import com.roncatech.vcat.models.TestVectorManifests;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
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
        void onSuccess(TestVectorManifests.Catalog catalog);
        void onError(String errorMessage);
    }

    public interface PlaylistCallback {
        void onSuccess(TestVectorManifests.PlaylistManifest playlist);
        void onError(String errorMessage);
        void onStatusUpdate(String statusMessage);
    }

    /**
     * Downloads the VCAT test‐vector catalog from the given URL and returns a parsed
     * VcatTestVectorPlaylistCatalog via the CatalogCallback.
     */
    public static void downloadCatalog(
            Context context,
            String catalogUrl,
            CatalogCallback callback
    ) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(catalogUrl)
                        .build();
                Response response = client.newCall(request).execute();

                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected HTTP " + response.code());
                }

                String json = response.body().string();
                TestVectorManifests.Catalog catalog =
                        new Gson().fromJson(
                                json,
                                TestVectorManifests.Catalog.class
                        );

                mainHandler.post(() -> callback.onSuccess(catalog));
            } catch (Exception e) {
                Log.e(TAG, "Failed to download catalog", e);
                String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError(msg));
            }
        });
    }

    public static TestVectorManifests.PlaylistManifest downloadPlaylistSync(
            Context context,
            TestVectorManifests.PlaylistAsset playlistAsset,
            Map<UUID, TestVectorMediaAsset> mediaAssetTable
    ) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<TestVectorManifests.PlaylistManifest> resultRef = new AtomicReference<>();
        final AtomicReference<Exception>     errorRef  = new AtomicReference<>();

        downloadPlaylist(
                context,
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
            TestVectorManifests.PlaylistAsset playlistAsset,
            Map<UUID, TestVectorMediaAsset> videoAssetTable,
            PlaylistCallback callback
    ) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        Future<?> future = executor.submit(() -> {
            try {
                // 1) Download & verify playlist manifest
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                mainHandler.post(() -> callback.onStatusUpdate("Downloading playlist manifest..."));
                String playlistJson = downloadJson(playlistAsset.url);

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

                    // a) Download & verify the media manifest JSON
                    mainHandler.post(() -> callback.onStatusUpdate("Downloading media manifest: " + ma.name));
                    String vmJson = downloadJson(ma.url);

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
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    mainHandler.post(() -> callback.onStatusUpdate("Downloading video file: " + va.name));
                    File videoFile = downloadToTempFile(va.url, context);

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

    // Download JSON from the URL
    private static String downloadJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        Response response = client.newCall(request).execute();

        if (!response.isSuccessful()) {
            throw new IOException("Failed to download JSON from " + url);
        }

        return response.body().string();
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
        Request req = new Request.Builder().url(url).build();
        Response resp = client.newCall(req).execute();
        if (!resp.isSuccessful()) {
            throw new IOException("Failed to download " + url + ": HTTP " + resp.code());
        }

        // Extract the original file name from the URL
        String originalName = url.substring(url.lastIndexOf('/') + 1);

        // Use a random UUID as the prefix, and “_originalName” as the suffix
        String prefix = UUID.randomUUID().toString();
        String suffix = "_" + originalName;

        File tmp = File.createTempFile(prefix, suffix, ctx.getCacheDir());
        try (InputStream in = resp.body().byteStream();
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        }
        return tmp;
    }


}

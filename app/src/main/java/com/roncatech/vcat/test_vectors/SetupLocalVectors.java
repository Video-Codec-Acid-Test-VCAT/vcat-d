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

package com.roncatech.vcat.test_vectors;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.roncatech.vcat.models.TestVectorManifests;
import com.roncatech.vcat.models.TestVectorMediaAsset;
import com.roncatech.vcat.tools.StorageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SetupLocalVectors {

    private final static String TAG = "SetupLocalVectors";

    /**
     * Moves each temp‑downloaded media asset into /sdcard/vcat/media/…
     * preserving any sub‑folders under "media/".  If the file already exists,
     * it’s checksum‑verified; if it matches, we reuse it, otherwise we fail.
     *
     * @param tempAssets the list of media assets (manifest + tempFile)
     * @return a new list of TestVectorMediaAsset pointing to the permanent files
     * @throws IOException on any I/O or checksum mismatch
     */
    public static Map<UUID, TestVectorMediaAsset> relocateMediaAssets(
            Context ctx,
            TestVectorManifests.PlaylistManifest playlist,
            Map<UUID, TestVectorMediaAsset> assets
    ) {
        Map<UUID, TestVectorMediaAsset> result = new HashMap<>();

        DocumentFile baseDir = StorageManager.getFolder(ctx, StorageManager.VCATFolder.MEDIA);
        if (baseDir == null) {
            Log.e(TAG, "MEDIA folder not available");
            return result;
        }

        for (TestVectorManifests.PlaylistAsset cur : playlist.mediaAssets) {
            TestVectorMediaAsset curAsset = assets.get(cur.uuid);
            if (curAsset == null) continue;
            TestVectorManifests.VideoManifest vm = curAsset.manifest;
            String assetUrl = vm.mediaAsset.url;

            // 1) compute relative path under "media/…"
            String relPath;
            int idx = assetUrl.indexOf("/media/");
            if (idx >= 0) {
                relPath = assetUrl.substring(idx + "/media/".length());
            } else {
                relPath = vm.mediaAsset.name;
            }

            // 2) resolve parent DocumentFile directory (create if absent)
            String[] parts = relPath.split("/");
            String fileName = parts[parts.length - 1];
            DocumentFile parentDir = baseDir;
            for (int i = 0; i < parts.length - 1; i++) {
                String dirName = parts[i];
                DocumentFile sub = parentDir.findFile(dirName);
                if (sub == null || !sub.isDirectory()) {
                    sub = parentDir.createDirectory(dirName);
                }
                if (sub == null) {
                    Log.e(TAG, "Unable to create directory: " + dirName);
                    return result;
                }
                parentDir = sub;
            }

            DocumentFile destDocFile = parentDir.findFile(fileName);

            // 3) if dest exists, verify checksum
            if (destDocFile != null && destDocFile.isFile()) {
                if (!DownloadTestVectors.verifyChecksum(ctx, destDocFile.getUri(), vm.mediaAsset.checksum)) {
                    Log.e(TAG, "Local file exists but checksum does not match: " + destDocFile.getUri());
                    return result;
                }
            } else {
                // 4) create dest and copy from temp source
                destDocFile = parentDir.createFile(getMimeType(fileName), fileName);
                if (destDocFile == null) {
                    Log.e(TAG, "Failed to create destination file: " + fileName);
                    return result;
                }
                try {
                    copyUri(ctx, curAsset.localUri, destDocFile.getUri());
                    // 5) verify freshly copied
                    if (!DownloadTestVectors.verifyChecksum(ctx, destDocFile.getUri(), vm.mediaAsset.checksum)) {
                        Log.e(TAG, "Checksum mismatch after copy: " + fileName);
                        destDocFile.delete();
                        return result;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Exception during copy of " + curAsset.localUri + ": " + e.getLocalizedMessage());
                    return result;
                }
            }

            // 6) add to result
            result.put(cur.uuid, new TestVectorMediaAsset(vm, destDocFile.getUri()));
        }

        return result;
    }

    private static void copyUri(Context ctx, Uri src, Uri dst) throws IOException {
        try (InputStream in = openInputStream(ctx, src);
             OutputStream out = ctx.getContentResolver().openOutputStream(dst)) {
            if (in == null) throw new IOException("Cannot open source: " + src);
            if (out == null) throw new IOException("Cannot open dest: " + dst);
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
    }

    private static InputStream openInputStream(Context ctx, Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            return new FileInputStream(new File(uri.getPath()));
        }
        return ctx.getContentResolver().openInputStream(uri);
    }

    private static String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }
}


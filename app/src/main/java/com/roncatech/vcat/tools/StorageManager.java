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

package com.roncatech.vcat.tools;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class StorageManager {
    private static final String TAG = "StorageManager";

    public enum VCATFolder {
        PLAYLIST("playlist"),
        MEDIA("media"),
        TEST_RESULTS("test_results");

        private final String folderName;

        VCATFolder(String folderName) {
            this.folderName = folderName;
        }

        public String getFolderName() {
            return folderName;
        }
    }

    private static Uri sRootTreeUri = null;

    /** Call once at startup after the SAF root URI is granted. */
    public static void init(Context ctx, Uri rootTreeUri) {
        sRootTreeUri = rootTreeUri;
        Log.i(TAG, "root_folder=" + rootTreeUri);
        DocumentFile root = DocumentFile.fromTreeUri(ctx, rootTreeUri);
        if (root == null || !root.isDirectory()) {
            Log.e(TAG, "Root is not a directory: " + rootTreeUri);
            return;
        }
        // Delegate to getFolder() rather than repeating findFile/createDirectory here. That
        // pairing is what produced eight "test_results (N)" duplicates on a Galaxy S25: findFile
        // can miss a directory that exists, and SAF's createDirectory renames on collision
        // instead of failing. This ran on every startup, so each launch could add another.
        FOLDER_CACHE.clear();          // the root may have changed since the last init
        for (VCATFolder folder : VCATFolder.values()) {
            DocumentFile sub = getFolder(ctx, folder);
            if (sub == null) {
                Log.e(TAG, "folder unavailable: " + folder.getFolderName());
            } else {
                Log.d(TAG, "folder ready: " + folder.getFolderName());
            }
        }
    }

    @Nullable
    public static DocumentFile getRoot(Context ctx) {
        if (sRootTreeUri == null) return null;
        return DocumentFile.fromTreeUri(ctx, sRootTreeUri);
    }

    @Nullable
    public static Uri getRootUri() {
        return sRootTreeUri;
    }

    /**
     * Best-effort conversion of the SAF root tree URI to an absolute filesystem path that
     * tools like {@code adb pull} / {@code adb shell ls} can use. SAF only hands us a
     * {@code content://} tree URI, but for the common {@code externalstorage} provider the
     * tree document id is {@code <volume>:<relative/path>}, which maps directly onto a real
     * path. Returns {@code null} if no root is set or the volume can't be resolved.
     */
    @Nullable
    public static String getRootFsPath() {
        if (sRootTreeUri == null) return null;
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(sRootTreeUri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (docId == null) return null;

        String volume;
        String relPath;
        int colon = docId.indexOf(':');
        if (colon >= 0) {
            volume = docId.substring(0, colon);
            relPath = docId.substring(colon + 1);
        } else {
            volume = docId;
            relPath = "";
        }

        String base;
        if ("primary".equalsIgnoreCase(volume)) {
            // /sdcard is the well-known symlink to primary shared storage and is the form
            // most convenient for adb pull / adb shell ls.
            base = "/sdcard";
        } else {
            // Removable/secondary volumes are mounted under /storage/<volumeId>.
            base = "/storage/" + volume;
        }
        return TextUtils.isEmpty(relPath) ? base : base + "/" + relPath;
    }

    /**
     * Resolved sub-folders, keyed by folder then root URI.
     *
     * <p>Cached because every miss risks creating a duplicate, and because the lookup lists the
     * whole tree. Keyed on the root so re-granting a different folder does not return a stale
     * handle.
     */
    private static final java.util.Map<String, DocumentFile> FOLDER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns the DocumentFile for a sub-folder, creating it only if genuinely absent.
     *
     * <p>This used to be {@code root.findFile(name)} and, on null, {@code createDirectory(name)}.
     * {@link DocumentFile#findFile(String)} can return null for a directory that exists — it
     * queries a provider that may answer from a stale tree — and SAF's createDirectory does not
     * fail on collision, it silently renames. The result on a Galaxy S25 was eight folders named
     * {@code test_results}, {@code test_results (1)} … {@code test_results (8)} and two
     * {@code playlist} duplicates, created in about a minute of opening the Logs panel. The app
     * then read and wrote the newest empty one, so the logs list came up blank while every file
     * sat safely in the original.
     *
     * <p>Resolution is now by listing the root and matching the name, which does not depend on
     * the provider answering a targeted query, and the result is cached so a miss cannot be
     * repeated. A rename on create is detected and reported rather than returned silently.
     */
    @Nullable
    public static DocumentFile getFolder(Context ctx, VCATFolder folder) {
        DocumentFile root = getRoot(ctx);
        if (root == null || !root.isDirectory()) return null;

        final String wanted = folder.getFolderName();
        final String key = root.getUri() + "|" + wanted;

        DocumentFile cached = FOLDER_CACHE.get(key);
        if (cached != null && cached.exists() && cached.isDirectory()) return cached;

        // Exact-name match over the listing. Any "name (1)" duplicate is deliberately ignored:
        // the canonical folder is the one holding the data.
        DocumentFile[] kids = root.listFiles();
        if (kids != null) {
            for (DocumentFile f : kids) {
                // Match on name alone. isDirectory() was in this test and cost us: it issues a
                // second provider query per child, and when that answers wrongly the folder
                // looks absent and a duplicate gets created. An exact name collision with a
                // plain file is far less likely than that, and far less damaging.
                if (wanted.equals(f.getName())) {
                    FOLDER_CACHE.put(key, f);
                    return f;
                }
            }
        }
        // Nothing matched. Record what the listing actually returned before creating anything --
        // an empty listing on a folder known to have children is the signature of a provider
        // answering from a stale tree, and that is worth seeing in a bug report.
        StringBuilder seen = new StringBuilder();
        if (kids == null) {
            seen.append("<listFiles() returned null>");
        } else if (kids.length == 0) {
            seen.append("<empty>");
        } else {
            for (DocumentFile f : kids) seen.append('[').append(f.getName()).append(']');
        }
        Log.w(TAG, "folder '" + wanted + "' not found under " + root.getUri()
                + "; listing returned " + seen);

        DocumentFile made = root.createDirectory(wanted);
        if (made == null) {
            Log.e(TAG, "could not create folder " + wanted);
            return null;
        }
        if (!wanted.equals(made.getName())) {
            // SAF renamed it, so something with that name already existed and the listing missed
            // it. Refusing to return it is not enough: createDirectory has already created the
            // directory on disk, so merely declining leaves litter behind and the next call
            // makes another. Delete what we just made, then report failure.
            String renamed = made.getName();
            boolean removed = made.delete();
            Log.e(TAG, "createDirectory(" + wanted + ") was renamed to " + renamed
                    + " -- the listing is not reporting existing children. Created directory "
                    + (removed ? "removed." : "could NOT be removed; delete it by hand."));
            return null;
        }
        FOLDER_CACHE.put(key, made);
        return made;
    }

    /**
     * Returns the most-recently created telemetry CSV in the TEST_RESULTS folder,
     * or null if none found.
     */
    @Nullable
    public static DocumentFile findLatestLogFile(Context ctx) {
        DocumentFile dir = getFolder(ctx, VCATFolder.TEST_RESULTS);
        if (dir == null) return null;

        DocumentFile[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            Log.w(TAG, "No files in TEST_RESULTS folder");
            return null;
        }

        DocumentFile latest = null;
        long maxTs = -1L;
        for (DocumentFile file : files) {
            String name = file.getName();
            if (name == null || !name.startsWith("vcatd_log_") || !name.endsWith(".csv")) continue;
            try {
                long ts = Long.parseLong(name.substring("vcatd_log_".length(), name.length() - ".csv".length()));
                if (ts > maxTs) {
                    maxTs = ts;
                    latest = file;
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Skipping invalid log file name: " + name);
            }
        }

        if (latest == null) {
            Log.w(TAG, "No valid vcatd_log_*.csv found");
        }
        return latest;
    }

    /**
     * Reads the very last timestamp (first CSV column) from a SAF-based telemetry CSV.
     * Scans the file line by line, returning the timestamp from the last non-empty line.
     */
    public static long readLastTimestamp(Context ctx, DocumentFile csvFile) {
        try (InputStream is = ctx.getContentResolver().openInputStream(csvFile.getUri());
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String lastLine = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lastLine = line.trim();
                }
            }
            if (lastLine == null) {
                return -1L;
            }
            String tsStr = lastLine.split(",", 2)[0].trim();
            try {
                return Long.parseLong(tsStr);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Last line timestamp not a valid long: '" + tsStr + "'");
                return -1L;
            }
        } catch (IOException e) {
            Log.e(TAG, "I/O error reading last timestamp", e);
            return -1L;
        }
    }
}

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
        for (VCATFolder folder : VCATFolder.values()) {
            DocumentFile sub = root.findFile(folder.getFolderName());
            if (sub == null || !sub.isDirectory()) {
                root.createDirectory(folder.getFolderName());
                Log.d(TAG, "Created subfolder: " + folder.getFolderName());
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
     * Returns the DocumentFile for a sub-folder, creating it if absent.
     */
    @Nullable
    public static DocumentFile getFolder(Context ctx, VCATFolder folder) {
        DocumentFile root = getRoot(ctx);
        if (root == null || !root.isDirectory()) return null;
        DocumentFile sub = root.findFile(folder.getFolderName());
        if (sub == null || !sub.isDirectory()) {
            sub = root.createDirectory(folder.getFolderName());
        }
        return sub;
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
            if (name == null || !name.startsWith("logs_") || !name.endsWith(".csv")) continue;
            try {
                long ts = Long.parseLong(name.substring("logs_".length(), name.length() - ".csv".length()));
                if (ts > maxTs) {
                    maxTs = ts;
                    latest = file;
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Skipping invalid log file name: " + name);
            }
        }

        if (latest == null) {
            Log.w(TAG, "No valid logs_*.csv found");
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

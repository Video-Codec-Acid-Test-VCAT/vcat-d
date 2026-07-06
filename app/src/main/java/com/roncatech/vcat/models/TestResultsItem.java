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

package com.roncatech.vcat.models;

import android.net.Uri;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TestResultsItem {
    private final long timestampMillis;
    private final String filePath;

    /**
     * Extracts the Unix timestamp from the file path or content:// URI string.
     * The file name format is "vcatd_log_<unixtime>.csv".
     */
    public static long getTimeStamp(String filePath){
        String name = getFileName(filePath);
        int start = name.lastIndexOf('_') + 1;
        int end = name.lastIndexOf('.');
        if (start <= 0 || end < 0 || start >= end) return -1;
        try {
            return Long.parseLong(name.substring(start, end));
        } catch(NumberFormatException unused){
            return -1;
        }
    }

    private static String getFileName(String filePath) {
        if (filePath != null && filePath.startsWith("content://")) {
            Uri uri = Uri.parse(filePath);
            String lastSegment = uri.getLastPathSegment();
            if (lastSegment != null) {
                String decoded = Uri.decode(lastSegment);
                int lastSlash = decoded.lastIndexOf('/');
                int lastColon = decoded.lastIndexOf(':');
                int nameStart = Math.max(lastSlash, lastColon) + 1;
                return decoded.substring(nameStart);
            }
            return "unknown";
        }
        return new File(filePath).getName();
    }

    public TestResultsItem(String filePath, long timestampMillis) {
        this.filePath = filePath;
        this.timestampMillis = timestampMillis;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getDisplayTime() {
        SimpleDateFormat sdf = new SimpleDateFormat(
                "dd MMMM yyyy HH:mm:ss",
                Locale.getDefault()
        );
        return sdf.format(new Date(timestampMillis)) + " (" + getFileName(this.filePath) + ")";
    }
}

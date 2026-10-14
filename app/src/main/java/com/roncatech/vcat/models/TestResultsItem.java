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
import androidx.annotation.Nullable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TestResultsItem {
    private final long timestampMillis;
    private final String filePath;
    private final long sizeBytes;
    /** Index entry for this log, when test_summary.json has one. Null for an unsummarised log. */
    @Nullable private TestSummary.Entry summary;

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

    /** Decodes the bare file name out of a path or a SAF content:// document URI. */
    public static String getFileName(String filePath) {
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

    public TestResultsItem(String filePath, long timestampMillis, long sizeBytes) {
        this.filePath = filePath;
        this.timestampMillis = timestampMillis;
        this.sizeBytes = sizeBytes;
    }

    public void setSummary(@Nullable TestSummary.Entry s) { this.summary = s; }

    @Nullable public TestSummary.Entry getSummary() { return this.summary; }

    /**
     * Primary row label: start, run length and codec, e.g.
     * {@code "26/10/14 04:33:21  -  8h 01m  -  VVC"}.
     *
     * <p>24-hour clock, seconds included: several runs can share a day, and the start time is
     * what matches a row to a telemetry file. It leaves portrait too narrow for most decoder
     * names, which is why the decoder is in its own ellipsized view and reads whole in landscape.
     *
     * <p>Falls back to the file name when the log has no summary entry, which happens only until
     * the next reconciliation backfills it. The file name carries nothing but an epoch
     * timestamp, so finding a particular run meant opening files one at a time.
     */
    public String getDisplayLabel() {
        if (this.summary == null) return getDisplayName();
        String when = new SimpleDateFormat("yy/MM/dd HH:mm:ss", Locale.US)
                .format(new Date(this.summary.startTimeMs > 0
                        ? this.summary.startTimeMs : this.timestampMillis));
        String codec = TestSummary.codecLabel(this.summary.codec);
        return when + "  -  " + TestSummary.formatDuration(this.summary.durationSec) + "  -  " + codec;
    }

    /**
     * Trailing row label: the decoder, on the same line as {@link #getDisplayLabel()}.
     *
     * <p>Kept in its own view so it is the part that gives way when the row runs out of width.
     * Only one decoder name is long enough to need it
     * ({@code vcat-dav2d-ee033a4@salomethirot-arm}); the rest fit whole.
     */
    public String getDisplaySubtitle() {
        if (this.summary == null) return getDisplaySize();
        return (this.summary.decoder == null) ? "" : this.summary.decoder;
    }

    /**
     * Date the row sorts by, which must be the one it displays.
     *
     * <p>The file name epoch and the recorded start time can disagree -- a log copied in, or one
     * whose run began before the file was opened -- and sorting on the name while showing the
     * summary put a row visibly out of order.
     */
    public long getSortTimeMillis() {
        return (this.summary != null && this.summary.startTimeMs > 0)
                ? this.summary.startTimeMs : this.timestampMillis;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    /** File name of this log, e.g. "vcatd_log_1757345025.csv". */
    public String getFileName() {
        return getFileName(this.filePath);
    }

    /**
     * Formats {@link #getSizeBytes()} as megabytes with a single decimal digit, rounded up
     * (e.g. "0.1 MB", "12.3 MB"). Rounding up so a non-empty log never reads "0.0 MB".
     */
    public String getDisplaySize() {
        double mb = sizeBytes / (1024.0 * 1024.0);
        double rounded = Math.ceil(mb * 10.0) / 10.0;
        return String.format(Locale.US, "%.1f MB", rounded);
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public String getFilePath() {
        return filePath;
    }

    /**
     * Row label for the logs list: the file name without its extension, e.g.
     * "vcatd_log_1788415095639". Matches vcat-ai's Logs tab. The timestamp is still parsed from
     * the name into {@link #getTimestampMillis()}, which is what the list sorts on.
     */
    public String getDisplayName() {
        String name = getFileName(this.filePath);
        return name.toLowerCase(Locale.US).endsWith(".csv")
                ? name.substring(0, name.length() - 4)
                : name;
    }
}

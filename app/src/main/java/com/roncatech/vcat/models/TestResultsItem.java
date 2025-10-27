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

package com.roncatech.vcat.models;

import java.io.File;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TestResultsItem {
    private final long timestampMillis;
    private final String filePath;

    public static long getTimeStamp(String filePath){
        String name = new File(filePath).getName();        // "log_<unixtime>.csv"
        int start = name.indexOf('_') + 1;
        int end = name.lastIndexOf('.');
        String tsPart = name.substring(start, end);        // "<unixtime>"

        try {
            return Long.parseLong(tsPart);
        } catch(NumberFormatException unused){
            return -1;
        }
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
        return sdf.format(new Date(timestampMillis)) + " (" + (new File(this.filePath).getName()) + ")";
    }
}

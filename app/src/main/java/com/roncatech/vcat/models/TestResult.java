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

import com.opencsv.CSVReaderHeaderAware;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.roncatech.vcat.tools.SequenceReader;
import android.util.Log;

public class TestResult {
    private static String TAG = "TestResult";
    private final SessionHeader sessionHeader;

    public SessionHeader getSessionHeader(){return this.sessionHeader;}
    private final List<Map<String,String>> telemetryRows;
    public List<Map<String,String>> getTelemetryRows(){return this.telemetryRows;}

    public TestResult(SessionHeader sessionHeader, List<Map<String,String>> telemetryRows){
        this.sessionHeader = sessionHeader;
        this.telemetryRows = telemetryRows;
    }

    public static TestResult fromLogFile(File telemetryFile){
        BufferedReader r = null;
        Reader csvSource = null;

        try{
            r = new BufferedReader(new FileReader(telemetryFile));

            // read session header
            SessionHeader sh = SessionHeader.fromLogFile(r);

            if(sh == null){
                Log.e(TAG, "Error reading session header");
                return null;
            }

            List<Map<String,String>> telemetryRows = new ArrayList<>();

            // find csv header row
            String headerLine = null;
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("test.timestamp")) {
                    headerLine = line;
                    break;
                }
            }

            if(headerLine == null){
                Log.e(TAG, "CSV Header not found");
                return null;
            }

            csvSource = new SequenceReader(
                    new StringReader(headerLine+"\n"),
                    r
            );

            try (CSVReaderHeaderAware csv = new CSVReaderHeaderAware(csvSource)) {

                Map<String, String> row;
                while ((row = csv.readMap()) != null) {
                    telemetryRows.add(row);
                }
            }catch (com.opencsv.exceptions.CsvException e){
                Log.e(TAG, "Error parsing CSV: "+ e.getLocalizedMessage());
                return null;
            }


            return new TestResult(sh, telemetryRows);
        } catch (IOException e) {
            // I/O error reading file
            return null;
        }
        finally{
            if(r != null) {
                try {
                    r.close();
                } catch(IOException ignored){}
            }

            if(csvSource != null){
                try {
                    csvSource.close();
                } catch (IOException ignored) {}
            }
        }
    }
}


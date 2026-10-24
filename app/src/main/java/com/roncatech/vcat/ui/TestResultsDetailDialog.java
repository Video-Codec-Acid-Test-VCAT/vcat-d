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

package com.roncatech.vcat.ui;

import android.net.Uri;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.TooltipCompat;
import androidx.fragment.app.DialogFragment;
import com.roncatech.vcat.R;

import com.roncatech.vcat.models.TestResult;
import com.roncatech.vcat.models.SessionInfo;

public class TestResultsDetailDialog extends DialogFragment {
    private static final String ARG_PATH = "file_path";

    private TestResult results;
    private String filePath;

    /**
     * Public and no-argument because the framework re-instantiates fragments itself.
     *
     * <p>On a configuration change -- rotating the device -- the activity is destroyed and every
     * fragment is rebuilt from saved state via {@code Fragment.instantiate()}, which looks up
     * exactly this constructor by reflection. A private one taking the file path left none to
     * find, so rotating with the details open crashed the app before it could restore:
     * {@code Fragment$InstantiationException ... NoSuchMethodException: <init> []}.
     *
     * <p>The path survives in the arguments bundle instead, which is what {@link #onCreate}
     * already read it from.
     */
    public TestResultsDetailDialog() {
        super();
    }

    public static TestResultsDetailDialog newInstance(String filePath) {
        TestResultsDetailDialog frag = new TestResultsDetailDialog();
        Bundle args = new Bundle();
        args.putString(ARG_PATH, filePath);
        frag.setArguments(args);
        return frag;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            filePath = getArguments().getString(ARG_PATH);
        }
    }

    /** Bitrate for display: "n/a" for the unset forms older logs carry. */
    private static String displayBitrate(String raw) {
        if (raw == null) return "n/a";
        String v = raw.trim();
        if (v.isEmpty() || "none".equals(v) || "empty".equals(v)
                || v.startsWith("-1") || "Unknown".equals(v)) {
            return "n/a";
        }
        return v;
    }

    static class TestFile{
        public final String fileName;
        public final String resolution;
        public final String codec;
        public final String decoder;
        public final String frameRate;
        /** As written to the log, e.g. "1.35 Mbps"; "none" on rows that predate the column. */
        public final String bitrate;
        public final int systemThermalStatus;
        public final double batteryThermalStatus;
        public final int frameDrops;



        public TestFile(String filename,
                        String resolution,
                        String codec,
                        String decoder,
                        String frameRate,
                        String bitrate,
                        int systemThermalStatus,
                        double batteryThermalStatus,
                        int frameDrops){
            this.fileName = filename;
            this.resolution = resolution;
            this.codec = codec;
            this.decoder = decoder;
            this.frameRate = frameRate;
            this.bitrate = bitrate;
            this.systemThermalStatus = systemThermalStatus;
            this.batteryThermalStatus = batteryThermalStatus;
            this.frameDrops = frameDrops;
        }

        private static int parseIntOr0(String strVal){
            try{
                return Integer.parseInt(strVal);
            }
            catch (NumberFormatException e){
                return 0;
            }
        }

        private static double parseDoubleOr0(String strVal){
            try{
                return Double.parseDouble(strVal);
            }
            catch (NumberFormatException e){
                return 0;
            }
        }

        /** True for the placeholders a row carries when no clip identity was available. */
        static boolean isUnnamed(String name){
            return name == null || name.trim().isEmpty()
                    || "none".equals(name) || "empty".equals(name);
        }

        public static TestFile fromCsvRow(Map<String,String> row){
            String videoPath = row.getOrDefault("test.filename", "none");
            if(videoPath.equals("none")){
                // try legacy
                videoPath = row.getOrDefault("video.filename", "none");
            }

            String displayName;
            if (videoPath.startsWith("content://")) {
                String seg = Uri.parse(videoPath).getLastPathSegment();
                if (seg != null) {
                    String decoded = Uri.decode(seg);
                    int cut = Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf(':'));
                    displayName = decoded.substring(cut + 1);
                } else {
                    displayName = videoPath;
                }
            } else {
                displayName = new File(videoPath).getName();
            }
            return new TestFile(
                    displayName,
                    row.getOrDefault("video.resolution", "none"),
                    row.getOrDefault("video.codec_name", "none"),
                    row.getOrDefault("video.decoder_name", "none"),
                    row.getOrDefault("video.framerate", "none"),
                    row.getOrDefault("video.bitrate", "none"),
                    parseIntOr0(row.getOrDefault("system.thermal_status", "0")),
                    parseDoubleOr0(row.getOrDefault("battery.temperature", "0")),
                    parseIntOr0(row.getOrDefault("video.frames_dropped", "0"))
            );
        }
    }

    static int greenYellowOrRed(double gmax, double ymax, double val){
        if(val < gmax){
            return Color.GREEN;
        }
        else if(val < ymax){
            return Color.YELLOW;
        }

        return Color.RED;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        this.results = TestResult.fromLogFile(requireContext(), Uri.parse(filePath));

        View content = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_test_results_detail, null);

        TextView logFile    = content.findViewById(R.id.tvLogFile);
        TextView tvStart    = content.findViewById(R.id.tvStartTime);
        TextView tvDuration = content.findViewById(R.id.tvTestDuration);
        TextView tvBatStart = content.findViewById(R.id.tvBattery);

        logFile.setText("Log File: " + Uri.parse(this.filePath).getLastPathSegment());

        if(this.results != null && results.getTelemetryRows().size() > 0){
            SessionInfo.StartTime startTime = results.getSessionHeader().getSessionInfo().start_time;
            long lastTime = Long.parseLong(results.getTelemetryRows().get(results.getTelemetryRows().size()-1).get("test.timestamp"));
            long durationSecs = (lastTime - startTime.unix_time_ms)/1000;
            double batteryStart = this.results.getSessionHeader().getSessionInfo().battery.initial_level_pct * 100;
            if(this.results.getSessionHeader().getHeaderVersion() < 34){
                // temp code to handle bug in previous logs
                batteryStart = Double.parseDouble(results.getTelemetryRows().get(0).get("battery.level")) * 100;
            }
            Double.parseDouble(results.getTelemetryRows().get(0).get("battery.level"));
            double batteryEnd = Double.parseDouble(results.getTelemetryRows().get(results.getTelemetryRows().size()-1).get("battery.level"));

            SimpleDateFormat sdf = new SimpleDateFormat(
                    "dd MMMM yyyy HH:mm:ss", Locale.getDefault());

            tvStart.setText("Start Time: " + sdf.format(new Date(startTime.unix_time_ms)));
            // Seconds stay the primary figure (they're what the telemetry records), with hours
            // alongside so a long run is readable at a glance: "18171s (5.05h)".
            tvDuration.setText(String.format(Locale.US, "Test Duration: %ds (%.2fh)",
                    durationSecs, durationSecs / 3600.0));

            tvBatStart.setText(String.format("Battery: %.2f%% -> %.2f%%", batteryStart, batteryEnd*100));

            Map<String, TestFile> uniqueFiles = new HashMap<>();
            int maxSysThermal = 0;
            double maxBatteryThermal = 0;
            double frameDrops = 0;

            for(Map<String,String> row : this.results.getTelemetryRows()){
                TestFile curFile = TestFile.fromCsvRow(row);
                // Rows written before the first Format arrives, or after the player is released,
                // carry no clip identity. Counting them listed a nameless second input alongside
                // the real one. Their thermal, battery and drop figures are still folded in
                // below -- only the identity is skipped.
                if(!TestFile.isUnnamed(curFile.fileName) && !uniqueFiles.containsKey(curFile.fileName)){
                    uniqueFiles.put(curFile.fileName, curFile);
                }

                maxSysThermal = Integer.max(maxSysThermal, curFile.systemThermalStatus);
                maxBatteryThermal = Double.max(maxBatteryThermal, curFile.batteryThermalStatus);
                frameDrops += curFile.frameDrops;
            }

            // system thermal status is green if < 2, and yellow if < 4, else red
            int sysThermalColor = greenYellowOrRed(PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_SEVERE, maxSysThermal);

            // battery thermal status is green if < 35ºC, yellow if < 45ºC, else red
            int battThermalColor = greenYellowOrRed(35, 45, maxBatteryThermal);

            // frame drop status is green if avg frame drops < .25/second, yellow if < 1/second, oherwise red
            double avgFrameDrops = frameDrops / durationSecs;
            int frameDropColor = greenYellowOrRed(.25, 1, avgFrameDrops);

            TextView sysThermalView = content.findViewById(R.id.viewStatusThermalSystem);
            TextView battThermalView = content.findViewById(R.id.viewStatusThermalBattery);

            View dropsView   = content.findViewById(R.id.viewStatusFrameDrops);
            sysThermalView.setBackgroundColor(sysThermalColor);
            battThermalView.setBackgroundColor(battThermalColor);

            dropsView.setBackgroundColor(frameDropColor);

            List<TestFile> inputsList = new ArrayList<>(uniqueFiles.values());

            TableLayout table = content.findViewById(R.id.tableVideoInputs);
            for (TestFile tf : inputsList) {
                TableRow row = new TableRow(getContext());

                TextView tvName = new TextView(getContext());
                tvName.setText(tf.fileName);
                tvName.setPadding(4,4,4,4);
                tvName.setSingleLine(true);

                TextView tvCodec = new TextView(getContext());
                tvCodec.setText(tf.codec);
                tvCodec.setPadding(4,4,4,4);
                tvCodec.setSingleLine(true);

                TextView tvRes = new TextView(getContext());
                tvRes.setText(tf.resolution);
                tvRes.setPadding(4,4,4,4);
                tvRes.setSingleLine(true);

                TextView tvDec = new TextView(getContext());

                // remove the word ''decoder' from the end
                String decoder = tf.decoder;
                if(decoder.endsWith(".decoder")){
                    decoder = decoder.substring(0, decoder.length() - 8);
                }
                tvDec.setText(decoder);
                tvDec.setSingleLine(true);
                // No ellipsizing now the table scrolls sideways: the full decoder name is what
                // distinguishes vcat-dav2d-ee033a4@salomethirot-arm from the upstream build.
                tvDec.setPadding(4,4,4,4);
                TooltipCompat.setTooltipText(tvDec, tf.decoder);

                String parts[] = tf.frameRate.split(" ");
                TextView tvFR = new TextView(getContext());
                try {
                    float fps = Float.parseFloat(parts != null ? parts[0] : "0");
                    tvFR.setText(String.format(Locale.US, "%.2f", fps));
                } catch (NumberFormatException e) {
                    tvFR.setText("n/a");
                }
                tvFR.setPadding(4,4,4,4);
                tvFR.setSingleLine(true);

                // Average bitrate, as the log recorded it. Older logs predate the column and the
                // measured fallback, so they carry "none" or "-1 bps" -- shown as n/a rather than
                // as a number that would invite comparison against runs that do have it.
                TextView tvBr = new TextView(getContext());
                tvBr.setText(displayBitrate(tf.bitrate));
                tvBr.setPadding(4,4,4,4);
                tvBr.setSingleLine(true);

                // add all cells to the row
                //row.addView(tvName);
                row.addView(tvCodec);
                row.addView(tvRes);
                row.addView(tvDec);
                row.addView(tvFR);
                row.addView(tvBr);

                // add the row to the table
                table.addView(row);
            }

        }

        return new AlertDialog.Builder(requireContext())
                .setView(content)
                .setPositiveButton("Close", (d, w) -> dismiss())
                .create();
    }
}


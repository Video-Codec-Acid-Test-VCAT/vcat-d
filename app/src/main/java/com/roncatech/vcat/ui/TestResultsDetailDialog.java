package com.roncatech.vcat.ui;

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

    private final TestResult results;
    private String filePath;

    private TestResultsDetailDialog(String filePath, TestResult results){
        super();
        this.results = results;
        this.filePath = filePath;
    }

    public static TestResultsDetailDialog newInstance(String filePath) {

        TestResult results = TestResult.fromLogFile(new File(filePath));

        TestResultsDetailDialog frag = new TestResultsDetailDialog(filePath, results);
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

    static class TestFile{
        public final String fileName;
        public final String resolution;
        public final String codec;
        public final String decoder;
        public final String frameRate;
        public final int systemThermalStatus;
        public final double batteryThermalStatus;
        public final int frameDrops;



        public TestFile(String filename,
                        String resolution,
                        String codec,
                        String decoder,
                        String frameRate,
                        int systemThermalStatus,
                        double batteryThermalStatus,
                        int frameDrops){
            this.fileName = filename;
            this.resolution = resolution;
            this.codec = codec;
            this.decoder = decoder;
            this.frameRate = frameRate;
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

        public static TestFile fromCsvRow(Map<String,String> row){
            String videoPath = row.getOrDefault("test.filename", "none");
            if(videoPath.equals("none")){
                // try legacy
                videoPath = row.getOrDefault("video.filename", "none");
            }

            return new TestFile(
                    new File(videoPath).getName(),
                    row.getOrDefault("video.resolution", "none"),
                    row.getOrDefault("video.codec_name", "none"),
                    row.getOrDefault("video.decoder_name", "none"),
                    row.getOrDefault("video.framerate", "none"),
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
        View content = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_test_results_detail, null);

        TextView logFile    = content.findViewById(R.id.tvLogFile);
        TextView tvStart    = content.findViewById(R.id.tvStartTime);
        TextView tvDuration = content.findViewById(R.id.tvTestDuration);
        TextView tvBatStart = content.findViewById(R.id.tvBattery);

        logFile.setText("Log File: " + (new File(this.filePath)).getName());

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
            tvDuration.setText("Test Duration: " + durationSecs + " secs");

            tvBatStart.setText(String.format("Battery: %.2f%% -> %.2f%%", batteryStart, batteryEnd*100));

            Map<String, TestFile> uniqueFiles = new HashMap<>();
            int maxSysThermal = 0;
            double maxBatteryThermal = 0;
            double frameDrops = 0;

            for(Map<String,String> row : this.results.getTelemetryRows()){
                TestFile curFile = TestFile.fromCsvRow(row);
                if(!uniqueFiles.containsKey(curFile.fileName)){
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

                TextView tvCodec = new TextView(getContext());
                tvCodec.setText(tf.codec);
                tvCodec.setPadding(4,4,4,4);

                TextView tvRes = new TextView(getContext());
                tvRes.setText(tf.resolution);
                tvRes.setPadding(4,4,4,4);

                TextView tvDec = new TextView(getContext());
                tvDec.setText(tf.decoder);
                tvDec.setSingleLine(true);
                tvDec.setEllipsize(TextUtils.TruncateAt.END);
                tvDec.setMaxEms(10);  // adjust as you like
                TooltipCompat.setTooltipText(tvDec, tf.decoder);

                String parts[] = tf.frameRate.split(" ");
                TextView tvFR = new TextView(getContext());
                tvFR.setText(parts != null  ? parts[0] : "none");
                tvFR.setPadding(4,4,4,4);

                // add all cells to the row
                //row.addView(tvName);
                row.addView(tvCodec);
                row.addView(tvRes);
                row.addView(tvDec);
                row.addView(tvFR);

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


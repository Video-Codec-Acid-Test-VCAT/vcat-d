package com.roncatech.vcat.telemetry;

import static android.content.Context.BATTERY_SERVICE;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Log;

import com.roncatech.vcat.models.SessionHeader;
import com.roncatech.vcat.models.RunConfig;
import com.roncatech.vcat.tools.AppMemoryInfo;
import com.roncatech.vcat.tools.BatteryInfo;
import com.roncatech.vcat.models.SessionInfo;
import com.roncatech.vcat.tools.DeviceInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Logs telemetry data to csv file, inserting empty column for missing fields.
 * columns are written in the order of the 'Colum' enumeration.
 */
public class TelemetryLogger {
    private static final String TAG = "TelemetryLogger";

    /**
     * Column definitions uses to generater header and write rows.  The header labels will be in the
     * same order as the Column enum values, and clumn data will align under the appropriate label
     */
    public enum Column {
        TEST_TIMESTAMP        ("test.timestamp"),
        TEST_DURATION         ("test.duration"),
        TEST_FILENAME         ("test.filename"),
        BATTERY_CHARGE_COUNTER("battery.charge_counter"),
        BATTERY_MILLIAMPS     ("battery.milliamps"),
        BATTERY_TEMPERATURE   ("battery.temperature"),
        SYSTEM_THERMAL_STATUS ("system.thermal_status"),
        CPU_FREQ              ("cpu.freq"),           // a *list* of values
        VIDEO_FRAMES_DROPPED  ("video.frames_dropped"),
        VIDEO_RESOLUTION      ("video.resolution"),
        VIDEO_BITRATE         ("video.bitrate"),
        VIDEO_CODEC_NAME      ("video.codec_name"),
        VIDEO_FRAMERATE       ("video.framerate"),
        VIDEO_DECODER_NAME    ("video.decoder_name"),
        CPU_USAGE_TOTAL       ("cpu.usage.total"),
        BATTERY_LEVEL         ("battery.level"),
        TEST_RESTART          ("test.restart"),
        TEST_END_OF_CUR_FILE  ("test.end_of_cur_file"),
        TEST_SYSTEM_MEMORY ("test.memory.system"),
        TEST_VCAT_MEMORY("test.memory.vcat");

        private final String name;
        Column(String name) { this.name = name; }
        public String getName() { return name; }
    }


    private final String csvPath;
    private final int numCpus;

    public static class VideoInfo{
        public final String fileName;
        public final String width;
        public final String height;
        public final String mimeType;
        public final String bitrate ;
        public final String codec;
        public final String decoderName;
        public final float fps;

        public static final VideoInfo empty = new VideoInfo();

        private VideoInfo(){
            this.fileName = this.width = this.height = this.mimeType = this.bitrate = this.codec =
                    this.decoderName = "empty";
            this.fps = -1;
        }

        public VideoInfo(String fileName, String width, String height, String mimeType, String bitrate,
                         String codec, String decoderName, float fps){
            this.fileName = fileName;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
            this.bitrate = bitrate;
            this.codec = codec;
            this.decoderName = decoderName;
            this.fps = fps;
        }

    }

    public TelemetryLogger(String csvFileName){
        this.csvPath = Environment.getExternalStorageDirectory()
                .getAbsolutePath() + "/vcat/test_results/" + csvFileName;
        this.numCpus = getTotalCpus();
    }

    private void writeRow(String row){
        try{
            FileWriter fw = new FileWriter(this.csvPath, true);
            fw.write(row + "\n");
            fw.close();
        }
        catch(IOException e){
            Log.e(TAG, "Error writing csv data: " + e.getLocalizedMessage());
        }
    }

    /**
     * Write the header to the csv log file.  Header labels are in the same order as the Column enum values.
     * However, for cpu frequency, there will be n column headers, one per cpu core.
     */
    public void writeCsvHeader(){

        List<String> hdr = new ArrayList<>();

        for (Column col : Column.values()) {
            if(col == Column.CPU_FREQ){
                for(int i = 0; i < this.numCpus; ++i){
                    hdr.add(col.getName()+i);
                }
            }
            else{
                hdr.add(col.getName());
            }
        }

        writeRow(String.join(",", hdr));
    }

    /**
     * Generate a row of empty data values to be used when writing a row.  This
     * ensures that all cells will be populated, even if with ""
     *
     * @param cpuCount
     * @return
     */
    private static Map<Column,Object> initRow(int cpuCount) {
        Map<Column,Object> row = new EnumMap<>(Column.class);

        for (Column col : Column.values()) {
            if (col == Column.CPU_FREQ) {
                String[] freqs = emptyFreqs(cpuCount);
                row.put(col, freqs);
            } else {
                // default empty string for all other columns
                row.put(col, "");
            }
        }

        return row;
    }

    /**
     * Log one Row, all cells will be populated even if only with ""
     * @param ct the Context to be used when collecting data
     * @param startTimeMS the time the test started.  Used to calculate current duration
     * @param vi the video information for the video columns
     * @param frameDrops number of frame drops
     * @param isResume true if the test was resumed
     */
    public void logTelemetryRow(Context ct, long startTimeMS, VideoInfo vi, Integer frameDrops, boolean isResume, boolean isEndOfCurFile){
        Map<Column, Object> row = initRow(this.numCpus);

        Long curTime = System.currentTimeMillis();
        row.put(Column.TEST_TIMESTAMP, curTime.toString());
        row.put(Column.TEST_DURATION, Long.toString(curTime - startTimeMS));
        row.put(Column.TEST_RESTART, Boolean.toString(isResume));
        row.put(Column.TEST_FILENAME, vi.fileName);
        row.put(Column.TEST_END_OF_CUR_FILE, Boolean.toString(isEndOfCurFile));

        BatteryManager batteryManager = (BatteryManager)ct.getSystemService(BATTERY_SERVICE);
        Long chargeCount = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long level = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = ct.registerReceiver(null, ifilter);
        Log.i(TAG, "Battery Changed Receiver registered in getLogsInCsv() with null receiver");

        Double batteryInMilliamps = (double) chargeCount / level * 100.0 / 1000.0;
        Double batteryTemperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0;
        Double batLevelPcnt = BatteryInfo.getBatteryLevel(ct) / 100.0;

        row.put(Column.BATTERY_CHARGE_COUNTER, chargeCount.toString());
        row.put(Column.BATTERY_MILLIAMPS, batteryInMilliamps.toString());
        row.put(Column.BATTERY_TEMPERATURE, batteryTemperature.toString());

        PowerManager powerManager = (PowerManager) ct.getSystemService(Context.POWER_SERVICE);
        Integer thermalCode = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalCode = powerManager.getCurrentThermalStatus();
        }

        row.put(Column.SYSTEM_THERMAL_STATUS, thermalCode.toString());

        Double[] cpuFreqs = getCurrentCpuFreq(this.numCpus);
        String[] strCpuFreqs = new String[cpuFreqs.length];
        for(int i = 0; i < cpuFreqs.length; ++i){strCpuFreqs[i] = cpuFreqs[i].toString();}

        row.put(Column.CPU_FREQ, strCpuFreqs);
        row.put(Column.VIDEO_FRAMES_DROPPED, frameDrops.toString());
        row.put(Column.VIDEO_DECODER_NAME, vi.decoderName);
        row.put(Column.VIDEO_RESOLUTION, vi.width + "x" + vi.height);
        row.put(Column.VIDEO_BITRATE, vi.bitrate);
        row.put(Column.VIDEO_CODEC_NAME, vi.codec);
        row.put(Column.VIDEO_FRAMERATE, Float.toString(vi.fps));

        row.put(Column.CPU_USAGE_TOTAL, String.format(Locale.US, "%.3f", TelemetryLogger.CpuStats.getInstance().getAppCpuUsage()));
        row.put(Column.BATTERY_LEVEL, batLevelPcnt.toString());

        Long vcatMemUsed = AppMemoryInfo.getBytes(ct);
        DeviceInfo.MemoryInfo sysMemInfo = DeviceInfo.MemoryInfo.getMemory(ct);
        Long sysMemUsed = sysMemInfo.total - sysMemInfo.available;
        row.put(Column.TEST_VCAT_MEMORY, vcatMemUsed.toString());
        row.put(Column.TEST_SYSTEM_MEMORY, sysMemUsed.toString());


        // build row string in same order as column defiinitions
        List<String> values = new ArrayList<>();
        for (Column col : Column.values()) {
            if (col == Column.CPU_FREQ) {
                String[] freqs = emptyFreqs(this.numCpus);
                Object o = row.getOrDefault(col, freqs);

                if (o instanceof String[]) {
                    freqs = (String[]) o;
                }
                else{
                    Log.e(TAG, "Unexpected CPU Freqencies value.  Expected String[], got " + (o==null?"null":o.getClass()));
                    // use default empty set
                }

                for (String f : freqs) {
                    values.add(f);
                }
            } else {
                // All other values are Strings
                values.add(row.getOrDefault(col, "").toString());
            }
        }

        String rowStr = String.join(",", values);
        writeRow(rowStr);
    }

    private static String[] emptyFreqs(int totalCpus){
        String[] cpuFrequencies = new String[totalCpus];
        for(int i = 0; i < totalCpus; ++i){
            cpuFrequencies[i] = "";
        }

        return cpuFrequencies;
    }

    /**
     * Get current CPU Frequencies
     * @param totalCpus the # of CPU's being tracked (0-totalCpus)
     * @return Double [totalCpus] with current freqs
     */
    public static Double[] getCurrentCpuFreq(int totalCpus) {
        Double[] cpuFrequencies = new Double[totalCpus];

        for (int i = 0; i < totalCpus; i++) {
            String scalingPath = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq";
            String cpuInfoPath = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_cur_freq";
            String validPath = null;
            for (String path : new String[]{scalingPath, cpuInfoPath}) {
                File freqFile = new File(path);
                if (freqFile.exists() && freqFile.canRead()) {
                    validPath = path;
                    break;
                }
            }
            // If no valid path is found, add a default value and continue
            if (validPath == null) {
                cpuFrequencies[i] = -1.0; // Indicate no valid path
                continue;
            }
            // Read the CPU frequency from the valid path
            try {
                Process process = Runtime.getRuntime().exec("cat " + validPath);
                process.waitFor();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                if (line != null) {
                    float freq = Float.parseFloat(line);
                    cpuFrequencies[i] = freq / 1000.0; // Convert to MHz
                } else {
                    cpuFrequencies[i] = 0.0; // Default value if no data
                }
            } catch (Exception e) {
                Log.e(TAG, e.getLocalizedMessage());
                cpuFrequencies[i] = 0.0; // Default value on error
            }
        }
        return cpuFrequencies;
    }

    public static int getTotalCpus() {
        int cpuCount = 0;

        try {
            File cpuDir = new File("/sys/devices/system/cpu/");
            File[] files = cpuDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().matches("cpu[0-9]+")) {
                        cpuCount++;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage());
        }

        return cpuCount;
    }

    public static class CpuStats {
        private static final String TAG = "CpuStats";

        // keep your last timestamps here
        private long lastAppCpuTimeMs = -1;
        private long lastWallTimeMs   = -1;

        /**
         * Private constructor; primes the baseline.
         */
        private CpuStats() {
            getAppCpuUsage();
        }

        /**
         * Holder class idiom for lazy, thread-safe singleton
         */
        private static class Holder {
            private static final CpuStats INSTANCE = new CpuStats();
        }

        /**
         * Get the one-and-only instance
         */
        public static CpuStats getInstance() {
            return Holder.INSTANCE;
        }

        /**
         * Compute app‐CPU vs. wall‐clock since last call.
         * Marked synchronized if you might call from multiple threads.
         */
        public synchronized float getAppCpuUsage() {
            long nowCpu  = android.os.Process.getElapsedCpuTime();
            long nowWall = android.os.SystemClock.elapsedRealtime();

            float usage = 0f;
            if (lastAppCpuTimeMs >= 0 && lastWallTimeMs >= 0) {
                long deltaCpu  = nowCpu  - lastAppCpuTimeMs;
                long deltaWall = nowWall - lastWallTimeMs;
                if (deltaWall > 0) {
                    usage = deltaCpu / (float) deltaWall;
                }
            }

            lastAppCpuTimeMs = nowCpu;
            lastWallTimeMs   = nowWall;
            return usage;
        }
    }

    public void writeHeaderRows(Context ct, String playlist, RunConfig runCfg, long startTime){

        List<String> rows = new ArrayList<>();

        double batLevelPct = BatteryInfo.getBatteryLevel(ct) / 100.0;
        long capacityMA = (long) BatteryInfo.getBatteryDesignCapacity(ct);
        SessionInfo si = new SessionInfo(ct, startTime, playlist, batLevelPct, capacityMA);

        SessionHeader sh = new SessionHeader(ct, runCfg, si);

        rows.add(SessionHeader.gson.toJson(sh, SessionHeader.class).toString());

        for(String row : rows){
            writeRow(row);
        }

        writeRow("");
        writeRow("");
    }
}



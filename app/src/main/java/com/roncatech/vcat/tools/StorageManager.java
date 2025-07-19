package com.roncatech.vcat.tools;

import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;

public class StorageManager {
    private static final String TAG = "StorageManager";

    public enum VCATFolder {
        ROOT("vcat"),
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

        public static String playListFolder(){return ROOT.folderName + "/" + PLAYLIST.folderName;}
        public static String resultsFolder(){return ROOT.folderName + "/" + TEST_RESULTS.folderName;}
        public static String mediaFolder(){return ROOT.folderName + "/" + MEDIA.folderName;}

    }

    public static File getFolder(VCATFolder folder){

        if(folder != VCATFolder.ROOT){
            return new File(Environment.getExternalStorageDirectory(), VCATFolder.ROOT.getFolderName() + "/" + folder.folderName);
        }

        return new File(Environment.getExternalStorageDirectory(), VCATFolder.ROOT.getFolderName());
    }


    /**
     * Creates the VCAT directory structure on external storage:
     * /vcat/{playlist, media, test_results}
     *
     * @return true if all directories exist or were created successfully, false otherwise
     */
    public static boolean createVcatFolder() {
        File baseDir = getFolder(VCATFolder.ROOT);

        boolean allSubDirsCreated = true;

        if (!baseDir.exists()) {
            if (baseDir.mkdirs()) {
                Log.d(TAG, "Base folder created at: " + baseDir.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create base folder: " + baseDir.getAbsolutePath());
                return false;
            }
        } else {
            Log.d(TAG, "Base folder already exists: " + baseDir.getAbsolutePath());
        }

        for (VCATFolder cur : VCATFolder.values()) {
            if(cur == VCATFolder.ROOT){continue;}

            File dir = getFolder(cur);

            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    Log.d(TAG, "Subfolder created: " + dir.getAbsolutePath());
                } else {
                    Log.e(TAG, "Failed to create subfolder: " + dir.getAbsolutePath());
                    allSubDirsCreated = false;
                }
            }
        }

        return allSubDirsCreated;
    }

    /**
     * @return the most-recently created telemetry CSV in /storage/emulated/0/vcat/test_results
     *         (i.e. the `logs_<timestamp>.csv` file with the largest timestamp), or null
     *         if none found or on error.
     */
    public static File findLatestLogFile() {
        File dir = getFolder(VCATFolder.TEST_RESULTS);

        if (!dir.isDirectory()) {
            Log.w(TAG, "Not a directory: " + dir.getAbsolutePath());
            return null;
        }

        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith("logs_") && name.endsWith(".csv");
            }
        });
        if (files == null || files.length == 0) {
            Log.w(TAG, "No valid logs_*.csv in " + dir.getAbsolutePath());
            return null;
        }

        File latest = null;
        long maxTs = -1L;
        for (File file : files) {
            String name = file.getName();
            try {
                String tsStr = name.substring(
                        "logs_".length(),
                        name.length() - ".csv".length()
                );
                long ts = Long.parseLong(tsStr);
                if (ts > maxTs) {
                    maxTs = ts;
                    latest = file;
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Skipping invalid log file name: " + name);
            }
        }

        if (latest == null) {
            Log.w(TAG, "No valid logs_*.csv in " + dir.getAbsolutePath());
        }
        return latest;
    }

    /**
     * Reads the very last timestamp (first CSV column) from a VCAT telemetry file.
     * If anything goes wrong (I/O error, malformed lines, empty file, etc.)
     * returns -1L and logs the error.
     */
    public static long readLastTimestamp(File csvFile) {
        if (!csvFile.exists()) {
            Log.w(TAG, "Log file does not exist: " + csvFile.getAbsolutePath());
            return -1L;
        }

        try (RandomAccessFile raf = new RandomAccessFile(csvFile, "r")) {
            long length = raf.length();
            if (length == 0L) {
                Log.w(TAG, "Log file is empty: " + csvFile.getAbsolutePath());
                return -1L;
            }

            long ptr = length - 1;
            raf.seek(ptr);
            if ((char) raf.readByte() == '\n') {
                ptr--;
            }

            while (ptr > 0) {
                raf.seek(ptr);
                if ((char) raf.readByte() == '\n') {
                    ptr++;
                    break;
                }
                ptr--;
            }

            raf.seek(ptr);
            String lastLine = raf.readLine();
            if (lastLine == null) {
                Log.w(TAG, "Could not read last line in " + csvFile.getAbsolutePath());
                return -1L;
            }

            String tsString = lastLine.split(",", 2)[0].trim();
            try {
                return Long.parseLong(tsString);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Last line timestamp not a valid long: '" + tsString + "'");
                return -1L;
            }
        } catch (IOException e) {
            Log.e(TAG, "I/O error reading last timestamp", e);
            return -1L;
        }
    }
}

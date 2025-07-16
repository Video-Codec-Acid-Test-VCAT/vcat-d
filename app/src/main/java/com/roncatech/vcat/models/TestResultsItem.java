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

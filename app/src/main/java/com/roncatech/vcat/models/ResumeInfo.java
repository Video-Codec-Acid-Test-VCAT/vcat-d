package com.roncatech.vcat.models;


public class ResumeInfo {
    public final String playlistName;
    public final String logFileUri;
    public final long testStartTime;
    public final long lastLogTimestamp;

    public final long logTimestampOffset;

    public static final ResumeInfo empty = new ResumeInfo("", "", -1, 0, 0);

    public ResumeInfo(String playlistName, String logFileUri, long testStartTime, long lastLogTimestamp, long logTimestampOffset){
        this.playlistName = playlistName;
        this.logFileUri = logFileUri;
        this.testStartTime = testStartTime;
        this.lastLogTimestamp = lastLogTimestamp;
        this.logTimestampOffset = logTimestampOffset;
    }
}


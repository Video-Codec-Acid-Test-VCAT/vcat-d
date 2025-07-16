package com.roncatech.vcat.models;

import android.net.Uri;

import com.google.gson.Gson;

import com.roncatech.vcat.tools.UriUtils;

import java.net.URI;
import java.time.Instant;

public class TestStatus {
    public static final CurrentTestVideo emptyTestVideo = new CurrentTestVideo();
    public final static String emptyPlaylist = "None";

    private String startTime = ""; // ISO 8601
    private Uri playlistUri = Uri.EMPTY;
    public enum TestState { Starting, Running, Stopped};
    private TestState testState;


    private CurrentTestVideo currentTestVideo = emptyTestVideo;

    public final static TestStatus emptyStatus = new TestStatus();

    public TestStatus() {
        reset();
    }

    public void reset(){
        this.startTime = Instant.now().toString(); // Machine-readable ISO 8601
        this.playlistUri = Uri.EMPTY;
        this.currentTestVideo = emptyTestVideo;
        this.testState = TestState.Stopped;
    }

    public void startTest(Uri playlistUri){
        this.startTime = Instant.now().toString(); // Machine-readable ISO 8601
        this.playlistUri = playlistUri;
        this.currentTestVideo = emptyTestVideo;
        this.testState = TestState.Starting;
    }

    public String getStartTime() {
        return startTime;
    }

    public long getStartTimeAsEpoch(){
        Instant instant = Instant.parse(this.startTime);
        return instant.toEpochMilli();
    }


    public String getPlaylistFileName() {

        return UriUtils.fileNameFromURI(playlistUri);
    }

    public Uri getPlaylist(){
        return this.playlistUri;
    }

    public CurrentTestVideo getCurrentTestVideo() {
        return currentTestVideo;
    }

    public TestState getTestState(){return this.testState;}

    public void setCurrentTestVideo(CurrentTestVideo currentTestVideo) {
        this.currentTestVideo = currentTestVideo;
        if(this.testState != TestState.Running){this.testState = TestState.Running;}
    }

    public String toJson() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }

    public static class CurrentTestVideo {
        private final String startTime;
        private final String fileName;
        private final String videoCodec;
        private final String videoDecoder;
        private final String resolution;
        private final String mimeType;
        private final String bitrate;
        private final double fps;

        private CurrentTestVideo(){
            this.startTime = "";
            this.fileName = "None";
            this.videoCodec = "None";
            this.videoDecoder  = "None";
            this.resolution  = "None";
            this.mimeType  = "None";
            this.bitrate  = "None";
            this.fps = 0.0;
        }

        public CurrentTestVideo(
                String fileName,
                String videoCodec,
                String videoDecoder,
                String resolution,
                String mimeType,
                String bitrate,
                double fps
        ) {
            this(Instant.now().toString(), fileName, videoCodec, videoDecoder, resolution, mimeType, bitrate, fps);
        }


        public CurrentTestVideo(
                String startTime,
                String fileName,
                String videoCodec,
                String videoDecoder,
                String resolution,
                String mimeType,
                String bitrate,
                double fps
        ) {
            this.startTime = startTime; // Machine-readable ISO 8601
            this.fileName = fileName;
            this.videoCodec = videoCodec;
            this.videoDecoder = videoDecoder;
            this.resolution = resolution;
            this.mimeType = mimeType;
            this.bitrate = bitrate;
            this.fps = fps;
        }

        public CurrentTestVideo addDecder(String decder){
            return new CurrentTestVideo(
                    this.startTime,
                    this.fileName,
                    this.videoCodec,
                    this.videoDecoder,
                    this.resolution,
                    this.mimeType,
                    this.bitrate,
                    this.fps
            );
        }
        public String getFileName() {
            return fileName;
        }
        public String getStartTime(){
            return this.startTime;
        }
        public String getVideoCodec() {
            return this.videoCodec;
        }
        public String getVideoDecoder() {
            return this.videoDecoder;
        }
        public String getResolution(){return this.resolution;}
        public String getMimeType(){ return this.mimeType;}
        public String getBitrate(){return this.bitrate;}
        public double getFps(){return this.fps;}
    }
}


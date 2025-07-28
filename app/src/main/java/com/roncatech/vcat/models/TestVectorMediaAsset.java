package com.roncatech.vcat.models;

import java.io.File;

public class TestVectorMediaAsset {
    public final TestVectorManifests.VideoManifest manifest;
    public final File localPath;


    public TestVectorMediaAsset(TestVectorManifests.VideoManifest videoManifest, File localPath){
        this.manifest = videoManifest;
        this.localPath = localPath;
    }
}

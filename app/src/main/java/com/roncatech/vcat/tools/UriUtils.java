package com.roncatech.vcat.tools;

import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.net.URI;

public class UriUtils {
    public static String fileNameFromURI(Uri uri){
        return new File(uri.getPath()).getName();
    }

    public static String fileNameFromURI(String uriString){
        String[] parts = uriString.split("/");
        return parts[parts.length - 1];
    }
}

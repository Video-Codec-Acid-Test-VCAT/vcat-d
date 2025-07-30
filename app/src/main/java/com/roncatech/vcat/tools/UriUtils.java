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

    public static String makeBaseUrlWithUri(String url) {
        URI uri = URI.create(url);
        URI base = uri.resolve("./");   // drops everything after the last '/'
        return base.toString();         // includes trailing slash
    }
}

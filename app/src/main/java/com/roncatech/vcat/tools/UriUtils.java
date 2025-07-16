package com.roncatech.vcat.tools;

import android.net.Uri;
import android.provider.DocumentsContract;

import java.net.URI;

public class UriUtils {
    public static String fileNameFromURI(Uri uri){
        String documentId = DocumentsContract.getDocumentId(uri); // e.g. "primary:Vcat/av1-720-fd2.xspf"
        return fileNameFromURI(documentId);
    }

    public static String fileNameFromURI(String uriString){
        String[] parts = uriString.split("/");
        return parts[parts.length - 1];
    }
}

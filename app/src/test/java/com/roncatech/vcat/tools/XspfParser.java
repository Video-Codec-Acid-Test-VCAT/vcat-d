package com.roncatech.vcat.tools;

import android.content.Context;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class XspfParser {
    private static final String TAG = "XspfParser";

    /**
     * Parses the XSPF at the given Uri and returns a list of all track URIs
     * found in <location> elements. On any error, returns an empty list.
     */
    public static List<Uri> parsePlaylist(Context context, Uri playlistUri) {
        List<Uri> uris = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        try (InputStream in = context.getContentResolver().openInputStream(playlistUri)) {
            if (in == null) {
                Log.w(TAG, "Unable to open URI: " + playlistUri);
                return uris;
            }
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, "UTF-8");
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG
                        && "location".equals(parser.getName())) {
                    if (parser.next() == XmlPullParser.TEXT) {
                        String text = parser.getText().trim();
                        if (!text.isEmpty()) {
                            uris.add(Uri.parse(text));
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing playlist " + playlistUri, e);
            // on error, return empty list
        }
        return uris;
    }
}


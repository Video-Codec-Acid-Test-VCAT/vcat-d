/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of vcat-d.
 *
 * vcat-d is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * vcat-d is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All vcat-d artwork is owned exclusively by RoncaTech LLC. Use of vcat-d logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting vcat-d itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.tools;


import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.w3c.dom.Node;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class XSPFPlaylistCreator {

    public static void createOrAppendXSPF(Context context, Uri fileUri, int playTimeInSeconds, String playlistFileName) {
        try {
            // Resolve the real file path
            String realPath = getRealPathFromUri(context, fileUri);
            if (realPath == null || realPath.isEmpty()) {
                throw new RuntimeException("Unable to resolve file path for: " + fileUri);
            }

            File benchResourcesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getParent(), "vcat/playlist");
            // Ensure the directory exists
            if (!benchResourcesDir.exists()) {
                benchResourcesDir.mkdirs();
            }
            File playlistFile = new File(benchResourcesDir, playlistFileName);

            int filePlayBackTime = getVideoDuration(realPath);
            int repeatCount = playTimeInSeconds/filePlayBackTime + 1;
            // Create or update the playlist
            updateOrCreateXSPF(realPath, repeatCount, playlistFile);

        } catch (Exception e) {
            Log.e("XSPFPlaylist", "Error creating/updating playlist", e);
        }
    }

    private static void updateOrCreateXSPF(String realPath,  int repeatCount, File playlistFile) throws Exception {
        // Initialize XML document
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document;

        if (playlistFile.exists()) {
            // Parse existing playlist
            document = builder.parse(playlistFile);
        } else {
            // Create new playlist structure
            document = builder.newDocument();
            Element playlist = document.createElement("playlist");
            playlist.setAttribute("version", "1");
            playlist.setAttribute("xmlns", "http://xspf.org/ns/0/");
            document.appendChild(playlist);

            appendIdentifier(document, playlist, PlaylistIdentity.newId());

            Element trackList = document.createElement("trackList");
            playlist.appendChild(trackList);
        }

        // Locate or create the trackList element
        NodeList trackListNodes = document.getElementsByTagName("trackList");
        Element trackList = (Element) trackListNodes.item(0);

        // Add tracks
        for (int i = 0; i < repeatCount; i++) {
            Element track = document.createElement("track");

            Element location = document.createElement("location");
            location.setTextContent("file://" + realPath);
            track.appendChild(location);
            trackList.appendChild(track);
        }

        // Write back to file
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4"); // Proper indentation
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

        DOMSource source = new DOMSource(document);
        try (FileOutputStream outputStream = new FileOutputStream(playlistFile)) {
            StreamResult result = new StreamResult(outputStream);
            transformer.transform(source, result);
        }
        Log.i("XSPFPlaylist", "Playlist saved at: " + playlistFile.getAbsolutePath());
    }

    public static void writePlaylistFile(Context context, Uri xspfFile, List<String> files){
        writePlaylistFile(context, xspfFile, files, PlaylistIdentity.newId());
    }

    /**
     * @param playlistId the identity written as the playlist-scope {@code <identifier>}. Callers
     *                   that already hold a stable id for this playlist (a test-vector manifest
     *                   uuid, or the existing id when re-saving an edited playlist) should pass it
     *                   so the playlist keeps one identity across rewrites.
     */
    public static void writePlaylistFile(Context context, Uri xspfFile, List<String> files, String playlistId){

        try {
            // Create XML Document
            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

            Document doc = docBuilder.newDocument();

            // Root <playlist> element
            Element rootElement = doc.createElement("playlist");
            rootElement.setAttribute("version", "1");
            rootElement.setAttribute("xmlns", "http://xspf.org/ns/0/");
            doc.appendChild(rootElement);

            appendIdentifier(doc, rootElement, playlistId);

            // <trackList> element
            Element trackList = doc.createElement("trackList");
            rootElement.appendChild(trackList);

            // Add <track> elements for each file
            for (String file : files) {

                if (!file.startsWith("file://") && !file.startsWith("content://")) {
                    file = "file://" + file;
                }

                Element track = doc.createElement("track");

                Element location = doc.createElement("location");
                location.setTextContent(file);
                track.appendChild(location);

                trackList.appendChild(track);
            }

            // Create XML transformer
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4"); // Proper indentation
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

            DOMSource source = new DOMSource(doc);

            // Open output stream using ContentResolver
            try (OutputStream outputStream = context.getContentResolver().openOutputStream(xspfFile, "w")) {
                if (outputStream != null) {
                    StreamResult result = new StreamResult(outputStream);
                    transformer.transform(source, result);
                    outputStream.flush();
                    outputStream.close();
                    Log.d("PlaylistWriter", "Playlist written successfully to: " + xspfFile.toString());
                } else {
                    Log.e("PlaylistWriter", "Failed to open OutputStream for Uri: " + xspfFile.toString());
                }
            }
            // Force media scanner to update the file system
            MediaScannerConnection.scanFile(context, new String[]{xspfFile.getPath()}, null,
                    (path, uri) -> Log.d("PlaylistWriter", "MediaScanner updated: " + path));
        }
        catch(Exception e){
            Log.e("XSPFPlaylist", "Error creating/updating playlist"+xspfFile.toString(), e);
        }
    }

    /**
     * Writes the playlist-scope {@code <identifier>} (see {@link PlaylistIdentity}). A caller-
     * supplied id that is not a usable UUID is replaced with a fresh one, so a playlist VCAT writes
     * is never left non-conformant (and therefore hidden) by a bad id from upstream.
     */
    private static void appendIdentifier(Document doc, Element playlist, String playlistId) {
        Element identifier = doc.createElement("identifier");
        identifier.setTextContent(PlaylistIdentity.toUrn(PlaylistIdentity.canonicalizeOrNew(playlistId)));
        playlist.appendChild(identifier);
    }

    /** The conformant playlist id on a direct child {@code <identifier>}, or null if there is none. */
    private static String readIdentifier(Element playlist) {
        NodeList children = playlist.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "identifier".equals(child.getNodeName())) {
                return PlaylistIdentity.fromUrn(child.getTextContent());
            }
        }
        return null;
    }

    /**
     * Brings a pre-identifier playlist into conformance by inserting a playlist-scope
     * {@code <identifier>} ahead of {@code <trackList>}, rewriting the file in place. Track entries
     * are preserved exactly as parsed. A playlist that already has a conformant identifier is left
     * untouched.
     *
     * @return the id the playlist now carries, or null if it could not be read or rewritten — in
     *         which case the playlist stays non-conformant and remains hidden from the UI.
     */
    public static String stampIdentifier(Context context, Uri playlistUri) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc;
            try (InputStream in = context.getContentResolver().openInputStream(playlistUri)) {
                if (in == null) {
                    Log.e("XSPFPlaylist", "stampIdentifier: cannot open " + playlistUri);
                    return null;
                }
                doc = builder.parse(in);
            }

            Element root = doc.getDocumentElement();
            if (root == null || !"playlist".equals(root.getNodeName())) {
                Log.e("XSPFPlaylist", "stampIdentifier: not an XSPF playlist: " + playlistUri);
                return null;
            }

            String existing = readIdentifier(root);
            if (existing != null) {
                return existing;
            }

            String playlistId = PlaylistIdentity.newId();
            Element identifier = doc.createElement("identifier");
            identifier.setTextContent(PlaylistIdentity.toUrn(playlistId));
            root.insertBefore(identifier, root.getFirstChild());

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

            // "wt" truncates: "w" leaves any trailing bytes of the longer original file behind on
            // providers that don't truncate implicitly, which would corrupt the XML.
            try (OutputStream out = context.getContentResolver().openOutputStream(playlistUri, "wt")) {
                if (out == null) {
                    Log.e("XSPFPlaylist", "stampIdentifier: cannot write " + playlistUri);
                    return null;
                }
                transformer.transform(new DOMSource(doc), new StreamResult(out));
                out.flush();
            }
            Log.i("XSPFPlaylist", "Stamped identifier " + playlistId + " into " + playlistUri);
            return playlistId;
        } catch (Exception e) {
            Log.e("XSPFPlaylist", "stampIdentifier failed for " + playlistUri, e);
            return null;
        }
    }

    public static String getRealPathFromUri(Context context, Uri uri) {
        String filePath = null;

        if (DocumentsContract.isDocumentUri(context, uri)) {
            String documentId = DocumentsContract.getDocumentId(uri);
            String[] split = documentId.split(":");
            String type = split[0];

            if ("primary".equalsIgnoreCase(type)) {
                filePath = Environment.getExternalStorageDirectory() + "/" + split[1];
            } else {
                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                String selection = "_id=?";
                String[] selectionArgs = new String[]{split[1]};
                filePath = getDataColumn(context, contentUri, selection, selectionArgs);
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            filePath = getDataColumn(context, uri, null, null);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            filePath = uri.getPath();
        }

        return filePath;
    }

    private static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        String column = "_data";
        String[] projection = {column};

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(columnIndex);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }
    public static int getVideoDuration(String videoPath) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoPath);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            int duration = 0;
            if (d != null) duration = Integer.parseInt(d);
            retriever.release();
            return duration/1000;
        } catch (Exception e) {
            // Handle exception
            return 0;
        }
    }
}


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

package com.roncatech.vcat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;


import com.roncatech.vcat.R;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import android.util.Log;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.roncatech.vcat.tools.StorageManager;
import com.roncatech.vcat.tools.XSPFPlaylistCreator;

public class PlaylistDialog extends DialogFragment {

    private Context context;
    private String playlistName;
    private Uri selectedFolderUri;
    private Uri playlistUri;
    private List<String> playlistEntries;
    private PlaylistAdapter adapter;
    private Button saveButton;
    private Button saveAsButton;
    private boolean isDirty = false; // Track if playlist is modified

    public void setDirty(){
        this.isDirty = true;
    }

    private final PlaylistUpdates listener;

    private final int REQUEST_CODE_PICK_FILE = 1001;

    public PlaylistDialog(Context context, String playlistName, Uri playlistUri, Uri selectedFolderUri, PlaylistUpdates listener) {
        this.context = context;
        this.playlistName = playlistName;
        this.playlistUri = playlistUri;
        this.selectedFolderUri = selectedFolderUri;
        this.playlistEntries = loadPlaylist(playlistUri);
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        int currentOrientation = getResources().getConfiguration().orientation;

// If already in portrait, keep portrait; otherwise, keep landscape
        if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
            requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.playlist_dialog, null);
        builder.setView(view);

        // Find UI Elements
        TextView titleView = view.findViewById(R.id.playlistTitle);
        titleView.setText(playlistName);

        RecyclerView recyclerView = view.findViewById(R.id.playlistRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        saveButton = view.findViewById(R.id.saveButton);
        saveAsButton = view.findViewById(R.id.saveAsButton);
        Button cancelButton = view.findViewById(R.id.cancelButton);
        Button addEntryButton = view.findViewById(R.id.addEntryButton);

        // Setup Adapter
        adapter = new PlaylistAdapter(playlistEntries, this::onEntryDeleted);
        recyclerView.setAdapter(adapter);

        // Add New Entry
        addEntryButton.setOnClickListener(v -> getNewPlaylistFile());

        // Save & Cancel Actions
        saveButton.setOnClickListener(v -> savePlaylist());
        saveAsButton.setOnClickListener(v -> saveAsPlaylist());
        cancelButton.setOnClickListener(v -> dismiss());

        return builder.create();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }


    private List<String> loadPlaylist(Uri fileUri) {
        List<String> entries = new ArrayList<>();

        try {
            // Use DocumentFile.fromSingleUri() to access the file
            DocumentFile documentFile = DocumentFile.fromSingleUri(context, fileUri);

            if (documentFile == null || !documentFile.isFile()) {
                return entries;
            }

            // Open InputStream via ContentResolver
            InputStream inputStream = context.getContentResolver().openInputStream(documentFile.getUri());
            if (inputStream == null) {
                return entries;
            }

            // Parse XSPF XML
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(inputStream, "UTF-8");

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && "location".equals(parser.getName())) {
                    parser.next();
                    entries.add(parser.getText()); // Extract track path
                }
                eventType = parser.next();
            }

            inputStream.close();
        } catch (Exception e) {
            String msg = e.toString();
        }

        return entries;
    }

    /**
     * 🔹 Method to Launch File Picker (Old Way)
     */
    private void getNewPlaylistFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // ".ivf" (and other raw bitstream containers) have no registered video/* MIME, so a
        // "video/*" filter hides them. Use "*/*" biased toward video plus octet-stream — the
        // MIME Android's MimeTypeMap falls back to for unregistered extensions like .ivf.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"video/*", "application/octet-stream"});

        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }


    /**
     * 🔹 Handle Result from File Picker
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == Activity.RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                addNewEntry(fileUri);
            }
        }
    }


    // Add New Entry
    private void addNewEntry(Uri fileUri) {
        if (fileUri != null) {
            playlistEntries.add(fileUri.toString());
            setDirty();
            saveButton.setEnabled(this.isDirty && this.playlistUri != null);
            saveAsButton.setEnabled(this.isDirty);
            adapter.notifyDataSetChanged();
        }
    }


    // Remove Entry
    private void onEntryDeleted(int position) {
        playlistEntries.remove(position);
        adapter.notifyDataSetChanged();
        setDirty();
        saveButton.setEnabled(this.isDirty && this.playlistUri != null);
        saveAsButton.setEnabled(this.isDirty && !(this.playlistEntries.isEmpty()));
    }

    private void savePlaylist() {
        if (this.playlistUri == null) {
            Log.e("PlaylistDialog", "Cannot save: playlistUri is null");
            return;
        }

        // SAF delete/create/write are synchronous IPC to the storage provider; running them on
        // the UI thread times out input dispatching (ANR). Do the I/O on a background thread and
        // only touch the dialog on the UI thread.
        final Context appContext = context.getApplicationContext();
        final Uri existingUri = this.playlistUri;
        final Uri folderUri = this.selectedFolderUri;
        final List<String> entries = new ArrayList<>(this.playlistEntries);

        new Thread(() -> {
            try {
                String filename = getFileNameFromURI(appContext, existingUri);
                if (filename == null) {
                    Log.e("PlaylistDialog", "Failed to retrieve filename.");
                    return;
                }

                deletePlaylist(appContext, existingUri);

                Uri recreated = createFileInSelectedFolder(appContext, folderUri, filename);
                if (recreated == null) {
                    Log.e("PlaylistDialog", "Failed to recreate playlist file");
                    return;
                }

                XSPFPlaylistCreator.writePlaylistFile(appContext, recreated, entries);

                runOnUiThread(() -> {
                    this.playlistUri = recreated;
                    dismissSafely();
                });
            } catch (Exception e) {
                Log.e("PlaylistDialog", "Error clearing and recreating playlist file", e);
            }
        }).start();
    }

    private void runOnUiThread(Runnable r) {
        Activity a = getActivity();
        if (a != null) {
            a.runOnUiThread(r);
        }
    }

    private void dismissSafely() {
        if (isAdded()) {
            dismiss();
        }
    }

    private void deletePlaylist(Context context, Uri playlistUri) {
        if (playlistUri == null) {
            Log.e("DeletePlaylist", "Invalid playlistUri: null");
            return;
        }

        try {
            boolean deleted = DocumentsContract.deleteDocument(context.getContentResolver(), playlistUri);
            if (deleted) {
                Log.i("DeletePlaylist", "Playlist deleted successfully: " + playlistUri);
            } else {
                Log.e("DeletePlaylist", "Failed to delete playlist.");
            }
        } catch (Exception e) {
            Log.e("DeletePlaylist", "Error deleting playlist", e);
        }
    }

    private String getFileNameFromURI(Context context, Uri fileUri) {
        Cursor cursor = null;
        try {
            String[] projection = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
            cursor = context.getContentResolver().query(fileUri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e("PlaylistDialog", "Error getting filename", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }



    private void saveAsPlaylist() {

        // Show a filename input dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Enter Playlist Name");

        // Input field for filename
        final EditText input = new EditText(context);
        input.setHint("playlist.xspf");
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String filename = input.getText().toString().trim();
            if (!filename.endsWith(".xspf")) {
                filename += ".xspf"; // Ensure correct file extension
            }

            if (selectedFolderUri == null) {
                Toast.makeText(context, "Please select a playlist folder first.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Offload the SAF create/write off the UI thread (see savePlaylist()).
            final Context appContext = context.getApplicationContext();
            final Uri folderUri = this.selectedFolderUri;
            final List<String> entries = new ArrayList<>(this.playlistEntries);
            final String fname = filename;

            new Thread(() -> {
                Uri newPlaylistUri = createFileInSelectedFolder(appContext, folderUri, fname);
                if (newPlaylistUri == null) {
                    Log.e("PlaylistDialog", "Failed to create playlist file in selected folder.");
                    return;
                }
                XSPFPlaylistCreator.writePlaylistFile(appContext, newPlaylistUri, entries);
                runOnUiThread(() -> {
                    if (this.listener != null) {
                        this.listener.onPlaylistAdded(newPlaylistUri);
                    }
                    dismissSafely(); // Close the dialog after saving
                });
            }).start();
        });

        builder.show();
    }

    private Uri createFileInSelectedFolder(Context ctx, Uri folderUri, String filename) {
        // Playlists live in the fixed PLAYLIST subfolder — the same folder FragmentMain lists.
        // Resolve it via StorageManager rather than DocumentFile.fromTreeUri(folderUri): the latter
        // always resolves back to the tree root, so saved playlists would land in the root and
        // never appear in the list (and pile up as "name (N).xspf" dedup copies).
        DocumentFile folder = StorageManager.getFolder(ctx, StorageManager.VCATFolder.PLAYLIST);
        if (folder == null || !folder.isDirectory()) {
            Log.e("PlaylistDialog", "Invalid playlist folder (root not selected?)");
            return null;
        }
        DocumentFile newFile = folder.createFile("application/xspf+xml", filename);
        if (newFile == null) {
            Log.e("PlaylistDialog", "Failed to create file in folder: " + folderUri);
            return null;
        }
        return newFile.getUri();
    }

}



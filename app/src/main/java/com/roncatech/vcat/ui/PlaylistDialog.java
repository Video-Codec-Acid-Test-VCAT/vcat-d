package com.roncatech.vcat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
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
        intent.setType("video/*"); // Restrict selection to video files

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
        String filePath = XSPFPlaylistCreator.getRealPathFromUri(context, fileUri);
        if(!(filePath == null || filePath.isEmpty())){
            playlistEntries.add(filePath); // Placeholder
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

        try {
            // Extract folder URI and filename before deleting
            Uri folderUri = DocumentsContract.buildDocumentUriUsingTree(playlistUri,
                    DocumentsContract.getTreeDocumentId(playlistUri));
            String filename = getFileNameFromURI(context, playlistUri); // Get the original filename

            if (filename == null) {
                Log.e("PlaylistDialog", "Failed to retrieve filename.");
                return;
            }

            // Delete the existing file
            deletePlaylist(context, playlistUri);

            // Create a new file with the same name
            playlistUri = createFileInSelectedFolder(folderUri, filename);

            if (playlistUri == null) {
                Log.e("PlaylistDialog", "Failed to recreate playlist file");
                return;
            }

            // Now write the new playlist entries
            XSPFPlaylistCreator.writePlaylistFile(this.context, this.playlistUri, this.playlistEntries);
            // Force the media scanner to update
            Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            scanIntent.setData(playlistUri);
            context.sendBroadcast(scanIntent);
        } catch (Exception e) {
            Log.e("PlaylistDialog", "Error clearing and recreating playlist file", e);
        }

        dismiss();
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

            // Create a Uri for the new file inside the selected folder
            Uri newPlaylistUri = createFileInSelectedFolder(selectedFolderUri, filename);
            if(selectedFolderUri == null){
                Toast.makeText(context, "Please select a playlist folder first.", Toast.LENGTH_SHORT).show();
            }
            else if (newPlaylistUri != null) {
                // Save the playlist to the new Uri
                XSPFPlaylistCreator.writePlaylistFile(this.context, newPlaylistUri, this.playlistEntries);
                if(this.listener != null){
                    this.listener.onPlaylistAdded(newPlaylistUri);
                }

                dismiss(); // Close the dialog after saving
            } else {
                Log.e("PlaylistDialog", "Failed to create playlist file in selected folder.");
            }
        });

        builder.show();
    }

    private Uri createFileInSelectedFolder(Uri folderUri, String filename) {
        ContentValues values = new ContentValues();
        values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, filename);
        values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, "application/xspf+xml"); // MIME type for XSPF

        try {
            Uri folderDocumentUri = DocumentsContract.buildDocumentUriUsingTree(folderUri,
                    DocumentsContract.getTreeDocumentId(folderUri));

            Uri fileUri = DocumentsContract.createDocument(context.getContentResolver(), folderDocumentUri, "application/xspf+xml", filename);

            if (fileUri != null) {
                Log.d("PlaylistDialog", "Created file: " + fileUri.toString());
            }
            return fileUri;
        } catch (Exception e) {
            Log.e("PlaylistDialog", "Error creating file in selected folder", e);
            return null;
        }
    }

}



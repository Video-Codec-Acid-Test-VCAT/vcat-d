package com.roncatech.vcat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.roncatech.vcat.R;
import com.roncatech.vcat.models.ResumeInfo;
import com.roncatech.vcat.models.SessionHeader;
import com.roncatech.vcat.models.SharedViewModel;
import com.roncatech.vcat.tools.BatteryInfo;
import com.roncatech.vcat.tools.CpuInfo;
import com.roncatech.vcat.tools.DeviceInfo;
import com.roncatech.vcat.tools.StorageManager;
import com.roncatech.vcat.video.FullScreenPlayerActivity;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FragmentMain extends Fragment implements PlaylistUpdates {
    private final static String TAG = "MainFragment";

    private TextView playlistFolderText;
    private List<Uri> playlistNames;
    private TableLayout playlistTable;
    private SharedViewModel viewModel;

    public FragmentMain()  {
        this.playlistNames = new ArrayList<>();
    }

    public void onPlaylistAdded(Uri playlistUri){

        getPlaylistFiles();
    }

    private static String displayNameFromFileUri(Uri uri){
        String ret = "<null>";
        if(uri != null && uri.getPath() != null){
            File file = new File(uri.getPath());
            ret = file.getName();
        }

        return ret;
    }

    public void onPlaylistDeleted(Uri playlistUri){
        getPlaylistFiles();
    }

    private void fillDeviceLayout(View view) {
        TextView model = view.findViewById(R.id.modelText);
        TextView android = view.findViewById(R.id.androidVersionText);
        TextView cpu = view.findViewById(R.id.cpuText);
        TextView cpuspeed = view.findViewById(R.id.cpuSpeedText);
        TextView memory = view.findViewById(R.id.memoryText);
        TextView resolution = view.findViewById(R.id.displayResolutionText);
        TextView freeSpace = view.findViewById(R.id.storageText);

        model.setText(Build.MODEL);
        android.setText(Build.VERSION.RELEASE);
        cpu.setText(DeviceInfo.CpuInfoUtil.getCpuModel());
        cpuspeed.setText(CpuInfo.getMinCpuFrequency() + " - " + CpuInfo.getMaxCpuFrequency());

        DeviceInfo.MemoryInfo mi = DeviceInfo.MemoryInfo.getMemory(getContext());
        memory.setText(Long.toString(mi.total));

        DeviceInfo.DisplayResolution dr = new DeviceInfo.DisplayResolution(getContext());
        if (getActivity() != null)
            resolution.setText(dr.toString());
        else
            Log.e(TAG, "fillDeviceLayout: null activity");

        DeviceInfo.MemoryInfo si = DeviceInfo.MemoryInfo.getStorage();
        freeSpace.setText(DeviceInfo.MemoryInfo.getPrettyMemSize(mi.total));
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_main, container, false);

        //checkBackgroundRestriction(requireContext());
        //checkBatteryOptimization();
        //checkPauseAppIfUnused();

        // Get ViewModel
        this.viewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        ImageButton browsePlaylistsButton = view.findViewById(R.id.browsePlaylistsButton);
        ImageButton createPlaylistButton = view.findViewById(R.id.create_playlist_button);
        playlistFolderText = view.findViewById(R.id.playlistFolderText);
        playlistTable = view.findViewById(R.id.playlistTable);

        //  Set Click Listener
        browsePlaylistsButton.setOnClickListener(v -> openFolderSelection());
        createPlaylistButton.setOnClickListener(v -> createPlaylist());

        fillDeviceLayout(view);
        updatePlaylistFolder();
        return view;
    }

    private void createPlaylist(){
        PlaylistDialog dialog = new PlaylistDialog(requireContext(), "New Playlist", null, this.viewModel.getFolderUri(), this);
        dialog.show(getChildFragmentManager(), "PlaylistDialog");
    }

    private static final int REQUEST_CODE_OPEN_FOLDER = 100;
    //  Open System Folder Picker
    private void openFolderSelection() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_OPEN_FOLDER);
    }

    public static File safUriToFile(Context context, Uri treeUri) {
        if (DocumentsContract.isTreeUri(treeUri)) {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);  // e.g. "primary:vcat"
            String[] parts = docId.split(":");
            if (parts.length == 2 && parts[0].equalsIgnoreCase("primary")) {
                return new File(Environment.getExternalStorageDirectory(), parts[1]);
            }
        }
        return null;
    }

    //  Handle Folder Selection Result
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_OPEN_FOLDER && resultCode == Activity.RESULT_OK) {
            Uri treeUri = data.getData();

            File resolved = safUriToFile(requireContext(), treeUri);
            if (resolved != null && resolved.exists() && resolved.isDirectory()) {
                viewModel.setFolderUri(Uri.fromFile(resolved));
                updatePlaylistFolder();
            } else {
                Toast.makeText(getContext(), "Invalid folder selected", Toast.LENGTH_LONG).show();
                // Optionally: fallback to default
                File fallback = new File(Environment.getExternalStorageDirectory(), "vcat/playlist");
                viewModel.setFolderUri(Uri.fromFile(fallback));
                updatePlaylistFolder();
            }
        }
    }

    private void updatePlaylistFolder(){
        //  Update UI
        if(this.viewModel.getFolderUri() != null) {
            playlistFolderText.setText("Folder: " + this.viewModel.getFolderUri().getPath());
            getPlaylistFiles();
        }
    }

    //  Scan Selected Folder for .xspf Files
    private void getPlaylistFiles() {
        Uri folderUri = viewModel.getFolderUri();
        if (folderUri == null) {
            return;
        }

        // Force refresh the folder
        //requireActivity().getContentResolver().notifyChange(this.viewModel.getFolderUri(), null);

        File playlistDir = new File(folderUri.getPath());
        if (!playlistDir.exists() || !playlistDir.isDirectory()) {
            return;
        }

        playlistNames.clear(); // Clear previous results

        File[] files = playlistDir.listFiles((dir, name) -> name.endsWith(".xspf"));
        if (files != null) {
            // Sort alphabetically (case-insensitive)
            Arrays.sort(files, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));

            for (File file : files) {
                playlistNames.add(Uri.fromFile(file));
            }
        }

        populatePlaylistTable();
    }

    //  Populate Table with Playlist Rows (Single Tap for Menu)
    private void populatePlaylistTable() {

        // setup the last test run for resume.  To be resumable, must have valid log file
        // with at least one entry after the headers (a valid timestamp)
        // this is only for the most recent test and strictly managed

        ResumeInfo resumeInfo = ResumeInfo.empty;
        File latest = StorageManager.findLatestLogFile();
        if(latest != null){
            long lastLogTimestamp = StorageManager.readLastTimestamp(latest);
            if(lastLogTimestamp >= 0){
                SessionHeader sh = SessionHeader.fromLogFile(latest);

                if(sh != null) {
                    resumeInfo = new ResumeInfo(
                            sh.getSessionInfo().playlist,
                            latest.getAbsolutePath(),
                            sh.getSessionInfo().start_time.unix_time_ms,
                            lastLogTimestamp,
                            System.currentTimeMillis() -   lastLogTimestamp

                    );
                }
            }
        }
        playlistTable.removeAllViews(); // Clear old rows

        for (Uri playlistItem : playlistNames) {
            addOnePlaylistTableRow(playlistItem, resumeInfo);
        }
    }


    private void addOnePlaylistTableRow(Uri playlistItem, ResumeInfo resumeInfo){
        TableRow row = new TableRow(getContext());

        //  Playlist Name (Clickable Row)
        TextView playlistText = new TextView(getContext());
        String playlistName = displayNameFromFileUri(playlistItem);
        playlistText.setText(playlistName);
        playlistText.setTextSize(16);
        playlistText.setPadding(16, 16, 16, 16);
        playlistText.setGravity(Gravity.START);
        playlistText.setBackgroundResource(android.R.drawable.list_selector_background);

        //  Add to Row
        row.addView(playlistText, new TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if(!playlistName.isEmpty() && resumeInfo.playlistName.equals(playlistName)){
            playlistText.setTag(R.id.resume_info, resumeInfo);
        }

        //  Handle Click to Open PlaylistDialog
        playlistText.setOnClickListener(v -> showPlaylistOptionsMenu_menu(v, playlistItem));

        //  Add Row to Table
        playlistTable.addView(row);
    }

    private void showPlaylistOptionsMenu_menu(View view, Uri playlistUri) {
        if (!isAdded() || getActivity() == null) {
            return; // Prevent crash if fragment is detached
        }

        final ResumeInfo resumeInfo;

        Object maybeResumeInfo = view.getTag(R.id.resume_info);
        if (maybeResumeInfo instanceof ResumeInfo) {
            resumeInfo = (ResumeInfo) maybeResumeInfo;
        }
        else {
            resumeInfo = ResumeInfo.empty;
        }

        PopupMenu popupMenu = new PopupMenu(requireContext(), view);
        popupMenu.getMenuInflater().inflate(R.menu.playlist_options_menu, popupMenu.getMenu());

        if(resumeInfo != ResumeInfo.empty){
            popupMenu.getMenu()
                    .findItem(R.id.menu_resume)
                    .setVisible(true);
        }

        // ✅ Force icons to show
        try {
            Field field = popupMenu.getClass().getDeclaredField("mPopup");
            field.setAccessible(true);
            Object menuPopupHelper = field.get(popupMenu);
            Class<?> classPopupHelper = Class.forName(menuPopupHelper.getClass().getName());
            Method setForceShowIcon = classPopupHelper.getMethod("setForceShowIcon", boolean.class);
            setForceShowIcon.invoke(menuPopupHelper, true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        popupMenu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_edit) {
                openPlaylistDialog(playlistUri); // Edit Playlist
                return true;
            } else if (id == R.id.menu_play || id == R.id.menu_resume) {

                // if resumable, we will have a valid resumeInfo even if we want to play
                // forcign to ResumeINfo.empty for play
                ResumeInfo ri = id == R.id.menu_play ? ResumeInfo.empty : resumeInfo;

                int batteryLevel = BatteryInfo.getBatteryLevel(getContext());
                if ((this.viewModel.getRunConfig().runMode.name().equals("BATTERY")) && (this.viewModel.getRunConfig().runLimit >= (batteryLevel - 1))) {
                    Toast.makeText(requireContext(), "Battery limit must be at least 2% less than the current battery level", Toast.LENGTH_SHORT).show();
                } else {
                    this.viewModel.curTestDetails.startTest(playlistUri.toString());

                    Intent i = new Intent(getActivity(), FullScreenPlayerActivity.class);
                    startActivity(i);
                }
                return true;
            }else if (id == R.id.menu_delete) {
                deletePlaylist(getContext(), playlistUri); // Delete Playlist
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    void deletePlaylist(Context context, Uri playlistUri) {
        try {
            Uri folderUri = this.viewModel.getFolderUri();
            if (folderUri == null) {
                Log.e(TAG, "DeletePlaylist: No valid folder selected.");
                return;
            }

            File folder = new File(folderUri.getPath());
            if (!folder.exists() || !folder.isDirectory()) {
                Log.e(TAG, "DeletePlaylist: Selected folder is invalid: " + folder.getAbsolutePath());
                return;
            }

            // Extract the playlist file name from the Uri
            String playlistFileName = new File(playlistUri.getPath()).getName();
            File targetFile = new File(folder, playlistFileName);

            if (targetFile.exists() && targetFile.delete()) {
                Log.i(TAG, "Deleted playlist: " + targetFile.getAbsolutePath());
                onPlaylistDeleted(Uri.fromFile(targetFile));
            } else {
                Log.e(TAG, "DeletePlaylist" + "Failed to delete playlist or file not found: " + targetFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "DeletePlaylist: Error deleting playlist", e);
        }
    }

    private String getFileNameFromUri(Context context, Uri uri) {
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    private void openPlaylistDialog(Uri playlist) {
        if (this.viewModel.getFolderUri() == null) {
            return;
        }

        //  Pass the `Uri` Instead of a `File`
        PlaylistDialog dialog = new PlaylistDialog(requireContext(), displayNameFromFileUri(playlist),  playlist, this.viewModel.getFolderUri(), this);
        dialog.show(getChildFragmentManager(), "PlaylistDialog");
    }
}

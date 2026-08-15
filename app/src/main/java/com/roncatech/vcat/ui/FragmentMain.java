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

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import androidx.documentfile.provider.DocumentFile;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class FragmentMain extends Fragment implements PlaylistUpdates {
    private final static String TAG = "MainFragment";

    private TextView playlistFolderText;
    private TableLayout playlistTable;
    private SharedViewModel viewModel;

    public FragmentMain()  {
    }

    /** Pre-resolved playlist metadata. {@code DocumentFile.getName()} is a SAF IPC query, so we
     *  resolve the name/uri once on a background thread and never touch SAF on the UI thread. */
    private static final class PlaylistEntry {
        final DocumentFile doc;
        final String name;
        final String uriStr;
        PlaylistEntry(DocumentFile doc, String name, String uriStr) {
            this.doc = doc;
            this.name = name;
            this.uriStr = uriStr;
        }
    }

    private void runOnUiThreadIfAdded(Runnable r) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(r);
        }
    }

    public void onPlaylistAdded(Uri playlistUri){
        getPlaylistFiles();
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

        browsePlaylistsButton.setVisibility(View.GONE);
        createPlaylistButton.setOnClickListener(v -> createPlaylist());

        fillDeviceLayout(view);
        updatePlaylistFolder();
        return view;
    }

    private void createPlaylist(){
        PlaylistDialog dialog = new PlaylistDialog(requireContext(), "New Playlist", null, this.viewModel.getFolderUri(), this);
        dialog.show(getChildFragmentManager(), "PlaylistDialog");
    }

    private void updatePlaylistFolder(){
        Uri folderUri = this.viewModel.getFolderUri();
        if (folderUri != null) {
            DocumentFile folder = DocumentFile.fromSingleUri(requireContext(), folderUri);
            String name = folder != null ? folder.getName() : null;
            playlistFolderText.setText("Folder: " + (name != null ? name : folderUri.toString()));
            getPlaylistFiles();
        }
    }

    //  Scan Selected Folder for .xspf Files.
    //  Every DocumentFile.getName() is a SAF ContentResolver.query (IPC); the listing, the sort
    //  comparator, and the resume-info log scan all hit SAF, so doing this on the UI thread ANRs
    //  (input-dispatch timeout) — especially after a save. Gather everything on a background
    //  thread, then render the table on the UI thread from pre-resolved values.
    private void getPlaylistFiles() {
        final Context ctx = requireContext().getApplicationContext();
        new Thread(() -> {
            List<PlaylistEntry> entries = new ArrayList<>();
            DocumentFile playlistDir = StorageManager.getFolder(ctx, StorageManager.VCATFolder.PLAYLIST);
            if (playlistDir != null) {
                DocumentFile[] files = playlistDir.listFiles();
                if (files != null) {
                    for (DocumentFile f : files) {
                        String name = f.getName();               // one IPC per file
                        if (name != null && name.endsWith(".xspf")) {
                            entries.add(new PlaylistEntry(f, name, f.getUri().toString()));
                        }
                    }
                    entries.sort((a, b) -> a.name.compareToIgnoreCase(b.name)); // cached names, no IPC
                }
            }
            ResumeInfo resumeInfo = computeResumeInfo(ctx);

            final List<PlaylistEntry> finalEntries = entries;
            final ResumeInfo finalResume = resumeInfo;
            runOnUiThreadIfAdded(() -> populatePlaylistTable(finalEntries, finalResume));
        }).start();
    }

    // Resolve resume info from the latest log file. Touches SAF — must run off the UI thread.
    private ResumeInfo computeResumeInfo(Context ctx) {
        DocumentFile latest = StorageManager.findLatestLogFile(ctx);
        if (latest != null) {
            long lastLogTimestamp = StorageManager.readLastTimestamp(ctx, latest);
            if (lastLogTimestamp >= 0) {
                SessionHeader sh = SessionHeader.fromLogFile(ctx, latest.getUri());
                if (sh != null) {
                    return new ResumeInfo(
                            sh.getSessionInfo().playlist,
                            latest.getUri().toString(),
                            sh.getSessionInfo().start_time.unix_time_ms,
                            lastLogTimestamp,
                            System.currentTimeMillis() - lastLogTimestamp
                    );
                }
            }
        }
        return ResumeInfo.empty;
    }

    //  Populate Table with Playlist Rows (Single Tap for Menu). UI thread only — no SAF calls.
    private void populatePlaylistTable(List<PlaylistEntry> entries, ResumeInfo resumeInfo) {
        playlistTable.removeAllViews();
        for (PlaylistEntry entry : entries) {
            addOnePlaylistTableRow(entry, resumeInfo);
        }
    }

    private void addOnePlaylistTableRow(PlaylistEntry entry, ResumeInfo resumeInfo){
        TableRow row = new TableRow(getContext());

        TextView playlistText = new TextView(getContext());
        playlistText.setText(entry.name);
        playlistText.setTextSize(16);
        playlistText.setPadding(16, 16, 16, 16);
        playlistText.setGravity(Gravity.START);
        playlistText.setBackgroundResource(android.R.drawable.list_selector_background);

        row.addView(playlistText, new TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String docUriStr = entry.uriStr;
        if (!docUriStr.isEmpty() && resumeInfo.playlistName.equals(docUriStr)) {
            playlistText.setTag(R.id.resume_info, resumeInfo);
        }

        playlistText.setOnClickListener(v -> showPlaylistOptionsMenu_menu(v, entry.doc));

        playlistTable.addView(row);
    }

    private void showPlaylistOptionsMenu_menu(View view, DocumentFile playlistDoc) {
        if (!isAdded() || getActivity() == null) {
            return;
        }

        final ResumeInfo resumeInfo;
        Object maybeResumeInfo = view.getTag(R.id.resume_info);
        resumeInfo = (maybeResumeInfo instanceof ResumeInfo) ? (ResumeInfo) maybeResumeInfo : ResumeInfo.empty;

        PopupMenu popupMenu = new PopupMenu(requireContext(), view);
        popupMenu.getMenuInflater().inflate(R.menu.playlist_options_menu, popupMenu.getMenu());

        if (resumeInfo != ResumeInfo.empty) {
            popupMenu.getMenu().findItem(R.id.menu_resume).setVisible(true);
        }

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
                openPlaylistDialog(playlistDoc);
                return true;
            } else if (id == R.id.menu_play || id == R.id.menu_resume) {
                ResumeInfo ri = id == R.id.menu_play ? ResumeInfo.empty : resumeInfo;
                int batteryLevel = BatteryInfo.getBatteryLevel(getContext());
                if ((this.viewModel.getRunConfig().runMode.name().equals("BATTERY")) && (this.viewModel.getRunConfig().runLimit >= (batteryLevel - 1))) {
                    Toast.makeText(requireContext(), "Battery limit must be at least 2% less than the current battery level", Toast.LENGTH_SHORT).show();
                } else {
                    this.viewModel.curTestDetails.startTest(playlistDoc.getUri().toString());
                    Intent i = new Intent(getActivity(), FullScreenPlayerActivity.class);
                    startActivity(i);
                }
                return true;
            } else if (id == R.id.menu_delete) {
                deletePlaylist(playlistDoc);
                return true;
            }
            return false;
        });

        popupMenu.show();
    }

    void deletePlaylist(DocumentFile playlistDoc) {
        if (playlistDoc.delete()) {
            Log.i(TAG, "Deleted playlist: " + playlistDoc.getUri());
            onPlaylistDeleted(playlistDoc.getUri());
        } else {
            Log.e(TAG, "DeletePlaylist: Failed to delete: " + playlistDoc.getUri());
        }
    }

    private void openPlaylistDialog(DocumentFile playlistDoc) {
        Uri folderUri = this.viewModel.getFolderUri();
        if (folderUri == null) return;
        String name = playlistDoc.getName() != null ? playlistDoc.getName() : "";
        PlaylistDialog dialog = new PlaylistDialog(requireContext(), name, playlistDoc.getUri(), folderUri, this);
        dialog.show(getChildFragmentManager(), "PlaylistDialog");
    }
}

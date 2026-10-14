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

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.roncatech.vcat.models.TestResultsItem;
import com.roncatech.vcat.models.TestSummary;
import com.roncatech.vcat.tools.StorageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import com.roncatech.vcat.R;

public class FragmentTestLogs extends Fragment {

    private static final String TAG = "TestResultsFragment";

    private TestResultsAdapter adapter;

    private ImageButton btnEditLogs;
    private LinearLayout selectAllRow;
    private Button btnSelectAll;
    private Button btnCancelEdit;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_test_results, container, false);

        btnEditLogs   = view.findViewById(R.id.btnEditLogs);
        selectAllRow  = view.findViewById(R.id.selectAllRow);
        btnSelectAll  = view.findViewById(R.id.btnSelectAll);
        btnCancelEdit = view.findViewById(R.id.btnCancelEdit);

        RecyclerView rv = view.findViewById(R.id.rvTestResults);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new TestResultsAdapter();
        adapter.setOnResultClickListener(this::onLogFileClick);
        adapter.setOnSelectionChangedListener(this::onSelectionChanged);
        rv.setAdapter(adapter);

        btnEditLogs.setOnClickListener(v -> {
            if (adapter.isEditMode()) {
                // Already selecting: the button is the trashcan, so this commits the delete.
                deleteSelectedFiles();
            } else {
                enterEditMode();
            }
        });

        btnSelectAll.setOnClickListener(v -> {
            if (adapter.areAllSelected()) {
                adapter.unselectAll();
            } else {
                adapter.selectAll();
            }
        });

        btnCancelEdit.setOnClickListener(v -> exitEditMode());

        loadTestResults();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // A run started from another tab writes a new log, so re-read on every return to this tab.
        loadTestResults();
    }

    private void enterEditMode() {
        adapter.setEditMode(true);
        selectAllRow.setVisibility(View.VISIBLE);
        btnSelectAll.setText(R.string.select_all);
        btnEditLogs.setImageResource(R.drawable.ic_trashcan);
        setTrashEnabled(false);
    }

    /** Leave select mode and restore the normal edit-button state. */
    private void exitEditMode() {
        adapter.setEditMode(false);
        selectAllRow.setVisibility(View.GONE);
        btnSelectAll.setText(R.string.select_all);
        btnEditLogs.setImageResource(R.drawable.ic_edit_playlist);
        setTrashEnabled(true);
    }

    /** Dim as well as disable: an undimmed trashcan that ignores taps looks broken. */
    private void setTrashEnabled(boolean enabled) {
        btnEditLogs.setEnabled(enabled);
        btnEditLogs.setAlpha(enabled ? 1.0f : 0.4f);
    }

    private void onSelectionChanged(int selectedCount) {
        if (adapter.isEditMode()) {
            setTrashEnabled(selectedCount > 0);
            btnSelectAll.setText(adapter.areAllSelected()
                    ? R.string.unselect_all : R.string.select_all);
        }
    }

    private void onLogFileClick(View view, String filePath) {
        PopupMenu popup = new PopupMenu(requireContext(), view);
        popup.getMenuInflater().inflate(R.menu.log_file_context_menu, popup.getMenu());

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_details) {
                TestResultsDetailDialog
                        .newInstance(filePath)
                        .show(getParentFragmentManager(), "test_result_detail");
                return true;
            } else if (id == R.id.action_delete) {
                confirmDeleteLogFile(filePath);
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void confirmDeleteLogFile(String filePath) {
        String name = TestResultsItem.getFileName(filePath);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_log_confirm_title)
                .setMessage(getString(R.string.delete_log_confirm_message, name))
                .setPositiveButton(R.string.delete_confirm_yes, (dialog, which) -> {
                    boolean ok = deleteLog(filePath);
                    Toast.makeText(requireContext(),
                            ok ? R.string.delete_log_ok : R.string.delete_log_failed,
                            Toast.LENGTH_SHORT).show();
                    loadTestResults();
                })
                .setNegativeButton(R.string.delete_confirm_no, null)
                .show();
    }

    private void deleteSelectedFiles() {
        List<String> toDelete = adapter.getSelectedFiles();
        if (toDelete.isEmpty()) return;

        int count = toDelete.size();
        int msgRes = count == 1
                ? R.string.delete_logs_confirm_message_one
                : R.string.delete_logs_confirm_message_many;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_logs_confirm_title)
                .setMessage(getString(msgRes, count))
                .setPositiveButton(R.string.delete_confirm_yes,
                        (dialog, which) -> performBatchDelete(toDelete))
                .setNegativeButton(R.string.delete_confirm_no, null)
                .show();
    }

    private void performBatchDelete(List<String> toDelete) {
        int deleted = 0;
        for (String filePath : toDelete) {
            if (deleteLog(filePath)) deleted++;
        }

        // Report the partial count rather than a bare success: a SAF delete can fail per-file,
        // and silently dropping that would leave logs the user believes are gone.
        String msg = deleted == toDelete.size()
                ? getResources().getQuantityString(R.plurals.logs_deleted, deleted, deleted)
                : getString(R.string.logs_deleted_partial, deleted, toDelete.size());
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();

        exitEditMode();
        loadTestResults();
    }

    /** Deletes one log by document URI. Returns whether the file is gone afterwards. */
    private boolean deleteLog(String filePath) {
        DocumentFile logFile = DocumentFile.fromSingleUri(requireContext(), Uri.parse(filePath));
        if (logFile == null || !logFile.exists()) {
            Log.w(TAG, "Log already gone: " + filePath);
            return true;
        }
        if (logFile.delete()) {
            Log.i(TAG, "Deleted log: " + filePath);
            return true;
        }
        Log.e(TAG, "Failed to delete log: " + filePath);
        return false;
    }

    private void loadTestResults() {
        DocumentFile logsDir = StorageManager.getFolder(requireContext(), StorageManager.VCATFolder.TEST_RESULTS);
        if (logsDir == null) {
            Log.e(TAG, "TEST_RESULTS folder not available");
            adapter.setResults(new ArrayList<>());
            return;
        }

        DocumentFile[] files = logsDir.listFiles();
        List<TestResultsItem> results = new ArrayList<>();
        Map<String, Uri> logsByName = new LinkedHashMap<>();

        if (files != null) {
            for (DocumentFile f : files) {
                String name = f.getName();
                if (name == null || !name.startsWith("vcatd_log_") || !name.endsWith(".csv")) continue;

                String uriString = f.getUri().toString();
                long timeStampMS = TestResultsItem.getTimeStamp(uriString);

                if (timeStampMS > 0) {
                    results.add(new TestResultsItem(uriString, timeStampMS, f.length()));
                    logsByName.put(name, f.getUri());
                } else {
                    Log.e(TAG, "Invalid log file: " + name);
                }
            }
            Collections.sort(results, (a, b) ->
                    Long.compare(b.getTimestampMillis(), a.getTimestampMillis())
            );
        }
        adapter.setResults(results);

        // Reconcile the index against what is actually in the folder, then attach entries to the
        // rows. Off the main thread: backfilling an unsummarised log parses it, and a folder of
        // long runs would otherwise stall the UI on first open. The list is already showing file
        // names by then, so the labels simply improve when this returns.
        final DocumentFile dir = logsDir;
        new Thread(() -> {
            Map<String, TestSummary.Entry> byName = new LinkedHashMap<>();
            for (TestSummary.Entry e : TestSummary.reconcile(requireContext().getApplicationContext(),
                                                             dir, logsByName)) {
                if (e != null && e.file != null) byName.put(e.file, e);
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                for (TestResultsItem item : results) {
                    item.setSummary(byName.get(item.getFileName()));
                }
                adapter.notifyDataSetChanged();
            });
        }, "vcat-summary-reconcile").start();
    }
}

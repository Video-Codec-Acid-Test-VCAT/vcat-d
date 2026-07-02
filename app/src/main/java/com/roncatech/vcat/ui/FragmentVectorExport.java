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

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import androidx.documentfile.provider.DocumentFile;

import com.roncatech.vcat.R;
import com.roncatech.vcat.test_vectors.ExportTestVectors;
import com.roncatech.vcat.tools.StorageManager;

import java.util.ArrayList;
import java.util.List;

public class FragmentVectorExport extends Fragment implements ExportTestVectorsDialog.Listener {
    private TableLayout tableExport;

    private ImageButton btnExport;

    // Track the currently selected playlist (single selection)
    private DocumentFile selectedPlaylist = null;
    private TableRow selectedRow = null;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_vector_export, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tableExport = view.findViewById(R.id.tableExport);
        btnExport = view.findViewById(R.id.btnExportPlaylists);

        // Hide the Select All controls (single selection mode)
        View cbSelectAll = view.findViewById(R.id.cbSelectAll);
        View tvSelectAll = view.findViewById(R.id.tvSelectAll);
        if (cbSelectAll != null) cbSelectAll.setVisibility(View.GONE);
        if (tvSelectAll != null) tvSelectAll.setVisibility(View.GONE);

        btnExport.setOnClickListener(v -> doExport());
        btnExport.setEnabled(false);

        loadPlaylists();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPlaylists();
    }

    private void loadPlaylists() {
        DocumentFile playlistDir = StorageManager.getFolder(requireContext(), StorageManager.VCATFolder.PLAYLIST);

        tableExport.removeAllViews();
        selectedPlaylist = null;
        selectedRow = null;
        btnExport.setEnabled(false);

        if (playlistDir == null) return;

        DocumentFile[] allFiles = playlistDir.listFiles();
        if (allFiles == null || allFiles.length == 0) return;

        List<DocumentFile> xspfFiles = new ArrayList<>();
        for (DocumentFile f : allFiles) {
            String name = f.getName();
            if (name != null && name.toLowerCase().endsWith(".xspf")) {
                xspfFiles.add(f);
            }
        }
        if (xspfFiles.isEmpty()) return;

        xspfFiles.sort((a, b) -> {
            String na = a.getName() != null ? a.getName() : "";
            String nb = b.getName() != null ? b.getName() : "";
            return na.compareToIgnoreCase(nb);
        });

        LayoutInflater inflater = getLayoutInflater();
        for (DocumentFile f : xspfFiles) {
            TableRow row = (TableRow) inflater.inflate(
                    R.layout.row_test_vector, tableExport, false);

            CheckBox cb = row.findViewById(R.id.cbRow);
            cb.setChecked(false);

            TextView tv = row.findViewById(R.id.tvRowText);
            tv.setText(f.getName());

            row.setTag(f);

            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    if (selectedRow != null && selectedRow != row) {
                        CheckBox prevCb = selectedRow.findViewById(R.id.cbRow);
                        if (prevCb != null) prevCb.setChecked(false);
                    }
                    selectedRow = row;
                    selectedPlaylist = f;
                    btnExport.setEnabled(true);
                } else {
                    if (selectedRow == row) {
                        selectedRow = null;
                        selectedPlaylist = null;
                        btnExport.setEnabled(false);
                    }
                }
            });

            tableExport.addView(row);
        }
    }

    /**
     * Handle export button click.
     * Shows ExportTestVectorsDialog if a playlist is selected.
     */
    private void doExport() {
        if (selectedPlaylist == null) {
            Toast.makeText(requireContext(), "Please select a playlist to export", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show the modal ExportTestVectorsDialog
        ExportTestVectorsDialog exportDialog = new ExportTestVectorsDialog();
        exportDialog.setListener(this);
        exportDialog.show(getParentFragmentManager(), "ExportTestVectorsDialog");
    }

    @Override
    public void onExportConfirmed(android.net.Uri stagingUri, String vectorName, String createdBy, String description) {
        if (selectedPlaylist == null) {
            Toast.makeText(requireContext(), "No playlist selected", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder dlgBuilder = new AlertDialog.Builder(requireContext());
        dlgBuilder.setTitle("Export Progress");
        ScrollView statusScroll = new ScrollView(requireContext());
        TextView statusTv = new TextView(requireContext());
        statusTv.setPadding(32, 32, 32, 32);
        statusScroll.addView(statusTv);
        dlgBuilder.setView(statusScroll);
        dlgBuilder.setPositiveButton("OK", null);
        dlgBuilder.setCancelable(false);
        AlertDialog statusDialog = dlgBuilder.create();
        statusDialog.show();
        statusDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

        ExportTestVectors.exportPlaylist(
                requireContext(),
                selectedPlaylist.getUri(),
                stagingUri,
                vectorName,
                createdBy,
                description,
                new ExportTestVectors.ExportCallback() {
                    @Override
                    public void onProgress(String message) {
                        statusTv.append(message + "\n");
                        statusScroll.fullScroll(View.FOCUS_DOWN);
                    }

                    @Override
                    public void onSuccess(android.net.Uri exportUri) {
                        statusTv.append("\nExport completed successfully!\n");
                        statusTv.append("Location: " + exportUri.toString() + "\n");
                        statusScroll.fullScroll(View.FOCUS_DOWN);
                        statusDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    }

                    @Override
                    public void onError(String errorMessage) {
                        statusTv.append("\nExport failed: " + errorMessage + "\n");
                        statusScroll.fullScroll(View.FOCUS_DOWN);
                        statusDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    }
                }
        );
    }

    @Override
    public void onExportCancelled() {
        // Nothing to do
    }
}

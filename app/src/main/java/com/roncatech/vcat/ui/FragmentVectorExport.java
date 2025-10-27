/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting VCAT itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.roncatech.vcat.R;
import com.roncatech.vcat.tools.StorageManager;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public class FragmentVectorExport extends Fragment implements ExportTestVectorsDialog.Listener{
    private TableLayout tableExport;
    private CheckBox   cbSelectAll;
    private TextView   tvSelectAll;

    private ImageButton btnExport;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_vector_export, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        tableExport   = view.findViewById(R.id.tableExport);
        cbSelectAll   = view.findViewById(R.id.cbSelectAll);
        tvSelectAll   = view.findViewById(R.id.tvSelectAll);
        btnExport = view.findViewById(R.id.btnExportPlaylists);

        btnExport.setOnClickListener(v ->doExport());

        loadPlaylists();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPlaylists();
    }

    private void loadPlaylists() {
        File playlistDir = StorageManager.getFolder(StorageManager.VCATFolder.PLAYLIST);

        tableExport.removeAllViews();

        File[] files = playlistDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xspf"));
        if (files == null || files.length == 0) {
            cbSelectAll.setVisibility(View.GONE);
            tvSelectAll.setVisibility(View.GONE);
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));

        LayoutInflater inflater = getLayoutInflater();
        for (File f : files) {
            TableRow row = (TableRow) inflater.inflate(
                    R.layout.row_test_vector, tableExport, false);

            CheckBox cb = row.findViewById(R.id.cbRow);
            cb.setChecked(false);

            TextView tv = row.findViewById(R.id.tvRowText);
            tv.setText(f.getName());

            row.setTag(f);
            tableExport.addView(row);
        }

        boolean showSelectAll = files.length >= 2;
        cbSelectAll.setVisibility(showSelectAll ? View.VISIBLE : View.GONE);
        tvSelectAll.setVisibility(showSelectAll ? View.VISIBLE : View.GONE);

        if (showSelectAll) {
            cbSelectAll.setChecked(false);
            cbSelectAll.setOnCheckedChangeListener((button, isChecked) -> {
                for (int i = 0; i < tableExport.getChildCount(); i++) {
                    TableRow r = (TableRow) tableExport.getChildAt(i);
                    CheckBox c = r.findViewById(R.id.cbRow);
                    c.setChecked(isChecked);
                }
            });
        }
    }

    /**
     * Handle export button click.
     * Shows ExportTestVectorsDialog, then UnderConstructionDialog.
     */
    private void doExport() {
        // Show the modal ExportTestVectorsDialog
        ExportTestVectorsDialog exportDialog = new ExportTestVectorsDialog();
        exportDialog.setListener(this);
        exportDialog.show(getParentFragmentManager(), "ExportTestVectorsDialog");
    }

    @Override
    public void onExportConfirmed(String stagingFolder, String vectorName, String createdBy, String description){
        UnderConstructionDialog.newInstance()
                .show(getParentFragmentManager(), UnderConstructionDialog.TAG);
    }

    @Override
    public void onExportCancelled(){

    }

}

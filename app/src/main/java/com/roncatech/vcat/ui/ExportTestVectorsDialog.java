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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.DialogFragment;
import com.roncatech.vcat.tools.StorageManager;

import com.roncatech.vcat.R;

public class ExportTestVectorsDialog extends DialogFragment {

    public interface Listener {
        void onExportConfirmed(String stagingFolder, String vectorName, String createdBy, String description);
        void onExportCancelled();
    }

    private static final int REQUEST_CODE_PICK_FOLDER = 101;

    private EditText edit_staging_folder;
    private EditText edit_vector_name;
    private EditText edit_created_by;
    private EditText edit_description;
    private Button button_staging_folder;

    private Uri selectedFolderUri;
    private Listener listener;
    private AlertDialog dialog;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_export_test_vectors_info, null);

        // bind to XML IDs
        edit_staging_folder = view.findViewById(R.id.edit_staging_folder);
        edit_vector_name    = view.findViewById(R.id.edit_vector_name);
        edit_created_by     = view.findViewById(R.id.edit_created_by);
        edit_description   = view.findViewById(R.id.edit_description);
        button_staging_folder = view.findViewById(R.id.button_staging_folder);

        // folder picker
        button_staging_folder.setOnClickListener(v -> openFolderPicker());

        // TextWatcher to enable/disable OK button
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateOkButtonState(); }
        };
        edit_staging_folder.addTextChangedListener(watcher);
        edit_vector_name.addTextChangedListener(watcher);
        edit_created_by.addTextChangedListener(watcher);
        edit_description.addTextChangedListener(watcher);

        // Build AlertDialog
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("Export Test Vectors")
                .setView(view)
                .setPositiveButton("OK", null) // override later to control enable/disable
                .setNegativeButton("Cancel", (dialogInterface, which) -> {
                    if (listener != null) listener.onExportCancelled();
                });

        dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            okButton.setEnabled(false);
            okButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onExportConfirmed(
                            edit_staging_folder.getText().toString(),
                            edit_vector_name.getText().toString(),
                            edit_created_by.getText().toString(),
                            edit_description.getText().toString()
                    );
                }
                dismiss();
            });
        });

        return dialog;
    }

    private void updateOkButtonState() {
        if (dialog == null) return;
        Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (okButton == null) return;

        boolean enabled = !edit_staging_folder.getText().toString().trim().isEmpty()
                && !edit_vector_name.getText().toString().trim().isEmpty()
                && !edit_created_by.getText().toString().trim().isEmpty()
                && !edit_description.getText().toString().trim().isEmpty();
        okButton.setEnabled(enabled);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER);
    }

    @Override
    @SuppressLint("WrongConstant")
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FOLDER && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri treeUri = data.getData();

                // Persist permissions
                final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                requireContext().getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

                // Convert Uri to a full path (if possible)
                String fullPath = StorageManager.getFullPathFromUri(requireContext(), treeUri);

                edit_staging_folder.setText(fullPath);

                updateOkButtonState();
            }
        }
    }
}

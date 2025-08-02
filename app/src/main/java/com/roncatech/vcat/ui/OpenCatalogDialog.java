package com.roncatech.vcat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.roncatech.vcat.R;

public class OpenCatalogDialog extends DialogFragment {
    public interface Listener {
        void onCatalogChosen(@NonNull String catalogUrl);
    }

    private Listener listener;
    private RadioGroup    rgSource;
    private RadioButton   rbHttp, rbFile;
    private EditText      etHttpUrl, etFolderUri;
    private LinearLayout  llFilePicker;
    private ImageButton   btnBrowse;
    private Uri lastPickerTreeUri = null;

    private static final String ARG_INITIAL_URL = "initial_url";

    private final ActivityResultLauncher<Uri> openTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri != null) {
                            // user picked a local folder
                            etFolderUri.setText(uri.toString());
                        } else {
                            // user hit “Cancel” → go back to HTTP-URL mode
                            rbHttp.setChecked(true);
                        }
                    });

    public static OpenCatalogDialog newInstance(@NonNull String initialUrl) {
        OpenCatalogDialog dlg = new OpenCatalogDialog();
        Bundle args = new Bundle();
        args.putString(ARG_INITIAL_URL, initialUrl);
        dlg.setArguments(args);
        return dlg;
    }

    private ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            Intent data = result.getData();
                            if (data != null && data.getData() != null) {
                                Uri treeUri = data.getData();
                                // take persistable permission so we can read children later
                                requireContext().getContentResolver()
                                        .takePersistableUriPermission(
                                                treeUri,
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        );
                                listener.onCatalogChosen(treeUri.toString());
                                OpenCatalogDialog.this.dismiss();
                            }
                        }
                    });

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // host fragment/activity must implement Listener
        if (getParentFragment() instanceof Listener) {
            listener = (Listener) getParentFragment();
        } else if (context instanceof Listener) {
            listener = (Listener) context;
        } else {
            throw new IllegalStateException("Must implement OpenCatalogDialog.Listener");
        }
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {

        // 1) Inflate our custom layout
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View root = inflater.inflate(R.layout.dialog_open_catalog, null, false);

        // 2) Find all views
        rgSource    = root.findViewById(R.id.rgSource);
        rbHttp      = root.findViewById(R.id.rbHttp);
        rbFile      = root.findViewById(R.id.rbFolder);
        etHttpUrl   = root.findViewById(R.id.etHttpUrl);
        llFilePicker= root.findViewById(R.id.llFilePicker);
        etFolderUri = root.findViewById(R.id.etFileUri);
        btnBrowse   = root.findViewById(R.id.btnBrowse);

        // 3) Wire up the HTTP/Folder toggle
        rgSource.setOnCheckedChangeListener((group, checkedId) -> {
            boolean httpSelected = (checkedId == R.id.rbHttp);
            etHttpUrl.setVisibility(httpSelected ? View.VISIBLE : View.GONE);
            llFilePicker.setVisibility(httpSelected ? View.GONE    : View.VISIBLE);

            if (!httpSelected) {
                // User chose “Folder” → immediately fire the SAF folder picker
                openTreeLauncher.launch(null);
            } else {
                // Back in HTTP mode: clear out any prior folder URI
                etFolderUri.setText("");
                etFolderUri.setHint("https://example.com/my_playlist_catalog.json");
            }
        });


        // 4) Pre‐populate from the initial URL argument
        String initialUrl = "";
        Bundle args = getArguments();
        if (args != null) {
            initialUrl = args.getString(ARG_INITIAL_URL, "");
        }
        if (initialUrl.startsWith("http://") || initialUrl.startsWith("https://")) {
            rbHttp.setChecked(true);
            etHttpUrl.setText(initialUrl);
        } else if (initialUrl.startsWith("file://")
                || initialUrl.startsWith("jar:")
                || initialUrl.startsWith("zip:")) {
            rbFile.setChecked(true);
            etFolderUri.setText(initialUrl);
        } else {
            // default to HTTP if it’s something unexpected
            rbHttp.setChecked(true);
            etHttpUrl.setText(initialUrl);
        }

        // 5) Browse button to pick a local folder that contains the test content
        btnBrowse.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            );
            // if we’ve picked before, start there…
            if (lastPickerTreeUri != null
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, lastPickerTreeUri);
            } else {
                // otherwise default to Downloads:
                Uri downloadsTree = DocumentsContract.buildTreeDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Download"
                );

                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsTree);
            }
            folderPicker.launch(i);
        });

        // 6) Build and return the AlertDialog
        return new AlertDialog.Builder(requireContext())
                .setTitle("Open Catalog")
                .setView(root)
                .setPositiveButton("OK", (dialog, which) -> {
                    String chosen = rbHttp.isChecked()
                            ? etHttpUrl .getText().toString().trim()
                            : etFolderUri.getText().toString().trim();
                    if (!chosen.isEmpty()) {
                        listener.onCatalogChosen(chosen);
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
    }

}

package com.roncatech.vcat.ui;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import com.roncatech.vcat.R;
import com.roncatech.vcat.models.TestVectorManifests;
import com.roncatech.vcat.models.TestVectorMediaAsset;
import com.roncatech.vcat.test_vectors.DownloadTestVectors;
import com.roncatech.vcat.test_vectors.SetupLocalVectors;
import com.roncatech.vcat.test_vectors.XspfBuilder;
import com.roncatech.vcat.tools.StorageManager;
import com.roncatech.vcat.tools.UriUtils;
import com.roncatech.vcat.tools.XSPFPlaylistCreator;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FragmentTestVectors extends Fragment implements OpenCatalogDialog.Listener {
    private final static String TAG = "FragmentTestVectors";

    private EditText etCatalogUrl;
    private ImageButton btnOpenCatalog;
    private TableLayout tableVectors;
    private ImageButton btnDownloadPlaylists;
    CheckBox cbSelectAll;
    TextView tvSelectAll;

    private String resolvedCatalogUrl = "";

    private int colorInProgress;
    private int colorSuccess;
    private int colorFailure;

    public FragmentTestVectors() {
        // Required empty public constructor
    }

    public static FragmentTestVectors newInstance() {
        return new FragmentTestVectors();
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_test_vectors, container, false);
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        colorInProgress = ContextCompat.getColor(getContext(), R.color.playlist_highlight);
        colorSuccess    = ContextCompat.getColor(getContext(), R.color.download_success);
        colorFailure    = ContextCompat.getColor(getContext(), R.color.download_failure);

        etCatalogUrl      = view.findViewById(R.id.etCatalogUrl);
        btnOpenCatalog = view.findViewById(R.id.btnOpenCatalog);
        tableVectors      = view.findViewById(R.id.tableVectors);
        btnDownloadPlaylists = view.findViewById(R.id.btnDownloadPlaylists);
        cbSelectAll = view.findViewById(R.id.cbSelectAll);
        tvSelectAll = view.findViewById(R.id.tvSelectAllText);

        btnDownloadPlaylists.setEnabled(false);

        String catalogUrl = "https://www.roncatech.com/vcat_vectors";

        etCatalogUrl.setText(catalogUrl);

        btnDownloadPlaylists.setOnClickListener(
                v -> startBatchDownload()
        );

        btnOpenCatalog.setOnClickListener(chk -> {
            String current = etCatalogUrl.getText().toString().trim();
            OpenCatalogDialog.newInstance(current)
                    .show(getChildFragmentManager(), "openCatalog");
        });

        String url = etCatalogUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(requireContext(), "Please enter a catalog URL", Toast.LENGTH_SHORT).show();
            return;
        }
        //startCatalogDownload(url);


        cbSelectAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Iterate all rows currently in the table
            for (int i = 0; i < tableVectors.getChildCount(); i++) {
                View child = tableVectors.getChildAt(i);
                // Each row was inflated from row_test_vector.xml
                CheckBox rowCb = child.findViewById(R.id.cbRow);
                if (rowCb != null) {
                    rowCb.setChecked(isChecked);
                }
            }
        });


        // TODO: optionally prepopulate etCatalogUrl from saved prefs
    }

    @Override
    public void onCatalogChosen(@NonNull String catalogUrl) {

        if (catalogUrl.startsWith("http://") || catalogUrl.startsWith("https://")) {
            startCatalogDownload(catalogUrl);
            return;
        }

        Uri treeUri = Uri.parse(catalogUrl);
        DocumentFile tree = DocumentFile.fromTreeUri(requireContext(), treeUri);

        // 1) find all “*_playlist_catalog.json” (or whatever suffix you use)
        List<DocumentFile> candidates = new ArrayList<>();
        for (DocumentFile df : tree.listFiles()) {
            if (df.isFile()
                    && df.getName() != null
                    && df.getName().endsWith("_playlist_catalog.json")) {
                candidates.add(df);
            }
        }

        if (candidates.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("No catalogs found")
                    .setMessage("That folder contains no catalog JSON.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        // 2) if more than one, let the user pick
        if (candidates.size() > 1) {
            String[] names = new String[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                names[i] = candidates.get(i).getName();
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle("Select catalog")
                    .setItems(names, (dlg, which) -> {
                        startCatalogDownload(candidates.get(which).toString());
                    })
                    .show();
        } else {
            startCatalogDownload(candidates.get(0).getUri().toString());
        }
    }

    private void startCatalogDownload(String catalogUrl) {
        // 1) Clear any previous rows
        tableVectors.removeAllViews();

        List<TestVectorMediaAsset> videoAssetTable = new ArrayList<>();

        DownloadTestVectors.CatalogCallback callback =new DownloadTestVectors.CatalogCallback() {
            @Override
            public void onSuccess(TestVectorManifests.Catalog catalog, String resolvedCatalogUrl) {
                FragmentTestVectors.this.resolvedCatalogUrl = resolvedCatalogUrl;

                // Make sure we're on the main thread (downloadCatalog already does this)
                // 3) Populate the table with one row per playlist
                for (TestVectorManifests.PlaylistAsset playlist : catalog.playlists) {
                    TableRow row = (TableRow) getLayoutInflater()
                            .inflate(R.layout.row_test_vector, tableVectors, false);

                    CheckBox cb = row.findViewById(R.id.cbRow);
                    cb.setChecked(false); // default

                    TextView tv = row.findViewById(R.id.tvRowText);
                    tv.setText(playlist.name);

                    // tag the row so you can retrieve the playlist URL later
                    row.setTag(playlist);

                    tableVectors.addView(row);
                }

                // now decide whether to show the select-all control
                if (catalog.playlists.size() >= 2) {
                    cbSelectAll.setVisibility(View.VISIBLE);
                    tvSelectAll.setVisibility(View.VISIBLE);
                } else {
                    cbSelectAll.setVisibility(View.GONE);
                    tvSelectAll.setVisibility(View.GONE);
                }
                btnDownloadPlaylists.setEnabled(catalog.playlists.size()> 0);
            }

            @Override
            public void onError(String errorMessage) {
                Toast.makeText(
                        requireContext(),
                        "Failed to download catalog: " + errorMessage,
                        Toast.LENGTH_SHORT
                ).show();
            }
        };

        // 2) Kick off the download
        if (catalogUrl.startsWith("http://") || catalogUrl.startsWith("https://")) {
            DownloadTestVectors.downloadCatalogHttp(requireContext(), catalogUrl, callback);
        } else{
            DownloadTestVectors.downloadCatalogFromFile(requireContext(),
                    Uri.parse(catalogUrl),
                    callback);
        }
    }

    private void startBatchDownload() {
        // 1) Gather checked assets & their rows
        List<Pair<TestVectorManifests.PlaylistAsset, TableRow>> queue = new ArrayList<>();
        for (int i = 0; i < tableVectors.getChildCount(); i++) {
            TableRow row = (TableRow) tableVectors.getChildAt(i);
            CheckBox cb  = row.findViewById(R.id.cbRow);
            if (cb != null && cb.isChecked()) {
                TestVectorManifests.PlaylistAsset asset =
                        (TestVectorManifests.PlaylistAsset) row.getTag();
                queue.add(new Pair<>(asset, row));
            }
        }
        if (queue.isEmpty()) return;

        // disable Download button to prevent re-entry
        btnDownloadPlaylists.setEnabled(false);

        // 2) Build & show the status dialog
        AlertDialog.Builder dlgBuilder = new AlertDialog.Builder(requireContext());
        dlgBuilder.setTitle("Download Status");
        ScrollView statusScroll = new ScrollView(requireContext());
        TextView statusTv = new TextView(requireContext());
        statusTv.setPadding(16,16,16,16);
        statusScroll.addView(statusTv);
        dlgBuilder.setView(statusScroll);
        dlgBuilder.setPositiveButton("OK", null);
        dlgBuilder.setCancelable(false);
        AlertDialog statusDialog = dlgBuilder.create();
        statusDialog.show();
        // disable OK until all done
        statusDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

        // 3) Kick off single‐threaded executor
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());

        exec.execute(() -> {
            // a shared mediaTable
            Map<UUID, TestVectorMediaAsset> mediaTable = new HashMap<>();

            String baseUrl = UriUtils.makeBaseUrlWithUri(this.resolvedCatalogUrl.trim());

            for (Pair<TestVectorManifests.PlaylistAsset, TableRow> p : queue) {
                TestVectorManifests.PlaylistAsset asset = p.first;
                TableRow row                           = p.second;

                // mark in‐progress
                main.post(() -> {
                    row.setBackgroundColor(colorInProgress);
                    statusTv.append(asset.name + ": STARTING…\n");
                    statusScroll.fullScroll(View.FOCUS_DOWN);
                });

                try {
                    // 3a) download playlist & videos
                    TestVectorManifests.PlaylistManifest manifest =
                            DownloadTestVectors.downloadPlaylistSync(
                                    requireContext(), baseUrl, asset, mediaTable
                            );

                    // 3b) relocate only this playlist’s assets

                    Map<UUID, TestVectorMediaAsset> finalAssets =
                            SetupLocalVectors.relocateMediaAssets(manifest, mediaTable);

                    // 3c) build & write XSPF
                    String xspfXml = XspfBuilder.buildPlaylistString(manifest, finalAssets);
                    File xspfFile = new File(
                            StorageManager.getFolder(StorageManager.VCATFolder.PLAYLIST),
                            manifest.header.name.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".xspf"
                    );

                    if (!xspfFile.exists()) {
                        List<String> playlistFiles = new ArrayList<>();
                        for (TestVectorManifests.PlaylistAsset pa : manifest.mediaAssets) {
                            TestVectorMediaAsset tv = finalAssets.get(pa.uuid);
                            if (tv != null) {
                                playlistFiles.add(tv.localPath.getAbsolutePath());
                            }
                        }
                        XSPFPlaylistCreator.writePlaylistFile(
                                requireContext(),
                                Uri.fromFile(xspfFile),
                                playlistFiles
                        );
                        main.post(() -> {
                            statusTv.append(asset.name + ": XSPF OK\n");
                            statusScroll.fullScroll(View.FOCUS_DOWN);
                        });
                    } else {
                        main.post(() -> {
                            statusTv.append(asset.name + ": XSPF already exists\n");
                            statusScroll.fullScroll(View.FOCUS_DOWN);
                        });
                    }

                    // mark success
                    main.post(() -> {
                        row.setBackgroundColor(colorSuccess);
                        statusTv.append(asset.name + ": SUCCESS\n");
                        statusScroll.fullScroll(View.FOCUS_DOWN);
                    });

                } catch (Exception ex) {
                    // mark failure
                    main.post(() -> {
                        row.setBackgroundColor(colorFailure);
                        statusTv.append(asset.name + ": FAILED – " + ex.getMessage() + "\n");
                        statusScroll.fullScroll(View.FOCUS_DOWN);
                    });
                }
            }

            // 4) all done → enable OK and Download button
            main.post(() -> {
                statusTv.append("\nAll playlists processed.\n");
                statusScroll.fullScroll(View.FOCUS_DOWN);
                statusDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                btnDownloadPlaylists.setEnabled(true);
            });

            exec.shutdown();
        });
    }

}

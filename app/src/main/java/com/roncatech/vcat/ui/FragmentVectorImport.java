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

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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

public class FragmentVectorImport extends Fragment implements OpenCatalogDialog.Listener {
    private final static String TAG = "FragmentTestVectors";

    private EditText etCatalogUrl;
    private ImageButton btnOpenCatalog;
    private TableLayout tableVectors;
    private ImageButton btnDownloadPlaylists;

    // Maps each catalog header row to its child vector rows
    private final Map<TableRow, List<TableRow>> catalogChildRows = new HashMap<>();
    // Maps each catalog header row to its resolved URL for downloads
    private final Map<TableRow, String> catalogResolvedUrls = new HashMap<>();

    private int colorInProgress;
    private int colorSuccess;
    private int colorFailure;

    public FragmentVectorImport() {
        // Required empty public constructor
    }

    public static FragmentVectorImport newInstance() {
        return new FragmentVectorImport();
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_vector_import, container, false);
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
        startCatalogDownload(url);

        // TODO: optionally prepopulate etCatalogUrl from saved prefs
    }

    @Override
    public void onCatalogChosen(@NonNull String catalogUrl) {

        if (catalogUrl.startsWith("http://") || catalogUrl.startsWith("https://")) {
            clearCatalogData();
            startCatalogDownload(catalogUrl);
            return;
        }

        Uri treeUri = Uri.parse(catalogUrl);
        DocumentFile tree = DocumentFile.fromTreeUri(requireContext(), treeUri);

        // 1) find all "*_playlist_catalog.json" (or whatever suffix you use)
        List<DocumentFile> candidates = new ArrayList<>();
        for (DocumentFile df : tree.listFiles()) {
            if (df.isFile()
                    && df.getName() != null
                    && df.getName().endsWith(".json")) {
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

        // 2) Load all found catalogs and display each with its own header
        clearCatalogData();
        for (DocumentFile candidate : candidates) {
            startCatalogDownload(candidate.getUri().toString());
        }
    }

    private void clearCatalogData() {
        tableVectors.removeAllViews();
        catalogChildRows.clear();
        catalogResolvedUrls.clear();
    }

    private void startCatalogDownload(String catalogUrl) {
        DownloadTestVectors.CatalogOrIndexCallback callback = new DownloadTestVectors.CatalogOrIndexCallback() {
            @Override
            public void onCatalog(TestVectorManifests.Catalog catalog, String resolvedCatalogUrl) {
                populateCatalog(catalog, resolvedCatalogUrl);
            }

            @Override
            public void onCatalogIndex(TestVectorManifests.CatalogIndex index, String resolvedIndexUrl) {
                // Sort catalogs alphabetically by name
                List<TestVectorManifests.CatalogAsset> sortedCatalogs = new ArrayList<>(index.catalogs);
                sortedCatalogs.sort((a, b) -> {
                    String nameA = a.name != null ? a.name : "";
                    String nameB = b.name != null ? b.name : "";
                    return nameA.compareToIgnoreCase(nameB);
                });

                // Load catalogs sequentially to maintain sort order
                String baseUrl = UriUtils.makeBaseUrlWithUri(resolvedIndexUrl);
                loadCatalogsSequentially(sortedCatalogs, 0, baseUrl);
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

        // Kick off the download
        if (catalogUrl.startsWith("http://") || catalogUrl.startsWith("https://")) {
            DownloadTestVectors.downloadCatalogOrIndexHttp(requireContext(), catalogUrl, callback);
        } else {
            DownloadTestVectors.downloadCatalogOrIndexFromFile(requireContext(),
                    Uri.parse(catalogUrl),
                    callback);
        }
    }

    /**
     * Loads catalogs sequentially to maintain sort order.
     */
    private void loadCatalogsSequentially(List<TestVectorManifests.CatalogAsset> catalogs, int index, String baseUrl) {
        if (index >= catalogs.size()) {
            return; // All catalogs loaded
        }

        TestVectorManifests.CatalogAsset catalogAsset = catalogs.get(index);
        String catalogAssetUrl;
        try {
            catalogAssetUrl = UriUtils.resolveUri(requireContext(), baseUrl, catalogAsset.url).toString();
        } catch (Exception e) {
            Toast.makeText(
                    requireContext(),
                    "Failed to resolve catalog URL: " + catalogAsset.name,
                    Toast.LENGTH_SHORT
            ).show();
            // Continue with next catalog
            loadCatalogsSequentially(catalogs, index + 1, baseUrl);
            return;
        }

        DownloadTestVectors.CatalogCallback callback = new DownloadTestVectors.CatalogCallback() {
            @Override
            public void onSuccess(TestVectorManifests.Catalog catalog, String resolvedCatalogUrl) {
                populateCatalog(catalog, resolvedCatalogUrl);
                // Load next catalog
                loadCatalogsSequentially(catalogs, index + 1, baseUrl);
            }

            @Override
            public void onError(String errorMessage) {
                Toast.makeText(
                        requireContext(),
                        "Failed to download catalog: " + errorMessage,
                        Toast.LENGTH_SHORT
                ).show();
                // Continue with next catalog even on error
                loadCatalogsSequentially(catalogs, index + 1, baseUrl);
            }
        };

        if (catalogAssetUrl.startsWith("http://") || catalogAssetUrl.startsWith("https://")) {
            DownloadTestVectors.downloadCatalogHttp(requireContext(), catalogAssetUrl, callback);
        } else {
            DownloadTestVectors.downloadCatalogFromFile(requireContext(),
                    Uri.parse(catalogAssetUrl),
                    callback);
        }
    }

    /**
     * Loads a single catalog (not an index) from the given URL.
     */
    private void loadSingleCatalog(String catalogUrl) {
        DownloadTestVectors.CatalogCallback callback = new DownloadTestVectors.CatalogCallback() {
            @Override
            public void onSuccess(TestVectorManifests.Catalog catalog, String resolvedCatalogUrl) {
                populateCatalog(catalog, resolvedCatalogUrl);
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

        if (catalogUrl.startsWith("http://") || catalogUrl.startsWith("https://")) {
            DownloadTestVectors.downloadCatalogHttp(requireContext(), catalogUrl, callback);
        } else {
            DownloadTestVectors.downloadCatalogFromFile(requireContext(),
                    Uri.parse(catalogUrl),
                    callback);
        }
    }

    /**
     * Populates the UI with a single catalog's header and playlists.
     */
    private void populateCatalog(TestVectorManifests.Catalog catalog, String resolvedCatalogUrl) {
        // 1) Create catalog header row
        TableRow headerRow = (TableRow) getLayoutInflater()
                .inflate(R.layout.row_catalog_header, tableVectors, false);

        CheckBox headerCb = headerRow.findViewById(R.id.cbCatalogHeader);
        TextView headerTv = headerRow.findViewById(R.id.tvCatalogName);

        String catalogName = (catalog.header != null && catalog.header.name != null)
                ? catalog.header.name
                : "Catalog";
        headerTv.setText(catalogName);
        headerCb.setChecked(false);

        tableVectors.addView(headerRow);

        // Store the resolved URL for this catalog
        catalogResolvedUrls.put(headerRow, resolvedCatalogUrl);

        // 2) Create list to track child vector rows
        List<TableRow> childRows = new ArrayList<>();

        // Flag to prevent recursive listener calls
        final boolean[] updatingFromHeader = {false};

        // Sort playlists alphabetically by name
        List<TestVectorManifests.PlaylistAsset> sortedPlaylists = new ArrayList<>(catalog.playlists);
        sortedPlaylists.sort((a, b) -> {
            String nameA = a.name != null ? a.name : "";
            String nameB = b.name != null ? b.name : "";
            return nameA.compareToIgnoreCase(nameB);
        });

        // 3) Add vector rows under this catalog header
        for (TestVectorManifests.PlaylistAsset playlist : sortedPlaylists) {
            TableRow row = (TableRow) getLayoutInflater()
                    .inflate(R.layout.row_test_vector, tableVectors, false);

            CheckBox cb = row.findViewById(R.id.cbRow);
            cb.setChecked(false);

            TextView tv = row.findViewById(R.id.tvRowText);
            tv.setText(playlist.name);

            // tag the row so you can retrieve the playlist URL later
            row.setTag(playlist);

            tableVectors.addView(row);
            childRows.add(row);
        }

        // 4) Store the header-to-children mapping
        catalogChildRows.put(headerRow, childRows);

        // 5) Set up child checkbox listeners to update header checkbox
        for (TableRow childRow : childRows) {
            CheckBox childCb = childRow.findViewById(R.id.cbRow);
            if (childCb != null) {
                childCb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (updatingFromHeader[0]) {
                        return; // Avoid recursive updates
                    }
                    // Update header checkbox based on children state
                    List<TableRow> children = catalogChildRows.get(headerRow);
                    if (children != null) {
                        boolean allChecked = true;
                        for (TableRow cr : children) {
                            CheckBox ccb = cr.findViewById(R.id.cbRow);
                            if (ccb != null && !ccb.isChecked()) {
                                allChecked = false;
                                break;
                            }
                        }
                        updatingFromHeader[0] = true;
                        headerCb.setChecked(allChecked);
                        updatingFromHeader[0] = false;
                    }
                });
            }
        }

        // 6) Set up header checkbox to toggle all child checkboxes
        headerCb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingFromHeader[0]) {
                return; // Avoid recursive updates from child checkbox changes
            }
            updatingFromHeader[0] = true;
            List<TableRow> children = catalogChildRows.get(headerRow);
            if (children != null) {
                for (TableRow childRow : children) {
                    CheckBox childCb = childRow.findViewById(R.id.cbRow);
                    if (childCb != null) {
                        childCb.setChecked(isChecked);
                    }
                }
            }
            updatingFromHeader[0] = false;
        });

        // Enable download button if we have any playlists
        btnDownloadPlaylists.setEnabled(tableVectors.getChildCount() > 0);
    }

    /**
     * Finds the catalog header row that contains the given vector row.
     * Returns null if not found.
     */
    private TableRow findCatalogHeaderForRow(TableRow vectorRow) {
        for (Map.Entry<TableRow, List<TableRow>> entry : catalogChildRows.entrySet()) {
            if (entry.getValue().contains(vectorRow)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Data class to hold a playlist asset, its row, and the resolved catalog URL.
     */
    private static class DownloadQueueItem {
        final TestVectorManifests.PlaylistAsset asset;
        final TableRow row;
        final String resolvedCatalogUrl;

        DownloadQueueItem(TestVectorManifests.PlaylistAsset asset, TableRow row, String resolvedCatalogUrl) {
            this.asset = asset;
            this.row = row;
            this.resolvedCatalogUrl = resolvedCatalogUrl;
        }
    }

    private void startBatchDownload() {
        // 1) Gather checked assets & their rows, along with their catalog's resolved URL
        List<DownloadQueueItem> queue = new ArrayList<>();
        for (int i = 0; i < tableVectors.getChildCount(); i++) {
            TableRow row = (TableRow) tableVectors.getChildAt(i);
            CheckBox cb = row.findViewById(R.id.cbRow);
            if (cb != null && cb.isChecked()) {
                TestVectorManifests.PlaylistAsset asset =
                        (TestVectorManifests.PlaylistAsset) row.getTag();
                if (asset != null) {
                    TableRow catalogHeader = findCatalogHeaderForRow(row);
                    String resolvedUrl = catalogHeader != null
                            ? catalogResolvedUrls.get(catalogHeader)
                            : null;
                    if (resolvedUrl != null) {
                        queue.add(new DownloadQueueItem(asset, row, resolvedUrl));
                    }
                }
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

            for (DownloadQueueItem item : queue) {
                TestVectorManifests.PlaylistAsset asset = item.asset;
                TableRow row = item.row;
                String baseUrl = UriUtils.makeBaseUrlWithUri(item.resolvedCatalogUrl.trim());

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

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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.roncatech.vcat.R;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.roncatech.vcat.http.HttpRequestHandler;
import com.roncatech.vcat.http.HttpServer;
import com.roncatech.vcat.models.SharedViewModel;
import com.roncatech.vcat.service.CommandReceiver;
import com.roncatech.vcat.tools.StorageManager;

import java.io.IOException;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private final static String TAG = "MainActivity";
    private static final int REQUEST_CODE_ROOT_FOLDER = 200;

    private SharedViewModel viewModel;

    private TextView curViewTitle;
    private BottomNavigationView bottomNav;
    private boolean uiLoaded = false;
    private boolean permissionPrompted = false;
    private boolean waitingForPermissionResult = false;

    private HttpServer server;
    HttpRequestHandler http_handler;
    private CommandReceiver receiver;

    private boolean hasAllPermissions() {
        Uri rootUri = viewModel.getRootUri();
        if (rootUri == null) return false;
        List<UriPermission> perms = getContentResolver().getPersistedUriPermissions();
        for (UriPermission p : perms) {
            if (p.getUri().equals(rootUri) && p.isReadPermission() && p.isWritePermission()) {
                return true;
            }
        }
        return false;
    }

    private void requestAllPermissions() {
        if (permissionPrompted) return;
        permissionPrompted = true;

        new AlertDialog.Builder(this)
            .setTitle("Storage Access Required")
            .setMessage("vcat-d needs a folder at /sdcard/vcat-d.\n\nTap OK. If the folder already exists the picker will open inside it — just tap \"Use this folder\".\n\nIf not:\n1. Tap the ⊕ New folder button\n2. Type vcat-d\n3. Tap OK, then \"Use this folder\"")
            .setPositiveButton("OK", (d, w) -> launchRootFolderPicker())
            .setCancelable(false)
            .show();
    }

    private void launchRootFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // Hint at vcat-d: if it exists the picker opens inside it (user just taps "Use this folder");
        // if not, picker opens at internal storage root where the user can create it.
        Uri hint = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:vcat-d");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint);
        startActivityForResult(intent, REQUEST_CODE_ROOT_FOLDER);
        waitingForPermissionResult = true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_ROOT_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) {
                Toast.makeText(this, "No folder selected. Exiting.", Toast.LENGTH_LONG).show();
                finishAffinity();
                return;
            }
            getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            viewModel.setRootUri(treeUri);
            waitingForPermissionResult = false;
            loadUI(null);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // need shared view before we can do anything else
        this.viewModel = new ViewModelProvider(this).get(SharedViewModel.class);

        this.viewModel.appIpAddr = HttpRequestHandler.getLocalIpAddress(this);

        this.http_handler = new HttpRequestHandler(this, this.viewModel);
        int port = this.viewModel.getHttpPort();
        try {
            this.server = new HttpServer(port, this.http_handler);
            this.server.start();
        } catch (IOException e) {
            Log.e("VCAT", String.format("VCAT Failed to start HTTP server on port %d", port), e);
        }

        IntentFilter filter = new IntentFilter(CommandReceiver.broadcastLogHttp);
        filter.addAction(CommandReceiver.broadcastLogRoot);
        receiver = new CommandReceiver(this);
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);

        if (hasAllPermissions()) {
            loadUI(savedInstanceState);
        } else {
            requestAllPermissions();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (this.server != null) {
            this.server.stop();
            Log.i("VCAT", "VCAT HTTP server stopped.");
        }

        if (this.receiver != null) {
            this.unregisterReceiver(receiver);
            Log.i("VCAT", "VCAT_CommandReceiver unregistered.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (uiLoaded) return;

        if (waitingForPermissionResult) {
            if (hasAllPermissions()) {
                loadUI(null);
                return;
            } else {
                Toast.makeText(this, "Folder access not granted. Exiting.", Toast.LENGTH_LONG).show();
                finishAffinity();
                return;
            }
        }

        if (!hasAllPermissions()) {
            requestAllPermissions();
            permissionPrompted = true;
            waitingForPermissionResult = true;
        } else {
            loadUI(null);
        }
    }

    private void loadUI(Bundle savedInstanceState) {
        if (uiLoaded) return;
        uiLoaded = true;

        Uri rootUri = viewModel.getRootUri();
        if (rootUri == null) {
            Log.e(TAG, "Root URI is null after permission granted — should not happen");
            finishAffinity();
            return;
        }

        StorageManager.init(this, rootUri);

        DocumentFile playlistFolder = StorageManager.getFolder(this, StorageManager.VCATFolder.PLAYLIST);
        if (playlistFolder == null) {
            Log.w(TAG, "Root folder missing or inaccessible — re-prompting picker.");
            viewModel.setRootUri(null);
            uiLoaded = false;
            permissionPrompted = false;
            requestAllPermissions();
            return;
        }

        this.viewModel.setFolderUri(playlistFolder.getUri());

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(ContextCompat.getColor(this, R.color.background_blue));

        setContentView(R.layout.activity_main);

        ConstraintLayout topBar = findViewById(R.id.top_bar);
        TextView curView = topBar.findViewById(R.id.toolbar_cur_view);
        TextView title   = topBar.findViewById(R.id.toolbar_title);

        curViewTitle = findViewById(R.id.toolbar_cur_view);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new FragmentMain())
                    .commit();
        }

        bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(this::onNavItemSelected);

        hideSystemNavBar();
    }

    /**
     * Hide the system navigation/button bar so it doesn't cover VCAT's own bottom
     * navigation menu. Uses sticky immersive behavior so a swipe from the edge
     * temporarily reveals the system bar, then it auto-hides again. On some devices
     * (e.g. Samsung 3-button navigation) the bar does not hide by default under
     * edge-to-edge, so this must be requested explicitly.
     */
    private void hideSystemNavBar() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.navigationBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Re-hide the nav bar after it transiently reappears (dialogs, swipes, etc.).
        if (hasFocus && uiLoaded) hideSystemNavBar();
    }

    private boolean onNavItemSelected(@NonNull MenuItem item) {
        Fragment frag;
        int id = item.getItemId();
        if (id == R.id.home_nav) {
            frag = new FragmentMain();
            curViewTitle.setText(R.string.title_home);
        } else if (id == R.id.logs_nav) {
            frag = new FragmentTestLogs();
            curViewTitle.setText(R.string.title_logs);
        } else if (id == R.id.conditions_nav) {
            frag = new FragmentTestConditions();
            curViewTitle.setText(R.string.title_conditions);
        } else if (id == R.id.vectors_nav) {
            frag = new FragmentTestVector();
            curViewTitle.setText(R.string.title_test_vectors);
        } else {
            frag = new FragmentMain();
            curViewTitle.setText(R.string.title_home);
        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, frag)
                .commit();

        return true;
    }
}

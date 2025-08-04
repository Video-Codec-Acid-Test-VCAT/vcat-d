package com.roncatech.vcat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
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

public class MainActivity extends AppCompatActivity {

    private final static String TAG = "MainActivity";

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
        return Environment.isExternalStorageManager() && Settings.System.canWrite(this);
    }

    private void requestAllPermissions() {
        if(permissionPrompted){
            return;
        }

        permissionPrompted = true;

         if (!Environment.isExternalStorageManager()) {
            Intent storageIntent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(storageIntent);
        }

        if (!Settings.System.canWrite(this)) {
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
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
            Log.e("VCAT", String.format("VCAT Failed to start HTTP server on port %d",port), e);
        }

        IntentFilter filter = new IntentFilter(CommandReceiver.broadcastLogHttp);
        receiver = new CommandReceiver(this);  // use actual port
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);

        if (hasAllPermissions()) {
            loadUI(savedInstanceState);
        } else {
            requestAllPermissions();
        }
    }

    @Override
    protected void onDestroy(){
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

        // If we're waiting for the user to act on permissions, check now
        if (waitingForPermissionResult) {
            if (hasAllPermissions()) {
                loadUI(null);
                return;
            } else {
                // Still not granted after user returned
                Toast.makeText(this, "Required permissions not granted. Exiting.", Toast.LENGTH_LONG).show();
                finishAffinity();
                return;
            }
        }

        // First entry — check if we need to prompt for permissions
        if (!hasAllPermissions()) {
            requestAllPermissions();
            permissionPrompted = true;
            waitingForPermissionResult = true;
        } else {
            loadUI(null);
        }
    }


    private void loadUI(Bundle savedInstanceState){

        if (uiLoaded) {return;}  // prevent accidental re-entry

        uiLoaded = true;

        // We're here so Permission granted — create folders on the main thread
        if (!StorageManager.createVcatFolder()) {
            Log.e(TAG, "Failed to create vcat folders.");
            Toast.makeText(this, "Storage setup failed. Exiting.", Toast.LENGTH_LONG).show();
            finishAffinity();
            return;
        }

        // set the playlist folder
        this.viewModel.setFolderUri(Uri.fromFile(StorageManager.getFolder(StorageManager.VCATFolder.PLAYLIST)));

        // Tell the window we’re going to draw the system bar backgrounds
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        // Now paint the status bar exactly your background_blue
        window.setStatusBarColor(ContextCompat.getColor(this, R.color.background_blue));

        setContentView(R.layout.activity_main);

        ConstraintLayout topBar = findViewById(R.id.top_bar);
        TextView curView       = topBar.findViewById(R.id.toolbar_cur_view);
        TextView title         = topBar.findViewById(R.id.toolbar_title);

        curViewTitle = findViewById(R.id.toolbar_cur_view);

        // Show the default tab (e.g. MainFragment)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new FragmentMain())
                    .commit();
        }

        // Bottom navigation menu
        bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(this::onNavItemSelected);
    }

    private boolean onNavItemSelected(@NonNull MenuItem item) {
        Fragment frag;
        int id = item.getItemId();
        if (id == R.id.home_nav) {
            frag = new FragmentMain();
            curViewTitle.setText(R.string.title_home);
        }else if (id == R.id.logs_nav) {
            frag = new FragmentTestLogs();
            curViewTitle.setText(R.string.title_logs);
        } else if (id == R.id.conditions_nav) {
            frag = new FragmentTestConditions();
            curViewTitle.setText(R.string.title_conditions);
        }else if(id == R.id.vectors_nav){
            frag = new FragmentTestVectors();
            curViewTitle.setText(R.string.title_test_vectors);
        } else{
            frag = new FragmentMain();
            curViewTitle.setText(R.string.title_home);
        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, frag)
                .commit();

        return true;
    }

}

package com.roncatech.vcat.tools;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.Toast;

public class Permissions {

    public static final int REQUEST_CODE_WRITE_STORAGE = 1001;
    /**
     * Request write access to external storage.
     *
     * <ul>
     *   <li>On Android 11+ (API 30+), fires the Settings screen for MANAGE_EXTERNAL_STORAGE.</li>
     *   <li>On Android 10 and below, requests WRITE_EXTERNAL_STORAGE at runtime.</li>
     * </ul>
     *
     * @param activity    The host Activity (must implement onRequestPermissionsResult).
     * @param requestCode The request code for WRITE_EXTERNAL_STORAGE permission.
     */
    public static void requestFileSystemWritePermission(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ all-files access
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            }
        } else {
            // Android 10 and below: runtime WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(
                        activity,
                        new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE },
                        REQUEST_CODE_WRITE_STORAGE
                );
            }
        }
    }

    /**
     * Launches the system settings screen to grant WRITE_SETTINGS permission.
     * <p>
     * This permission allows the app to modify system settings (e.g., screen brightness, timeout).
     * If the permission is already granted, this call is a no-op.
     *
     * @param activity The Activity context used to start the settings Intent and show a Toast.
     */
    public static void requestWriteSettingsPermission(Activity activity) {
        // Only prompt if permission not yet granted
        if (!Settings.System.canWrite(activity)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            Toast.makeText(
                    activity,
                    "Please grant permission to modify system settings.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}

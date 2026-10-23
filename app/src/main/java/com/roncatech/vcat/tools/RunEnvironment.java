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

package com.roncatech.vcat.tools;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * Ambient conditions that invalidate a battery comparison if they differ between runs.
 *
 * <p><b>Why this exists.</b> A run with radios up, the charger attached, or power-save engaged
 * produces a log indistinguishable from a clean one. That cost real time: a 20 h baseline was
 * compared against an 8 h run for days before anyone established that the two had different radio
 * state, because nothing recorded it. None of these are device capabilities (which belong in
 * {@link DeviceInfo}) and none are chosen test conditions (which belong in
 * {@code RunConfig}) — they are the state of the world when the run started.
 *
 * <p>Captured once at header-write time. These can change mid-run — a radio waking, power-save
 * engaging as the battery falls — so the header proves the *starting* state, not the whole run.
 * Catching drift would need a per-row column and therefore a CSV schema change.
 *
 * <p>All reads use permissions already declared ({@code ACCESS_NETWORK_STATE},
 * {@code ACCESS_WIFI_STATE}) or none at all, so nothing prompts the user.
 */
public final class RunEnvironment {
    private static final String TAG = "RunEnvironment";

    @SerializedName("airplane_mode")
    public final boolean airplaneMode;

    @SerializedName("wifi_enabled")
    public final boolean wifiEnabled;

    @SerializedName("wifi_connected")
    public final boolean wifiConnected;

    @SerializedName("cellular_connected")
    public final boolean cellularConnected;

    /** True if a charger is attached — on its own enough to make a battery run meaningless. */
    @SerializedName("charging")
    public final boolean charging;

    @SerializedName("power_save_mode")
    public final boolean powerSaveMode;

    /**
     * True if the system is in automatic-brightness mode.
     *
     * <p>VCAT forces its own window brightness, which is supposed to override the system setting
     * including auto-brightness. Recorded because that override is not observable from the app: if
     * a platform ever stops honouring it, the run silently uses system brightness instead, and the
     * display is the largest single power term in a run. Auto mode plus a high
     * {@link #systemBrightness} is the signature of that failure.
     */
    @SerializedName("auto_brightness")
    public final boolean autoBrightness;

    /**
     * Raw {@code Settings.System.SCREEN_BRIGHTNESS} (nominally 0–255), or -1 if unreadable.
     *
     * <p>Advisory only. Newer Android tracks brightness on an internal float scale, so this legacy
     * value can be stale or unrelated to what the panel is doing — which is why it is recorded
     * beside {@link #autoBrightness} rather than trusted on its own.
     */
    @SerializedName("system_brightness")
    public final int systemBrightness;

    public RunEnvironment(boolean airplaneMode,
                          boolean wifiEnabled,
                          boolean wifiConnected,
                          boolean cellularConnected,
                          boolean charging,
                          boolean powerSaveMode,
                          boolean autoBrightness,
                          int systemBrightness) {
        this.airplaneMode = airplaneMode;
        this.wifiEnabled = wifiEnabled;
        this.wifiConnected = wifiConnected;
        this.cellularConnected = cellularConnected;
        this.charging = charging;
        this.powerSaveMode = powerSaveMode;
        this.autoBrightness = autoBrightness;
        this.systemBrightness = systemBrightness;
    }

    /** Reads the current state. Each probe is independently guarded — one failure must not lose the rest. */
    public static RunEnvironment capture(Context ctx) {
        Context app = ctx.getApplicationContext();
        return new RunEnvironment(
                readAirplaneMode(app),
                readWifiEnabled(app),
                hasTransport(app, NetworkCapabilities.TRANSPORT_WIFI),
                hasTransport(app, NetworkCapabilities.TRANSPORT_CELLULAR),
                readCharging(app),
                readPowerSaveMode(app),
                readAutoBrightness(app),
                readSystemBrightness(app));
    }

    private static boolean readAirplaneMode(Context ctx) {
        try {
            return Settings.Global.getInt(
                    ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (Exception e) {
            Log.w(TAG, "airplane mode unreadable: " + e.getLocalizedMessage());
            return false;
        }
    }

    private static boolean readWifiEnabled(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            Log.w(TAG, "wifi state unreadable: " + e.getLocalizedMessage());
            return false;
        }
    }

    /**
     * Whether the <em>active</em> network uses the given transport. Reports actual connectivity
     * rather than whether a radio is merely enabled: an enabled-but-unassociated WiFi radio costs
     * far less than an associated one.
     */
    private static boolean hasTransport(Context ctx, int transport) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(transport);
        } catch (Exception e) {
            Log.w(TAG, "connectivity unreadable: " + e.getLocalizedMessage());
            return false;
        }
    }

    private static boolean readCharging(Context ctx) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            return bm != null && bm.isCharging();
        } catch (Exception e) {
            Log.w(TAG, "charging state unreadable: " + e.getLocalizedMessage());
            return false;
        }
    }

    private static boolean readAutoBrightness(Context ctx) {
        try {
            return Settings.System.getInt(
                    ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        } catch (Exception e) {
            Log.w(TAG, "brightness mode unreadable: " + e.getLocalizedMessage());
            return false;
        }
    }

    private static int readSystemBrightness(Context ctx) {
        try {
            return Settings.System.getInt(
                    ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        } catch (Exception e) {
            Log.w(TAG, "system brightness unreadable: " + e.getLocalizedMessage());
            return -1;
        }
    }

    private static boolean readPowerSaveMode(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isPowerSaveMode();
        } catch (Exception e) {
            Log.w(TAG, "power save mode unreadable: " + e.getLocalizedMessage());
            return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RunEnvironment)) return false;
        RunEnvironment that = (RunEnvironment) o;
        return this.airplaneMode == that.airplaneMode
                && this.wifiEnabled == that.wifiEnabled
                && this.wifiConnected == that.wifiConnected
                && this.cellularConnected == that.cellularConnected
                && this.charging == that.charging
                && this.powerSaveMode == that.powerSaveMode
                && this.autoBrightness == that.autoBrightness
                && this.systemBrightness == that.systemBrightness;
    }

    @Override
    public int hashCode() {
        return Objects.hash(airplaneMode, wifiEnabled, wifiConnected, cellularConnected,
                charging, powerSaveMode, autoBrightness, systemBrightness);
    }
}

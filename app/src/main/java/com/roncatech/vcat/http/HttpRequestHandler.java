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

package com.roncatech.vcat.http;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.roncatech.vcat.models.SharedViewModel;
import com.roncatech.vcat.service.PlayerCommandBus;
import com.roncatech.vcat.tools.DeviceInfo;

import java.util.Locale;

public class HttpRequestHandler implements HttpServer.VCAT_ControlHandler{

    private final static String TAG = "HttpRequestHandler";
    private final SharedViewModel viewModel;
    private final Context context;

    public SharedViewModel getViewModel(){return this.viewModel;}

    public static String getLocalIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ipInt = wifiInfo.getIpAddress();
            return String.format(
                    Locale.getDefault(),
                    "%d.%d.%d.%d",
                    (ipInt & 0xff),
                    (ipInt >> 8 & 0xff),
                    (ipInt >> 16 & 0xff),
                    (ipInt >> 24 & 0xff)
            );
        }
        return "";
    }


    public HttpRequestHandler(Context context, SharedViewModel viewModel){
        this.context = context;
        this.viewModel = viewModel;
    }


    @Override
    public void onPlayPause() {
        // Logic to play/pause VCAT
        Log.d(TAG, "Play/Pause triggered");
        PlayerCommandBus.get().dispatchPlayPause();
    }

    @Override
    public void onStop() {
        Log.d(TAG, "Stop triggered");
        PlayerCommandBus.get().dispatchStop();
    }

    @Override
    public void onShowVideoStats() {
        Log.d(TAG, "Show Video Stats triggered");
        PlayerCommandBus.get().dispatchToggleVideoInfo();
    }


    @Override
    public String onGetDeviceInfo(){
        DeviceInfo deviceInfo = new DeviceInfo(this.context);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(deviceInfo);
    }

    @Override
    public String onGetRunConfig(){
        return this.viewModel.getRunConfig().toJson();
    }
    @Override
    public String onGetTestStatus(){
        String ret = this.viewModel.curTestDetails.toJson();
        return ret;
    }

}


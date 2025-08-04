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


package com.roncatech.vcat.http;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.roncatech.vcat.models.SharedViewModel;

import java.util.Locale;

public class HttpRequestHandler implements VCAT_HttpServer.VCAT_ControlHandler{

    static class tempParent{

    }
    private final static String TAG = "HttpRequestHandler";
    private final tempParent parent;
    private final SharedViewModel viewModel;

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


    public HttpRequestHandler(tempParent parent){
        this.parent = parent;
        this.viewModel = null; //new ViewModelProvider(parent).get(VCatSharedViewModel.class);
    }

    /*
    @Override
    public void onPlayPause() {
        // Logic to play/pause VLC
        Log.d(TAG, "Play/Pause triggered");
        Intent intent = new Intent("org.vlc.ADB_PLAY_PAUSE");
        intent.setPackage("org.videolan.vlc");
        this.parent.getApplicationContext().sendBroadcast(intent);
    }

    @Override
    public void onStop() {
        Log.d(TAG, "Stop triggered");

        CancelBenchmarkReceiver rcv = new CancelBenchmarkReceiver();
        Intent cancelIntent = new Intent(this.parent, CancelBenchmarkReceiver.class);
        rcv.onReceive(this.parent, cancelIntent);
        viewModel.curTestDetails.reset();
    }

    @Override
    public void onShowVideoStats() {
        Log.d(TAG, "Show Video Stats triggered");
        Intent intent = new Intent("org.vlc.ADB_VIDEO_STATS");
        intent.setPackage("org.videolan.vlc");
        this.parent.getApplicationContext().sendBroadcast(intent);
    }


    @Override
    public String onGetDeviceInfo(){
        Map<String, Object> deviceInfo = DeviceInfo.getDeviceInfo(parent.getApplicationContext());

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(deviceInfo);
    }

     */

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


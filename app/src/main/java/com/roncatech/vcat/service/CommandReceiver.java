package com.roncatech.vcat.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.roncatech.vcat.http.HttpServer;
import com.roncatech.vcat.models.SharedViewModel;

public class CommandReceiver extends BroadcastReceiver {

    private final int httpPort = HttpServer.defPort;
    private final SharedViewModel viewModel;

    public final static String broadcastLogHttp = "com.roncatech.vcat.ADB_LOG_HTTP_INFO";

    public CommandReceiver(FragmentActivity activity) {
        this.viewModel = new ViewModelProvider(activity).get(SharedViewModel.class);;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (broadcastLogHttp.equals(intent.getAction())) {
            HttpServer.logStatus(this.viewModel.appIpAddr, this.viewModel.getHttpPort());
        }
    }
}



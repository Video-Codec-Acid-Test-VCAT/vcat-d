package com.roncatech.vcat.service;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

public class PlayerCommandBus {
    public interface Listener{
        void onStopTest();
        void onToggleVideoInfo();
        void onPlayPause();
    }
    private static final PlayerCommandBus INSTANCE = new PlayerCommandBus();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private @Nullable Listener listener;

    private PlayerCommandBus(){ }

    public static PlayerCommandBus get(){
        return INSTANCE;
    }

    public void setListener(Listener listener){
        this.listener = listener;
    }

    public void dispatchStop(){
        if(listener != null){
            mainHandler.post(()->listener.onStopTest());
        }
    }

    public void dispatchPlayPause(){
        if(listener != null){
            mainHandler.post(()->listener.onPlayPause());
        }
    }

    public void dispatchToggleVideoInfo(){
        if(listener != null){
            mainHandler.post(()->listener.onToggleVideoInfo());
        }
    }

}

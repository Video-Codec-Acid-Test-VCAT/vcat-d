package com.roncatech.vcat.http;

import android.util.Log;

import com.roncatech.vcat.models.SharedViewModel;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {

    public final static int defPort = 53000;

    public interface VCAT_ControlHandler {
        void onPlayPause();
        void onStop();
        void onShowVideoStats();
        String onGetDeviceInfo();
        String onGetRunConfig();
        SharedViewModel getViewModel();
        String onGetTestStatus();
    }

    private final VCAT_ControlHandler handler;

    public HttpServer(VCAT_ControlHandler handler) {
        this(defPort, handler);
    }
    public HttpServer(int port, VCAT_ControlHandler handler)  {
        super(port);
        this.handler = handler;

    }

    public static void logStatus(String ipAddr, int port){
        Log.i("VCAT", String.format("VCAT started HTTP server @ %s:%d", ipAddr, port));
    }

    @Override
    public void start() throws IOException{
        try{
            super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            logStatus(this.handler.getViewModel().appIpAddr, super.getListeningPort());
        } catch (IOException e) {
            Log.e("VCAT", String.format("VCAT Failed to start HTTP server on port %d",super.getListeningPort()), e);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Log.i("VCAT", "Request received: " + session.getUri());

        if (Method.GET.equals(session.getMethod())) {
            switch(uri){
                case "/api/status":
                    return newFixedLengthResponse("OK");
                case "/api/device_info":
                    String jsonDevInfo = handler.onGetDeviceInfo();
                    return newFixedLengthResponse(Response.Status.OK, "application/json", jsonDevInfo);

                case "/api/run_config":
                    return newFixedLengthResponse(Response.Status.OK, "application/json", this.handler.onGetRunConfig());
                case "/api/test/status":
                    return newFixedLengthResponse(Response.Status.OK, "application/json", this.handler.onGetTestStatus());
                case "/api/control/playpause":
                    handler.onPlayPause();
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "OK");
                case "/api/control/stop":
                    handler.onStop();
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "OK");
                case "/api/control/show_stats":
                    handler.onShowVideoStats();
                    return newFixedLengthResponse(Response.Status.OK, "application/json", "OK");

                default:
                    return newFixedLengthResponse("404 Not Found");
            }

        }
        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Only POST allowed here");
    }
}



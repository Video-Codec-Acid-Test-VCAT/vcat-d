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



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

package com.roncatech.vcat.models;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.roncatech.vcat.http.HttpServer;

public class SharedViewModel extends AndroidViewModel {
    public static final String LOG_FOLDER = "/vcat/test_results";

    private MutableLiveData<Uri> folderUri = new MutableLiveData<>(null);
    private RunConfig runConfig;
    private final MutableLiveData<Integer> httpPortLive = new MutableLiveData<>(HttpServer.defPort);
    public String appIpAddr = "";
    public TestStatus curTestDetails = TestStatus.emptyStatus;
    private final SharedPreferences prefs;

    public ResumeInfo resumeInfo = ResumeInfo.empty;
    public long logOffset = 0;
    public boolean markRestart = false;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_HTTP_PORT = "http_port";
    private static final String KEY_RUN_CONFIG = "vcat_run_config";

    public SharedViewModel(@NonNull Application application){
        super(application);
        prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadFolderUri(); // Load saved Uri when ViewModel is created
        loadRunConfig();
        loadHttpPort();
    }

    public Uri getFolderUri() {
        return folderUri.getValue();
    }

    public MutableLiveData<Uri> getObservableFolderUri(){return this.folderUri;}

    public void setFolderUri(Uri uri) {
        if (uri != null) {
            folderUri.setValue(uri);
        } else {
            folderUri.setValue(null);
        }
    }

    private void saveFolderUri(Uri uri) {
        prefs.edit().putString(KEY_FOLDER_URI, (uri != null) ? uri.toString() : null).apply();
    }

    private void loadFolderUri() {
        String uriString = prefs.getString(KEY_FOLDER_URI, null);
        folderUri.setValue((uriString != null) ? Uri.parse(uriString) : null);
    }

    public void setHttpPort(int port){
        this.httpPortLive.postValue(port);
        saveHttpPort(port);
    }

    public int getHttpPort(){
        Integer port = this.httpPortLive.getValue();
        return (port != null) ? port : -1;
    }

    private void loadHttpPort() {
        int httpPort = prefs.getInt(KEY_HTTP_PORT, HttpServer.defPort);
        this.httpPortLive.setValue(httpPort);
    }

    private void saveHttpPort(int port) {
        prefs.edit().putInt(KEY_HTTP_PORT, port).apply();
    }

    public RunConfig getRunConfig() {
        return runConfig;
    }

    public void setRunConfig(RunConfig config) {
        this.runConfig = config;
        saveRunConfig(config);
    }

    private void saveRunConfig(RunConfig config) {
        String json = (config != null) ? new Gson().toJson(config) : null;
        prefs.edit().putString(KEY_RUN_CONFIG, json).apply();
    }

    private void loadRunConfig() {
        String json = prefs.getString(KEY_RUN_CONFIG, null);
        this.runConfig = (json != null) ? RunConfig.fromJson(json) : new RunConfig();
    }
}



/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.roncatech.vcat;

import android.app.Application;

import com.roncatech.vcat.video.DecoderPluginLoader;

public final class VcatApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DecoderPluginLoader.loadAll(this);
    }
}

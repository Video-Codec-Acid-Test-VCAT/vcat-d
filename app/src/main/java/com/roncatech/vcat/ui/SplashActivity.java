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

package com.roncatech.vcat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.roncatech.vcat.legal.TermsPayload;
import com.roncatech.vcat.legal.TermsRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.roncatech.vcat.tools.SplashPicker;
import com.roncatech.vcat.R;

public class SplashActivity extends AppCompatActivity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1) Show the splash art immediately
        setContentView(R.layout.activity_splash);
        ImageView img = findViewById(R.id.splashImage);
        int resId = SplashPicker.bestPortraitResId(this); // considers 360x640, 540x960, 720x1280, 1080x1920, 1440x2560
        if (resId != 0) img.setImageResource(resId);

        // 2) Do your terms check in background as you already had
        exec.submit(() -> {
            TermsRepository repo = new TermsRepository(SplashActivity.this);
            TermsPayload latest = repo.fetchLatestOrFallback();
            int accepted = repo.getAcceptedVersion();
            boolean needs = TermsRepository.needsAcceptance(latest.version, accepted);

            runOnUiThread(() -> {
                if (needs) {
                    Intent i = new Intent(SplashActivity.this, LicenseActivity.class);
                    i.putExtra(LicenseActivity.EXTRA_REQUIRE_ACCEPT, true);
                    startActivity(i);
                } else {
                    startActivity(new Intent(SplashActivity.this, MainActivity.class));
                }
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }
}
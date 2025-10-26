package com.roncatech.vcat.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.roncatech.vcat.legal.TermsPayload;
import com.roncatech.vcat.legal.TermsRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SplashActivity extends AppCompatActivity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        exec.submit(() -> {
            TermsRepository repo = new TermsRepository(SplashActivity.this);
            TermsPayload latest = repo.fetchLatestOrFallback();
            int accepted = repo.getAcceptedVersion();

            boolean needs = TermsRepository.needsAcceptance(latest.version, accepted);
            runOnUiThread(() -> {
                if (needs) {
                    // Route to LicenseActivity which hosts your LicenseFragment
                    Intent i = new Intent(SplashActivity.this, LicenseActivity.class);
                    i.putExtra(LicenseActivity.EXTRA_REQUIRE_ACCEPT, true);
                    // Optionally pass the latest version via extras if you like
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

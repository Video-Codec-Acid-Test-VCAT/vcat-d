package com.roncatech.vcat.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.roncatech.vcat.R;

public class LicenseActivity extends AppCompatActivity {

    public static final String EXTRA_REQUIRE_ACCEPT = "require_accept";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_license_host); // FrameLayout @id/license_container

        if (savedInstanceState == null) {
            // Read the flag from the Activity intent (Splash sets this)
            boolean requireAccept = getIntent().getBooleanExtra(EXTRA_REQUIRE_ACCEPT, false);

            // Pass it to the Fragment via arguments
            FragmentLicense frag = new FragmentLicense();
            Bundle args = new Bundle();
            args.putBoolean(EXTRA_REQUIRE_ACCEPT, requireAccept);
            frag.setArguments(args);

            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.license_container, frag, "license")
                    .commit();
        }
    }
}

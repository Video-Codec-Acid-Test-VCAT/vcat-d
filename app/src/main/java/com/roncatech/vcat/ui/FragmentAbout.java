package com.roncatech.vcat.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.roncatech.vcat.BuildConfig;
import com.roncatech.vcat.R;

public class FragmentAbout extends Fragment {

    public FragmentAbout() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.about_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Set build info dynamically
        TextView buildInfoText = view.findViewById(R.id.aboutBuildInfo);
        String buildInfo = String.format("Build: %s • %s", BuildConfig.VERSION_NAME, BuildConfig.BUILD_TIME);
        buildInfoText.setText(buildInfo);

    }
}


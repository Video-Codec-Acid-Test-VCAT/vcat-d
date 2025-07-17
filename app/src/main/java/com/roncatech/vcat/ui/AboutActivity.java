package com.roncatech.vcat.ui;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import androidx.appcompat.widget.Toolbar;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.roncatech.vcat.R;

public class AboutActivity extends AppCompatActivity {

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private AboutPagerAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        MaterialToolbar toolbar = findViewById(R.id.aboutToolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tabLayout = findViewById(R.id.aboutTabs);
        viewPager = findViewById(R.id.aboutViewPager);

        adapter = new AboutPagerAdapter(this);
        viewPager.setAdapter(adapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            View customTab = getLayoutInflater().inflate(R.layout.custom_tab_layout, null);

            ImageView icon = customTab.findViewById(R.id.tabIcon);
            TextView label = customTab.findViewById(R.id.tabLabel);

            if (position == 0) {
                icon.setImageResource(R.drawable.ic_about);
                label.setText("About");
            } else {
                icon.setImageResource(R.drawable.ic_license);
                label.setText("License");
            }

            tab.setCustomView(customTab);
        }).attach();

    }

}

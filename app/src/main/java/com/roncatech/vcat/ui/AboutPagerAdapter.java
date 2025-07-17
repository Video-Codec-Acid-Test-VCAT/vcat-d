package com.roncatech.vcat.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class AboutPagerAdapter extends FragmentStateAdapter {

    public AboutPagerAdapter(@NonNull FragmentActivity fa) {
        super(fa);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            return new FragmentAbout();
        } else {
            return new FragmentLicense();
        }
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}


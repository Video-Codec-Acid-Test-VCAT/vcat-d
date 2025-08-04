package com.roncatech.vcat.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.roncatech.vcat.R;

import java.util.Arrays;
import java.util.List;

public class FragmentTestVector extends Fragment {
    private static final List<String> TAB_TITLES =
            Arrays.asList("Import", "Export");

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_test_vector, container, false);
    }

    @Override
    public void onViewCreated(
            @NonNull View view,
            @Nullable Bundle savedInstanceState
    ) {
        super.onViewCreated(view, savedInstanceState);

        androidx.viewpager2.widget.ViewPager2 viewPager =
                view.findViewById(R.id.viewPager);
        TabLayout tabs = view.findViewById(R.id.tabs);

        // Adapter: create one fragment per tab
        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @Override
            public int getItemCount() {
                return TAB_TITLES.size();
            }

            @NonNull
            @Override
            public Fragment createFragment(int position) {
                switch (position) {
                    case 0:
                        return new FragmentVectorImport(); // your import/download UI
                    case 1:
                        return new FragmentVectorExport();
                    default:
                        return new FragmentVectorImport();
                }
            }
        });

        // Link the TabLayout and the ViewPager2
        new TabLayoutMediator(tabs, viewPager,
                (tab, position) -> tab.setText(TAB_TITLES.get(position))
        ).attach();
    }
}

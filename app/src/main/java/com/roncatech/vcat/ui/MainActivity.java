package com.roncatech.vcat.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.roncatech.vcat.R;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private TextView curViewTitle;
    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tell the window we’re going to draw the system bar backgrounds
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        // Now paint the status bar exactly your background_blue
        window.setStatusBarColor(ContextCompat.getColor(this, R.color.background_blue));

        setContentView(R.layout.activity_main);

        ConstraintLayout topBar = findViewById(R.id.top_bar);
        TextView curView       = topBar.findViewById(R.id.toolbar_cur_view);
        TextView title         = topBar.findViewById(R.id.toolbar_title);

        curViewTitle = findViewById(R.id.toolbar_cur_view);

        // Show the default tab (e.g. MainFragment)
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new FragmentMain())
                    .commit();
        }

        // Bottom navigation menu
        bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(this::onNavItemSelected);
    }

    private boolean onNavItemSelected(@NonNull MenuItem item) {
        Fragment frag;
        int id = item.getItemId();
        if (id == R.id.home_nav) {
            frag = new FragmentMain();
            curViewTitle.setText(R.string.title_home);
        }else if (id == R.id.logs_nav) {
            frag = new FragmentTestLogs();
            curViewTitle.setText(R.string.title_logs);
        } else if (id == R.id.conditions_nav) {
            frag = new FragmentTestConditions();
            curViewTitle.setText(R.string.title_conditions);
        } else{
            frag = new FragmentMain();
            curViewTitle.setText(R.string.title_home);
        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, frag)
                .commit();

        return true;
    }

}

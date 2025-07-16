package com.roncatech.vcat.ui;

import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.roncatech.vcat.models.TestResultsItem;
import com.roncatech.vcat.models.SharedViewModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.roncatech.vcat.R;
import java.util.Arrays;

public class FragmentTestLogs extends Fragment {

    private static final String TAG = "TestResultsFragment";

    public boolean isEmpty(){
        return this.adapter.getItemCount() > 0;
    }
    private TestResultsAdapter adapter;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(
                R.layout.fragment_test_results, container, false);

        RecyclerView rv = view.findViewById(R.id.rvTestResults);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new TestResultsAdapter();
        rv.setAdapter(adapter);

        adapter.setOnResultClickListener(filePath -> {
            // show your dialog
            TestResultsDetailDialog
                    .newInstance(filePath)
                    .show(getParentFragmentManager(), "test_result_detail");
        });

        loadTestResults();
        return view;
    }

    private void loadTestResults() {
        File logsDir = new File(Environment.getExternalStorageDirectory(), SharedViewModel.LOG_FOLDER);
        File[] csvs = logsDir.listFiles((dir, name) ->
                name.startsWith("logs_") && name.endsWith(".csv")
        );

        Log.d(TAG, "logsDir path: " + logsDir.getAbsolutePath()
                + " | exists: " + logsDir.exists()
                + " | isDirectory: " + logsDir.isDirectory()
                + " | canRead: " + logsDir.canRead());

        String[] rawNames = logsDir.list();
        if (rawNames == null) {
            Log.e(TAG, "Raw list() returned null — likely a permissions/scoped‐storage block");
        } else {
            Log.d(TAG, "Raw directory entries: " + Arrays.toString(rawNames));
        }

        String[] names = logsDir.list();
        if (names == null) {
            Log.e(TAG, "list() returned null — likely a permissions or I/O error");
        } else {
            Log.d(TAG, "list() returned " + names.length + " entries: " + Arrays.toString(names));
        }

        List<TestResultsItem> results = new ArrayList<>();
        if (csvs != null) {
            for (File f : csvs) {
                long timeStampMS = TestResultsItem.getTimeStamp(f.getAbsolutePath());

                // if timestampMillis < 0, then the file is malformed, and we will ignore it
                if(timeStampMS > 0) {
                    results.add(new TestResultsItem(f.getAbsolutePath(), timeStampMS));
                } else {
                    Log.e(TAG, "Invalid log file: " + f.getAbsolutePath());
                }
            }
            Collections.sort(results, (a, b) ->
                    Long.compare(b.getTimestampMillis(), a.getTimestampMillis())
            );
        }
        adapter.setResults(results);
    }
}
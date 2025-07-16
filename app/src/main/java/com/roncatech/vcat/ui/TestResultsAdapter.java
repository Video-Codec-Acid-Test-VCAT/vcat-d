package com.roncatech.vcat.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.roncatech.vcat.models.TestResultsItem;

import java.util.ArrayList;
import java.util.List;
import com.roncatech.vcat.R;


public class TestResultsAdapter
        extends RecyclerView.Adapter<TestResultsAdapter.ViewHolder> {

    public interface OnResultClickListener {
        void onResultClick(String filePath);
    }

    private final List<TestResultsItem> items = new ArrayList<>();
    private OnResultClickListener listener;

    public void setOnResultClickListener(OnResultClickListener listener) {
        this.listener = listener;
    }

    public void setResults(List<TestResultsItem> results) {
        items.clear();
        items.addAll(results);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.test_results_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TestResultsItem tr = items.get(position);
        holder.tvTimestamp.setText(tr.getDisplayTime());
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onResultClick(tr.getFilePath());
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTimestamp;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
        }
    }
}

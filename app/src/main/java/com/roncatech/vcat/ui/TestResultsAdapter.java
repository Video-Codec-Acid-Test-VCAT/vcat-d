/*
 * vcat-d (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 vcat-d authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of vcat-d.
 *
 * vcat-d is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * vcat-d is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with vcat-d. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All vcat-d artwork is owned exclusively by RoncaTech LLC. Use of vcat-d logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting vcat-d itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.roncatech.vcat.models.TestResultsItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.roncatech.vcat.R;


public class TestResultsAdapter
        extends RecyclerView.Adapter<TestResultsAdapter.ViewHolder> {

    public interface OnResultClickListener {
        void onResultClick(View view, String filePath);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount);
    }

    private final List<TestResultsItem> items = new ArrayList<>();

    /**
     * Selection is keyed by the log's document URI rather than its display name: two logs can
     * render the same timestamp string, and the URI is also what a delete needs.
     */
    private final Set<String> selected = new HashSet<>();
    private boolean editMode = false;

    private OnResultClickListener listener;
    private OnSelectionChangedListener selectionChangedListener;

    public void setOnResultClickListener(OnResultClickListener listener) {
        this.listener = listener;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    public void setResults(List<TestResultsItem> results) {
        items.clear();
        items.addAll(results);
        // Drop selections whose log no longer exists, so a refresh after an external delete
        // cannot leave the trashcan enabled for a file that is already gone.
        Set<String> present = new HashSet<>();
        for (TestResultsItem item : results) present.add(item.getFilePath());
        selected.retainAll(present);
        notifySelectionChanged();
        notifyDataSetChanged();
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        if (!editMode) selected.clear();
        notifySelectionChanged();
        notifyDataSetChanged();
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void selectAll() {
        selected.clear();
        for (TestResultsItem item : items) selected.add(item.getFilePath());
        notifySelectionChanged();
        notifyDataSetChanged();
    }

    public void unselectAll() {
        selected.clear();
        notifySelectionChanged();
        notifyDataSetChanged();
    }

    public boolean areAllSelected() {
        return !items.isEmpty() && selected.size() == items.size();
    }

    /** Document URIs of the currently selected logs. */
    public List<String> getSelectedFiles() {
        return new ArrayList<>(selected);
    }

    private void notifySelectionChanged() {
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selected.size());
        }
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
        String filePath = tr.getFilePath();

        // Date / duration / codec, falling back to the file name until the summary backfills.
        holder.tvTimestamp.setText(tr.getDisplayLabel());
        holder.tvSize.setText(tr.getDisplaySubtitle());

        if (editMode) {
            holder.cbSelected.setVisibility(View.VISIBLE);
            holder.cbSelected.setChecked(selected.contains(filePath));
            holder.itemView.setOnClickListener(v -> {
                if (!selected.remove(filePath)) selected.add(filePath);
                holder.cbSelected.setChecked(selected.contains(filePath));
                notifySelectionChanged();
            });
        } else {
            holder.cbSelected.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onResultClick(v, filePath);
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final CheckBox cbSelected;
        final TextView tvTimestamp;
        final TextView tvSize;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cbSelected  = itemView.findViewById(R.id.cbSelected);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvSize      = itemView.findViewById(R.id.tvSize);
        }
    }
}

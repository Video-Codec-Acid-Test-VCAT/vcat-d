package com.roncatech.vcat.ui;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.roncatech.vcat.R;

import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {

    private List<String> playlistEntries;
    private OnEntryDeletedListener deleteListener;

    public interface OnEntryDeletedListener {
        void onEntryDeleted(int position);
    }

    public PlaylistAdapter(List<String> playlistEntries, OnEntryDeletedListener deleteListener) {
        this.playlistEntries = playlistEntries;
        this.deleteListener = deleteListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.playlist_entries, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.trackPath.setText(playlistEntries.get(position));
        holder.deleteButton.setOnClickListener(v -> deleteListener.onEntryDeleted(position));
    }

    @Override
    public int getItemCount() {
        return playlistEntries.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView trackPath;
        ImageButton deleteButton;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            trackPath = itemView.findViewById(R.id.playlistEntryPath);
            deleteButton = itemView.findViewById(R.id.deleteEntryButton);
        }
    }
}


package com.roncatech.vcat.ui;


import android.net.Uri;

public interface PlaylistUpdates {
    public void onPlaylistAdded(Uri playlistUri);
    public void onPlaylistDeleted(Uri playlistUri);
}

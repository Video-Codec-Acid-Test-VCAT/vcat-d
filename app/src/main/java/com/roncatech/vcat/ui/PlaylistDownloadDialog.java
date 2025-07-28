package com.roncatech.vcat.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.roncatech.vcat.R;
import com.roncatech.vcat.models.TestVectorManifests;
import com.roncatech.vcat.models.TestVectorMediaAsset;
import com.roncatech.vcat.test_vectors.DownloadTestVectors;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

public class PlaylistDownloadDialog extends DialogFragment {

    public interface IPlaylistDownloadListener {
        void onDownloadComplete(TestVectorManifests.PlaylistManifest manifest);
        void onDownloadError(String error);
    }
    private static final String ARG_NAME     = "arg_name";
    private static final String ARG_URL      = "arg_url";
    private static final String ARG_CHECKSUM = "arg_checksum";
    private static final String ARG_UUID     = "arg_uuid";

    private TextView tvStatus;
    private Button   btnCancel;
    private boolean  isDone = false;

    private Future<?> downloadFuture;

    private Map<UUID, TestVectorMediaAsset> videoAssetTable;

    private IPlaylistDownloadListener listener;

    public static PlaylistDownloadDialog newInstance(TestVectorManifests.PlaylistAsset asset,
                                                     Map<UUID, TestVectorMediaAsset> videoAssetTable) {
        Bundle args = new Bundle();
        args.putString(ARG_NAME,     asset.name);
        args.putString(ARG_URL,      asset.url);
        args.putString(ARG_CHECKSUM, asset.checksum);
        args.putString(ARG_UUID,     asset.uuid.toString());
        PlaylistDownloadDialog dlg = new PlaylistDownloadDialog();
        dlg.setArguments(args);
        dlg.videoAssetTable = videoAssetTable;
        return dlg;
    }

    public void setListener(IPlaylistDownloadListener listener){
        this.listener = listener;
    }

    @NonNull @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        Dialog dialog = new Dialog(ctx);
        dialog.setContentView(R.layout.dialog_playlist_download);
        dialog.setCancelable(false);

        // title + views
        TextView tvTitle = dialog.findViewById(R.id.tvDialogTitle);
        tvStatus         = dialog.findViewById(R.id.tvStatus);
        btnCancel        = dialog.findViewById(R.id.btnCancel);

        // set playlist name in title
        String name = getArguments().getString(ARG_NAME);
        tvTitle.setText("Downloading: " + name);

        btnCancel.setOnClickListener(v -> {
            // If it’s still running, cancel it
            if (downloadFuture != null && !downloadFuture.isDone()) {
                downloadFuture.cancel(true);
                appendStatus("Cancelling…\n");
            } else {
                dismiss();
            }
        });

        // kick off the async download
        startDownload();

        return dialog;
    }

    private void startDownload() {
        // rebuild the asset from args
        Bundle a = getArguments();
        TestVectorManifests.PlaylistAsset asset =
                new TestVectorManifests.PlaylistAsset(
                        a.getString(ARG_NAME),
                        a.getString(ARG_URL),
                        a.getString(ARG_CHECKSUM),
                        0L,
                        UUID.fromString(a.getString(ARG_UUID)),
                        ""
                );

        downloadFuture = DownloadTestVectors.downloadPlaylist(
                requireContext(),
                asset,
                this.videoAssetTable,
                new DownloadTestVectors.PlaylistCallback() {
                    @Override
                    public void onStatusUpdate(String statusMessage) {
                        appendStatus(statusMessage + "\n");
                    }

                    @Override
                    public void onSuccess(TestVectorManifests.PlaylistManifest manifest) {
                        appendStatus("Playlist complete: " + manifest.header.name + "\n");
                        if(PlaylistDownloadDialog.this.listener != null){
                            PlaylistDownloadDialog.this.listener.onDownloadComplete(manifest);
                        }
                        done();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        appendStatus("Error: " + errorMessage + "\n");
                        done();
                    }
                }
        );
    }

    public void appendStatus(String msg) {
        tvStatus.append(msg);
        // scroll to bottom
        ScrollView sv = (ScrollView)tvStatus.getParent();
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    private void done() {
        isDone = true;
        btnCancel.setText("OK");
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        // nothing else to clean up
    }
}

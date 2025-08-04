package com.roncatech.vcat.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.roncatech.vcat.R;

public class UnderConstructionDialog extends DialogFragment {
    public static final String TAG = "UnderConstructionDialog";
    private ImageButton btnOk;

    public static UnderConstructionDialog newInstance() {
        return new UnderConstructionDialog();
    }

    @Override
    public @NonNull Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // inflate your dialog layout
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_under_construction, null, false);

        btnOk = content.findViewById(R.id.btnOk);

        btnOk.setOnClickListener(v -> dismiss());

        return new AlertDialog.Builder(requireContext())
                .setView(content)
                .create();
    }
}

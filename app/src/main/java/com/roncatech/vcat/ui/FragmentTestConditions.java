package com.roncatech.vcat.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.roncatech.vcat.models.RunConfig;
import com.roncatech.vcat.models.SharedViewModel;

import com.roncatech.vcat.tools.VideoDecoderEnumerator;
import com.roncatech.vcat.R;
import com.roncatech.vcat.BuildConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FragmentTestConditions extends Fragment {

    private static final String TAG = "SettingsFragmentVCAT";
    private static final String PREF_RUN_CONFIG = "vcat_run_config";

    private SharedViewModel viewModel;
    private RunConfig runConfig;
    private SeekBar brightnessSeekBar;
    private Spinner threadsSpinner;
    private RadioGroup runModeRadioGroup;
    private TextView batteryPickerText, durationPickerText;
    private LinearLayout decoderContainer;
    EditText httpPortEditText;

    ImageButton aboutButton;

    public FragmentTestConditions(){
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable android.view.ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_conditions, container, false);

        // Get ViewModel
        this.viewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);
        this.runConfig = new RunConfig(this.viewModel.getRunConfig());

        setupUIElements(view);

        return view;
    }

    @Override
    public void onDestroyView(){
        super.onDestroyView();

        if(!this.runConfig.equals(this.viewModel.getRunConfig())){
            this.viewModel.setRunConfig(this.runConfig);
        }

        persistHttpPort();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if(!this.runConfig.equals(this.viewModel.getRunConfig())){
            this.viewModel.setRunConfig(this.runConfig);
        }

        persistHttpPort();
    }

    private void persistHttpPort() {
        if (httpPortEditText != null) {
            String text = httpPortEditText.getText().toString().trim();
            try {
                int port = Integer.parseInt(text);
                if(port != this.viewModel.getHttpPort()) {
                    if (port >= 1024 && port <= 65535) {
                        viewModel.setHttpPort(port);
                    } else {
                        Log.w("VCAT", "Invalid port range: " + port);
                    }
                }
            } catch (NumberFormatException e) {
                Log.w("VCAT", "Port not a valid number: " + text);
            }
        }
    }

    private void setupUIElements(View view) {

        this.aboutButton = view.findViewById(R.id.aboutBtn);
        this.aboutButton.setOnClickListener(v -> {
            Context context = v.getContext();
            Intent intent = new Intent(context, AboutActivity.class);
            context.startActivity(intent);
        });

        httpPortEditText = view.findViewById(R.id.httpPortEditText);
        viewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        // Set current port value
        Integer currentPort = viewModel.getHttpPort();
        httpPortEditText.setText(String.valueOf(currentPort));

        // ✅ Brightness SeekBar
        brightnessSeekBar = view.findViewById(R.id.brightnessSeekBar);
        TextView currentBrightnessValue = view.findViewById(R.id.currentBrightnessValue);

        if (brightnessSeekBar != null) {
            brightnessSeekBar.setProgress(runConfig.screenBrightness);
            currentBrightnessValue.setText("Brightness: " + runConfig.screenBrightness + "%");

            brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int _1Progress = progress + 1; // slider is 0-99, value is 1-100
                        runConfig.screenBrightness = _1Progress;
                        currentBrightnessValue.setText("Brightness: " + _1Progress + "%");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // ✅ Threads Spinner
        threadsSpinner = view.findViewById(R.id.threadsDropdown);

        if (threadsSpinner != null) {
            ArrayAdapter<CharSequence> threadAdapter = ArrayAdapter.createFromResource(
                    getContext(), R.array.threads_options, android.R.layout.simple_spinner_item);
            threadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            threadsSpinner.setAdapter(threadAdapter);

            threadsSpinner.setSelection(runConfig.threads);

            threadsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    runConfig.threads = Integer.parseInt(parent.getItemAtPosition(position).toString());
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }

        // ✅ Radio Buttons for Run Mode
        runModeRadioGroup = view.findViewById(R.id.radioGroup);
        batteryPickerText = view.findViewById(R.id.batteryPickerText);
        durationPickerText = view.findViewById(R.id.durationPickerText);

        if (runModeRadioGroup != null) {
            switch (runConfig.runMode) {
                case ONCE:
                    runModeRadioGroup.check(R.id.radioOnce);
                    batteryPickerText.setVisibility(View.GONE);
                    durationPickerText.setVisibility(View.GONE);
                    break;
                case BATTERY:
                    runModeRadioGroup.check(R.id.radioUntilBattery);
                    batteryPickerText.setText(String.format("%02d%%", runConfig.runLimit));
                    batteryPickerText.setVisibility(View.VISIBLE);
                    durationPickerText.setVisibility(View.GONE);
                    break;
                case TIME:
                    runModeRadioGroup.check(R.id.radioUntilTime);
                    int hours = runConfig.runLimit / 60;
                    int minutes = runConfig.runLimit % 60;
                    durationPickerText.setText(String.format("%02d:%02d", hours, minutes));
                    batteryPickerText.setVisibility(View.GONE);
                    durationPickerText.setVisibility(View.VISIBLE);
            }

            runModeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.radioUntilBattery) {
                    runConfig.runMode = RunConfig.RunMode.BATTERY;
                    runConfig.runLimit = RunConfig.defaultBattery;
                    batteryPickerText.setVisibility(View.VISIBLE);
                    durationPickerText.setVisibility(View.GONE);
                } else if (checkedId == R.id.radioUntilTime) {
                    runConfig.runMode = RunConfig.RunMode.TIME;
                    runConfig.runLimit = RunConfig.defaultTime;
                    batteryPickerText.setVisibility(View.GONE);
                    durationPickerText.setVisibility(View.VISIBLE);
                } else {
                    runConfig.runMode = RunConfig.RunMode.ONCE;
                    batteryPickerText.setVisibility(View.GONE);
                    durationPickerText.setVisibility(View.GONE);
                }
            });
        }

        batteryPickerText.setOnClickListener(v -> showBatteryPickerDialog());
        durationPickerText.setOnClickListener(v -> showDurationPickerDialog());

        // ✅ Decoder Selection Section
        decoderContainer = view.findViewById(R.id.dynamicDecoderContainer);
        setupDecoderSelection();
    }

    private void setupDecoderSelection() {
        decoderContainer.removeAllViews();

        VideoDecoderEnumerator.MimeType[] decoderMimeTypes = VideoDecoderEnumerator.MimeType.values();

        for (VideoDecoderEnumerator.MimeType mimeType : decoderMimeTypes) {
            List<MediaCodecInfo> codecInfos;
            try {
                codecInfos = MediaCodecUtil.getDecoderInfos(mimeType.toString(), false, false);
            } catch (MediaCodecUtil.DecoderQueryException e) {
                codecInfos = new ArrayList<>();
            }

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.VERTICAL);

            TextView mimeTypeText = new TextView(getContext());
            mimeTypeText.setText(mimeType.toString());

            Spinner decoderSpinner = new Spinner(getContext());
            List<String> decoderNames = new ArrayList<>();
            // Add "VCAT SW Decoder" only for av01 if extension-av1-release.aar is present
            if (mimeType.toString().equalsIgnoreCase("video/av01") && BuildConfig.HAS_LIDAV1D_EXTENSION) {
                decoderNames.add("VCAT SW Decoder");
            }

            for (MediaCodecInfo info : codecInfos) {
                decoderNames.add(info.name);
            }
            ArrayAdapter<String> decoderAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, decoderNames);
            decoderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            decoderSpinner.setAdapter(decoderAdapter);

            row.addView(mimeTypeText);
            row.addView(decoderSpinner);
            decoderContainer.addView(row);

            if (this.runConfig.decoderCfg.contains(mimeType)) {
                int idx = decoderAdapter.getPosition(this.runConfig.decoderCfg.getDecoder(mimeType));
                decoderSpinner.post(() -> decoderSpinner.setSelection(idx, false));
            }

            decoderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    String selectedItem = (String) parent.getItemAtPosition(position);
                    if (position == 0) {
                        runConfig.decoderCfg.removeDecoder(mimeType);
                    } else {
                        runConfig.decoderCfg.setDecoder(mimeType, selectedItem);
                    }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();// ✅ Apply changes when the user exits
    }


    private void showDurationPickerDialog() {
        // Create a custom dialog
        Dialog dialog = new Dialog(this.getContext());
        dialog.setContentView(R.layout.duration_picker); // Load XML layout
        dialog.setTitle("Select Duration");

        // Get references to NumberPickers inside the dialog
        NumberPicker hoursPicker = dialog.findViewById(R.id.hoursPicker);
        NumberPicker minutesPicker = dialog.findViewById(R.id.minutesPicker);
        TextView confirmButton = dialog.findViewById(R.id.confirmButton);

        // Configure Hours Picker (0 to 23 hours)
        hoursPicker.setMinValue(0);
        hoursPicker.setMaxValue(32);
        hoursPicker.setValue(16); // Default: 1 hour
        hoursPicker.setWrapSelectorWheel(false); // Stops looping

        // Configure Minutes Picker (0 to 59 minutes)
        minutesPicker.setMinValue(0);
        minutesPicker.setMaxValue(59);
        minutesPicker.setValue(0); // Default: 0 minutes
        minutesPicker.setWrapSelectorWheel(false); // Stops looping

        // Confirm button updates TextView with selected duration
        confirmButton.setOnClickListener(v -> {
            int selectedHours = hoursPicker.getValue();
            int selectedMinutes = minutesPicker.getValue();
            String duration = String.format(Locale.getDefault(), "%02d:%02d", selectedHours, selectedMinutes);
            durationPickerText.setText(duration); // Update UI
            this.runConfig.runLimit = selectedHours * 60 + selectedMinutes;
            //this.isDirty = true;
            dialog.dismiss();
        });

        dialog.show();
    }

    private void showBatteryPickerDialog() {
        // Create a custom dialog
        Dialog dialog = new Dialog(this.getContext());
        dialog.setContentView(R.layout.battery_level_picker);
        dialog.setTitle("Select Battery Percentage");

        // Get references to UI elements in the dialog
        NumberPicker batteryPicker = dialog.findViewById(R.id.batteryPercentagePicker);
        TextView confirmButton = dialog.findViewById(R.id.confirmBatteryButton);

        // Configure NumberPicker (0% to 100%) - No wrap-around
        batteryPicker.setMinValue(0);
        batteryPicker.setMaxValue(100);
        batteryPicker.setValue(15); // Default to 50%
        batteryPicker.setWrapSelectorWheel(false); // Prevents looping

        // Confirm button updates TextView with selected percentage
        confirmButton.setOnClickListener(v -> {
            int selectedPercentage = batteryPicker.getValue();
            String batteryText = selectedPercentage + "%";
            batteryPickerText.setText(batteryText); // Update UI
            this.runConfig.runLimit = selectedPercentage;
            //this.isDirty = true;
            dialog.dismiss();
        });

        dialog.show();
    }

}


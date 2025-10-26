package com.roncatech.vcat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.fragment.app.Fragment;

import com.roncatech.vcat.R;
import com.roncatech.vcat.legal.TermsPayload;
import com.roncatech.vcat.legal.TermsRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FragmentLicense extends Fragment {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private ScrollView scrollView;
    private TextView licenseText;
    private Button acceptBtn;
    private Button declineBtn;

    private TermsRepository repo;
    private volatile TermsPayload curPayload;

    /** Set by Splash when this screen is gating app launch. */
    private boolean requireAccept;

    public FragmentLicense() { /* empty public ctor */ }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_license, container, false);

        scrollView  = root.findViewById(R.id.scrollView);
        licenseText = root.findViewById(R.id.licenseText);
        acceptBtn   = root.findViewById(R.id.acceptButton);
        declineBtn  = root.findViewById(R.id.declineButton);

        // Splash sets this to true when License is required at launch.
        requireAccept = false;

        if(getArguments() != null) {
            requireAccept = getArguments().getBoolean(LicenseActivity.EXTRA_REQUIRE_ACCEPT, false);
        }

        repo = new TermsRepository(requireContext());

        // --- Initial button visibility policy ---
        if (requireAccept) {
            // First launch / re-launch when not accepted: show BOTH buttons
            acceptBtn.setVisibility(View.VISIBLE);
            declineBtn.setVisibility(View.VISIBLE);
            acceptBtn.setEnabled(false); // enabled after scroll-to-bottom
        } else {
            // Read-only visits (e.g., from About): hide both
            acceptBtn.setVisibility(View.GONE);
            declineBtn.setVisibility(View.GONE);
        }

        // --- Fetch Terms, render HTML, and set up gating (does NOT hide buttons when gating) ---
        exec.submit(() -> {
            try {
                curPayload = repo.fetchLatestOrFallback();
                final String html = (curPayload != null && curPayload.html != null)
                        ? curPayload.html
                        : "<h2>Terms</h2><p>Failed to load remote terms.</p>";
                final Spanned spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY);

                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;

                    licenseText.setText(spanned);
                    licenseText.setMovementMethod(LinkMovementMethod.getInstance());

                    if (requireAccept) {
                        // Gate Accept by requiring a full scroll
                        setupScrollGate();
                    }
                });
            } catch (Exception e) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    Spanned sp = HtmlCompat.fromHtml(
                            "<h2>Terms</h2><p>Unable to load terms. Using local fallback.</p>",
                            HtmlCompat.FROM_HTML_MODE_LEGACY);
                    licenseText.setText(sp);
                    licenseText.setMovementMethod(LinkMovementMethod.getInstance());

                    if (requireAccept) {
                        setupScrollGate();
                    }
                });
            }
        });

        // Accept → persist acceptance and continue to Main
        acceptBtn.setOnClickListener(v -> {
            if (requireAccept) {
                if (curPayload != null) {
                    repo.storeAccepted(curPayload.version, curPayload.html != null ? curPayload.html : "");
                } else {
                    repo.storeAccepted(repo.localVersion(), "");
                }
                if (isAdded()) {
                    startActivity(new Intent(requireContext(), MainActivity.class));
                    requireActivity().finish();
                }
            }
        });

        // Decline → terminate app (no acceptance stored). Next launch will show license again.
        declineBtn.setOnClickListener(v -> {
            if (requireAccept && isAdded()) {
                requireActivity().finishAffinity(); // close the whole task
            }
        });

        return root;
    }

    /** Enable Accept after user scrolls to bottom (or immediately if content fits). */
    private void setupScrollGate() {
        if (scrollView == null || acceptBtn == null) {
            acceptBtn.setEnabled(true);
            return;
        }
        final View content = scrollView.getChildAt(0);

        // If content already fits, enable immediately
        scrollView.post(() -> {
            if (content == null) return;
            boolean atBottom = scrollView.getScrollY() + scrollView.getHeight() >= content.getMeasuredHeight() - 16;
            if (atBottom) acceptBtn.setEnabled(true);
        });

        // Otherwise, enable once user scrolls to bottom
        scrollView.getViewTreeObserver().addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() {
                if (content == null) return;
                int y  = scrollView.getScrollY();
                int h  = scrollView.getHeight();
                int ch = content.getMeasuredHeight();
                if (y + h >= ch - 16) {
                    acceptBtn.setEnabled(true);
                    scrollView.getViewTreeObserver().removeOnScrollChangedListener(this);
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        exec.shutdownNow();
    }
}

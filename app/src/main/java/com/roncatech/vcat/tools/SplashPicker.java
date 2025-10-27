package com.roncatech.vcat.tools;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class SplashPicker {

    private static final class Sz {
        final int w, h;
        final long area;
        Sz(int w, int h) { this.w = w; this.h = h; this.area = (long) w * (long) h; }
        String name(String base) { return base + "_" + w + "x" + h; }
        double aspect() { return (double) h / (double) w; } // portrait aspect
    }

    // Now includes 540x960
    private static final List<Sz> CANDIDATES = Arrays.asList(
            new Sz(360, 640),
            new Sz(540, 960),
            new Sz(720, 1280),
            new Sz(1080, 1920),
            new Sz(1440, 2560)
    );

    private SplashPicker() {}

    /** Picks the smallest image that is >= screen in both dims; ties broken by closer aspect. */
    public static int bestPortraitResId(Context context) {
        return bestPortraitResId(context, "vcat_splash_screen_v");
    }

    public static int bestPortraitResId(Context context, String baseName) {
        int[] wh = getScreenPixels(context);
        int w = Math.min(wh[0], wh[1]);
        int h = Math.max(wh[0], wh[1]);
        double screenAspect = (double) h / (double) w;

        // Prefer candidates >= screen; among them, choose closest aspect then smallest area.
        Sz best = CANDIDATES.stream()
                .filter(s -> s.w >= w && s.h >= h)
                .min(Comparator
                        .comparingDouble((Sz s) -> Math.abs(s.aspect() - screenAspect))
                        .thenComparingLong(s -> s.area))
                .orElseGet(() ->
                        // If none big enough, use the largest overall (fallback)
                        CANDIDATES.stream().max(Comparator.comparingLong(s -> s.area)).get()
                );

        String resName = best.name(baseName);
        int id = context.getResources().getIdentifier(resName, "drawable", context.getPackageName());
        if (id != 0) return id;

        // Final fallback
        return context.getResources().getIdentifier(
                "vcat_splash_screen_v_1080x1920", "drawable", context.getPackageName());
    }

    private static int[] getScreenPixels(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new int[]{ b.width(), b.height() };
        } else {
            @SuppressWarnings("deprecation")
            DisplayMetrics dm = new DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new int[]{ dm.widthPixels, dm.heightPixels };
        }
    }
}

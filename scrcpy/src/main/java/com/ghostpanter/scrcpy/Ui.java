package com.ghostpanter.scrcpy;

import android.content.Context;
import android.content.res.Configuration;
import android.annotation.SuppressLint;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import java.util.Locale;

// Window inset plumbing for the non-fullscreen activities.
//
// targetSdk 35 and later make every window edge-to-edge on Android 15: the system
// no longer insets the content view, so an unhandled layout draws under
// the status and navigation bars. Mirror wants that and opts in itself;
// Main and Settings do not, so they pad their root by the bar sizes.
//
// WindowInsets.Type / getInsets are API 30+. On API 28–29 we fall back to the
// deprecated system-window inset getters so the controller still runs on
// Android 9–10.
public final class Ui {

    /** Primary tablet threshold; matches {@code layout-sw600dp}. */
    public static final int TABLET_SMALLEST_WIDTH_DP = 600;

    private Ui() {}

    /**
     * Effective tablet vs phone mode — single source of truth for mode-specific
     * behavior (native {@code max_size} default, Mirror tools pane, etc.).
     *
     * <p>Honors {@link Settings#uiMode(Context)}:
     * <ul>
     *   <li>{@link Settings#UI_MODE_PHONE} → always phone ({@code false})</li>
     *   <li>{@link Settings#UI_MODE_TABLET} → always tablet ({@code true})</li>
     *   <li>{@link Settings#UI_MODE_AUTO} (default) → {@link #detectTablet(Context)}</li>
     * </ul>
     *
     * <p>Mirror shows/hides {@code tools_pane} from this result so force overrides
     * change the UI even when resource qualifiers would disagree.
     */
    public static boolean isTablet(Context ctx) {
        switch (Settings.uiMode(ctx)) {
            case Settings.UI_MODE_PHONE:
                return false;
            case Settings.UI_MODE_TABLET:
                return true;
            default:
                return detectTablet(ctx);
        }
    }

    /**
     * Hardware / configuration auto-detect (ignores Settings override).
     * Treat as tablet when <b>any</b> of:
     * <ul>
     *   <li>{@code smallestScreenWidthDp >= 600} (primary; matches sw600dp), or</li>
     *   <li>{@link Configuration#SCREENLAYOUT_SIZE_LARGE} / {@code XLARGE}.</li>
     * </ul>
     */
    public static boolean detectTablet(Context ctx) {
        Configuration c = ctx.getResources().getConfiguration();
        if (c.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP) {
            return true;
        }
        int size = c.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        return size == Configuration.SCREENLAYOUT_SIZE_LARGE
                || size == Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    /** Available width ≥ 600dp (w600dp), e.g. tablet or phone landscape. */
    public static boolean isWide(Context ctx) {
        Configuration c = ctx.getResources().getConfiguration();
        return c.screenWidthDp >= TABLET_SMALLEST_WIDTH_DP;
    }

    /** Short label for the persisted UI mode override. */
    public static String uiModeLabel(Context ctx) {
        switch (Settings.uiMode(ctx)) {
            case Settings.UI_MODE_PHONE:
                return "force-phone";
            case Settings.UI_MODE_TABLET:
                return "force-tablet";
            default:
                return "auto";
        }
    }

    /**
     * Compact summary for logs: effective mode, override, and Configuration fields.
     */
    public static String deviceModeSummary(Context ctx) {
        Configuration c = ctx.getResources().getConfiguration();
        int size = c.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        return String.format(Locale.US,
                "mode=%s override=%s auto=%s sw=%ddp w=%ddp h=%ddp screenLayoutSize=%d",
                isTablet(ctx) ? "tablet" : "phone",
                uiModeLabel(ctx),
                detectTablet(ctx) ? "tablet" : "phone",
                c.smallestScreenWidthDp, c.screenWidthDp, c.screenHeightDp, size);
    }

    /** Pad root for status + navigation bars. */
    public static void padForSystemBars(View root) {
        padForInsets(root, /*ime=*/ false);
    }

    /** Pad root for system bars and the IME (keyboard). */
    public static void padForSystemBarsAndIme(View root) {
        padForInsets(root, /*ime=*/ true);
    }

    // Add the insets to whatever padding the layout already declares.
    // Applied on top of the XML padding, not instead of it.
    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private static void padForInsets(View root, boolean ime) {
        int left   = root.getPaddingLeft();
        int top    = root.getPaddingTop();
        int right  = root.getPaddingRight();
        int bottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int inL;
            int inT;
            int inR;
            int inB;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int types = WindowInsets.Type.systemBars();
                if (ime) types |= WindowInsets.Type.ime();
                Insets in = insets.getInsets(types);
                inL = in.left;
                inT = in.top;
                inR = in.right;
                inB = in.bottom;
            } else {
                inL = insets.getSystemWindowInsetLeft();
                inT = insets.getSystemWindowInsetTop();
                inR = insets.getSystemWindowInsetRight();
                inB = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left + inL, top + inT,
                         right + inR, bottom + inB);
            return insets;
        });
        root.requestApplyInsets();
    }
}

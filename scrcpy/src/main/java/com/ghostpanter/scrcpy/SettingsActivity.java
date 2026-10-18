package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.Toast;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

// Codec choice + streaming knobs. All persisted to SharedPreferences
// via Settings on click; the next session bringup reads them and
// applies to the scrcpy server cmdline.
public final class SettingsActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ScrcpyHost.wrap(base));
    }

    private Shizuku.OnRequestPermissionResultListener shizukuPermissionListener;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.settings);
        Ui.padForSystemBars(findViewById(R.id.root));
        showVersion();

        RadioGroup videoGroup   = findViewById(R.id.video_codec);
        RadioGroup audioGroup   = findViewById(R.id.audio_codec);
        RadioGroup maxSizeGroup = findViewById(R.id.max_size);
        RadioGroup bitRateGroup = findViewById(R.id.video_bit_rate);
        RadioGroup maxFpsGroup  = findViewById(R.id.max_fps);
        RadioGroup uiModeGroup  = findViewById(R.id.ui_mode);

        syncUiFromSettings();

        videoGroup.setOnCheckedChangeListener((g, id) -> {
            String v = "h264";
            if (id == R.id.video_h265) v = "h265";
            else if (id == R.id.video_av1) v = "av1";
            Settings.setVideoCodec(this, v);
            Log.i("settings: video_codec=%s", v);
        });

        audioGroup.setOnCheckedChangeListener((g, id) -> {
            String a = id == R.id.audio_opus ? "opus" : "raw";
            Settings.setAudioCodec(this, a);
            Log.i("settings: audio_codec=%s", a);
        });

        maxSizeGroup.setOnCheckedChangeListener((g, id) -> {
            int v = 0;
            if      (id == R.id.max_size_480)  v = 480;
            else if (id == R.id.max_size_720)  v = 720;
            else if (id == R.id.max_size_1080) v = 1080;
            else if (id == R.id.max_size_1440) v = 1440;
            else if (id == R.id.max_size_2160) v = 2160;
            Settings.setMaxSize(this, v);
            Log.i("settings: max_size=%d", v);
        });

        bitRateGroup.setOnCheckedChangeListener((g, id) -> {
            // Every button maps explicitly. Falling back to
            // DEFAULT_VIDEO_BIT_RATE for the unmatched one silently tied
            // whichever button that was to the default's current value,
            // so changing the default changed what that button stored.
            int v;
            if      (id == R.id.bit_rate_1m)  v = 1_000_000;
            else if (id == R.id.bit_rate_2m)  v = 2_000_000;
            else if (id == R.id.bit_rate_4m)  v = 4_000_000;
            else if (id == R.id.bit_rate_6m)  v = 6_000_000;
            else if (id == R.id.bit_rate_8m)  v = 8_000_000;
            else if (id == R.id.bit_rate_16m) v = 16_000_000;
            else return;
            Settings.setVideoBitRate(this, v);
            Log.i("settings: video_bit_rate=%d", v);
        });

        maxFpsGroup.setOnCheckedChangeListener((g, id) -> {
            int v = 60;
            if      (id == R.id.max_fps_0)   v = 0;
            else if (id == R.id.max_fps_15)  v = 15;
            else if (id == R.id.max_fps_30)  v = 30;
            else if (id == R.id.max_fps_60)  v = 60;
            else if (id == R.id.max_fps_120) v = 120;
            else return;
            Settings.setMaxFps(this, v);
            Log.i("settings: max_fps=%d", v);
        });

        uiModeGroup.setOnCheckedChangeListener((g, id) -> {
            int mode = Settings.UI_MODE_AUTO;
            if (id == R.id.ui_mode_phone) mode = Settings.UI_MODE_PHONE;
            else if (id == R.id.ui_mode_tablet) mode = Settings.UI_MODE_TABLET;
            Settings.setUiMode(this, mode);
            Log.i("settings: ui_mode=%s effective=%s",
                    Ui.uiModeLabel(this), Ui.isTablet(this) ? "tablet" : "phone");
            Toast.makeText(this, R.string.ui_mode_apply_hint, Toast.LENGTH_SHORT).show();
        });

        CheckBox toolsOnLeftBox = findViewById(R.id.tools_on_left);
        toolsOnLeftBox.setOnCheckedChangeListener((b, checked) -> {
            Settings.setToolsOnLeft(this, checked);
            Log.i("settings: tools_on_left=%b", checked);
            Toast.makeText(this, checked
                    ? R.string.split_now_tools_preview
                    : R.string.split_now_preview_tools, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.view_logs).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LogViewerActivity.class)));

        findViewById(R.id.donate).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, Donate.class)));

        findViewById(R.id.licenses).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, Licenses.class)));

        CheckBox lowLatencyBox = findViewById(R.id.low_latency);
        lowLatencyBox.setOnCheckedChangeListener((b, checked) -> {
            Settings.setLowLatency(this, checked);
            Log.i("settings: low_latency=%b", checked);
        });

        CheckBox clipboardBox = findViewById(R.id.clipboard_sync);
        clipboardBox.setOnCheckedChangeListener((b, checked) -> {
            Settings.setClipboardSync(this, checked);
            Log.i("settings: clipboard=%b", checked);
        });

        CheckBox rootCaptureBox = findViewById(R.id.root_capture_secure);
        rootCaptureBox.setOnCheckedChangeListener((b, checked) -> {
            Settings.setRootCaptureSecure(this, checked);
            Log.i("settings: root_capture=%b", checked);
        });

        CheckBox preferShizukuBox = findViewById(R.id.prefer_shizuku);
        preferShizukuBox.setOnCheckedChangeListener((b, checked) -> {
            Settings.setPreferShizuku(this, checked);
            Log.i("settings: prefer_shizuku=%b", checked);
            if (checked && ShizukuHelper.isReady(this)) {
                ShizukuHelper.bindUserService(this);
            }
            refreshShizukuStatus();
        });

        findViewById(R.id.shizuku_request).setOnClickListener(v -> {
            if (!ShizukuHelper.isInstalled(this)) {
                Toast.makeText(this, R.string.shizuku_status_not_installed, Toast.LENGTH_LONG).show();
                return;
            }
            ShizukuHelper.requestPermission();
        });
        shizukuPermissionListener = (requestCode, grantResult) -> {
            Log.i("settings: shizuku permission result=%d", grantResult);
            refreshShizukuStatus();
            if (grantResult == 0 && Settings.preferShizuku(this)) {
                ShizukuHelper.bindUserService(this);
            }
        };
        ShizukuHelper.addPermissionListener(shizukuPermissionListener);
        refreshShizukuStatus();
        if (Settings.preferShizuku(this) && ShizukuHelper.isReady(this)) {
            ShizukuHelper.bindUserService(this);
        }

        // One-tap streaming presets. Persist like any other setting;
        // next connection applies them (same as manual radio changes).
        findViewById(R.id.preset_smooth).setOnClickListener(v ->
                applyPreset(/*maxSize*/720, /*bitRate*/6_000_000, /*fps*/60,
                        /*codec*/"h264", /*lowLatency*/true,
                        R.string.preset_smooth_toast));
        findViewById(R.id.preset_quality).setOnClickListener(v ->
                applyPreset(/*maxSize*/1080, /*bitRate*/8_000_000, /*fps*/60,
                        /*codec*/"h264", /*lowLatency*/true,
                        R.string.preset_quality_toast));
    }

    private void applyPreset(int maxSize, int bitRate, int fps,
                             String codec, boolean lowLatency, int toastRes) {
        Settings.setMaxSize(this, maxSize);
        Settings.setVideoBitRate(this, bitRate);
        Settings.setMaxFps(this, fps);
        Settings.setVideoCodec(this, codec);
        Settings.setLowLatency(this, lowLatency);
        syncUiFromSettings();
        Toast.makeText(this, toastRes, Toast.LENGTH_LONG).show();
        Log.i("settings: preset max_size=%d bit_rate=%d fps=%d codec=%s low_latency=%b",
                maxSize, bitRate, fps, codec, lowLatency);
    }

    // Reflect persisted Settings onto every radio / checkbox so a preset
    // (or any future bulk write) leaves the UI consistent with storage.
    private void syncUiFromSettings() {
        switch (Settings.videoCodec(this)) {
            case "h265": ((RadioButton) findViewById(R.id.video_h265)).setChecked(true); break;
            case "av1":  ((RadioButton) findViewById(R.id.video_av1)).setChecked(true);  break;
            default:     ((RadioButton) findViewById(R.id.video_h264)).setChecked(true);
        }
        switch (Settings.audioCodec(this)) {
            case "opus": ((RadioButton) findViewById(R.id.audio_opus)).setChecked(true); break;
            default:     ((RadioButton) findViewById(R.id.audio_raw)).setChecked(true);
        }
        switch (Settings.maxSize(this)) {
            case 480:  ((RadioButton) findViewById(R.id.max_size_480)).setChecked(true); break;
            case 720:  ((RadioButton) findViewById(R.id.max_size_720)).setChecked(true); break;
            case 1080: ((RadioButton) findViewById(R.id.max_size_1080)).setChecked(true); break;
            case 1440: ((RadioButton) findViewById(R.id.max_size_1440)).setChecked(true); break;
            case 2160: ((RadioButton) findViewById(R.id.max_size_2160)).setChecked(true); break;
            default:   ((RadioButton) findViewById(R.id.max_size_0)).setChecked(true);
        }
        switch (Settings.videoBitRate(this)) {
            case 1_000_000:  ((RadioButton) findViewById(R.id.bit_rate_1m)).setChecked(true);  break;
            case 2_000_000:  ((RadioButton) findViewById(R.id.bit_rate_2m)).setChecked(true);  break;
            case 6_000_000:  ((RadioButton) findViewById(R.id.bit_rate_6m)).setChecked(true);  break;
            case 8_000_000:  ((RadioButton) findViewById(R.id.bit_rate_8m)).setChecked(true);  break;
            case 16_000_000: ((RadioButton) findViewById(R.id.bit_rate_16m)).setChecked(true); break;
            default:         ((RadioButton) findViewById(R.id.bit_rate_4m)).setChecked(true);
        }
        switch (Settings.maxFps(this)) {
            case 15:  ((RadioButton) findViewById(R.id.max_fps_15)).setChecked(true); break;
            case 30:  ((RadioButton) findViewById(R.id.max_fps_30)).setChecked(true); break;
            case 120: ((RadioButton) findViewById(R.id.max_fps_120)).setChecked(true); break;
            case 0:   ((RadioButton) findViewById(R.id.max_fps_0)).setChecked(true); break;
            default:  ((RadioButton) findViewById(R.id.max_fps_60)).setChecked(true);
        }
        ((CheckBox) findViewById(R.id.low_latency)).setChecked(Settings.lowLatency(this));
        ((CheckBox) findViewById(R.id.clipboard_sync)).setChecked(Settings.clipboardSync(this));
        ((CheckBox) findViewById(R.id.root_capture_secure)).setChecked(Settings.rootCaptureSecure(this));
        ((CheckBox) findViewById(R.id.prefer_shizuku)).setChecked(Settings.preferShizuku(this));
        switch (Settings.uiMode(this)) {
            case Settings.UI_MODE_PHONE:
                ((RadioButton) findViewById(R.id.ui_mode_phone)).setChecked(true);
                break;
            case Settings.UI_MODE_TABLET:
                ((RadioButton) findViewById(R.id.ui_mode_tablet)).setChecked(true);
                break;
            default:
                ((RadioButton) findViewById(R.id.ui_mode_auto)).setChecked(true);
        }
        ((CheckBox) findViewById(R.id.tools_on_left)).setChecked(Settings.toolsOnLeft(this));
        refreshShizukuStatus();
    }

    private void refreshShizukuStatus() {
        TextView tv = findViewById(R.id.shizuku_status);
        if (tv == null) return;
        String label;
        switch (ShizukuHelper.status(this)) {
            case NOT_INSTALLED: label = getString(R.string.shizuku_status_not_installed); break;
            case DEAD: label = getString(R.string.shizuku_status_dead); break;
            case DENIED: label = getString(R.string.shizuku_status_denied); break;
            case GRANTED: label = getString(R.string.shizuku_status_granted); break;
            default: label = "?";
        }
        tv.setText(getString(R.string.shizuku_status, label + " (" + ShizukuHelper.detail(this) + ")"));
    }

    @Override protected void onDestroy() {
        if (shizukuPermissionListener != null) {
            ShizukuHelper.removePermissionListener(shizukuPermissionListener);
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void showVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            ((TextView) findViewById(R.id.version)).setText(
                    getString(R.string.version_format, info.versionName, info.getLongVersionCode())
                    + "\n" + getString(R.string.compat_requirements));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("installed package is missing", e);
        }
    }
}

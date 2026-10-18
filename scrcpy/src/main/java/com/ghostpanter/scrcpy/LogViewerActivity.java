package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

// In-app ring-buffer log viewer (Settings → 日志). Lets the user pick a
// minimum level and copy/share the full elevate / ADB / su / server text
// that toasts truncate.
public final class LogViewerActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ScrcpyHost.wrap(base));
    }

    private TextView logText;
    private TextView logCount;
    private ScrollView logScroll;
    private Log.Level minLevel = Log.Level.INFO;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.log_viewer);
        Ui.padForSystemBars(findViewById(R.id.root));

        logText = findViewById(R.id.log_text);
        logCount = findViewById(R.id.log_count);
        logScroll = findViewById(R.id.log_scroll);

        RadioGroup levelGroup = findViewById(R.id.log_level);
        levelGroup.setOnCheckedChangeListener((g, id) -> {
            if (id == R.id.log_level_verbose) minLevel = Log.Level.VERBOSE;
            else if (id == R.id.log_level_debug) minLevel = Log.Level.DEBUG;
            else if (id == R.id.log_level_info) minLevel = Log.Level.INFO;
            else if (id == R.id.log_level_warn) minLevel = Log.Level.WARN;
            else if (id == R.id.log_level_error) minLevel = Log.Level.ERROR;
            else return;
            refresh();
        });

        findViewById(R.id.log_refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.log_copy).setOnClickListener(v -> copy());
        findViewById(R.id.log_share).setOnClickListener(v -> share());
        findViewById(R.id.log_clear).setOnClickListener(v -> {
            Log.clear();
            refresh();
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show();
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String dump = Log.dump(minLevel);
        if (dump.isEmpty()) dump = getString(R.string.log_empty);
        logText.setText(dump);
        logCount.setText(getString(R.string.log_count_format, Log.size(), minLevel.label));
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void copy() {
        String dump = Log.dump(minLevel);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, R.string.log_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("scrcpy-android-log", dump));
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
    }

    private void share() {
        String dump = Log.dump(minLevel);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "scrcpy-android log");
        send.putExtra(Intent.EXTRA_TEXT, dump);
        startActivity(Intent.createChooser(send, getString(R.string.log_share)));
    }
}

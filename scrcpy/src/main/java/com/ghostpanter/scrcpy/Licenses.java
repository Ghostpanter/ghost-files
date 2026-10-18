package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// Shows the bundled third-party notices.
//
// This is a legal requirement, not a courtesy. The APK links
// libspake2.so, which is LGPL-3.0: section 4 lets an LGPL library be
// combined into a differently-licensed work only if the combined work
// gives prominent notice that the library is used and is covered by the
// LGPL, and points at the source needed to relink it. Shipping that text
// as an asset no code reads is not notice. This screen is what makes it
// notice.
public final class Licenses extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ScrcpyHost.wrap(base));
    }

    private static final String ASSET = "THIRD_PARTY_NOTICES";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.licenses);
        Ui.padForSystemBars(findViewById(R.id.root));
        ((TextView) findViewById(R.id.notices)).setText(read());
    }

    private String read() {
        try (InputStream in = getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) >= 0; ) {
                if (n == 0) throw new IOException("license asset read made no progress");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            Log.e(e, "licenses: cannot read %s", ASSET);
            return getString(R.string.licenses_unavailable);
        }
    }
}

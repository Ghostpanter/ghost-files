package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

// Display a WIFI:T:ADB QR for the target to scan, discover the pairing
// server over mDNS, run SPAKE2 pairing, then auto-save when the connect
// endpoint is discovered (or ask for the address).
public final class QrPair extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ScrcpyHost.wrap(base));
    }

    private static final int QR_SIZE_PX = 720;
    private static final int REQ_NEARBY = 42;
    private static final long CONNECT_DISCOVER_MS = 12_000L;

    private QrCodes.Payload payload;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener pairDiscovery;
    private final AtomicBoolean pairing = new AtomicBoolean(false);
    private final AtomicBoolean saved = new AtomicBoolean(false);
    private volatile String pairedHost;
    private volatile int pairedConnectPort = -1;

    private ImageView qrView;
    private TextView status;
    private EditText addressField;
    private Button saveButton;
    private Adb adb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.qr_pair);
        Ui.padForSystemBarsAndIme(findViewById(R.id.root));

        qrView = findViewById(R.id.qr_image);
        status = findViewById(R.id.qr_status);
        addressField = findViewById(R.id.device_address);
        saveButton = findViewById(R.id.save_address);
        Button refresh = findViewById(R.id.refresh_qr);

        saveButton.setEnabled(false);
        payload = QrCodes.generate();
        showQr();

        refresh.setOnClickListener(v -> {
            stopDiscovery();
            pairing.set(false);
            saved.set(false);
            pairedHost = null;
            pairedConnectPort = -1;
            payload = QrCodes.generate();
            showQr();
            status.setText(R.string.qr_waiting);
            maybeStartDiscovery();
        });

        saveButton.setOnClickListener(v -> {
            Devices.Device target = Devices.parseAddress(addressField.getText().toString());
            if (target == null) {
                Toast.makeText(this, R.string.bad_address, Toast.LENGTH_LONG).show();
                return;
            }
            persist(target, /*auto*/ false);
        });

        new Thread(() -> {
            try {
                Adb a = Adb.getInstance(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    adb = a;
                    maybeStartDiscovery();
                });
            } catch (Exception e) {
                Log.e(e, "qr adb init failed");
                runOnUiThread(() -> status.setText(getString(R.string.qr_failed, e.getMessage())));
            }
        }, "qr-adb-init").start();
    }

    private void showQr() {
        qrView.setImageBitmap(QrCodes.encode(payload.qrText, QR_SIZE_PX));
        status.setText(R.string.qr_waiting);
    }

    private void maybeStartDiscovery() {
        if (adb == null) return;
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.NEARBY_WIFI_DEVICES},
                        REQ_NEARBY);
                status.setText(R.string.qr_permission_nearby);
                return;
            }
        }
        startDiscovery();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        if (requestCode == REQ_NEARBY) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                startDiscovery();
            } else {
                status.setText(R.string.qr_permission_nearby);
            }
        }
    }

    private void startDiscovery() {
        stopDiscovery();
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        if (nsd == null) {
            status.setText(getString(R.string.qr_failed, getString(R.string.nsd_unavailable)));
            return;
        }
        status.setText(R.string.qr_waiting);
        pairDiscovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {
                Log.w("nsd pair start failed: %d", e);
            }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {
                Log.i("nsd pair discovery started");
            }
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo info) {
                String name = info.getServiceName();
                Log.i("nsd found pairing service: %s", name);
                if (name == null || !name.equals(payload.serviceName)) return;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo s, int errorCode) {
                        Log.w("nsd resolve failed: %d", errorCode);
                    }
                    @Override public void onServiceResolved(NsdServiceInfo resolved) {
                        onPairingServiceResolved(resolved);
                    }
                });
            }
            @Override public void onServiceLost(NsdServiceInfo info) {}
        };
        try {
            nsd.discoverServices(AdbDiscovery.PAIRING_TYPE, NsdManager.PROTOCOL_DNS_SD, pairDiscovery);
        } catch (Exception e) {
            Log.e(e, "nsd discover failed");
            status.setText(getString(R.string.qr_failed, e.getMessage()));
        }
    }

    private void onPairingServiceResolved(NsdServiceInfo info) {
        if (!pairing.compareAndSet(false, true)) return;
        AdbDiscovery.Endpoint ep = AdbDiscovery.endpointOf(info);
        if (ep == null) {
            pairing.set(false);
            return;
        }
        Log.i("qr pair target %s:%d", ep.host, ep.port);
        runOnUiThread(() -> status.setText(R.string.pairing));
        new Thread(() -> {
            try {
                adb.pairDevice(ep.host, ep.port, payload.password);
                pairedHost = ep.host;
                Log.i("qr pair ok host=%s", ep.host);
                // Prefer AdbDiscovery for the connect endpoint, then auto-save.
                AdbDiscovery.Endpoint connect = AdbDiscovery.discoverConnect(
                        getApplicationContext(), ep.host, CONNECT_DISCOVER_MS);
                if (connect != null) {
                    pairedConnectPort = connect.port;
                    Devices.Device target = new Devices.Device(connect.host, connect.port);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        addressField.setText(target.toString());
                        saveButton.setEnabled(true);
                        status.setText(R.string.qr_need_connect_address);
                    });
                    persist(target, /*auto*/ true);
                    return;
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText(R.string.qr_need_connect_address);
                    if (pairedConnectPort > 0) {
                        addressField.setText(ep.host + ":" + pairedConnectPort);
                    } else if (addressField.getText().length() == 0) {
                        addressField.setText(ep.host + ":");
                    }
                    saveButton.setEnabled(true);
                    Toast.makeText(this, R.string.qr_paired, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(e, "qr pair failed");
                pairing.set(false);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    status.setText(getString(R.string.qr_failed, e.getMessage()));
                });
            }
        }, "qr-pair").start();
    }

    private void persist(Devices.Device target, boolean auto) {
        if (auto) {
            if (!saved.compareAndSet(false, true)) return;
        } else {
            saved.set(true);
        }
        try {
            Devices.upsert(getApplicationContext(), target);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this,
                        auto ? R.string.qr_autosaved : R.string.qr_paired,
                        Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception e) {
            saved.set(false);
            Log.e(e, "qr save failed");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                saveButton.setEnabled(true);
                Toast.makeText(this, getString(R.string.could_not_save_device, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void stopDiscovery() {
        if (nsd == null) return;
        try {
            if (pairDiscovery != null) nsd.stopServiceDiscovery(pairDiscovery);
        } catch (Exception ignored) {}
        pairDiscovery = null;
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }
}

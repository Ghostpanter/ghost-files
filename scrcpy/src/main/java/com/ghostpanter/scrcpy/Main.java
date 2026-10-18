package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

// Pairing form + saved-device list + IP-only reconnect after reboot.
//
// Flow: pair first (pairing-dialog IP:port + 6-digit code), then resolve the
// Wireless debugging *connect* endpoint (user-supplied or mDNS
// _adb-tls-connect._tcp for that host) and save that. LAN discovery can fill
// the pairing address from _adb-tls-pairing._tcp.
//
// After reboot the connect port is ephemeral again: 「一键重连（仅 IP）」
// rediscovers _adb-tls-connect._tcp for the entered IPv4 (no pairing code).
public final class Main extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(ScrcpyHost.wrap(base));
    }

    private static final int REQ_NEARBY_PAIR = 43;
    private static final int REQ_NEARBY_DISCOVER = 44;
    private static final int REQ_NEARBY_RECONNECT = 45;
    private static final long CONNECT_DISCOVER_MS = 12_000L;
    private static final long PAIRING_DISCOVER_MS = 8_000L;
    private static final long RECONNECT_DISCOVER_MS = 15_000L;

    private volatile Adb adb;
    private LinearLayout deviceList;
    private TextView     devicesEmpty;
    private Button       pairButton;
    private Button       discoverPairButton;
    private Button       reconnectByIpButton;
    private EditText     pairAddress;
    private EditText     pairPort;
    private EditText     pairCode;
    private EditText     connectAddress;
    private EditText     reconnectIp;
    private EditText     directAddress;
    private Button       directConnectButton;
    private static final String PREF_LAST_DIRECT = "last_direct_endpoint";
    private Runnable     pendingAfterNearby;
    private boolean      openMirrorAfterReconnect = true;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.main);
        // Auto tablet/phone detection (no manual toggle). Used by Server maxSize
        // and Mirror tools; log once so first-run mode is visible in logcat.
        Log.i("main: auto-detect %s", Ui.deviceModeSummary(this));

        // The IME inset is in the mask too: the window is edge-to-edge, so
        // nothing resizes it when the keyboard opens. The page is one
        // ScrollView, so padding the root keeps the form reachable behind
        // the keyboard.
        Ui.padForSystemBarsAndIme(findViewById(R.id.root));

        pairAddress          = findViewById(R.id.pair_address);
        pairPort             = findViewById(R.id.pair_port);
        pairCode             = findViewById(R.id.pair_code);
        connectAddress       = findViewById(R.id.connect_address);
        reconnectIp          = findViewById(R.id.reconnect_ip);
        pairButton           = findViewById(R.id.pair);
        discoverPairButton   = findViewById(R.id.discover_pairing);
        reconnectByIpButton  = findViewById(R.id.reconnect_by_ip);
        directAddress        = findViewById(R.id.direct_address);
        directConnectButton  = findViewById(R.id.direct_connect);
        View settingsBtn     = findViewById(R.id.settings);
        deviceList  = findViewById(R.id.devices);
        devicesEmpty = findViewById(R.id.devices_empty);

        settingsBtn.setOnClickListener(v -> startActivity(
                new Intent(this, SettingsActivity.class)));
        String lastDirect = Settings.prefs(this).getString(PREF_LAST_DIRECT, "");
        if (!TextUtils.isEmpty(lastDirect)) directAddress.setText(lastDirect);
        findViewById(R.id.direct_howto).setOnClickListener(v -> showDirectHowto());
        directConnectButton.setOnClickListener(v -> {
            if (adb == null) return;
            Devices.Device d = parseDirectAddress(directAddress.getText().toString());
            if (d == null) {
                Toast.makeText(this, R.string.bad_direct_address, Toast.LENGTH_LONG).show();
                return;
            }
            startDirectConnect(d);
        });
        findViewById(R.id.pair_qr).setOnClickListener(v ->
                startActivity(new Intent(this, QrPair.class)));

        try {
            List<Devices.Device> loaded = Devices.load(this);
            showDevices(loaded);
            prefillReconnectIp(loaded);
        } catch (IOException e) {
            Log.e(e, "devices: load failed");
            showDevices(java.util.Collections.emptyList());
            Toast.makeText(this, R.string.device_list_unreadable, Toast.LENGTH_LONG).show();
        }

        // RSA keygen on first launch can take 1-3 s; never on the UI thread.
        pairButton.setEnabled(false);
        discoverPairButton.setEnabled(false);
        reconnectByIpButton.setEnabled(false);
        directConnectButton.setEnabled(false);
        new Thread(() -> {
            try {
                Adb a = Adb.getInstance(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    adb = a;
                    pairButton.setEnabled(true);
                    discoverPairButton.setEnabled(true);
                    reconnectByIpButton.setEnabled(true);
                    directConnectButton.setEnabled(true);
                });
            } catch (Exception e) {
                Log.e(e, "adb init failed");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, getString(R.string.adb_init_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "adb-init").start();

        pairButton.setOnClickListener(v -> {
            if (adb == null) return;
            ParsedPairing parsed = parsePairingFields();
            if (parsed == null) return;
            String connectRaw = connectAddress.getText().toString().trim();
            Devices.Device explicitConnect = null;
            if (!TextUtils.isEmpty(connectRaw)) {
                explicitConnect = Devices.parseAddress(connectRaw);
                if (explicitConnect == null) {
                    Toast.makeText(this, R.string.bad_connect_address, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            Devices.Device explicit = explicitConnect;
            setBusy(true);
            ensureNearby(() -> {
                        Toast.makeText(this, R.string.pairing, Toast.LENGTH_SHORT).show();
                        new Thread(
                                () -> pairAndSave(parsed.host, parsed.pairPort, parsed.code, explicit),
                                "pair").start();
                    },
                    REQ_NEARBY_PAIR);
        });

        discoverPairButton.setOnClickListener(v -> {
            if (adb == null) return;
            setBusy(true);
            ensureNearby(() -> {
                        Toast.makeText(this, R.string.discovering_pairing, Toast.LENGTH_SHORT).show();
                        new Thread(this::discoverPairingAndChoose, "discover-pair").start();
                    },
                    REQ_NEARBY_DISCOVER);
        });

        reconnectByIpButton.setOnClickListener(v -> {
            if (adb == null) return;
            String ip = parseReconnectIpField();
            if (ip == null) return;
            startReconnectByIp(ip, openMirrorAfterReconnect);
        });
    }

    // Legacy `adb tcpip` (Android 9/10): tell the user to tap "Allow" on the
    // controlled device while Adb.connect() waits for the RSA key approval.
    private final Adb.AuthPromptListener authPrompt = (h, p) -> runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, getString(R.string.auth_prompt_hint), Toast.LENGTH_LONG).show();
    });

    @Override
    protected void onResume() {
        super.onResume();
        Adb.setAuthPromptListener(authPrompt);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Adb.setAuthPromptListener(null);
    }

    private void prefillReconnectIp(List<Devices.Device> devices) {
        if (devices == null || devices.isEmpty()) return;
        // Prefer the last list entry (most recently upserted).
        Devices.Device last = devices.get(devices.size() - 1);
        String host = last.host;
        if (AdbDiscovery.isIpv4(host)) {
            reconnectIp.setText(AdbDiscovery.normalizeIpv4(host.trim()));
        } else if (looksLikeHostOnly(host)) {
            reconnectIp.setText(host);
        }
    }

    private String parseReconnectIpField() {
        String raw = reconnectIp.getText().toString().trim();
        String norm = AdbDiscovery.normalizeIpv4(raw);
        if (norm == null) {
            Toast.makeText(this, R.string.bad_reconnect_ip, Toast.LENGTH_LONG).show();
            return null;
        }
        return norm;
    }

    private void startReconnectByIp(String ip, boolean openMirror) {
        // Legacy adb tcpip entry (fixed 5555): connect directly, no mDNS scan.
        Devices.Device legacy = savedLegacyFor(ip);
        if (legacy != null) {
            startDirectConnect(legacy);
            return;
        }
        setBusy(true);
        ensureNearby(() -> {
                    Toast.makeText(this, R.string.discovering_connect, Toast.LENGTH_SHORT).show();
                    new Thread(() -> reconnectByIp(ip, openMirror), "reconnect-ip").start();
                },
                REQ_NEARBY_RECONNECT);
    }

    private static final class ParsedPairing {
        final String host;
        final int pairPort;
        final String code;
        ParsedPairing(String host, int pairPort, String code) {
            this.host = host;
            this.pairPort = pairPort;
            this.code = code;
        }
    }

    // Accept "ip:pairPort" in the primary field, or legacy IP-only + pair_port.
    private ParsedPairing parsePairingFields() {
        String raw = pairAddress.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) {
            Toast.makeText(this, R.string.bad_pair_address, Toast.LENGTH_LONG).show();
            return null;
        }
        String host;
        int pairP;
        Devices.Device asEndpoint = Devices.parseAddress(raw);
        if (asEndpoint != null) {
            host = asEndpoint.host;
            pairP = asEndpoint.port;
        } else if (looksLikeHostOnly(raw)) {
            // Host-only: require the legacy pair_port field.
            host = raw;
            pairP = Devices.parsePort(pairPort.getText().toString());
            if (pairP < 0) {
                Toast.makeText(this, R.string.bad_pair_port, Toast.LENGTH_LONG).show();
                return null;
            }
        } else {
            Toast.makeText(this, R.string.bad_pair_address, Toast.LENGTH_LONG).show();
            return null;
        }
        String pc = pairCode.getText().toString().trim();
        if (TextUtils.isEmpty(pc) || !pc.matches("[0-9]{6}")) {
            Toast.makeText(this, R.string.bad_pair_code, Toast.LENGTH_LONG).show();
            return null;
        }
        return new ParsedPairing(host, pairP, pc);
    }

    private static boolean looksLikeHostOnly(String s) {
        if (s == null || s.isEmpty() || s.length() > 255) return false;
        if (s.indexOf(':') >= 0) return false; // unbracketed IPv6 not accepted here
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '[' || c == ']') {
                return false;
            }
        }
        return true;
    }

    private void ensureNearby(Runnable next, int requestCode) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingAfterNearby = next;
                requestPermissions(
                        new String[]{android.Manifest.permission.NEARBY_WIFI_DEVICES},
                        requestCode);
                Toast.makeText(this, R.string.qr_permission_nearby, Toast.LENGTH_LONG).show();
                // Keep buttons disabled until permission result re-enables or proceeds.
                return;
            }
        }
        next.run();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        if (requestCode == REQ_NEARBY_PAIR
                || requestCode == REQ_NEARBY_DISCOVER
                || requestCode == REQ_NEARBY_RECONNECT) {
            boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            Runnable pending = pendingAfterNearby;
            pendingAfterNearby = null;
            if (ok && pending != null) {
                pending.run();
            } else {
                Toast.makeText(this, R.string.qr_permission_nearby, Toast.LENGTH_LONG).show();
                reenableButtons();
            }
        }
    }

    private void setBusy(boolean busy) {
        if (isFinishing() || isDestroyed()) return;
        boolean enable = !busy && adb != null;
        pairButton.setEnabled(enable);
        discoverPairButton.setEnabled(enable);
        reconnectByIpButton.setEnabled(enable);
        directConnectButton.setEnabled(enable);
    }

    private void reenableButtons() {
        setBusy(false);
    }

    private void discoverPairingAndChoose() {
        try {
            List<AdbDiscovery.Endpoint> found =
                    AdbDiscovery.discoverPairing(getApplicationContext(), PAIRING_DISCOVER_MS);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                reenableButtons();
                if (found.isEmpty()) {
                    Toast.makeText(this, R.string.discover_pairing_none, Toast.LENGTH_LONG).show();
                    return;
                }
                if (found.size() == 1) {
                    applyPairingEndpoint(found.get(0));
                    Toast.makeText(this, R.string.discover_pairing_filled, Toast.LENGTH_SHORT).show();
                    return;
                }
                CharSequence[] labels = new CharSequence[found.size()];
                for (int i = 0; i < found.size(); i++) {
                    labels[i] = found.get(i).toString();
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.discover_pairing_title)
                        .setItems(labels, (d, which) -> {
                            applyPairingEndpoint(found.get(which));
                            Toast.makeText(this, R.string.discover_pairing_filled,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                reenableButtons();
                Toast.makeText(this, R.string.discover_pairing_none, Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) {
            Log.e(e, "discover pairing failed");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                reenableButtons();
                Toast.makeText(this, getString(R.string.discover_pairing_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void applyPairingEndpoint(AdbDiscovery.Endpoint ep) {
        String addr = ep.host.indexOf(':') < 0
                ? ep.host + ":" + ep.port
                : "[" + ep.host + "]:" + ep.port;
        pairAddress.setText(addr);
        pairPort.setText(String.valueOf(ep.port));
    }

    // Rediscover connect port for an already-paired host (IP only) via mDNS
    // _adb-tls-connect._tcp, connect, upsert discovered host:port, toast,
    // optionally open Mirror. Never locks a fixed tcpip port.
    private void reconnectByIp(String ip, boolean openMirror) {
        try {
            Log.i("reconnect-by-ip: discover connect for %s", ip);
            AdbDiscovery.Endpoint ep = AdbDiscovery.discoverConnectForHost(
                    getApplicationContext(), ip, RECONNECT_DISCOVER_MS);
            if (ep == null) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, R.string.reconnect_connect_none, Toast.LENGTH_LONG).show();
                    reenableButtons();
                });
                return;
            }

            Devices.Device connect = new Devices.Device(ep.host, ep.port);
            Log.i("reconnect-by-ip: connect %s", connect);
            adb.connect(connect.host, connect.port);

            try { adb.disconnect(); } catch (Exception ignored) {}

            List<Devices.Device> updated = Devices.upsertHost(getApplicationContext(), connect);
            Devices.Device saved = connect;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showDevices(updated);
                reconnectIp.setText(AdbDiscovery.isIpv4(saved.host)
                        ? AdbDiscovery.normalizeIpv4(saved.host)
                        : saved.host);
                Toast.makeText(this, getString(R.string.reconnect_ok, saved.toString()),
                        Toast.LENGTH_SHORT).show();
                reenableButtons();
                if (openMirror) {
                    Intent i = new Intent(this, Mirror.class);
                    i.putExtra(Mirror.EXTRA_HOST, saved.host);
                    i.putExtra(Mirror.EXTRA_PORT, saved.port);
                    startActivity(i);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { adb.disconnect(); } catch (Exception ignored) {}
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, R.string.reconnect_connect_none, Toast.LENGTH_LONG).show();
                reenableButtons();
            });
        } catch (Exception e) {
            Log.e(e, "reconnect-by-ip failed");
            try { adb.disconnect(); } catch (Exception ignored) {}
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, getString(R.string.reconnect_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
                reenableButtons();
            });
        }
    }

    static Devices.Device parseDirectAddress(String raw) {
        return Devices.parseDirect(raw, Adb.LEGACY_TCPIP_PORT);
    }

    private Devices.Device savedLegacyFor(String ip) {
        try {
            for (Devices.Device d : Devices.load(this)) {
                if (d.port != Adb.LEGACY_TCPIP_PORT) continue;
                String h = AdbDiscovery.isIpv4(d.host) ? AdbDiscovery.normalizeIpv4(d.host) : d.host;
                if (ip.equals(h)) return d;
            }
        } catch (IOException e) {
            Log.w("direct: load devices: %s", e);
        }
        return null;
    }

    private void showDirectHowto() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.direct_howto_title)
                .setMessage(R.string.direct_howto_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // Plain ADB TCP + RSA AUTH (adb tcpip) or TLS - whatever the target speaks.
    // No pairing, no mDNS. Saves host:port and opens Mirror on success.
    private void startDirectConnect(Devices.Device d) {
        if (adb == null) return;
        setBusy(true);
        directAddress.setText(d.toString());
        Toast.makeText(this, getString(R.string.direct_connecting, d.toString()),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                adb.connect(d.host, d.port, authPrompt);
                try { adb.disconnect(); } catch (Exception ignored) {}
                List<Devices.Device> updated = Devices.upsertHost(getApplicationContext(), d);
                Settings.prefs(this).edit().putString(PREF_LAST_DIRECT, d.toString()).apply();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    showDevices(updated);
                    if (AdbDiscovery.isIpv4(d.host)) {
                        reconnectIp.setText(AdbDiscovery.normalizeIpv4(d.host));
                    }
                    Toast.makeText(this, getString(R.string.direct_ok, d.toString()),
                            Toast.LENGTH_SHORT).show();
                    reenableButtons();
                    Intent i = new Intent(this, Mirror.class);
                    i.putExtra(Mirror.EXTRA_HOST, d.host);
                    i.putExtra(Mirror.EXTRA_PORT, d.port);
                    startActivity(i);
                });
            } catch (Exception e) {
                Log.e(e, "direct connect %s failed", d);
                try { adb.disconnect(); } catch (Exception ignored) {}
                String msg = String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (msg.contains("adb auth not allowed")) {
                        Toast.makeText(this, R.string.auth_denied, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, getString(R.string.direct_failed, msg),
                                Toast.LENGTH_LONG).show();
                    }
                    reenableButtons();
                });
            }
        }, "direct-connect").start();
    }

    // Rebuild the saved-device rows. There is no adapter: the whole page
    // is one ScrollView, a ListView cannot measure itself inside one, and
    // a handful of rows does not need recycling.
    private void showDevices(List<Devices.Device> devices) {
        deviceList.removeAllViews();
        devicesEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        for (Devices.Device d : devices) {
            View row = getLayoutInflater().inflate(R.layout.device_row, deviceList, false);
            ((TextView) row.findViewById(R.id.device_label)).setText(d.toString());
            row.setOnClickListener(v -> {
                Log.i("connect tap: %s", d);
                Intent i = new Intent(this, Mirror.class);
                i.putExtra(Mirror.EXTRA_HOST, d.host);
                i.putExtra(Mirror.EXTRA_PORT, d.port);
                startActivity(i);
            });
            row.setOnLongClickListener(v -> {
                showDeviceActions(d);
                return true;
            });
            deviceList.addView(row);
        }
    }

    private void showDeviceActions(Devices.Device d) {
        final ScrcpyHost.DeviceActions host = ScrcpyHost.deviceActions();
        CharSequence[] items = host == null
                ? new CharSequence[] {
                        getString(R.string.rediscover_port),
                        getString(R.string.forget_device),
                }
                : new CharSequence[] {
                        getString(R.string.rediscover_port),
                        getString(R.string.forget_device),
                        getString(R.string.device_open_tools),
                        getString(R.string.device_browse_files),
                };
        new AlertDialog.Builder(this)
                .setTitle(R.string.device_actions_title)
                .setItems(items, (dlg, which) -> {
                    if (which == 0) {
                        rediscoverPortForDevice(d);
                    } else if (which == 1) {
                        confirmForget(d);
                    } else if (which == 2 && host != null) {
                        host.openTools(this, d.host, d.port, null);
                    } else if (which == 3 && host != null) {
                        host.browseFiles(this, d.host, d.port, null);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void rediscoverPortForDevice(Devices.Device d) {
        if (adb == null) return;
        if (d.port == Adb.LEGACY_TCPIP_PORT) {
            startDirectConnect(d);
            return;
        }
        String host = d.host;
        String ip = AdbDiscovery.normalizeIpv4(host);
        if (ip == null) {
            // Still allow hostname / non-normalized: discovery hostsMatch can resolve.
            if (!looksLikeHostOnly(host)) {
                Toast.makeText(this, R.string.bad_reconnect_ip, Toast.LENGTH_LONG).show();
                return;
            }
            ip = host;
        }
        reconnectIp.setText(ip);
        // From a saved row: rediscover + save, then offer mirror via auto-open.
        startReconnectByIp(ip, openMirrorAfterReconnect);
    }

    private void confirmForget(Devices.Device d) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.forget_device)
                .setMessage(d.toString())
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    Log.i("forget device: %s", d);
                    try {
                        showDevices(Devices.remove(this, d));
                    } catch (Exception e) {
                        Log.e(e, "forget device: save failed");
                        Toast.makeText(this, R.string.could_not_save_device_list,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pairAndSave(String host, int pairPort, String code,
                             Devices.Device explicitConnect) {
        try {
            Log.i("pair: %s:%d", host, pairPort);
            adb.pairDevice(host, pairPort, code);
            Log.i("pair ok host=%s pair_port=%d", host, pairPort);

            Devices.Device connect = explicitConnect;
            if (connect == null) {
                Log.i("pair: discovering connect endpoint for %s", host);
                AdbDiscovery.Endpoint ep = AdbDiscovery.discoverConnect(
                        getApplicationContext(), host, CONNECT_DISCOVER_MS);
                if (ep == null) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(this, R.string.pair_ok_no_connect, Toast.LENGTH_LONG).show();
                        reenableButtons();
                    });
                    return;
                }
                connect = new Devices.Device(ep.host, ep.port);
                Devices.Device fill = connect;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    connectAddress.setText(fill.toString());
                });
            }

            boolean verified = false;
            try {
                Log.i("pair: probe connect %s", connect);
                adb.connect(connect.host, connect.port);
                verified = true;
                Log.i("pair: probe connect ok");
                try { adb.disconnect(); } catch (Exception ignored) {}
            } catch (Exception probe) {
                Log.w("pair: probe connect failed (still saving): %s", probe);
                try { adb.disconnect(); } catch (Exception ignored) {}
            }

            List<Devices.Device> updated = Devices.upsertHost(getApplicationContext(), connect);
            Devices.Device saved = connect;
            boolean ok = verified;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showDevices(updated);
                if (AdbDiscovery.isIpv4(saved.host)) {
                    reconnectIp.setText(AdbDiscovery.normalizeIpv4(saved.host));
                }
                if (ok) {
                    Toast.makeText(this, R.string.paired_and_saved, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.paired_saved_verify_failed, Toast.LENGTH_LONG).show();
                }
                reenableButtons();
            });
        } catch (Exception e) {
            Log.e(e, "pair failed");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, getString(R.string.pair_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
                reenableButtons();
            });
        }
    }
}

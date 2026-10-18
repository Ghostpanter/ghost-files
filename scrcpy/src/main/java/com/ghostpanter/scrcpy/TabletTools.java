package com.ghostpanter.scrcpy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

// Owns the tablet split-pane tools UI (file browser / app manager / device
// debug / terminal). Bound from Mirror when layout-sw600dp or layout-w600dp
// provides R.id.tools_pane (auto-detect + resource qualifiers).
public final class TabletTools {

    static final int RQ_PUSH_FILE = 7101;
    static final int RQ_PULL_FILE = 7102;
    static final int RQ_INSTALL_APK = 7103;

    private static final String REMOTE_APK = "/data/local/tmp/scrcpy-install.apk";

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tablet-tools");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean destroyed = new AtomicBoolean();

    private volatile Adb adb;
    private volatile AdbRemote remote;

    private View toolsPane;
    private View panelFiles, panelApps, panelDebug, panelTerminal;
    private TextView navFiles, navApps, navDebug, navTerminal;

    // Files
    private EditText filesPath;
    private TextView filesStatus;
    private ListView filesList;
    private final List<AdbRemote.FileEntry> fileEntries = new ArrayList<>();
    private FileAdapter fileAdapter;
    private String currentDir = "/sdcard";
    private String selectedRemoteFile;

    // Apps
    private EditText appsSearch;
    private CheckBox appsUser, appsSystem;
    private TextView appsStatus;
    private ListView appsList;
    private final List<AdbRemote.AppInfo> allApps = new ArrayList<>();
    private final List<AdbRemote.AppInfo> shownApps = new ArrayList<>();
    private AppAdapter appAdapter;

    // Debug
    private TextView debugPhysicalSize, debugCurrentSize;
    private TextView debugPhysicalDpi, debugCurrentDpi, debugStatus;
    private EditText debugWidth, debugHeight, debugDpi;
    private AdbRemote.DisplaySize lastSize;
    private AdbRemote.DisplayDensity lastDpi;

    // Terminal (ssh-pad-like UX over adb interactive shell:)
    private EditText terminalInput;
    private TextView terminalOutput, terminalStatus, terminalTitle;
    private View terminalStatusDot;
    private TextView terminalKeyCtrl, terminalKeyAlt, terminalKbdBtn;
    private ScrollView terminalScroll;
    private final StringBuilder terminalLog = new StringBuilder();
    private static final int TERMINAL_MAX_CHARS = 200_000;
    private final AtomicReference<AdbRemote.ShellSession> shellSession = new AtomicReference<>();
    private final Object shellStartLock = new Object();
    private final AtomicInteger shellGen = new AtomicInteger();
    private boolean terminalCtrlSticky;
    private boolean terminalAltSticky;
    private boolean terminalSoftImeForced;

    public TabletTools(Activity activity) {
        this.activity = activity;
    }

    public boolean bind() {
        toolsPane = activity.findViewById(R.id.tools_pane);
        if (toolsPane == null) return false;

        panelFiles = activity.findViewById(R.id.panel_files);
        panelApps = activity.findViewById(R.id.panel_apps);
        panelDebug = activity.findViewById(R.id.panel_debug);
        panelTerminal = activity.findViewById(R.id.panel_terminal);
        navFiles = activity.findViewById(R.id.nav_files);
        navApps = activity.findViewById(R.id.nav_apps);
        navDebug = activity.findViewById(R.id.nav_debug);
        navTerminal = activity.findViewById(R.id.nav_terminal);

        navFiles.setOnClickListener(v -> showPanel(0));
        navApps.setOnClickListener(v -> showPanel(1));
        navDebug.setOnClickListener(v -> showPanel(2));
        navTerminal.setOnClickListener(v -> showPanel(3));

        bindFiles();
        bindApps();
        bindDebug();
        bindTerminal();
        showPanel(0);
        return true;
    }

    public void setAdb(Adb adb) {
        this.adb = adb;
        this.remote = adb == null ? null : new AdbRemote(adb);
        closeShellSession();
        if (remote != null) {
            refreshFiles();
            refreshApps();
            refreshDebug();
            startShellSession();
        } else {
            setTerminalPhase(PHASE_IDLE, activity.getString(R.string.files_need_connection));
        }
    }

    public void destroy() {
        destroyed.set(true);
        closeShellSession();
        io.shutdownNow();
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return requestCode == RQ_PUSH_FILE || requestCode == RQ_PULL_FILE
                    || requestCode == RQ_INSTALL_APK;
        }
        Uri uri = data.getData();
        if (requestCode == RQ_PUSH_FILE) {
            pushUri(uri);
            return true;
        }
        if (requestCode == RQ_PULL_FILE) {
            pullToUri(uri);
            return true;
        }
        if (requestCode == RQ_INSTALL_APK) {
            installUri(uri);
            return true;
        }
        return false;
    }

    // ---- navigation ----

    private void showPanel(int index) {
        panelFiles.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelApps.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelDebug.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        panelTerminal.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        navFiles.setSelected(index == 0);
        navApps.setSelected(index == 1);
        navDebug.setSelected(index == 2);
        navTerminal.setSelected(index == 3);
        if (index == 1 && allApps.isEmpty() && remote != null) refreshApps();
        if (index == 2 && lastSize == null && remote != null) refreshDebug();
        if (index == 3 && remote != null) {
            AdbRemote.ShellSession s = shellSession.get();
            if (s == null || !s.isOpen()) startShellSession();
        }
    }

    // ---- files ----

    private void bindFiles() {
        filesPath = activity.findViewById(R.id.files_path);
        filesStatus = activity.findViewById(R.id.files_status);
        filesList = activity.findViewById(R.id.files_list);
        fileAdapter = new FileAdapter();
        filesList.setAdapter(fileAdapter);

        filesPath.setText(currentDir);
        filesPath.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigateTo(filesPath.getText().toString());
                return true;
            }
            return false;
        });

        activity.findViewById(R.id.files_up).setOnClickListener(v ->
                navigateTo(AdbRemote.parentPath(currentDir)));
        activity.findViewById(R.id.files_refresh).setOnClickListener(v -> refreshFiles());
        activity.findViewById(R.id.files_quick_root).setOnClickListener(v -> navigateTo("/"));
        activity.findViewById(R.id.files_quick_sdcard).setOnClickListener(v -> navigateTo("/sdcard"));
        activity.findViewById(R.id.files_quick_download).setOnClickListener(v ->
                navigateTo("/sdcard/Download"));
        activity.findViewById(R.id.files_quick_pictures).setOnClickListener(v ->
                navigateTo("/sdcard/Pictures"));
        activity.findViewById(R.id.files_quick_documents).setOnClickListener(v ->
                navigateTo("/sdcard/Documents"));

        activity.findViewById(R.id.files_push).setOnClickListener(v -> {
            if (!ensureRemote()) return;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(Intent.createChooser(i, activity.getString(R.string.files_push)),
                    RQ_PUSH_FILE);
        });
        activity.findViewById(R.id.files_pull).setOnClickListener(v -> {
            if (!ensureRemote()) return;
            if (selectedRemoteFile == null) {
                toast(R.string.files_select_first);
                return;
            }
            // Default: save into the controller's public Download folder.
            // Long-press keeps SAF "Save as…" for picking another location.
            pullToDownloads();
        });
        activity.findViewById(R.id.files_pull).setOnLongClickListener(v -> {
            if (!ensureRemote()) return true;
            if (selectedRemoteFile == null) {
                toast(R.string.files_select_first);
                return true;
            }
            String name = selectedRemoteFile.substring(selectedRemoteFile.lastIndexOf('/') + 1);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_TITLE, name);
            activity.startActivityForResult(i, RQ_PULL_FILE);
            return true;
        });

        filesList.setOnItemClickListener((parent, view, position, id) -> {
            AdbRemote.FileEntry e = fileEntries.get(position);
            if (e.directory) {
                navigateTo(AdbRemote.joinPath(currentDir, e.name));
            } else {
                selectedRemoteFile = AdbRemote.joinPath(currentDir, e.name);
                fileAdapter.notifyDataSetChanged();
                filesStatus.setText(selectedRemoteFile);
            }
        });
    }

    private void navigateTo(String path) {
        currentDir = AdbRemote.normalizePath(path);
        selectedRemoteFile = null;
        filesPath.setText(currentDir);
        refreshFiles();
    }

    private void refreshFiles() {
        AdbRemote r = remote;
        if (r == null) {
            filesStatus.setText(R.string.files_need_connection);
            return;
        }
        filesStatus.setText(R.string.files_status_loading);
        final String dir = currentDir;
        io.execute(() -> {
            try {
                List<AdbRemote.FileEntry> list = r.listDir(dir);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    fileEntries.clear();
                    fileEntries.addAll(list);
                    fileAdapter.notifyDataSetChanged();
                    filesStatus.setText(activity.getString(R.string.files_status_count, list.size()));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_error, msg(e)));
                });
            }
        });
    }

    private void pushUri(Uri uri) {
        AdbRemote r = remote;
        if (r == null) { toast(R.string.files_need_connection); return; }
        String rawName = displayName(uri);
        if (rawName == null || rawName.isEmpty()) rawName = "upload.bin";
        final String name = rawName;
        final String remotePath = AdbRemote.joinPath(currentDir, name);
        filesStatus.setText(R.string.files_status_loading);
        io.execute(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open " + uri);
                long n = r.push(in, remotePath);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_push_ok, name, (int) Math.min(n, Integer.MAX_VALUE)));
                    refreshFiles();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_error, msg(e)));
                });
            }
        });
    }

    private void pullToUri(Uri uri) {
        AdbRemote r = remote;
        if (r == null || selectedRemoteFile == null) return;
        final String remotePath = selectedRemoteFile;
        filesStatus.setText(R.string.files_status_loading);
        io.execute(() -> {
            try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("cannot open " + uri);
                long n = r.pull(remotePath, out);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_pull_ok, remotePath,
                            (int) Math.min(n, Integer.MAX_VALUE)));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_error, msg(e)));
                });
            }
        });
    }

    /** Pull selected remote file into the controller's public Download directory. */
    private void pullToDownloads() {
        AdbRemote r = remote;
        if (r == null || selectedRemoteFile == null) return;
        final String remotePath = selectedRemoteFile;
        String rawName = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        if (rawName.isEmpty()) rawName = "download.bin";
        final String name = rawName.replace('/', '_').replace('\\', '_');
        filesStatus.setText(R.string.files_status_loading);
        io.execute(() -> {
            Uri pendingUri = null;
            ContentResolver resolver = activity.getContentResolver();
            try {
                long n;
                String localLabel;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    values.put(MediaStore.Downloads.MIME_TYPE, guessMime(name));
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    pendingUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (pendingUri == null) throw new IOException("MediaStore insert failed");
                    try (OutputStream out = resolver.openOutputStream(pendingUri)) {
                        if (out == null) throw new IOException("cannot open " + pendingUri);
                        n = r.pull(remotePath, out);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(pendingUri, done, null, null);
                    pendingUri = null;
                    localLabel = Environment.DIRECTORY_DOWNLOADS + "/" + name;
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) throw new IOException("Downloads unavailable");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException("cannot create Downloads");
                    }
                    File dest = uniqueDownloadFile(dir, name);
                    try (OutputStream out = new FileOutputStream(dest)) {
                        n = r.pull(remotePath, out);
                    }
                    localLabel = dest.getAbsolutePath();
                }
                long bytes = n;
                String label = localLabel;
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_pull_download_ok,
                            label, (int) Math.min(bytes, Integer.MAX_VALUE)));
                    toast(R.string.files_pull_download_toast);
                });
            } catch (Exception e) {
                if (pendingUri != null) {
                    try { resolver.delete(pendingUri, null, null); } catch (Exception ignored) {}
                }
                ui.post(() -> {
                    if (destroyed.get()) return;
                    filesStatus.setText(activity.getString(R.string.files_error, msg(e)));
                    // API 28 without storage permission: offer SAF save-as.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        String n2 = selectedRemoteFile == null ? name
                                : selectedRemoteFile.substring(selectedRemoteFile.lastIndexOf('/') + 1);
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.setType("*/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.putExtra(Intent.EXTRA_TITLE, n2);
                        try {
                            activity.startActivityForResult(i, RQ_PULL_FILE);
                        } catch (Exception ignored) {}
                    }
                });
            }
        });
    }

    private static File uniqueDownloadFile(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File cand = new File(dir, base + "-" + i + ext);
            if (!cand.exists()) return cand;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static String guessMime(String name) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(name.replace(" ", "_"));
        if (ext == null || ext.isEmpty()) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) ext = name.substring(dot + 1);
        }
        if (ext != null && !ext.isEmpty()) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    // ---- apps ----

    private void bindApps() {
        appsSearch = activity.findViewById(R.id.apps_search);
        appsUser = activity.findViewById(R.id.apps_user);
        appsSystem = activity.findViewById(R.id.apps_system);
        appsStatus = activity.findViewById(R.id.apps_status);
        appsList = activity.findViewById(R.id.apps_list);
        appAdapter = new AppAdapter();
        appsList.setAdapter(appAdapter);

        appsSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filterApps(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        appsUser.setOnCheckedChangeListener((b, c) -> filterApps());
        appsSystem.setOnCheckedChangeListener((b, c) -> {
            if (c && allApps.stream().noneMatch(a -> a.system) && remote != null) {
                refreshApps();
            } else {
                filterApps();
            }
        });
        activity.findViewById(R.id.apps_refresh).setOnClickListener(v -> refreshApps());
        activity.findViewById(R.id.apps_install).setOnClickListener(v -> {
            if (!ensureRemote()) return;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("application/vnd.android.package-archive");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // Also allow */* for pickers that don't expose APK MIME
            Intent any = new Intent(Intent.ACTION_GET_CONTENT);
            any.setType("*/*");
            any.addCategory(Intent.CATEGORY_OPENABLE);
            Intent chooser = Intent.createChooser(i, activity.getString(R.string.apps_pick_apk));
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{any});
            activity.startActivityForResult(chooser, RQ_INSTALL_APK);
        });
    }

    private void refreshApps() {
        AdbRemote r = remote;
        if (r == null) {
            appsStatus.setText(R.string.files_need_connection);
            return;
        }
        appsStatus.setText(R.string.files_status_loading);
        final boolean withSystem = appsSystem.isChecked();
        io.execute(() -> {
            try {
                List<AdbRemote.AppInfo> list = r.listPackages(withSystem);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    allApps.clear();
                    allApps.addAll(list);
                    filterApps();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appsStatus.setText(activity.getString(R.string.apps_error, msg(e)));
                });
            }
        });
    }

    private void filterApps() {
        String q = appsSearch.getText() == null ? ""
                : appsSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean user = appsUser.isChecked();
        boolean system = appsSystem.isChecked();
        shownApps.clear();
        for (AdbRemote.AppInfo a : allApps) {
            if (a.system && !system) continue;
            if (!a.system && !user) continue;
            if (!q.isEmpty() && !a.packageName.toLowerCase(Locale.ROOT).contains(q)) continue;
            shownApps.add(a);
        }
        appAdapter.notifyDataSetChanged();
        appsStatus.setText(activity.getString(R.string.apps_status_count, shownApps.size()));
    }

    private void installUri(Uri uri) {
        AdbRemote r = remote;
        if (r == null) { toast(R.string.files_need_connection); return; }
        appsStatus.setText(R.string.apps_installing);
        io.execute(() -> {
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("cannot open " + uri);
                r.push(in, REMOTE_APK);
                String result = r.installApk(REMOTE_APK);
                try { r.shell("rm -f " + AdbRemote.singleQuote(REMOTE_APK), true); }
                catch (Exception ignored) {}
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appsStatus.setText(activity.getString(R.string.apps_install_ok, result));
                    refreshApps();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appsStatus.setText(activity.getString(R.string.apps_error, msg(e)));
                });
            }
        });
    }

    // ---- debug ----

    private void bindDebug() {
        debugPhysicalSize = activity.findViewById(R.id.debug_physical_size);
        debugCurrentSize = activity.findViewById(R.id.debug_current_size);
        debugPhysicalDpi = activity.findViewById(R.id.debug_physical_dpi);
        debugCurrentDpi = activity.findViewById(R.id.debug_current_dpi);
        debugWidth = activity.findViewById(R.id.debug_width);
        debugHeight = activity.findViewById(R.id.debug_height);
        debugDpi = activity.findViewById(R.id.debug_dpi);
        debugStatus = activity.findViewById(R.id.debug_status);

        activity.findViewById(R.id.debug_refresh).setOnClickListener(v -> refreshDebug());
        activity.findViewById(R.id.debug_apply_size).setOnClickListener(v -> applySize());
        activity.findViewById(R.id.debug_reset_size).setOnClickListener(v -> resetSize());
        activity.findViewById(R.id.debug_apply_dpi).setOnClickListener(v -> applyDpi());
        activity.findViewById(R.id.debug_reset_dpi).setOnClickListener(v -> resetDpi());
    }

    private void refreshDebug() {
        AdbRemote r = remote;
        if (r == null) {
            debugStatus.setText(R.string.files_need_connection);
            return;
        }
        debugStatus.setText(R.string.files_status_loading);
        io.execute(() -> {
            try {
                AdbRemote.DisplaySize size = r.readSize();
                AdbRemote.DisplayDensity dens = r.readDensity();
                ui.post(() -> {
                    if (destroyed.get()) return;
                    lastSize = size;
                    lastDpi = dens;
                    debugPhysicalSize.setText(activity.getString(
                            R.string.debug_physical_size, size.physicalLabel()));
                    debugCurrentSize.setText(activity.getString(
                            R.string.debug_current_size, size.currentLabel()));
                    debugPhysicalDpi.setText(activity.getString(
                            R.string.debug_physical_dpi, dens.physical));
                    debugCurrentDpi.setText(activity.getString(
                            R.string.debug_current_dpi, dens.current));
                    // Prefer physical values in the editors (native resolution first).
                    debugWidth.setText(String.valueOf(size.physicalW));
                    debugHeight.setText(String.valueOf(size.physicalH));
                    debugDpi.setText(String.valueOf(dens.physical));
                    debugStatus.setText(R.string.files_status_ready);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    private void applySize() {
        AdbRemote r = remote;
        if (r == null) { toast(R.string.files_need_connection); return; }
        int w, h;
        try {
            w = Integer.parseInt(debugWidth.getText().toString().trim());
            h = Integer.parseInt(debugHeight.getText().toString().trim());
        } catch (Exception e) {
            toast(R.string.debug_bad_size);
            return;
        }
        io.execute(() -> {
            try {
                r.applySize(w, h);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(R.string.debug_applied);
                    refreshDebug();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    private void resetSize() {
        AdbRemote r = remote;
        if (r == null) return;
        io.execute(() -> {
            try {
                r.resetSize();
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(R.string.debug_reset_ok);
                    refreshDebug();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    private void applyDpi() {
        AdbRemote r = remote;
        if (r == null) return;
        int dpi;
        try {
            dpi = Integer.parseInt(debugDpi.getText().toString().trim());
        } catch (Exception e) {
            toast(R.string.debug_bad_dpi);
            return;
        }
        if (dpi < 72 || dpi > 640) {
            toast(R.string.debug_bad_dpi);
            return;
        }
        io.execute(() -> {
            try {
                r.applyDensity(dpi);
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(R.string.debug_applied);
                    refreshDebug();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    private void resetDpi() {
        AdbRemote r = remote;
        if (r == null) return;
        io.execute(() -> {
            try {
                r.resetDensity();
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(R.string.debug_reset_ok);
                    refreshDebug();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    debugStatus.setText(activity.getString(R.string.debug_error, msg(e)));
                });
            }
        });
    }

    // ---- helpers ----

    // ---- terminal (ssh-pad-flutter UX over adb shell:) ----

    private void bindTerminal() {
        terminalInput = activity.findViewById(R.id.terminal_input);
        terminalOutput = activity.findViewById(R.id.terminal_output);
        terminalStatus = activity.findViewById(R.id.terminal_status);
        terminalTitle = activity.findViewById(R.id.terminal_title);
        terminalStatusDot = activity.findViewById(R.id.terminal_status_dot);
        terminalScroll = activity.findViewById(R.id.terminal_scroll);
        terminalKeyCtrl = activity.findViewById(R.id.terminal_key_ctrl);
        terminalKeyAlt = activity.findViewById(R.id.terminal_key_alt);
        terminalKbdBtn = activity.findViewById(R.id.terminal_kbd);

        View run = activity.findViewById(R.id.terminal_run);
        if (run != null) run.setOnClickListener(v -> sendTerminalLine());
        View disconnect = activity.findViewById(R.id.terminal_disconnect);
        if (disconnect != null) disconnect.setOnClickListener(v -> disconnectTerminal());
        View more = activity.findViewById(R.id.terminal_more);
        if (more != null) more.setOnClickListener(this::showTerminalMenu);
        if (terminalKbdBtn != null) {
            terminalKbdBtn.setOnClickListener(v -> toggleSoftKeyboard());
        }
        View scroll = terminalScroll;
        if (scroll != null) {
            scroll.setOnClickListener(v -> summonSoftKeyboard());
        }
        if (terminalOutput != null) {
            terminalOutput.setOnClickListener(v -> summonSoftKeyboard());
        }

        bindExtraKeys();

        terminalInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendTerminalLine();
                return true;
            }
            return false;
        });
        setTerminalPhase(PHASE_IDLE, activity.getString(R.string.terminal_status_ready));
        if (remote != null) startShellSession();
        else setTerminalPhase(PHASE_IDLE, activity.getString(R.string.files_need_connection));
    }

    private void bindExtraKeys() {
        setExtraKey(R.id.terminal_key_kbd, v -> toggleSoftKeyboard());
        setExtraKey(R.id.terminal_key_esc, v -> sendTerminalBytes(new byte[]{0x1b}));
        setExtraKey(R.id.terminal_key_tab, v -> sendTerminalBytes(new byte[]{0x09}));
        if (terminalKeyCtrl != null) {
            terminalKeyCtrl.setOnClickListener(v -> {
                terminalCtrlSticky = !terminalCtrlSticky;
                refreshModKeys();
            });
        }
        if (terminalKeyAlt != null) {
            terminalKeyAlt.setOnClickListener(v -> {
                terminalAltSticky = !terminalAltSticky;
                refreshModKeys();
            });
        }
        setExtraKey(R.id.terminal_key_up, v -> sendAnsi("\u001b[A"));
        setExtraKey(R.id.terminal_key_down, v -> sendAnsi("\u001b[B"));
        setExtraKey(R.id.terminal_key_right, v -> sendAnsi("\u001b[C"));
        setExtraKey(R.id.terminal_key_left, v -> sendAnsi("\u001b[D"));
        setExtraKey(R.id.terminal_key_home, v -> sendAnsi("\u001b[H"));
        setExtraKey(R.id.terminal_key_end, v -> sendAnsi("\u001b[F"));
        setExtraKey(R.id.terminal_key_ctrl_c, v -> sendTerminalInterrupt());
        setExtraKey(R.id.terminal_key_ctrl_d, v -> sendCtrlLetter('D'));
        setExtraKey(R.id.terminal_key_ctrl_z, v -> sendCtrlLetter('Z'));
        setExtraKey(R.id.terminal_key_pipe, v -> sendOrInsert("|"));
        setExtraKey(R.id.terminal_key_tilde, v -> sendOrInsert("~"));
        // Also wire legacy id if present in older inflates (no-op when missing).
        View legacyCtrlC = activity.findViewById(R.id.terminal_key_ctrl_c);
        if (legacyCtrlC == null) {
            View old = activity.findViewById(getIdQuiet("terminal_ctrl_c"));
            if (old != null) old.setOnClickListener(v -> sendTerminalInterrupt());
        }
    }

    private int getIdQuiet(String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private void setExtraKey(int id, View.OnClickListener listener) {
        View v = activity.findViewById(id);
        if (v != null) v.setOnClickListener(listener);
    }

    private void refreshModKeys() {
        styleModKey(terminalKeyCtrl, terminalCtrlSticky);
        styleModKey(terminalKeyAlt, terminalAltSticky);
        if (terminalKbdBtn != null) {
            terminalKbdBtn.setTextColor(activity.getColor(
                    terminalSoftImeForced ? R.color.terminal_accent : R.color.terminal_fg));
        }
        View extraKbd = activity.findViewById(R.id.terminal_key_kbd);
        if (extraKbd instanceof TextView) {
            TextView tv = (TextView) extraKbd;
            tv.setBackgroundResource(terminalSoftImeForced
                    ? R.drawable.bg_terminal_key_active
                    : R.drawable.bg_terminal_key);
            tv.setTextColor(activity.getColor(
                    terminalSoftImeForced ? R.color.terminal_accent : R.color.terminal_fg));
        }
    }

    private void styleModKey(TextView key, boolean active) {
        if (key == null) return;
        key.setBackgroundResource(active
                ? R.drawable.bg_terminal_key_active
                : R.drawable.bg_terminal_key);
        key.setTextColor(activity.getColor(
                active ? R.color.terminal_accent : R.color.terminal_fg));
    }

    private void clearMods() {
        if (terminalCtrlSticky || terminalAltSticky) {
            terminalCtrlSticky = false;
            terminalAltSticky = false;
            refreshModKeys();
        }
    }

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_CONNECTING = 1;
    private static final int PHASE_CONNECTED = 2;
    private static final int PHASE_ERROR = 3;

    private void setTerminalPhase(int phase, String label) {
        if (terminalStatus != null) {
            terminalStatus.setText(label);
            int color = R.color.terminal_status_idle;
            if (phase == PHASE_CONNECTING) color = R.color.terminal_status_connecting;
            else if (phase == PHASE_CONNECTED) color = R.color.terminal_status_connected;
            else if (phase == PHASE_ERROR) color = R.color.terminal_status_error;
            terminalStatus.setTextColor(activity.getColor(color));
            if (terminalStatusDot != null) {
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(activity.getColor(color));
                terminalStatusDot.setBackground(dot);
            }
        }
        if (terminalTitle != null && phase == PHASE_CONNECTED) {
            // Keep short title; status line carries detail.
            terminalTitle.setText(R.string.terminal_title);
        }
    }

    private void showTerminalMenu(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.terminal_menu_kbd);
        menu.getMenu().add(0, 2, 1, R.string.terminal_menu_paste);
        menu.getMenu().add(0, 3, 2, R.string.terminal_menu_clear);
        menu.getMenu().add(0, 4, 3, R.string.terminal_menu_reconnect);
        menu.getMenu().add(0, 5, 4, R.string.terminal_menu_disconnect);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) toggleSoftKeyboard();
            else if (id == 2) pasteIntoTerminal();
            else if (id == 3) clearTerminal();
            else if (id == 4) {
                closeShellSession();
                startShellSession();
            } else if (id == 5) disconnectTerminal();
            return true;
        });
        menu.show();
    }

    private void toggleSoftKeyboard() {
        terminalSoftImeForced = !terminalSoftImeForced;
        refreshModKeys();
        if (terminalSoftImeForced) summonSoftKeyboard();
        else hideSoftKeyboard();
    }

    private void summonSoftKeyboard() {
        if (terminalInput == null) return;
        terminalInput.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(terminalInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideSoftKeyboard() {
        if (terminalInput == null) return;
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(terminalInput.getWindowToken(), 0);
        }
    }

    private void pasteIntoTerminal() {
        ClipboardManager cm = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast(R.string.terminal_paste_empty);
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast(R.string.terminal_paste_empty);
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(activity);
        if (text == null || text.length() == 0) {
            toast(R.string.terminal_paste_empty);
            return;
        }
        String s = text.toString();
        // Multi-line paste: send as raw stdin (ssh-pad pastes into PTY).
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            sendTerminalRaw(s.replace("\r\n", "\n").replace('\r', '\n'));
        } else if (terminalInput != null) {
            int start = Math.max(terminalInput.getSelectionStart(), 0);
            int end = Math.max(terminalInput.getSelectionEnd(), 0);
            terminalInput.getText().replace(Math.min(start, end), Math.max(start, end), s);
        }
    }

    private void disconnectTerminal() {
        closeShellSession();
        setTerminalPhase(PHASE_IDLE, activity.getString(R.string.terminal_session_ended));
        appendTerminal("\n[" + activity.getString(R.string.terminal_session_ended) + "]\n");
    }

    private void clearTerminal() {
        terminalLog.setLength(0);
        if (terminalOutput != null) terminalOutput.setText("");
        AdbRemote.ShellSession s = shellSession.get();
        if (s != null && s.isOpen()) {
            setTerminalPhase(PHASE_CONNECTED,
                    activity.getString(R.string.terminal_status_connected_live));
        } else {
            setTerminalPhase(PHASE_IDLE, activity.getString(R.string.terminal_status_ready));
        }
    }

    private void startShellSession() {
        if (destroyed.get()) return;
        AdbRemote r = remote;
        if (r == null) {
            setTerminalPhase(PHASE_IDLE, activity.getString(R.string.files_need_connection));
            return;
        }
        io.execute(() -> {
            synchronized (shellStartLock) {
                if (destroyed.get()) return;
                AdbRemote.ShellSession existing = shellSession.get();
                if (existing != null && existing.isOpen()) return;
                closeShellSessionLocked();
                final int gen = shellGen.get();
                ui.post(() -> {
                    if (!destroyed.get()) {
                        setTerminalPhase(PHASE_CONNECTING,
                                activity.getString(R.string.terminal_status_connecting));
                    }
                });
                try {
                    final AtomicReference<AdbRemote.ShellSession> created =
                            new AtomicReference<>();
                    AdbRemote.ShellSession session = r.openShellSession(
                            new AdbRemote.ShellSession.Listener() {
                                @Override
                                public void onOutput(String chunk) {
                                    ui.post(() -> {
                                        if (destroyed.get()) return;
                                        appendTerminal(chunk);
                                    });
                                }

                                @Override
                                public void onClosed(String reason) {
                                    AdbRemote.ShellSession mine = created.get();
                                    if (mine != null) shellSession.compareAndSet(mine, null);
                                    if (gen != shellGen.get()) return;
                                    ui.post(() -> {
                                        if (destroyed.get()) return;
                                        if (gen != shellGen.get()) return;
                                        if (reason != null && !reason.isEmpty()) {
                                            appendTerminal("\n[" + activity.getString(
                                                    R.string.terminal_session_closed, reason) + "]\n");
                                            setTerminalPhase(PHASE_ERROR, activity.getString(
                                                    R.string.terminal_session_closed, reason));
                                        } else {
                                            appendTerminal("\n[" + activity.getString(
                                                    R.string.terminal_session_ended) + "]\n");
                                            setTerminalPhase(PHASE_IDLE,
                                                    activity.getString(R.string.terminal_status_ready));
                                        }
                                    });
                                }
                            });
                    created.set(session);
                    shellSession.set(session);
                    ui.post(() -> {
                        if (destroyed.get()) return;
                        setTerminalPhase(PHASE_CONNECTED,
                                activity.getString(R.string.terminal_status_connected_live));
                        appendTerminal(activity.getString(R.string.terminal_session_started) + "\n");
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        if (destroyed.get()) return;
                        setTerminalPhase(PHASE_ERROR,
                                activity.getString(R.string.terminal_error, msg(e)));
                        appendTerminal(activity.getString(R.string.terminal_error, msg(e)) + "\n");
                    });
                }
            }
        });
    }

    private void closeShellSession() {
        synchronized (shellStartLock) {
            closeShellSessionLocked();
        }
    }

    private void closeShellSessionLocked() {
        shellGen.incrementAndGet();
        AdbRemote.ShellSession s = shellSession.getAndSet(null);
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void sendTerminalLine() {
        if (!ensureRemote()) return;
        String raw = terminalInput.getText() == null ? "" : terminalInput.getText().toString();
        String cmd = sanitizeTerminalCommand(raw);
        AdbRemote.ShellSession s = shellSession.get();
        if (s == null || !s.isOpen()) {
            startShellSession();
            toast(R.string.terminal_status_connecting);
            return;
        }
        // Sticky Ctrl + single letter → control character (ssh-pad ExtraKeys feel).
        if (terminalCtrlSticky && cmd.length() == 1) {
            char ch = Character.toUpperCase(cmd.charAt(0));
            if (ch >= '@' && ch <= '_') {
                sendCtrlLetter(ch);
                terminalInput.setText("");
                return;
            }
        }
        final String toSend;
        if (terminalAltSticky && !cmd.isEmpty()) {
            // ESC-prefix each character (common readline meta binding).
            StringBuilder sb = new StringBuilder(cmd.length() * 2 + 1);
            for (int i = 0; i < cmd.length(); i++) {
                sb.append((char) 0x1b).append(cmd.charAt(i));
            }
            sb.append('\n');
            toSend = sb.toString();
        } else {
            toSend = cmd + "\n";
        }
        // Local echo of the command line (adb shell often has no PTY echo).
        appendTerminal(cmd + "\n");
        terminalInput.setText("");
        clearMods();
        setTerminalPhase(PHASE_CONNECTED,
                activity.getString(R.string.terminal_status_connected_live));
        io.execute(() -> {
            try {
                s.write(toSend);
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appendTerminal(activity.getString(R.string.terminal_error, msg(e)) + "\n");
                    setTerminalPhase(PHASE_ERROR,
                            activity.getString(R.string.terminal_error, msg(e)));
                    closeShellSession();
                    startShellSession();
                });
            }
        });
    }

    private void sendOrInsert(String token) {
        if (terminalInput != null && terminalInput.hasFocus()
                && terminalInput.getText() != null
                && terminalInput.getText().length() > 0) {
            int start = Math.max(terminalInput.getSelectionStart(), 0);
            int end = Math.max(terminalInput.getSelectionEnd(), 0);
            terminalInput.getText().replace(Math.min(start, end), Math.max(start, end), token);
            return;
        }
        sendTerminalRaw(token);
    }

    private void sendAnsi(String seq) {
        sendTerminalRaw(seq);
        clearMods();
    }

    private void sendCtrlLetter(char letter) {
        char upper = Character.toUpperCase(letter);
        byte ctrl = (byte) (upper & 0x1f);
        sendTerminalBytes(new byte[]{ctrl});
        clearMods();
    }

    private void sendTerminalRaw(String text) {
        if (text == null || text.isEmpty()) return;
        sendTerminalBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendTerminalBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        if (!ensureRemote()) return;
        AdbRemote.ShellSession s = shellSession.get();
        if (s == null || !s.isOpen()) {
            startShellSession();
            toast(R.string.terminal_status_connecting);
            return;
        }
        clearMods();
        io.execute(() -> {
            try {
                s.writeBytes(bytes);
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appendTerminal(activity.getString(R.string.terminal_error, msg(e)) + "\n");
                });
            }
        });
    }

    private void sendTerminalInterrupt() {
        AdbRemote.ShellSession s = shellSession.get();
        if (s == null || !s.isOpen()) {
            toast(R.string.files_need_connection);
            return;
        }
        appendTerminal("^C");
        clearMods();
        io.execute(() -> {
            try {
                s.sendInterrupt();
            } catch (Exception e) {
                ui.post(() -> {
                    if (destroyed.get()) return;
                    appendTerminal(activity.getString(R.string.terminal_error, msg(e)) + "\n");
                });
            }
        });
    }

    /** Strip habitual "adb " / "adb shell " prefixes; trim. Keep interior spaces. */
    private static String sanitizeTerminalCommand(String raw) {
        if (raw == null) return "";
        String s = raw;
        String trimmed = s.trim();
        if (trimmed.regionMatches(true, 0, "adb shell ", 0, 10)) {
            s = trimmed.substring(10);
        } else if (trimmed.regionMatches(true, 0, "adb ", 0, 4)) {
            String rest = trimmed.substring(4);
            if (rest.regionMatches(true, 0, "shell ", 0, 6)) {
                s = rest.substring(6);
            } else if (rest.equalsIgnoreCase("shell")) {
                s = "";
            } else {
                s = rest;
            }
        } else {
            s = trimmed;
        }
        return s;
    }

    private void appendTerminal(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        terminalLog.append(chunk);
        if (terminalLog.length() > TERMINAL_MAX_CHARS) {
            terminalLog.delete(0, terminalLog.length() - TERMINAL_MAX_CHARS);
        }
        if (terminalOutput != null) {
            terminalOutput.setText(terminalLog.toString());
        }
        if (terminalScroll != null) {
            terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private boolean ensureRemote() {
        if (remote == null) {
            toast(R.string.files_need_connection);
            return false;
        }
        return true;
    }

    private void toast(int res) {
        Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = activity.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null) {
            String path = uri.getLastPathSegment();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                name = slash >= 0 ? path.substring(slash + 1) : path;
            }
        }
        if (name != null) {
            // Strip path separators that could escape the destination dir.
            name = name.replace('/', '_').replace('\\', '_');
        }
        return name;
    }

    private final class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return fileEntries.size(); }
        @Override public AdbRemote.FileEntry getItem(int position) { return fileEntries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(activity).inflate(R.layout.file_row, parent, false);
            }
            AdbRemote.FileEntry e = getItem(position);
            TextView icon = v.findViewById(R.id.file_icon);
            TextView name = v.findViewById(R.id.file_name);
            icon.setText(e.directory ? "📁" : "📄");
            name.setText(e.name);
            String full = AdbRemote.joinPath(currentDir, e.name);
            boolean sel = !e.directory && full.equals(selectedRemoteFile);
            v.setBackgroundColor(sel
                    ? activity.getColor(R.color.sidebar_selected)
                    : 0x00000000);
            return v;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public AdbRemote.AppInfo getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(activity).inflate(R.layout.app_row, parent, false);
            }
            AdbRemote.AppInfo a = getItem(position);
            TextView name = v.findViewById(R.id.app_name);
            TextView pkg = v.findViewById(R.id.app_package);
            String label = a.packageName;
            int dot = label.lastIndexOf('.');
            name.setText(dot >= 0 ? label.substring(dot + 1) : label);
            pkg.setText(a.packageName + (a.system ? "  [system]" : ""));
            return v;
        }
    }
}

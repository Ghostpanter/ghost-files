package com.ghostpanter.scrcpy;

import java.io.BufferedReader;
import java.io.InputStreamReader;

// Instantiated by Shizuku in a shell-uid process on THIS device.
public final class ShellUserService extends IShellUserService.Stub {

    public ShellUserService() {
        Log.i("shizuku: ShellUserService created");
    }

    @Override
    public void destroy() {
        Log.i("shizuku: ShellUserService destroy");
        System.exit(0);
    }

    @Override
    public String exec(String command) {
        if (command == null || command.isEmpty() || command.length() > 2048) {
            return "";
        }
        try {
            Process p = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                    if (out.length() > 4096) break;
                }
            }
            p.destroy();
            return out.toString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}

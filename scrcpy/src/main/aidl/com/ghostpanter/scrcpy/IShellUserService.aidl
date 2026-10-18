package com.ghostpanter.scrcpy;

// Shizuku UserService binder. Runs as shell on the controller device.
interface IShellUserService {
    void destroy() = 16777114;
    String exec(String command) = 1;
}

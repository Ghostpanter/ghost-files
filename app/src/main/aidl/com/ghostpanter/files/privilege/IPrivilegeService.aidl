package com.ghostpanter.files.privilege;

// Shell-uid service started via app_process (embedded privilege). Same file ops as IFileService,
// plus a simple exec used when AdbShellRunner is unavailable. Only the owning app uid may call.
interface IPrivilegeService {
    void destroy() = 16777114;

    int version() = 1;
    ParcelFileDescriptor list(String path) = 2;
    String stat(String path) = 3;
    ParcelFileDescriptor openRead(String path) = 4;
    ParcelFileDescriptor openWrite(String path, long offset) = 5;
    String mkdirs(String path) = 6;
    String remove(String path, boolean recursive) = 7;
    String rename(String from, String to) = 8;
    // Run `sh -c` and return stdout+stderr (capped); empty script rejected.
    String exec(String script) = 9;
    // Owning app uid this server was started for (sanity / diagnostics).
    int ownerUid() = 10;
}

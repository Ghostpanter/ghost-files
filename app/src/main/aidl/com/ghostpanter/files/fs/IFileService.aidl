package com.ghostpanter.files.fs;

// Shizuku UserService: file access as the shell uid through java.io / java.nio, without spawning sh per call.
// Errors come back as text (null / "" = success), never as exceptions across Binder.
interface IFileService {
    // Shizuku calls this when the service is unbound / replaced.
    void destroy() = 16777114;

    int version() = 1;
    // "OK\n" + listing (ShellFs format) or "ERR\t…"; streamed through a pipe so big folders fit.
    ParcelFileDescriptor list(String path) = 2;
    String stat(String path) = 3;
    ParcelFileDescriptor openRead(String path) = 4;
    // offset > 0: truncate to offset and append.
    ParcelFileDescriptor openWrite(String path, long offset) = 5;
    String mkdirs(String path) = 6;
    String remove(String path, boolean recursive) = 7;
    // "" = done, "EEXIST" = target exists, "FALLBACK" = use the shell (e.g. across file systems), else an error.
    String rename(String from, String to) = 8;
}

package com.ghostpanter.scrcpy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

// Crash-atomic whole-file write: stage the bytes into a sibling temp file,
// fsync it, then rename it over the destination. The rename is the only
// mutation a concurrent reader can observe, so a reader sees either the old
// file or the new file in full, never a truncated mix. A crash mid-write
// may leave a stale staging file, never a damaged destination. The next
// write removes stale staging files before creating its own.
//
// Deliberately no fsync of the parent directory: the rename itself may be
// lost on power failure (the old content survives intact). Callers store
// re-creatable state, so atomicity matters here and durability does not.
//
// android-free (java.io/java.nio only) so it is unit-testable on the JVM.
final class AtomicFiles {

    private AtomicFiles() {}

    static synchronized void write(File dest, byte[] data) throws IOException {
        File parent = dest.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("destination parent is not a directory: " + parent);
        }
        String prefix = dest.getName() + ".tmp";
        File[] stale = parent.listFiles((dir, name) ->
                name.equals(prefix) || name.startsWith(prefix + "-"));
        if (stale == null) throw new IOException("cannot list destination parent: " + parent);
        for (File file : stale) Files.deleteIfExists(file.toPath());
        File tmp = Files.createTempFile(parent.toPath(), dest.getName() + ".tmp-", null).toFile();
        boolean moved = false;
        try {
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(data);
                os.flush();
                os.getFD().sync();
            }
            Files.move(tmp.toPath(), dest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp.toPath());
        }
    }
}

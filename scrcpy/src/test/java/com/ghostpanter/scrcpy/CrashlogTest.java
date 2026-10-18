package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

public class CrashlogTest {

    @Rule
    public TemporaryFolder dir = new TemporaryFolder();

    @Test
    public void pruneKeepsNewestCrashlogsOnly() throws Exception {
        for (int i = 0; i < 7; i++) {
            File file = new File(dir.getRoot(), "crash-" + i + ".log");
            Files.write(file.toPath(), new byte[]{(byte) i});
            assertTrue(file.setLastModified(1_000L + i));
        }
        File unrelated = dir.newFile("notes.txt");

        Crashlog.prune(dir.getRoot(), 5);

        File[] logs = dir.getRoot().listFiles((parent, name) -> name.endsWith(".log"));
        assertEquals(5, logs.length);
        assertFalse(new File(dir.getRoot(), "crash-0.log").exists());
        assertFalse(new File(dir.getRoot(), "crash-1.log").exists());
        assertTrue(unrelated.exists());
    }
}

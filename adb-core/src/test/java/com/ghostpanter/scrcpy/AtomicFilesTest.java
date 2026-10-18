package com.ghostpanter.scrcpy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

public class AtomicFilesTest {

    @Rule
    public TemporaryFolder dir = new TemporaryFolder();

    @Test
    public void writeCreatesFile() throws Exception {
        File f = new File(dir.getRoot(), "x");
        AtomicFiles.write(f, "hello".getBytes());
        assertArrayEquals("hello".getBytes(), Files.readAllBytes(f.toPath()));
    }

    @Test
    public void writeReplacesExistingWhole() throws Exception {
        File f = new File(dir.getRoot(), "x");
        AtomicFiles.write(f, "old".getBytes());
        AtomicFiles.write(f, "new-and-longer".getBytes());
        assertArrayEquals("new-and-longer".getBytes(), Files.readAllBytes(f.toPath()));
    }

    @Test
    public void writeLeavesNoTempBehind() throws Exception {
        File f = new File(dir.getRoot(), "x");
        AtomicFiles.write(f, "data".getBytes());
        assertFalse(new File(dir.getRoot(), "x.tmp").exists());
    }

    @Test
    public void staleTempDoesNotCorruptDestination() throws Exception {
        // A crash after staging but before the rename leaves a partial ".tmp".
        // The destination must still hold the previous good content, and the
        // next successful write must overwrite the stale temp cleanly.
        File f = new File(dir.getRoot(), "x");
        AtomicFiles.write(f, "good".getBytes());
        Files.write(new File(dir.getRoot(), "x.tmp").toPath(), "partial".getBytes());
        assertArrayEquals("good".getBytes(), Files.readAllBytes(f.toPath()));

        AtomicFiles.write(f, "good2".getBytes());
        assertArrayEquals("good2".getBytes(), Files.readAllBytes(f.toPath()));
        assertFalse(new File(dir.getRoot(), "x.tmp").exists());
    }

    @Test
    public void writeRemovesEveryStaleTemp() throws Exception {
        File f = new File(dir.getRoot(), "x");
        Files.write(new File(dir.getRoot(), "x.tmp-old").toPath(), new byte[]{1});
        Files.write(new File(dir.getRoot(), "x.tmp-new").toPath(), new byte[]{2});

        AtomicFiles.write(f, "good".getBytes());

        assertFalse(new File(dir.getRoot(), "x.tmp-old").exists());
        assertFalse(new File(dir.getRoot(), "x.tmp-new").exists());
    }
}

package com.phone.adb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoMergeSafetyTest {
    @TempDir Path temp;
    @Test void timeoutAndNonzeroExitCannotBeTreatedAsSuccessfulMerge() throws Exception {
        Process process = mock(Process.class);
        Path output = temp.resolve("merged.mp4");
        assertThrows(IOException.class, () -> VideoMergeTest.verifyMerge(process, output));
        when(process.waitFor(120, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(1);
        Files.writeString(output, "partial");
        assertThrows(IOException.class, () -> VideoMergeTest.verifyMerge(process, output));
    }
    @Test void zeroExitStillRequiresNonemptyOutput() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(120, TimeUnit.SECONDS)).thenReturn(true);
        Path output = temp.resolve("merged.mp4");
        assertThrows(IOException.class, () -> VideoMergeTest.verifyMerge(process, output));
        Files.createFile(output);
        assertThrows(IOException.class, () -> VideoMergeTest.verifyMerge(process, output));
        Files.writeString(output, "recording bytes");
        assertDoesNotThrow(() -> VideoMergeTest.verifyMerge(process, output));
    }
}

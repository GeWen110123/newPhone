package com.phone.adb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdbVideoRecorderTest {
    @TempDir Path temp;

    private AdbVideoRecorder recorder(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        String classes = Path.of(FakeAdbProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new AdbVideoRecorder(List.of(java,"-cp",classes,FakeAdbProcess.class.getName(),mode),1,3);
    }

    @Test void transfersToDiskBeforeRemovingOnlyItsOwnRemoteRecording() throws Exception {
        Path output = temp.resolve("segment.mp4");
        recorder("ok").recordSegment("test-device",1,output);
        assertArrayEquals(new byte[]{0,1,2,3}, Files.readAllBytes(output));
        String log = Files.readString(Path.of(output + ".adb.log"));
        assertTrue(log.indexOf(" pull ") < log.indexOf(" shell rm "));
        assertTrue(log.contains("--bit-rate 20000000"));
        assertFalse(log.contains("pkill"));
    }

    @Test void failedTransferRetainsRemoteFileAndSurfacesFailure() throws Exception {
        Path output = temp.resolve("segment.mp4");
        IOException failure = assertThrows(IOException.class, () -> recorder("fail").recordSegment("test-device",1,output));
        assertTrue(failure.getMessage().contains("remote segment retained"));
        assertFalse(Files.exists(output));
        String log = Files.readString(Path.of(output + ".adb.log"));
        assertFalse(log.contains(" shell rm "));
        assertTrue(log.contains(" shell pkill -9 -f /sdcard/phone_capture_"));
    }

    @Test void stalledRecordingIsBoundedAndNeverPulledAsSuccess() throws Exception {
        Path output = temp.resolve("segment.mp4");
        long started = System.nanoTime();
        assertThrows(IOException.class, () -> recorder("hang").recordSegment("test-device",1,output));
        assertTrue((System.nanoTime()-started)/1_000_000 < 10000);
        assertFalse(Files.readString(Path.of(output + ".adb.log")).contains(" pull "));
        assertFalse(Files.exists(output));
    }

    @Test void cancellationBeforeRecordingDoesNotStartDeviceWork() throws Exception {
        AdbVideoRecorder recorder = recorder("ok");
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> recorder.recordSegment("test-device",1,temp.resolve("cancelled.mp4")));
        } finally { Thread.interrupted(); }
        assertFalse(Files.exists(temp.resolve("cancelled.mp4.adb.log")));
    }
}

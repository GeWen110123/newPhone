package com.phone.adb;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Transfer recording files over ADB instead of allocating a large Base64 HTTP response. */
public class AdbVideoRecorder {
    private static final Logger LOG = Logger.getLogger(AdbVideoRecorder.class.getName());
    private final List<String> command;
    private final int recordingGrace;
    private final int transferTimeout;

    public AdbVideoRecorder(String adb) { this(List.of(adb), 15, 45); }

    AdbVideoRecorder(List<String> command, int recordingGrace, int transferTimeout) {
        this.command = List.copyOf(command);
        this.recordingGrace = recordingGrace;
        this.transferTimeout = transferTimeout;
    }

    public void recordSegment(String device, int seconds, Path destination) throws IOException, InterruptedException {
        if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("Recording segment must be 1–30 seconds");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Recording cancelled");
        Files.createDirectories(destination.toAbsolutePath().getParent());
        String remote = "/sdcard/phone_capture_" + UUID.randomUUID().toString().replace("-", "") + ".mp4";
        Path log = Path.of(destination + ".adb.log");
        try {
            // Match the device/Appium default quality: native resolution and 20 Mbps.
            run(device, log, seconds + recordingGrace, "shell", "screenrecord", "--time-limit",
                    String.valueOf(seconds), "--bit-rate", "20000000", remote);
            run(device, log, transferTimeout, "pull", remote, destination.toAbsolutePath().toString());
            if (!Files.isRegularFile(destination) || Files.size(destination) == 0) {
                throw new IOException("Recording transfer produced an empty file");
            }
        } catch (IOException | InterruptedException e) {
            // The unique path matches only our recording, never other device processes.
            try { run(device, log, 5, "shell", "pkill", "-9", "-f", remote); }
            catch (Exception cleanup) { LOG.fine("Recording cancellation cleanup: " + cleanup.getMessage()); }
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            throw new IOException("Recording failed; remote segment retained at " + remote + "; log=" + log, e);
        }
        try { run(device, log, 5, "shell", "rm", "-f", remote); }
        catch (IOException e) { LOG.warning("Local recording saved; remote cleanup failed: " + remote); }
    }

    private void run(String device, Path log, int timeout, String... args) throws IOException, InterruptedException {
        List<String> invocation = new ArrayList<>(command);
        invocation.addAll(Arrays.asList("-s", device));
        invocation.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(invocation).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException("ADB command timed out: " + args[0]);
            if (process.exitValue() != 0) throw new IOException("ADB command failed: " + args[0] + " exit=" + process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}

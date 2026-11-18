package com.phone.adb;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Merge recordings only after verifying FFmpeg success; preserve parts on failure. */
public class VideoMergeTest {
    public static void mergeVideoSegments(List<String> segmentFiles, String finalFilename) throws Exception {
        if (segmentFiles.isEmpty()) throw new IOException("没有可合并的视频分段");
        Path output = Paths.get(finalFilename).toAbsolutePath();
        Path listFile = Files.createTempFile(output.getParent(), "merge-", ".txt");
        Process ffmpeg = null;
        try {
            StringBuilder list = new StringBuilder();
            for (String segment : segmentFiles) {
                list.append("file '").append(segment.replace("\\", "/").replace("'", "'\\''")).append("'\n");
            }
            Files.writeString(listFile, list, StandardCharsets.UTF_8);
            ffmpeg = new ProcessBuilder(DouyinPathUtils.resolve("ffmpeg", "bin", "ffmpeg.exe"),
                    "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                    "-c", "copy", finalFilename)
                    .redirectErrorStream(true)
                    .redirectOutput(new File(finalFilename + ".ffmpeg.log")).start();
            verifyMerge(ffmpeg, output);
            for (String segment : segmentFiles) Files.deleteIfExists(Paths.get(segment));
        } finally {
            if (ffmpeg != null && ffmpeg.isAlive()) ffmpeg.destroyForcibly();
            Files.deleteIfExists(listFile);
        }
    }

    static void verifyMerge(Process process, Path output) throws Exception {
        if (!process.waitFor(120, TimeUnit.SECONDS)) throw new IOException("FFmpeg 合并超时，保留原分段");
        if (process.exitValue() != 0 || !Files.exists(output) || Files.size(output) == 0) {
            throw new IOException("FFmpeg 合并失败，保留原分段，详见 " + output + ".ffmpeg.log");
        }
    }
}

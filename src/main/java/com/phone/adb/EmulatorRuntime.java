package com.phone.adb;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * D3 采集端模拟器运行时。
 *
 * <p>连接参数不来自真机 ADB：优先读取一键部署包写入的 one-click 回执，
 * 回执缺失时才使用 application.yml 中的固定基线（emulator-5668 / 5068 / 4725）。
 * 启动入口复用 run-d3.ps1，保证 Android 冷启动后 Serein 兼容层会被重新部署。</p>
 */
@Component
public class EmulatorRuntime {
    @Value("${phone.emulator.enabled:true}")
    private boolean enabled;
    @Value("${phone.emulator.instance-name:collector-new}")
    private String instanceName;
    @Value("${phone.emulator.serial:emulator-5668}")
    private String configuredSerial;
    @Value("${phone.emulator.adb-server-port:5068}")
    private int configuredAdbPort;
    @Value("${phone.emulator.appium-url:http://127.0.0.1:4725/wd/hub}")
    private String appiumUrl;
    @Value("${phone.emulator.lab-root:F:/D3SereinLab}")
    private String labRoot;
    @Value("${phone.emulator.start-script:run-d3.ps1}")
    private String startScript;
    @Value("${phone.emulator.auto-start:false}")
    private boolean autoStart;

    /** 面向前端的模拟器状态快照。 */
    public record Status(
            boolean enabled,
            String instanceName,
            String serial,
            int adbServerPort,
            String appiumUrl,
            boolean online,
            boolean runtimeReady,
            boolean appLaunchVerified,
            boolean calibrationReady,
            String startupManifest,
            String deploymentManifest,
            String calibrationPath,
            String message) {
    }

    private static final class Receipt {
        String serial;
        int adbPort;
        boolean runtimeReady;
        boolean appLaunchVerified;
        String startupManifest;
        String deploymentManifest;
        String calibrationPath;
        boolean calibrationReady;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    /** 仅 emulator-NNNN 形式的序列号视为模拟器；任务绑定真机码时不会误判。 */
    public static boolean isEmulatorSerial(String deviceId) {
        return deviceId != null && deviceId.trim().matches("emulator-\\d{3,5}");
    }

    public String effectiveSerial() {
        Receipt receipt = readReceipt();
        return receipt != null && receipt.serial != null && !receipt.serial.isBlank()
                ? receipt.serial
                : configuredSerial;
    }

    public int effectiveAdbPort() {
        Receipt receipt = readReceipt();
        return receipt != null && receipt.adbPort > 0 ? receipt.adbPort : configuredAdbPort;
    }

    public Status status(String deviceCode) {
        Receipt receipt = readReceipt();
        String serial = receipt != null && receipt.serial != null && !receipt.serial.isBlank()
                ? receipt.serial
                : configuredSerial;
        int adbPort = receipt != null && receipt.adbPort > 0 ? receipt.adbPort : configuredAdbPort;
        boolean online = false;
        String message = "";
        try {
            online = AndroidCollector.current().isOnline(serial);
            message = online ? "模拟器已就绪" : "模拟器未在线，请先运行 " + startScript;
        } catch (Exception e) {
            message = "检查模拟器连接失败: " + e.getMessage();
        }
        if (deviceCode != null && !deviceCode.isBlank()
                && isEmulatorSerial(deviceCode.trim())
                && !deviceCode.trim().equals(serial)) {
            message += "；当前登记设备 " + deviceCode.trim() + " 与运行实例 " + serial + " 不一致";
        }
        return new Status(
                enabled,
                instanceName,
                serial,
                adbPort,
                appiumUrl,
                online,
                receipt != null && receipt.runtimeReady,
                receipt != null && receipt.appLaunchVerified,
                receipt != null && receipt.calibrationReady,
                receipt == null ? null : receipt.startupManifest,
                receipt == null ? null : receipt.deploymentManifest,
                receipt == null ? null : receipt.calibrationPath,
                message);
    }

    /**
     * 启动/复用模拟器并恢复兼容层。串行执行，避免并发任务重复冷启动 AVD。
     */
    public synchronized Status start() {
        Receipt before = readReceipt();
        if (before != null && before.runtimeReady && before.appLaunchVerified) {
            try {
                if (AndroidCollector.current().isOnline(
                        before.serial != null ? before.serial : configuredSerial)) {
                    return status(null);
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // 检查失败时继续走启动/复用流程。
            }
        }
        Path script = resolveStartScript();
        if (!Files.isRegularFile(script)) {
            return statusWithError("找不到模拟器启动脚本: " + script);
        }
        // 一键部署包在 F:\D3SereinLab 下带便携 PowerShell，优先使用它。
        String powershell = "pwsh";
        Path portable = Path.of(labRoot, "tools", "powershell", "7.6.9", "pwsh.exe");
        if (Files.isRegularFile(portable)) powershell = portable.toString();
        ProcessBuilder builder = new ProcessBuilder(
                powershell, "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-NoPreview");
        builder.directory(Path.of(System.getProperty("user.dir", ".")).toFile());
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                return statusWithError("模拟器启动超时（10 分钟）: " + tail(output));
            }
            String hint = tail(output);
            if (process.exitValue() != 0) {
                return statusWithError("模拟器启动失败: " + hint);
            }
            Status started = status(null);
            if (!started.online() || !started.runtimeReady() || !started.appLaunchVerified()) {
                return statusWithError("启动脚本已返回，但模拟器未完成校验: " + hint);
            }
            return started;
        } catch (IOException e) {
            return statusWithError("无法执行模拟器启动脚本: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return statusWithError("模拟器启动被中断");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /** 返回可复制到 PowerShell 的 scrcpy 预览命令，仍指向模板实例的私有 ADB 服务。 */
    public String mirrorCommand(String deviceCode) {
        Receipt receipt = readReceipt();
        String serial = deviceCode != null && deviceCode.trim().matches("emulator-\\d{3,5}")
                ? deviceCode.trim()
                : receipt != null && receipt.serial != null && !receipt.serial.isBlank()
                        ? receipt.serial
                        : configuredSerial;
        int adbPort = receipt != null && receipt.adbPort > 0 ? receipt.adbPort : configuredAdbPort;
        Path scrcpy = Path.of(labRoot, "tools", "scrcpy", "4.1", "scrcpy.exe");
        String executable = Files.isRegularFile(scrcpy) ? scrcpy.toString() : "scrcpy";
        return String.join("\n",
                "$env:ADB_SERVER_SOCKET = \"tcp:127.0.0.1:" + adbPort + "\"",
                "$env:ANDROID_ADB_SERVER_PORT = \"" + adbPort + "\"",
                "$env:ADB_EMU = '0'; $env:ADB_USB = '0'; $env:ADB_MDNS_AUTO_CONNECT = '0'",
                "& \"" + executable + "\" --serial=" + serial
                        + " --display-id=0 --no-audio --window-title=D3-" + instanceName);
    }

    private Status statusWithError(String message) {
        Status current = status(null);
        return new Status(current.enabled(), current.instanceName(), current.serial(),
                current.adbServerPort(), current.appiumUrl(), current.online(),
                current.runtimeReady(), current.appLaunchVerified(), current.calibrationReady(),
                current.startupManifest(), current.deploymentManifest(), current.calibrationPath(),
                message);
    }

    private Path resolveStartScript() {
        Path path = Path.of(startScript);
        if (path.isAbsolute()) return path;
        return Path.of(System.getProperty("user.dir", "."), startScript).toAbsolutePath().normalize();
    }

    private Receipt readReceipt() {
        Path evidence = Path.of(labRoot, "instances", instanceName, "evidence");
        if (!Files.isDirectory(evidence)) return null;
        Path latest;
        try (Stream<Path> files = Files.list(evidence)) {
            latest = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("one-click-")
                            && p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
        if (latest == null) return null;
        try {
            JSONObject json = JSON.parseObject(Files.readString(latest, StandardCharsets.UTF_8));
            Receipt receipt = new Receipt();
            receipt.serial = json.getString("Serial");
            receipt.adbPort = json.getIntValue("AdbServerPort");
            receipt.runtimeReady = json.getBooleanValue("RuntimeReady");
            receipt.appLaunchVerified = json.getBooleanValue("AppLaunchVerified");
            receipt.startupManifest = json.getString("StartupManifest");
            receipt.deploymentManifest = json.getString("DeploymentManifest");
            receipt.calibrationPath = json.getString("CalibrationPath");
            receipt.calibrationReady = readCalibration(receipt.calibrationPath);
            return receipt;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean readCalibration(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file)) return false;
            return JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8))
                    .getBooleanValue("ready");
        } catch (Exception e) {
            return false;
        }
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) return "(no output)";
        String[] lines = output.trim().split("\\R");
        List<String> last = List.of(lines).subList(Math.max(0, lines.length - 8), lines.length);
        return String.join(" | ", last).toLowerCase(Locale.ROOT);
    }
}

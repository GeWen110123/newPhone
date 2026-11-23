package com.phone.adb;

import io.appium.java_client.MobileElement;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Shared ADB and Appium connection settings for physical devices and emulators. */
public final class AndroidCollector {
    private static final AndroidCollector INSTANCE = new AndroidCollector();
    private final List<String> adbCommand;

    private AndroidCollector() {
        this.adbCommand = null;
    }

    AndroidCollector(List<String> adbCommand) {
        if (adbCommand.isEmpty()) throw new IllegalArgumentException("ADB command is required");
        this.adbCommand = List.copyOf(adbCommand);
    }

    public static AndroidCollector current() {
        return INSTANCE;
    }

    public List<String> adbPrefix() {
        if (adbCommand != null) return adbCommand;
        String executable = setting("ADB_PATH", "");
        if (executable.isEmpty()) {
            String sdk = setting("ANDROID_HOME", setting("ANDROID_SDK_ROOT", ""));
            if (!sdk.isEmpty()) {
                Path candidate = Path.of(sdk, "platform-tools",
                        System.getProperty("os.name").startsWith("Windows") ? "adb.exe" : "adb");
                if (Files.isRegularFile(candidate)) executable = candidate.toString();
            }
        }
        return List.of(executable.isEmpty() ? "adb" : executable,
                "-P", Integer.toString(adbServerPort()));
    }

    public ProcessBuilder adb(String... arguments) {
        List<String> command = new ArrayList<>(adbPrefix());
        command.addAll(Arrays.asList(arguments));
        return new ProcessBuilder(command);
    }

    public boolean isOnline(String deviceId) throws IOException, InterruptedException {
        requireDevice(deviceId);
        if (hasDeviceState(deviceId)) return true;
        // BlueStacks exposes a local TCP endpoint; ADB loses this registration after a restart.
        if (!deviceId.matches("(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}")) return false;
        int port = Integer.parseInt(deviceId.substring(deviceId.lastIndexOf(':') + 1));
        if (port < 1 || port > 65535) return false;
        Process reconnect = adb("connect", deviceId).redirectErrorStream(true).start();
        try {
            if (!reconnect.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("ADB emulator reconnect timed out: " + deviceId);
            }
            // A successful connect command alone does not prove the device is authorized.
            return reconnect.exitValue() == 0 && hasDeviceState(deviceId);
        } finally {
            if (reconnect.isAlive()) reconnect.destroyForcibly();
        }
    }

    private boolean hasDeviceState(String deviceId) throws IOException, InterruptedException {
        Process process = adb("-s", deviceId, "get-state").redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("ADB device status timed out: " + deviceId);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && output.lines().anyMatch(line -> line.trim().equals("device"));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public AndroidDriver<MobileElement> connect(String deviceId) throws IOException, InterruptedException {
        if (!isOnline(deviceId)) throw new IOException("Device offline or unauthorized: " + deviceId);
        DesiredCapabilities caps = capabilities(deviceId);
        AndroidDriver<MobileElement> driver = new AndroidDriver<>(
                new URL(setting("APPIUM_URL", "http://127.0.0.1:4723/wd/hub")), caps);
        try {
            // Video animations never become idle; explicit page checks handle readiness.
            driver.setSetting("waitForIdleTimeout", 0);
            return driver;
        } catch (RuntimeException e) {
            try { driver.quit(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    DesiredCapabilities capabilities(String deviceId) {
        requireDevice(deviceId);
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability("platformName", "Android");
        caps.setCapability("automationName", "UiAutomator2");
        caps.setCapability("deviceName", deviceId);
        caps.setCapability("udid", deviceId);
        caps.setCapability("appPackage", setting("DOUYIN_APP_PACKAGE", "com.ss.android.ugc.aweme"));
        caps.setCapability("appActivity", setting("DOUYIN_APP_ACTIVITY", ".main.MainActivity"));
        caps.setCapability("noReset", true);
        caps.setCapability("autoLaunch", false);
        caps.setCapability("dontStopAppOnReset", true);
        caps.setCapability("newCommandTimeout", 300);
        caps.setCapability("adbExecTimeout", 60000);
        caps.setCapability("uiautomator2ServerLaunchTimeout", 60000);
        int port = adbServerPort();
        caps.setCapability("adbPort", port);
        // The D3 launcher owns its private ADB server and records its PID.
        if (port != 5037) caps.setCapability("suppressKillServer", true);
        return caps;
    }

    private static int adbServerPort() {
        String value = setting("ADB_SERVER_PORT", "5037");
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
            // Report the configuration name instead of a later device-offline error.
        }
        throw new IllegalArgumentException("ADB_SERVER_PORT must be an integer between 1 and 65535");
    }

    private static void requireDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("Device ID is required");
    }

    private static String setting(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

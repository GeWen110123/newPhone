package com.phone.adb;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AndroidCollectorTest {
    @Test void localTcpEmulatorIsReconnectedAndItsStateIsCheckedAgain() throws Exception {
        Path state = Files.createTempDirectory("adb-emulator-test").resolve("connected");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(AndroidCollectorTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        AndroidCollector collector = new AndroidCollector(List.of(java, "-cp", classes,
                TcpDeviceState.class.getName(), state.toString()));
        try {
            assertTrue(collector.isOnline("127.0.0.1:5555"));
            assertTrue(Files.exists(state));
            Files.delete(state);
            assertFalse(collector.isOnline("physical-device"));
            assertFalse(collector.isOnline("127.0.0.1:65536"));
            assertFalse(Files.exists(state));
        } finally {
            Files.deleteIfExists(state);
            Files.deleteIfExists(state.getParent());
        }
    }

    public static class TcpDeviceState {
        public static void main(String[] args) throws Exception {
            Path state = Path.of(args[0]);
            if ("connect".equals(args[1])) {
                Files.createFile(state);
                System.out.println("connected to " + args[2]);
            } else if (Files.exists(state)) {
                System.out.println("device");
            } else {
                System.out.println("offline");
                System.exit(1);
            }
        }
    }
    @Test void privateAdbServerIsSharedByShellRecordingAndAppium() {
        String oldPort = System.getProperty("ADB_SERVER_PORT");
        String oldPath = System.getProperty("ADB_PATH");
        try {
            System.setProperty("ADB_SERVER_PORT", "5068");
            System.setProperty("ADB_PATH", "F:/D3SereinLab/sdk-win/platform-tools/adb.exe");
            AndroidCollector collector = AndroidCollector.current();
            assertEquals(List.of("F:/D3SereinLab/sdk-win/platform-tools/adb.exe", "-P", "5068"),
                    collector.adbPrefix());
            assertEquals(5068, collector.capabilities("emulator-5668").getCapability("adbPort"));
            assertEquals(true, collector.capabilities("emulator-5668").getCapability("suppressKillServer"));
            assertEquals("emulator-5668", collector.capabilities("emulator-5668").getCapability("udid"));
            System.setProperty("ADB_SERVER_PORT", "5037");
            assertEquals(5037, collector.capabilities("emulator-5554").getCapability("adbPort"));
            assertNull(collector.capabilities("emulator-5554").getCapability("suppressKillServer"));
            for (String invalid : List.of("0", "65536", "5068 & echo invalid", "invalid")) {
                System.setProperty("ADB_SERVER_PORT", invalid);
                assertThrows(IllegalArgumentException.class, collector::adbPrefix);
                assertThrows(IllegalArgumentException.class, () -> collector.capabilities("emulator-5668"));
            }
        } finally {
            restoreProperty("ADB_SERVER_PORT", oldPort);
            restoreProperty("ADB_PATH", oldPath);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private AndroidCollector collector(String state) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes = Path.of(AndroidCollectorTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new AndroidCollector(List.of(java, "-cp", classes, DeviceState.class.getName(), state));
    }

    @Test void onlyAuthorizedOnlineDevicesAreAccepted() throws Exception {
        assertTrue(collector("device").isOnline("emulator-5554"));
        assertFalse(collector("offline").isOnline("emulator-5554"));
        assertFalse(collector("unauthorized").isOnline("emulator-5554"));
    }

    @Test void unavailableDevicesCannotCreateAnAppiumSession() throws Exception {
        assertThrows(java.io.IOException.class, () -> collector("offline").connect("emulator-5554"));
    }

    @Test void deviceIsRequiredAndArgumentsRemainSeparate() throws Exception {
        AndroidCollector collector = collector("device");
        assertThrows(IllegalArgumentException.class, () -> collector.isOnline(" "));
        String literal = "device with spaces & special characters";
        List<String> command = collector.adb("-s", literal, "get-state").command();
        assertEquals(List.of("-s", literal, "get-state"), command.subList(command.size() - 3, command.size()));
    }

    public static class DeviceState {
        public static void main(String[] args) {
            System.out.println(args[0]);
            if (!args[0].equals("device")) System.exit(1);
        }
    }
}

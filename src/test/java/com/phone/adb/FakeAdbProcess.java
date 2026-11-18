package com.phone.adb;

import java.nio.file.*;
import java.util.*;

/** Isolated subprocess fixture: no phone or real ADB commands are used by tests. */
public class FakeAdbProcess {
    public static void main(String[] args) throws Exception {
        List<String> command = Arrays.asList(args);
        System.out.println(String.join(" ", args));
        if (command.contains("screenrecord") && args[0].equals("hang")) Thread.sleep(30000);
        if (command.contains("pull")) {
            if (args[0].equals("fail")) System.exit(7);
            Files.write(Path.of(args[args.length - 1]), new byte[]{0,1,2,3});
        }
    }
}

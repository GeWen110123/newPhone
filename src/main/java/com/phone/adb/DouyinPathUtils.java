package com.phone.adb;

import com.phone.common.config.RuoYiConfig;
import com.phone.common.constant.Constants;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 抖音采集文件路径统一入口。
 * 所有采集输出都从 application.yml 的 phone.profile 读取。
 */
public final class DouyinPathUtils {

    private DouyinPathUtils() {
    }

    public static String root() {
        String profile = RuoYiConfig.getProfile();
        if (profile == null || profile.trim().isEmpty()) {
            throw new IllegalStateException("phone.profile 未配置，无法确定抖音输出目录");
        }
        return profile;
    }

    public static String resolve(String... children) {
        Path path = Paths.get(root(), children);
        return path.toString();
    }

    public static String webPath(String fullPath) {
        if (fullPath == null) {
            return null;
        }
        Path root = Paths.get(root()).toAbsolutePath().normalize();
        Path path = Paths.get(fullPath).toAbsolutePath().normalize();
        String relative;
        if (path.startsWith(root)) {
            relative = root.relativize(path).toString();
        } else {
            relative = fullPath;
        }
        return Constants.RESOURCE_PREFIX + "/" + relative.replace("\\", "/");
    }
}

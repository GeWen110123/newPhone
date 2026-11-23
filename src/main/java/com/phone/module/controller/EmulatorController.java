package com.phone.module.controller;

import com.phone.adb.EmulatorRuntime;
import com.phone.common.core.controller.BaseController;
import com.phone.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * D3 模拟器实例管理。采集任务只绑定模拟器序列号，不再连接真机 ADB。
 */
@RestController
@RequestMapping("/module/emulator")
public class EmulatorController extends BaseController {
    @Autowired
    private EmulatorRuntime emulatorRuntime;

    /** 读取一键部署回执 + ADB get-state，返回当前模拟器状态。 */
    @GetMapping("/status")
    public AjaxResult status(@RequestParam(required = false) String deviceCode) {
        return AjaxResult.success(emulatorRuntime.status(deviceCode));
    }

    /** 启动/复用实例并恢复 Serein 兼容层；幂等，已就绪时直接返回当前状态。 */
    @PostMapping("/start")
    public AjaxResult start() {
        EmulatorRuntime.Status status = emulatorRuntime.start();
        if (!status.online() || !status.runtimeReady() || !status.appLaunchVerified()) {
            return AjaxResult.error(status.message());
        }
        return AjaxResult.success(status);
    }

    /** 返回可复制到 PowerShell 的 scrcpy 预览命令，指向实例的私有 ADB 服务。 */
    @GetMapping("/mirror")
    public AjaxResult mirror(@RequestParam(required = false) String deviceCode) {
        return AjaxResult.success(emulatorRuntime.mirrorCommand(deviceCode));
    }
}

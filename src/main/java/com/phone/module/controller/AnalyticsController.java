package com.phone.module.controller;

import com.phone.common.core.domain.AjaxResult;
import com.phone.module.service.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Same authenticated access as the source business lists; users are additionally permission-scoped. */
@RestController
@RequestMapping("/module/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service = service; }

    @GetMapping("/executions")
    public AjaxResult executions(@RequestParam(defaultValue = "30") String period,
                                 @RequestParam(defaultValue = "all") String kind) {
        try { return AjaxResult.success(service.executions(period, kind)); }
        catch (IllegalArgumentException ex) { return AjaxResult.error(400, ex.getMessage()); }
    }

    @GetMapping("/resources")
    public AjaxResult resources(@RequestParam(required = false) String sources) {
        try { return AjaxResult.success(service.resources(sources)); }
        catch (IllegalArgumentException ex) { return AjaxResult.error(400, ex.getMessage()); }
    }

    @GetMapping("/content")
    public AjaxResult content(@RequestParam(defaultValue = "30") String period,
                              @RequestParam(defaultValue = "all") String kind) {
        try { return AjaxResult.success(service.content(period, kind)); }
        catch (IllegalArgumentException ex) { return AjaxResult.error(400, ex.getMessage()); }
    }
}

package com.phone.module.mapper;

import com.phone.module.domain.analytics.AnalyticsQuery;
import java.util.List;
import java.util.Map;

public interface AnalyticsMapper {
    Map<String, Object> executionSummary(AnalyticsQuery query);
    long missingExecutionTime(AnalyticsQuery query);
    List<Map<String, Object>> executionTrend(AnalyticsQuery query);
    List<Map<String, Object>> executionCalendar(AnalyticsQuery query);
    List<Map<String, Object>> executionRegions(AnalyticsQuery query);
    Map<String, Object> executionRegionSummary(AnalyticsQuery query);
    List<Map<String, Object>> executionTags(AnalyticsQuery query);
    List<Map<String, Object>> executionDevices(AnalyticsQuery query);
    List<Map<String, Object>> executionTasks(AnalyticsQuery query);
    long accountTotal();
    long locationTotal();
    long accountTaskTotal();
    long locationTaskTotal();
    long deviceTotal();
    long groupTotal();
    List<Map<String, Object>> deviceStatuses();
    List<Map<String, Object>> deviceModels();
    List<Map<String, Object>> groups();
    Map<String, Object> groupSummary();
    Map<String, Object> users(AnalyticsQuery query);
    Map<String, Object> accountVideoSummary();
    Map<String, Object> locationVideoSummary();
    Map<String, Object> commentSummary(AnalyticsQuery query);
    List<Map<String, Object>> accountVideoTrend(AnalyticsQuery query);
    List<Map<String, Object>> locationVideoTrend(AnalyticsQuery query);
    List<Map<String, Object>> topAccounts();
    List<Map<String, Object>> topLocations();
    long accountTargetMemberships();
    long locationTargetMemberships();
}

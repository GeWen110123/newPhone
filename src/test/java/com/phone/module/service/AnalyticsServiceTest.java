package com.phone.module.service;

import com.phone.framework.web.service.PermissionService;
import com.phone.module.domain.analytics.AnalyticsQuery;
import com.phone.module.domain.analytics.AnalyticsResponse;
import com.phone.module.mapper.AnalyticsMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {
    private AnalyticsMapper mapper;
    private AnalyticsUserService users;
    private PermissionService permissions;
    private AnalyticsService service;

    @BeforeEach void setUp() {
        mapper = mock(AnalyticsMapper.class);
        users = mock(AnalyticsUserService.class);
        permissions = mock(PermissionService.class);
        service = new AnalyticsService(mapper, users, permissions);
    }

    @Test void unrequestedSourcesAreNeverQueried() {
        AnalyticsResponse empty = service.resources("");
        assertEquals("denied", source(empty, "devices").get("status"));
        verifyNoInteractions(mapper, users, permissions);
        when(mapper.deviceTotal()).thenReturn(8L);
        when(mapper.deviceStatuses()).thenReturn(List.of(Map.of("name", "在线", "value", 5L)));
        AnalyticsResponse response = service.resources("devices");
        assertEquals(8L, response.get("deviceTotal"));
        assertNull(response.get("userTotal"));
        assertEquals("denied", source(response, "users").get("status"));
        verifyNoInteractions(users, permissions);
        verify(mapper, never()).accountTaskTotal();
        verify(mapper, never()).locationTaskTotal();
        verify(mapper, never()).groupTotal();
    }

    @Test void usersNeedTheirOriginalPermissionAndScopedService() {
        AnalyticsResponse denied = service.resources("users");
        assertEquals("denied", source(denied, "users").get("status"));
        verifyNoInteractions(users);
        when(permissions.hasPermi("system:user:list")).thenReturn(true);
        when(users.summarize(any())).thenReturn(Map.of("total", 2L, "active", 1L, "disabled", 1L, "unknown", 0L));
        AnalyticsResponse allowed = service.resources("users");
        assertEquals(2L, allowed.get("userTotal"));
        assertEquals("ok", source(allowed, "users").get("status"));
        verify(users).summarize(any());
        verify(mapper, never()).users(any());
    }

    @Test void failedSourceRemainsUnknownAndDoesNotEraseHealthySources() {
        when(mapper.accountTaskTotal()).thenThrow(new IllegalStateException("unavailable"));
        when(mapper.locationTaskTotal()).thenReturn(7L);
        when(permissions.hasPermi("system:user:list")).thenReturn(true);
        when(users.summarize(any())).thenThrow(new IllegalStateException("scope table missing"));
        AnalyticsResponse response = service.resources("accounts,locations,users");
        assertNull(response.get("accountsTotal"));
        assertNull(response.get("userTotal"));
        assertEquals(7L, response.get("locationsTotal"));
        assertEquals("error", source(response, "accounts").get("status"));
        assertEquals("ok", source(response, "locations").get("status"));
        assertEquals("error", source(response, "users").get("status"));
    }

    @Test void contentKindDoesNotReadTheOtherSource() {
        when(mapper.accountVideoSummary()).thenReturn(new HashMap<>(Map.of("total", 4L, "videoFiles", 2L, "imageFiles", 3L, "commentFiles", 1L)));
        when(mapper.accountVideoTrend(any())).thenReturn(List.of());
        when(mapper.commentSummary(any())).thenReturn(Map.of("total", 10L, "uniqueCommenters", 8L));
        AnalyticsResponse response = service.content("7", "account");
        Map<?, ?> summary = (Map<?, ?>) response.get("summary");
        assertEquals(4L, summary.get("videosTotal"));
        assertNull(summary.get("locationVideos"));
        assertEquals("denied", source(response, "locationVideos").get("status"));
        verify(mapper, never()).locationVideoSummary();
        verify(mapper, never()).locationVideoTrend(any());
        verify(mapper, never()).locationTotal();
        verify(mapper, never()).topLocations();
    }

    @Test void sourceFailureDoesNotPublishPartialAssetsAsComplete() {
        when(mapper.accountVideoSummary()).thenThrow(new IllegalStateException("missing table"));
        when(mapper.locationVideoSummary()).thenReturn(new HashMap<>(Map.of("total", 3L)));
        when(mapper.commentSummary(any())).thenReturn(Map.of("total", 0L, "uniqueCommenters", 0L));
        AnalyticsResponse response = service.content("30", "all");
        Map<?, ?> summary = (Map<?, ?>) response.get("summary");
        assertNull(summary.get("videosTotal"));
        assertEquals(3L, summary.get("locationVideos"));
        assertEquals(List.of(), response.get("assets"));
        assertEquals(List.of(), response.get("trend"));
        assertNull(((Map<?, ?>) response.get("breakdowns")).get("topAccounts"));
    }

    @Test void geographicDistributionUsesItsOwnDenominatorAndPreservesMissingAndRemainder() {
        when(mapper.executionSummary(any())).thenReturn(new HashMap<>(Map.of("total", 100L, "completed", 50L)));
        when(mapper.executionRegionSummary(any())).thenReturn(new HashMap<>(Map.of("total", 20L, "missing", 2L, "searchExcluded", 3L)));
        when(mapper.executionRegions(any())).thenReturn(List.of(Map.of("name", "上海", "key", "target:上海", "value", 12L)));
        when(mapper.executionTags(any())).thenReturn(List.of(Map.of("name", "视频", "value", 60L)));
        AnalyticsResponse response = service.executions("7", "all");
        Map<?, ?> region = (Map<?, ?>) response.get("regionSummary");
        assertEquals(20L, region.get("total"));
        assertEquals(6L, region.get("other"));
        List<?> rows = (List<?>) response.get("regions");
        assertEquals(3, rows.size());
        assertEquals("synthetic:missing", ((Map<?, ?>) rows.get(1)).get("key"));
        assertEquals("synthetic:other", ((Map<?, ?>) rows.get(2)).get("key"));
        Map<?, ?> tags = (Map<?, ?>) ((Map<?, ?>) response.get("breakdowns")).get("tags");
        assertEquals(100L, tags.get("total"));
        assertEquals(60L, tags.get("displayed"));
        assertEquals(40L, tags.get("other"));
    }

    @Test void groupTotalsUseResolvedMembersAndKeepRecordedCountOnlyForDiagnosis() {
        when(mapper.groupTotal()).thenReturn(13L);
        when(mapper.groups()).thenReturn(List.of(new HashMap<>(Map.of("name", "同名分组", "key", "group:1", "value", 3L, "currentMemberCount", 3L))));
        when(mapper.groupSummary()).thenReturn(new HashMap<>(Map.of("membershipsTotal", 9L, "mismatchedGroups", 1L, "unknownRecordedGroups", 1L)));
        AnalyticsResponse response = service.resources("groups");
        Map<?, ?> group = (Map<?, ?>) ((List<?>) response.get("groups")).get(0);
        assertEquals(3L, group.get("value"));
        assertNull(group.get("recordedCount"));
        Map<?, ?> summary = (Map<?, ?>) response.get("groupSummary");
        assertEquals(12L, summary.get("omittedGroups"));
        assertEquals(3L, summary.get("displayedMemberships"));
        assertEquals(9L, summary.get("membershipsTotal"));
        assertNull(response.get("deviceModelsSummary"));
    }

    @Test void seriesIncludesLeapDayAndEmptyDaysAndMonths() {
        AnalyticsQuery leap = new AnalyticsQuery("7", "all", LocalDate.of(2024, 3, 2));
        List<Map<String, Object>> days = AnalyticsService.fillSeries(List.of(Map.of("date", "2024-02-29", "total", 5L)), leap, "total");
        assertEquals(7, days.size());
        assertEquals("2024-02-25", days.get(0).get("date"));
        assertEquals(0L, days.get(0).get("total"));
        assertEquals(5L, days.get(4).get("total"));
        AnalyticsQuery all = new AnalyticsQuery("all", "all", LocalDate.of(2026, 3, 2));
        List<Map<String, Object>> months = AnalyticsService.fillSeries(List.of(Map.of("date", "2025-12", "total", 2L)), all, "total");
        assertEquals(4, months.size());
        assertEquals("2026-01", months.get(1).get("date"));
        assertEquals(0L, months.get(1).get("total"));
    }

    @Test void validatesFiltersAndDoesNotClaimAnEmptySuccessRate() {
        assertThrows(IllegalArgumentException.class, () -> service.executions("365", "all"));
        assertThrows(IllegalArgumentException.class, () -> service.content("7", "private"));
        assertThrows(IllegalArgumentException.class, () -> service.resources("users,passwords"));
        assertNull(AnalyticsService.rate(0, 0));
        assertEquals(new BigDecimal("66.7"), AnalyticsService.rate(2, 3));
        verifyNoInteractions(mapper);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> source(AnalyticsResponse response, String key) {
        return (Map<String, Object>) ((Map<?, ?>) response.get("sources")).get(key);
    }
}

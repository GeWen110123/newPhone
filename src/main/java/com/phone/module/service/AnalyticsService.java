package com.phone.module.service;

import com.phone.framework.web.service.PermissionService;
import com.phone.module.domain.analytics.AnalyticsQuery;
import com.phone.module.domain.analytics.AnalyticsResponse;
import com.phone.module.mapper.AnalyticsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsService.class);
    private static final Set<String> RESOURCE_SOURCES = Set.of("accounts", "locations", "devices", "groups", "users");
    private final AnalyticsMapper mapper;
    private final AnalyticsUserService users;
    private final PermissionService permissions;
    private final Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    public AnalyticsService(AnalyticsMapper mapper, AnalyticsUserService users, PermissionService permissions) {
        this.mapper = mapper;
        this.users = users;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalyticsResponse executions(String period, String kind) {
        AnalyticsQuery query = query(period, kind);
        AnalyticsResponse result = response(query);
        Map<String, Object> summary = mapper.executionSummary(query);
        if (!summary.containsKey("runTotal")) summary.put("runTotal", null);
        summary.put("completionRate", rate(number(summary, "completed"), number(summary, "total")));
        result.put("summary", summary);
        result.put("missingTime", mapper.missingExecutionTime(query));
        List<Map<String, Object>> trend = mapper.executionTrend(query);
        result.put("trendContinuous", continuous(trend, query));
        result.put("trend", fillSeries(trend, query, "total", "completed", "error"));
        AnalyticsQuery calendar = new AnalyticsQuery("90", kind, query.getEnd().minusDays(1).toLocalDate());
        result.put("calendar", fillSeries(mapper.executionCalendar(query), calendar, "value"));
        Map<String, Object> regionSummary = mapper.executionRegionSummary(query);
        List<Map<String, Object>> regions = new ArrayList<>(mapper.executionRegions(query));
        long displayedRegions = sum(regions, "value");
        long missingRegions = number(regionSummary, "missing");
        long otherRegions = number(regionSummary, "total") - displayedRegions - missingRegions;
        regionSummary.put("displayed", displayedRegions); regionSummary.put("other", otherRegions);
        if (missingRegions > 0) regions.add(Map.of("key", "synthetic:missing", "name", "未指定目标", "value", missingRegions));
        if (otherRegions > 0) regions.add(Map.of("key", "synthetic:other", "name", "其他地点", "value", otherRegions));
        result.put("regionSummary", regionSummary); result.put("regions", regions);
        List<Map<String, Object>> tags = mapper.executionTags(query);
        List<Map<String, Object>> devices = withRates(mapper.executionDevices(query));
        List<Map<String, Object>> tasks = withRates(mapper.executionTasks(query));
        result.put("tags", tags); result.put("devices", devices); result.put("tasks", tasks);
        long total = number(summary, "total");
        result.put("breakdowns", Map.of("tags", breakdown(tags, total, "value", 12),
            "devices", breakdown(devices, total, "total", 10), "tasks", breakdown(tasks, total, "total", 10)));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalyticsResponse resources(String selectedSources) {
        if (selectedSources == null) selectedSources = "accounts,locations,devices,groups,users";
        Set<String> selected = Arrays.stream(selectedSources.split(",")).map(String::trim)
            .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        if (!RESOURCE_SOURCES.containsAll(selected)) {
            throw new IllegalArgumentException("sources 只支持 accounts、locations、devices、groups、users");
        }
        AnalyticsResponse result = new AnalyticsResponse(OffsetDateTime.now(clock).toString());
        Map<String, Object> sources = new LinkedHashMap<>();
        result.put("sources", sources);
        result.put("deviceModelsSummary", null); result.put("groupSummary", null);
        for (String key : List.of("accountsTotal", "locationsTotal", "deviceTotal", "groupTotal", "userTotal")) result.put(key, null);
        for (String key : List.of("deviceStatuses", "deviceModels", "groups", "userStatuses")) result.put(key, List.of());
        loadSource(sources, "accounts", selected.contains("accounts"), () -> {
            result.put("accountsTotal", mapper.accountTaskTotal()); return null;
        });
        loadSource(sources, "locations", selected.contains("locations"), () -> {
            result.put("locationsTotal", mapper.locationTaskTotal()); return null;
        });
        loadSource(sources, "devices", selected.contains("devices"), () -> {
            long total = mapper.deviceTotal();
            List<Map<String, Object>> statuses = mapper.deviceStatuses();
            List<Map<String, Object>> models = mapper.deviceModels();
            result.put("deviceTotal", total); result.put("deviceStatuses", statuses); result.put("deviceModels", models);
            result.put("deviceModelsSummary", breakdown(models, total, "value", 12));
            return null;
        });
        loadSource(sources, "groups", selected.contains("groups"), () -> {
            long total = mapper.groupTotal(); List<Map<String, Object>> groups = mapper.groups();
            Map<String, Object> groupSummary = mapper.groupSummary();
            groups.forEach(group -> { if (!group.containsKey("recordedCount")) group.put("recordedCount", null); });
            groupSummary.put("displayedMemberships", sum(groups, "value"));
            groupSummary.put("omittedGroups", total - groups.size());
            result.put("groupTotal", total); result.put("groups", groups); result.put("groupSummary", groupSummary); return null;
        });
        loadSource(sources, "users", selected.contains("users") && permissions.hasPermi("system:user:list"), () -> {
            Map<String, Object> counts = users.summarize(query("all", "all"));
            result.put("userTotal", number(counts, "total"));
            result.put("userStatuses", List.of(bucket("正常", number(counts, "active")),
                bucket("停用", number(counts, "disabled")), bucket("未知", number(counts, "unknown"))));
            return null;
        });
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalyticsResponse content(String period, String kind) {
        AnalyticsQuery query = query(period, kind);
        AnalyticsResponse result = response(query);
        Map<String, Object> sources = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of("videosTotal", "accountVideos", "locationVideos", "accountsTotal", "locationsTotal", "sourceLabelsTotal",
                "accountTargetMemberships", "locationTargetMemberships", "commentRecords", "uniqueCommenters")) summary.put(key, null);
        result.put("sources", sources); result.put("summary", summary);
        Map<String, Object> breakdowns = new LinkedHashMap<>();
        breakdowns.put("topAccounts", null); breakdowns.put("topLocations", null); result.put("breakdowns", breakdowns);
        result.put("assets", List.of()); result.put("trend", List.of());
        result.put("topAccounts", List.of()); result.put("topLocations", List.of());
        boolean accountAllowed = !"location".equals(kind);
        boolean locationAllowed = !"account".equals(kind);
        Map<String, Object> account = loadSource(sources, "accountVideos", accountAllowed, () -> {
            Map<String, Object> data = mapper.accountVideoSummary();
            data.put("trend", mapper.accountVideoTrend(query));
            List<Map<String, Object>> ranking = mapper.topAccounts();
            long memberships = mapper.accountTargetMemberships();
            summary.put("accountTargetMemberships", memberships);
            breakdowns.put("topAccounts", breakdown(ranking, memberships, "value", 10));
            summary.put("accountVideos", number(data, "total")); result.put("topAccounts", ranking);
            return data;
        });
        Map<String, Object> location = loadSource(sources, "locationVideos", locationAllowed, () -> {
            Map<String, Object> data = mapper.locationVideoSummary();
            data.put("trend", mapper.locationVideoTrend(query));
            List<Map<String, Object>> ranking = mapper.topLocations();
            long locations = mapper.locationTotal();
            long memberships = mapper.locationTargetMemberships();
            summary.put("locationTargetMemberships", memberships);
            breakdowns.put("topLocations", breakdown(ranking, memberships, "value", 10));
            summary.put("locationVideos", number(data, "total")); summary.put("locationsTotal", locations);
            summary.put("sourceLabelsTotal", locations);
            result.put("topLocations", ranking); return data;
        });
        loadSource(sources, "accounts", accountAllowed, () -> { summary.put("accountsTotal", mapper.accountTotal()); return null; });
        loadSource(sources, "comments", true, () -> {
            Map<String, Object> data = mapper.commentSummary(query);
            summary.put("commentRecords", number(data, "total")); summary.put("uniqueCommenters", number(data, "uniqueCommenters"));
            return null;
        });
        if ((!accountAllowed || account != null) && (!locationAllowed || location != null)) {
            long total = number(account, "total") + number(location, "total");
            summary.put("videosTotal", total);
            List<Map<String, Object>> assets = new ArrayList<>();
            String[][] assetKeys = {{"视频文件", "videoFiles"}, {"页面截图", "imageFiles"}, {"评论文件", "commentFiles"}};
            for (String[] keys : assetKeys) {
                long covered = number(account, keys[1]) + number(location, keys[1]);
                Map<String, Object> asset = new LinkedHashMap<>(Map.of("name", keys[0], "covered", covered, "total", total));
                asset.put("rate", rate(covered, total)); assets.add(asset);
            }
            result.put("assets", assets);
            Map<String, Map<String, Object>> merged = new TreeMap<>();
            mergeContentTrend(merged, account, "accountVideos"); mergeContentTrend(merged, location, "locationVideos");
            List<Map<String, Object>> trend = new ArrayList<>(merged.values());
            result.put("trendContinuous", continuous(trend, query));
            result.put("trend", fillSeries(trend, query, "accountVideos", "locationVideos", "total"));
        }
        return result;
    }

    private AnalyticsQuery query(String period, String kind) { return new AnalyticsQuery(period, kind, LocalDate.now(clock)); }
    private AnalyticsResponse response(AnalyticsQuery query) {
        AnalyticsResponse response = new AnalyticsResponse(OffsetDateTime.now(clock).toString());
        response.put("period", query.getPeriod()); response.put("kind", query.getKind());
        response.put("rangeStart", query.getStart() == null ? null : query.getStart().toLocalDate().toString());
        response.put("rangeEnd", query.getEnd().minusDays(1).toLocalDate().toString());
        response.put("trendUnit", query.isMonthly() ? "month" : "day");
        response.put("trendContinuous", true);
        return response;
    }

    private <T> T loadSource(Map<String, Object> sources, String name, boolean allowed, Supplier<T> action) {
        if (!allowed) { sources.put(name, Map.of("status", "denied", "message", "未请求此来源或无访问权限")); return null; }
        try {
            T result = action.get(); sources.put(name, Map.of("status", "ok")); return result;
        } catch (RuntimeException ex) {
            LOG.warn("Analytics source {} could not be aggregated", name, ex);
            sources.put(name, Map.of("status", "error", "message", "这部分统计暂时无法加载，请稍后重试"));
            return null;
        }
    }

    static List<Map<String, Object>> fillSeries(List<Map<String, Object>> rows, AnalyticsQuery query, String... values) {
        Map<String, Map<String, Object>> byDate = new TreeMap<>();
        for (Map<String, Object> row : rows) byDate.put(String.valueOf(row.get("date")), row);
        if (query.isMonthly()) {
            if (byDate.isEmpty()) return List.of();
            if (!continuous(rows, query)) return new ArrayList<>(byDate.values());
            List<Map<String, Object>> result = new ArrayList<>();
            YearMonth end = YearMonth.from(query.getEnd().minusDays(1));
            for (YearMonth month = YearMonth.parse(byDate.keySet().iterator().next()); !month.isAfter(end); month = month.plusMonths(1)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", month.toString());
                Map<String, Object> stored = byDate.get(month.toString());
                for (String value : values) row.put(value, number(stored, value));
                result.add(row);
            }
            return result;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate date = query.getStart().toLocalDate(); date.isBefore(query.getEnd().toLocalDate()); date = date.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date.toString());
            Map<String, Object> stored = byDate.get(date.toString());
            for (String value : values) row.put(value, number(stored, value));
            result.add(row);
        }
        return result;
    }

    static boolean continuous(List<Map<String, Object>> rows, AnalyticsQuery query) {
        if (!query.isMonthly() || rows.isEmpty()) return true;
        String first = rows.stream().map(row -> String.valueOf(row.get("date"))).min(String::compareTo).orElseThrow();
        return ChronoUnit.MONTHS.between(YearMonth.parse(first), YearMonth.from(query.getEnd().minusDays(1))) < 1200;
    }

    @SuppressWarnings("unchecked")
    private void mergeContentTrend(Map<String, Map<String, Object>> merged, Map<String, Object> source, String key) {
        if (source == null) return;
        for (Map<String, Object> point : (List<Map<String, Object>>) source.get("trend")) {
            String date = String.valueOf(point.get("date"));
            Map<String, Object> target = merged.computeIfAbsent(date, d -> new LinkedHashMap<>(Map.of("date", d, "accountVideos", 0L, "locationVideos", 0L, "total", 0L)));
            target.put(key, number(point, "value"));
            target.put("total", number(target, "accountVideos") + number(target, "locationVideos"));
        }
    }
    private List<Map<String, Object>> withRates(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) row.put("completionRate", rate(number(row, "completed"), number(row, "total")));
        return rows;
    }
    private Map<String, Object> bucket(String name, long value) { return Map.of("name", name, "value", value); }
    static long sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToLong(row -> number(row, key)).sum();
    }
    static Map<String, Object> breakdown(List<Map<String, Object>> rows, long total, String key, int limit) {
        long displayed = sum(rows, key);
        return Map.of("total", total, "displayed", displayed, "other", total - displayed, "limit", limit);
    }
    static long number(Map<String, Object> row, String key) {
        if (row == null || row.get(key) == null) return 0L;
        return ((Number) row.get(key)).longValue();
    }
    static BigDecimal rate(long count, long total) {
        return total == 0 ? null : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }
}

package com.phone.module.domain.analytics;

import com.phone.common.core.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Server-created filters; never bind BaseEntity.params from an HTTP request. */
public class AnalyticsQuery extends BaseEntity {
    private final String period;
    private final String kind;
    private final LocalDateTime start;
    private final LocalDateTime end;
    private final LocalDateTime calendarStart;

    public AnalyticsQuery(String period, String kind, LocalDate today) {
        if (period == null || !java.util.Set.of("7", "30", "90", "all").contains(period)) {
            throw new IllegalArgumentException("period 只支持 7、30、90、all");
        }
        if (kind == null || !java.util.Set.of("all", "account", "location").contains(kind)) {
            throw new IllegalArgumentException("kind 只支持 all、account、location");
        }
        this.period = period;
        this.kind = kind;
        this.start = "all".equals(period) ? null : today.minusDays(Integer.parseInt(period) - 1L).atStartOfDay();
        this.end = today.plusDays(1).atStartOfDay();
        this.calendarStart = today.minusDays(89).atStartOfDay();
    }
    public String getPeriod() { return period; }
    public String getKind() { return kind; }
    public LocalDateTime getStart() { return start; }
    public LocalDateTime getEnd() { return end; }
    public LocalDateTime getCalendarStart() { return calendarStart; }
    public boolean isMonthly() { return "all".equals(period); }
}

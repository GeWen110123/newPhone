package com.phone.module.domain.analytics;

import java.util.LinkedHashMap;

/** Aggregated dashboard payload. Unavailable sources use null, never a fabricated zero. */
public class AnalyticsResponse extends LinkedHashMap<String, Object> {
    public AnalyticsResponse(String generatedAt) {
        put("generatedAt", generatedAt);
    }
}

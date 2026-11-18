package com.phone.module.service;

import com.phone.common.annotation.DataScope;
import com.phone.module.domain.analytics.AnalyticsQuery;
import com.phone.module.mapper.AnalyticsMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Separate bean so the existing department/self data-scope aspect always runs. */
@Service
public class AnalyticsUserService {
    private final AnalyticsMapper mapper;
    public AnalyticsUserService(AnalyticsMapper mapper) { this.mapper = mapper; }

    @DataScope(deptAlias = "u", userAlias = "u")
    public Map<String, Object> summarize(AnalyticsQuery query) {
        return mapper.users(query);
    }
}

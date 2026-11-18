package com.phone.module.mapper;

import com.phone.common.core.domain.entity.SysRole;
import com.phone.common.core.domain.entity.SysUser;
import com.phone.framework.aspectj.DataScopeAspect;
import com.phone.module.domain.analytics.AnalyticsQuery;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.aspectj.lang.JoinPoint;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsMapperTest {
    private JdbcDataSource dataSource;
    private Configuration configuration;

    @BeforeEach void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1");
        configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = getClass().getResourceAsStream("/mapper/module/AnalyticsMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, "AnalyticsMapper.xml", configuration.getSqlFragments()).parse();
        }
        execute("CREATE TABLE video_tags (id BIGINT, dev_id VARCHAR(64), task_id BIGINT, task_name VARCHAR(255), tags VARCHAR(255), status VARCHAR(20), run_num INT, address VARCHAR(255), create_time TIMESTAMP, update_time TIMESTAMP)");
        execute("CREATE TABLE sys_user (user_id BIGINT, dept_id BIGINT, status VARCHAR(1), del_flag VARCHAR(1))");
        execute("CREATE TABLE video (id BIGINT, uid VARCHAR(64), douyin_id VARCHAR(64), create_time TIMESTAMP, video_path VARCHAR(255), image_path VARCHAR(255), json_path VARCHAR(255))");
        execute("CREATE TABLE address_video (uid VARCHAR(64), video_index BIGINT, douyin_id VARCHAR(64), address VARCHAR(255), create_time TIMESTAMP, video_path VARCHAR(255), image_path VARCHAR(255), json_path VARCHAR(255))");
    }

    @Test void aggregatesBeyondTheOldFiveThousandRowLimitAndSeparatesKinds() throws Exception {
        execute("INSERT INTO video_tags SELECT X, 'dev', 1, 'task', '基本信息,视频', '2', 2, NULL, TIMESTAMP '2026-10-13 09:00:00', NULL FROM SYSTEM_RANGE(1, 6001)");
        execute("INSERT INTO video_tags VALUES (6002,'dev',2,'location','地址,视频','3',-1,NULL,TIMESTAMP '2026-10-13 10:00:00',NULL)");
        execute("INSERT INTO video_tags VALUES (6003,'dev',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
        execute("INSERT INTO video_tags VALUES (6004,'dev',2,'old','基本信息','0',0,NULL,TIMESTAMP '2025-01-01 00:00:00',NULL)");
        try (SqlSession session = session()) {
            AnalyticsMapper mapper = session.getMapper(AnalyticsMapper.class);
            Map<String, Object> selected = mapper.executionSummary(query("7", "all"));
            assertEquals(6002, number(selected, "total"));
            assertEquals(6001, number(selected, "completed"));
            assertEquals(1, number(selected, "error"));
            assertNull(selected.get("runTotal"));
            assertEquals(12002, number(selected, "runKnownTotal"));
            assertEquals(1, number(selected, "runInvalid"));
            assertEquals(6001, number(mapper.executionSummary(query("7", "account")), "total"));
            assertEquals(1, number(mapper.executionSummary(query("7", "location")), "total"));
            assertEquals(6004, number(mapper.executionSummary(query("all", "all")), "total"));
            assertEquals(1, number(mapper.executionSummary(query("all", "all")), "unknown"));
            assertEquals(1, mapper.missingExecutionTime(query("7", "all")));
        }
    }

    @Test void latestAssetSnapshotIsCountedOnceAndMissingUidRowsRemainIndependent() throws Exception {
        execute("INSERT INTO video VALUES (1,'same','a','2026-01-01','old','image',NULL), (2,'same','a','2026-02-01',NULL,'image','comments'), (3,NULL,'b','2026-02-01','video',NULL,NULL), (4,NULL,'b','2026-02-01','video',NULL,NULL)");
        execute("INSERT INTO address_video VALUES ('same',1,'a','place','2026-01-01','old','image',NULL), ('same',2,'a','place','2026-02-01',NULL,'image','comments'), (NULL,3,'b','place','2026-02-01','video',NULL,NULL), (NULL,4,'b','place','2026-02-01','video',NULL,NULL)");
        try (SqlSession session = session()) {
            AnalyticsMapper mapper = session.getMapper(AnalyticsMapper.class);
            for (Map<String, Object> summary : List.of(mapper.accountVideoSummary(), mapper.locationVideoSummary())) {
                assertEquals(3, number(summary, "total"));
                assertEquals(2, number(summary, "videoFiles"));
                assertEquals(1, number(summary, "imageFiles"));
                assertEquals(1, number(summary, "commentFiles"));
            }
        }
    }

    @Test void userAggregationHonorsExistingSelfAndDepartmentScopesAndExcludesDeletedUsers() throws Exception {
        execute("INSERT INTO sys_user VALUES (1,10,'0','0'),(2,10,'1','0'),(3,20,'0','0'),(4,10,'0','2')");
        try (SqlSession session = session()) {
            AnalyticsMapper mapper = session.getMapper(AnalyticsMapper.class);
            AnalyticsQuery self = scoped("5");
            Map<String, Object> selfCount = mapper.users(self);
            assertEquals(1, number(selfCount, "total"));
            assertEquals(1, number(selfCount, "disabled"));
            assertEquals(2, number(mapper.users(scoped("3")), "total"));
            assertEquals(3, number(mapper.users(scoped("1")), "total"));
        }
    }

    @Test void geographicTargetsFollowCrawlerPriorityAndHaveTheirOwnDenominator() throws Exception {
        execute("ALTER TABLE video_tags ADD douyin_id VARCHAR(255)");
        execute("INSERT INTO video_tags(id,tags,douyin_id,address,create_time) VALUES "
            + "(1,'基本信息,视频','account','不应当作执行地点','2026-10-13'),"
            + "(2,'地址','上海',NULL,'2026-10-13'),"
            + "(3,'附近','归档标签','北京','2026-10-13'),"
            + "(4,'综合视频','搜索词','不应当作地点','2026-10-13'),"
            + "(5,'地址,综合视频,附近','杭州','不应覆盖地址目标','2026-10-13'),"
            + "(6,'综合视频,附近','搜索词','不应当作地点','2026-10-13'),"
            + "(7,'附近','归档标签','  ','2026-10-13'),"
            + "(8,'地址',' ',NULL,'2026-10-13'),"
            + "(9,'地址','过期地点',NULL,'2025-01-01'),"
            + "(10,'地址','上海',NULL,'2026-10-13')");
        try (SqlSession session = session()) {
            AnalyticsMapper mapper = session.getMapper(AnalyticsMapper.class);
            Map<String, Object> summary = mapper.executionRegionSummary(query("7", "all"));
            assertEquals(6, number(summary, "total"));
            assertEquals(2, number(summary, "missing"));
            assertEquals(2, number(summary, "searchExcluded"));
            List<Map<String, Object>> regions = mapper.executionRegions(query("7", "all"));
            assertEquals(3, regions.size());
            assertEquals("上海", regions.get(0).get("name"));
            assertEquals(2, number(regions.get(0), "value"));
            assertEquals(0, number(mapper.executionRegionSummary(query("7", "account")), "total"));
            assertTrue(mapper.executionRegions(query("7", "account")).isEmpty());
        }
    }

    @Test void targetRankingsKeepEveryTargetAssociationWithoutDuplicatingSnapshots() throws Exception {
        execute("INSERT INTO video VALUES (1,'shared','a','2026-01-01',NULL,NULL,NULL), (2,'shared','b','2026-02-01',NULL,NULL,NULL), (3,'shared','a','2026-03-01',NULL,NULL,NULL), (4,NULL,'a','2026-03-01',NULL,NULL,NULL), (5,'missing:4','a','2026-03-01',NULL,NULL,NULL)");
        execute("INSERT INTO address_video VALUES ('shared',1,'a','place A','2026-01-01',NULL,NULL,NULL), ('shared',2,'a','place B','2026-02-01',NULL,NULL,NULL), ('shared',3,'a','place A','2026-03-01',NULL,NULL,NULL), (NULL,4,'a','place A','2026-03-01',NULL,NULL,NULL)");
        try (SqlSession session = session()) {
            AnalyticsMapper mapper = session.getMapper(AnalyticsMapper.class);
            assertEquals(3, number(mapper.accountVideoSummary(), "total"));
            assertEquals(4, mapper.accountTargetMemberships());
            assertEquals(3, number(mapper.topAccounts().get(0), "value"));
            assertEquals(2, number(mapper.locationVideoSummary(), "total"));
            assertEquals(3, mapper.locationTargetMemberships());
            assertEquals(2, number(mapper.topLocations().get(0), "value"));
            assertEquals("place A", mapper.topLocations().get(0).get("name"));
        }
    }

    private AnalyticsQuery scoped(String scope) {
        AnalyticsQuery query = query("all", "all");
        SysRole role = new SysRole(); role.setDataScope(scope);
        SysUser user = new SysUser(); user.setUserId(2L); user.setDeptId(10L); user.setRoles(List.of(role));
        JoinPoint point = mock(JoinPoint.class); when(point.getArgs()).thenReturn(new Object[] {query});
        DataScopeAspect.dataScopeFilter(point, user, "u", "u");
        return query;
    }
    private SqlSession session() { return new SqlSessionFactoryBuilder().build(configuration).openSession(); }
    private AnalyticsQuery query(String period, String kind) { return new AnalyticsQuery(period, kind, LocalDate.of(2026, 10, 14)); }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}

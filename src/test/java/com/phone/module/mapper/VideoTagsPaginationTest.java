package com.phone.module.mapper;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageInterceptor;
import com.phone.module.domain.VideoTags;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the real mapper and pagination interceptor against isolated H2 data only. */
class VideoTagsPaginationTest {
    private JdbcDataSource dataSource;
    private SqlSessionFactory sessions;

    @BeforeEach void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        Configuration configuration = new Configuration(
            new Environment("isolated-test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("VideoTags", VideoTags.class);
        PageInterceptor pagination = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        pagination.setProperties(properties);
        configuration.addInterceptor(pagination);
        try (InputStream input = getClass().getResourceAsStream("/mapper/module/VideoTagsMapper.xml")) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, "VideoTagsMapper.xml",
                configuration.getSqlFragments()).parse();
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE video_tags (id BIGINT AUTO_INCREMENT PRIMARY KEY, dev_id VARCHAR(64), "
                + "douyin_id VARCHAR(64), create_time TIMESTAMP, tags VARCHAR(255), status VARCHAR(20), "
                + "task_id BIGINT, task_name VARCHAR(255), remark VARCHAR(255), address VARCHAR(255), "
                + "run_num BIGINT, update_time TIMESTAMP)");
        }
    }

    @Test void insertionPersistsTheApplicationQueueTimestamp() {
        try (SqlSession session = sessions.openSession()) {
            VideoTags task = new VideoTags();
            task.setDouyinId("publisher"); task.setDevId("device"); task.setStatus("0");
            Timestamp time = Timestamp.valueOf("2026-10-16 13:05:54");
            task.setCreateTime(time); task.setUpdateTime(time);
            VideoTagsMapper mapper = session.getMapper(VideoTagsMapper.class);
            mapper.insertVideoTags(task);
            VideoTags stored = mapper.selectVideoTagsById(task.getId());
            assertEquals(time.getTime(), stored.getCreateTime().getTime());
            assertEquals(time.getTime(), stored.getUpdateTime().getTime());
        }
    }

    @Test void equalTimestampsRemainStableAcrossPageBoundariesWithoutLosingExecutions() throws Exception {
        // A lower-ID newer record and higher-ID older record prove time remains the primary key.
        insert(2, "same-account", 7, "2", "2026-10-02 05:09:00");
        for (long id = 100; id <= 123; id++) {
            insert(id, "same-account", 7, "2", "2026-10-01 05:09:00");
        }
        insert(999, "same-account", 7, "2", "2026-09-30 05:09:00");
        List<Long> expected = new ArrayList<>();
        expected.add(2L);
        expected.addAll(LongStream.rangeClosed(100, 123).map(id -> 223 - id).boxed().collect(Collectors.toList()));
        expected.add(999L);

        for (int attempt = 0; attempt < 3; attempt++) {
            List<Long> combined = new ArrayList<>();
            for (int page = 1; page <= 3; page++) {
                PageInfo<VideoTags> result = page(new VideoTags(), page, 10);
                assertEquals(26, result.getTotal());
                assertEquals(page == 3 ? 6 : 10, result.getList().size());
                combined.addAll(ids(result));
            }
            assertEquals(expected, combined);
            assertEquals(26, new HashSet<>(combined).size(), "Each execution must appear on exactly one page");
        }
    }

    @Test void existingFiltersAndTotalStillApplyBeforeStablePagination() throws Exception {
        for (long id = 1; id <= 13; id++) {
            insert(id, "selected-account", 7, "2", "2026-10-01 05:09:00");
        }
        insert(101, "other-account", 7, "2", "2026-10-02 05:09:00");
        insert(102, "selected-account", 8, "2", "2026-10-02 05:09:00");
        insert(103, "selected-account", 7, "0", "2026-10-02 05:09:00");
        VideoTags query = new VideoTags();
        query.setDouyinId("selected-account");
        query.setTaskId(7L);
        query.setStatus("2");

        PageInfo<VideoTags> first = page(query, 1, 10);
        PageInfo<VideoTags> second = page(query, 2, 10);
        assertEquals(13, first.getTotal());
        assertEquals(13, second.getTotal());
        assertEquals(List.of(13L, 12L, 11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L), ids(first));
        assertEquals(List.of(3L, 2L, 1L), ids(second));
        PageInfo<VideoTags> larger = page(query, 1, 20);
        assertEquals(13, larger.getTotal());
        assertEquals(13, larger.getList().size());
        List<Long> combined = new ArrayList<>(ids(first));
        combined.addAll(ids(second));
        assertEquals(combined, ids(larger));
    }

    @Test void missingUpdateTimesRemainLastAndUseTheSameUniqueTieBreaker() throws Exception {
        insert(1, "same-account", 7, "2", "2026-10-01 05:09:00");
        for (long id = 2; id <= 12; id++) insert(id, "same-account", 7, "2", null);
        PageInfo<VideoTags> first = page(new VideoTags(), 1, 10);
        PageInfo<VideoTags> second = page(new VideoTags(), 2, 10);
        assertEquals(12, first.getTotal());
        assertEquals(List.of(1L, 12L, 11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L), ids(first));
        assertEquals(List.of(3L, 2L), ids(second));
    }

    private PageInfo<VideoTags> page(VideoTags query, int page, int size) {
        try (SqlSession session = sessions.openSession()) {
            PageHelper.startPage(page, size);
            return new PageInfo<>(session.getMapper(VideoTagsMapper.class).selectVideoTagsList(query));
        } finally {
            PageHelper.clearPage();
        }
    }

    private List<Long> ids(PageInfo<VideoTags> page) {
        return page.getList().stream().map(VideoTags::getId).collect(Collectors.toList());
    }

    private void insert(long id, String account, long task, String status, String updated) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO video_tags(id, dev_id, douyin_id, task_id, status, create_time, update_time) "
                    + "VALUES (?, 'isolated-device', ?, ?, ?, TIMESTAMP '2026-09-01 00:00:00', ?)")) {
            statement.setLong(1, id);
            statement.setString(2, account);
            statement.setLong(3, task);
            statement.setString(4, status);
            statement.setTimestamp(5, updated == null ? null : Timestamp.valueOf(updated));
            statement.executeUpdate();
        }
    }
}

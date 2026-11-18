package com.phone.module.mapper;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageInterceptor;
import com.phone.module.domain.AddressVideo;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AddressVideoListTest {
    private SqlSessionFactory factory;

    @BeforeEach void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("create table address_video(uid varchar(80),address varchar(80),douyin_id varchar(80),content varchar(500), video_index bigint, dev_id varchar(80), create_time timestamp, video_path varchar(255), image_path varchar(255), json_string varchar(500), json_path varchar(255), follow_count bigint, fans_count bigint, likes_count bigint, ip_address varchar(80))");
            s.execute("insert into address_video(uid,address,douyin_id,dev_id,create_time,ip_address,fans_count,content) values "
                + "('a-old','hotel','a','phone','2026-10-01 10:00:00','上海',999,'old'),"
                + "('a-new','hotel','a','phone','2026-10-02 10:00:00','北京',10,'new'),"
                + "('a-new','hotel','a','phone','2026-10-02 10:00:00','北京',10,'new'),"
                + "('b','hotel','b','phone','2026-10-02 10:00:00','北京',20,'b'),"
                + "('c','hotel','c','phone','2026-10-02 10:00:00','北京',20,'c'),"
                + "('wrong-device','hotel','a','other','2026-10-03 10:00:00','上海',2000,'wrong-device'),"
                + "('wrong-address','elsewhere','a','phone','2026-10-04 10:00:00','上海',3000,'wrong-address')");
        }
        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.getTypeAliasRegistry().registerAlias("AddressVideo", AddressVideo.class);
        PageInterceptor interceptor = new PageInterceptor();
        Properties properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        interceptor.setProperties(properties);
        cfg.addInterceptor(interceptor);
        try (var input = getClass().getResourceAsStream("/mapper/module/AddressVideoMapper.xml")) {
            new XMLMapperBuilder(input, cfg, "AddressVideoMapper.xml", cfg.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(cfg);
    }

    private AddressVideo filter() {
        AddressVideo filter = new AddressVideo();
        filter.setAddress("hotel");
        filter.setDevId("phone");
        filter.setDouyinId("");
        filter.setSortField("");
        filter.setSortOrder("");
        return filter;
    }

    private PageInfo<AddressVideo> page(AddressVideo filter, int number, int size) {
        try (var session = factory.openSession()) {
            PageHelper.startPage(number, size);
            return new PageInfo<>(session.getMapper(AddressVideoMapper.class).selectAddressVideoList(filter));
        } finally {
            PageHelper.clearPage();
        }
    }

    @Test void preservesCompleteLatestSnapshotAndStablePublisherPagination() {
        var first = page(filter(), 1, 2);
        var second = page(filter(), 2, 2);
        assertEquals(3, first.getTotal());
        assertEquals(3, second.getTotal());
        assertEquals(List.of("a", "b"), first.getList().stream().map(AddressVideo::getDouyinId).collect(Collectors.toList()));
        assertEquals("c", second.getList().get(0).getDouyinId());
        assertEquals("a-new", first.getList().get(0).getUid());
        assertEquals("new", first.getList().get(0).getContent());
        assertEquals(10L, first.getList().get(0).getFansCount());
    }

    @Test void appliesDeviceAddressIpAndPublisherFiltersBeforeRanking() {
        AddressVideo filter = filter();
        filter.setIpAddress("上海");
        filter.setDouyinId("a");
        var result = page(filter, 1, 10);
        assertEquals(1, result.getTotal());
        assertEquals("a-old", result.getList().get(0).getUid());
        filter.setAddress("missing");
        assertEquals(0, page(filter, 1, 10).getTotal());
    }

    @Test void sortsSelectedSnapshotsAndBreaksEqualValuesByPublisher() {
        AddressVideo filter = filter();
        filter.setSortField("fansCount");
        filter.setSortOrder("desc");
        var result = page(filter, 1, 10);
        assertEquals(List.of("b", "c", "a"), result.getList().stream().map(AddressVideo::getDouyinId).collect(Collectors.toList()));
        filter.setSortOrder("asc");
        assertEquals("a", page(filter, 1, 10).getList().get(0).getDouyinId());
    }
}

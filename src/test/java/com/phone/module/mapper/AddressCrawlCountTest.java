package com.phone.module.mapper;

import com.phone.module.domain.AddressVideo;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AddressCrawlCountTest {
    @Test void countsDistinctVideosNotAuthorsOrDuplicateRows() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:crawlcount;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var c = ds.getConnection(); var stmt = c.createStatement()) {
            stmt.execute("create table address_video(uid varchar(80),address varchar(80),douyin_id varchar(80),content varchar(500), video_index bigint, dev_id varchar(80), create_time timestamp, video_path varchar(255), image_path varchar(255), json_string varchar(500), json_path varchar(255), follow_count bigint, fans_count bigint, likes_count bigint, ip_address varchar(80))");
            stmt.execute("insert into address_video(uid,address,douyin_id) values ('v1','圆山','same-author'),('v2','圆山','same-author'),"
                    + "('v2','圆山','same-author'),('v2','other','other-author')");
        }
        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.getTypeAliasRegistry().registerAlias("AddressVideo", AddressVideo.class);
        try (var input = getClass().getResourceAsStream("/mapper/module/AddressVideoMapper.xml")) {
            new XMLMapperBuilder(input, cfg, "AddressVideoMapper.xml", cfg.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(cfg).openSession()) {
            assertEquals(2, session.getMapper(AddressVideoMapper.class).countAddressVideos("圆山"));
            assertNotNull(session.getMapper(AddressVideoMapper.class).selectAddressVideo("v2", "圆山"));
            assertNotNull(session.getMapper(AddressVideoMapper.class).selectAddressVideoByUid("v2"));
            assertEquals(0, session.getMapper(AddressVideoMapper.class).countAddressVideos("missing"));
            AddressVideo updated = new AddressVideo();
            updated.setUid("v2"); updated.setAddress("圆山"); updated.setContent("[]");
            assertEquals(2, session.getMapper(AddressVideoMapper.class).updateCrawledAddressVideo(updated));
            try (var stmt = session.getConnection().createStatement(); var result = stmt.executeQuery(
                    "select content from address_video where address='other'")) {
                assertTrue(result.next());
                assertNull(result.getString(1));
            }
            try (var insert = session.getConnection().prepareStatement(
                    "insert into address_video(uid,address,content) values (?,'archive','[{}]')")) {
                for (int i = 0; i < 25; i++) { insert.setString(1, "pending" + i); insert.executeUpdate(); }
            }
            assertEquals(20, session.getMapper(AddressVideoMapper.class).selectAddressVideoList200(new AddressVideo()).size());
        }
    }
}

package com.phone.module.mapper;

import com.phone.module.domain.Video;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VideoCrawlCountTest {
    @Test void countsDistinctVideosAndBoundsRawCommentMaintenanceReads() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:personcount;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var c = ds.getConnection(); var stmt = c.createStatement()) {
            stmt.execute("create table video(id bigint auto_increment primary key,uid varchar(80),address varchar(80),douyin_id varchar(80),content varchar(500), video_index bigint, dev_id varchar(80), create_time timestamp, video_path varchar(255), image_path varchar(255), json_string varchar(500), json_path varchar(255))");
            try (var insert = c.prepareStatement("insert into video(uid,douyin_id,content) values (?,'author','[{}]')")) {
                for (int i = 0; i < 25; i++) { insert.setString(1, "video" + i); insert.executeUpdate(); }
                insert.setString(1, "video0"); insert.executeUpdate();
            }
        }
        Configuration cfg = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        cfg.getTypeAliasRegistry().registerAlias("Video", Video.class);
        try (var input = getClass().getResourceAsStream("/mapper/module/VideoMapper.xml")) {
            new XMLMapperBuilder(input, cfg, "VideoMapper.xml", cfg.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(cfg).openSession()) {
            VideoMapper mapper = session.getMapper(VideoMapper.class);
            assertEquals(25, mapper.countVideos("author"));
            assertEquals(0, mapper.countVideos("missing"));
            assertNotNull(mapper.selectVideoByUId("video0"));
            assertEquals(20, mapper.selectVideoList200(new Video()).size());
        }
    }
}

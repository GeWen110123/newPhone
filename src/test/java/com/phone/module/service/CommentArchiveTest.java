package com.phone.module.service;

import com.phone.module.domain.AddressVideo;
import com.phone.module.domain.Comment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommentArchiveTest {
    JdbcTemplate jdbc;
    ICommentService comments;
    CommentArchiveService archive;
    AddressVideo source;

    @BeforeEach void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:archive" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table address_video(uid varchar(80), address varchar(80), douyin_id varchar(80), content varchar(10000), json_string varchar(2000), create_time timestamp)");
        jdbc.execute("create table comment(uid varchar(80), user_uid varchar(80), nickname varchar(80), comment_text varchar(1000), reply_to varchar(80), comment_time varchar(80))");
        comments = mock(ICommentService.class);
        when(comments.insertComment(any())).thenAnswer(i -> insert(i.getArgument(0)));
        CommentArchiveService target = new CommentArchiveService(ds, comments);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds), new AnnotationTransactionAttributeSource()));
        archive = (CommentArchiveService) factory.getProxy();
        source = new AddressVideo(); source.setUid("video"); source.setAddress("圆山"); source.setDouyinId("publisher");
        source.setJsonString("{\"comments\":\"2\",\"comments_complete\":true}");
        source.setContent("[{\"user_uid\":\"u1\",\"text\":\"one\"},{\"user_uid\":\"u2\",\"text\":\"two\"}]");
        jdbc.update("insert into address_video values (?,?,?,?,?,CURRENT_TIMESTAMP)", source.getUid(), source.getAddress(), source.getDouyinId(), source.getContent(), source.getJsonString());
    }

    @Test void archiveIsIdempotentAndDoesNotChangeOtherAddressRows() throws Exception {
        jdbc.update("insert into address_video values ('video','elsewhere','other',?,?,CURRENT_TIMESTAMP)", source.getContent(), source.getJsonString());
        assertTrue(archive.archive(source));
        assertFalse(archive.archive(source));
        assertEquals(2, jdbc.queryForObject("select count(*) from comment", Integer.class));
        assertEquals("", jdbc.queryForObject("select content from address_video where address='圆山'", String.class));
        assertEquals(source.getContent(), jdbc.queryForObject("select content from address_video where address='elsewhere'", String.class));
        assertTrue(jdbc.queryForObject("select json_string from address_video where address='圆山'", String.class).contains("\"comments_imported_count\":2"));
    }

    @Test void failedInsertRollsBackEarlierCommentsAndPreservesRawSource() throws Exception {
        doAnswer(i -> {
            Comment comment = i.getArgument(0);
            if ("u2".equals(comment.getUserUid())) throw new IllegalStateException("connection lost");
            return insert(comment);
        }).when(comments).insertComment(any());
        assertThrows(IllegalStateException.class, () -> archive.archive(source));
        assertEquals(0, jdbc.queryForObject("select count(*) from comment", Integer.class));
        assertEquals(source.getContent(), jdbc.queryForObject("select content from address_video", String.class));
        doAnswer(i -> insert(i.getArgument(0))).when(comments).insertComment(any());
        assertTrue(archive.archive(source));
        assertEquals(2, jdbc.queryForObject("select count(*) from comment", Integer.class));
    }

    @Test void historicalCommentAndDuplicateRawEntriesAreNotInsertedAgain() throws Exception {
        jdbc.update("insert into comment values ('video','u1','','one','','')");
        String duplicated = "[{\"user_uid\":\"u1\",\"text\":\"one\"},{\"user_uid\":\"u1\",\"text\":\"one\"}]";
        jdbc.update("update address_video set content=?", duplicated); source.setContent(duplicated);
        assertTrue(archive.archive(source));
        assertEquals(1, jdbc.queryForObject("select count(*) from comment", Integer.class));
        assertTrue(jdbc.queryForObject("select json_string from address_video", String.class).contains("\"comments_imported_count\":1"));
    }

    @Test void changedSnapshotIsNotOverwritten() throws Exception {
        jdbc.update("update address_video set content='[{\"text\":\"newer\"}]'");
        assertFalse(archive.archive(source));
        assertEquals(0, jdbc.queryForObject("select count(*) from comment", Integer.class));
    }

    private int insert(Comment c) {
        return jdbc.update("insert into comment values (?,?,?,?,?,?)", c.getUid(), c.getUserUid(), c.getNickname(), c.getCommentText(), c.getReplyTo(), c.getCommentTime());
    }
}

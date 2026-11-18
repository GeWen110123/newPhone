package com.phone.module.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.phone.module.domain.AddressVideo;
import com.phone.module.domain.Comment;
import com.phone.module.domain.Video;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.*;

/** Atomically move a raw comment snapshot into the searchable comment table. */
@Service
public class CommentArchiveService {
    private final JdbcTemplate jdbc;
    private final ICommentService comments;
    private final ObjectMapper mapper = new ObjectMapper();

    public CommentArchiveService(DataSource dataSource, ICommentService comments) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.comments = comments;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean archive(Video video) throws Exception {
        return archive("video", "id = ?", new Object[]{video.getId()}, video.getUid(),
                video.getDouyinId(), video.getContent(), video.getJsonString());
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean archive(AddressVideo video) throws Exception {
        return archive("address_video", "uid = ? and address = ?", new Object[]{video.getUid(), video.getAddress()},
                video.getUid(), video.getDouyinId(), video.getContent(), video.getJsonString());
    }

    private boolean archive(String table, String predicate, Object[] key, String uid,
                            String author, String content, String metadata) throws Exception {
        if (content == null || content.isEmpty() || "[]".equals(content)) return false;
        JsonNode array = mapper.readTree(content);
        if (array == null || !array.isArray()) throw new IllegalArgumentException("评论不是数组 uid=" + uid);
        // Lock and recheck the actual source. A second invocation must not consume a stale snapshot.
        List<Map<String, Object>> sources = jdbc.queryForList(
                "select content, json_string from " + table + " where " + predicate + " for update", key);
        boolean current = sources.stream().anyMatch(row -> Objects.equals(content, row.get("content"))
                && Objects.equals(metadata, row.get("json_string")));
        if (!current) return false;
        Set<List<String>> seen = new HashSet<>();
        Set<List<String>> snapshot = new HashSet<>();
        jdbc.query("select user_uid, nickname, comment_text, reply_to, comment_time from comment where uid = ?",
                rs -> { seen.add(identity(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))); }, uid);
        for (JsonNode node : array) {
            if (!node.isObject()) throw new IllegalArgumentException("评论元素不是对象 uid=" + uid);
            List<String> identity = identity(node.path("user_uid").asText(), node.path("nickname").asText(),
                    node.path("text").asText(), node.path("replyTo").asText(), node.path("time").asText());
            snapshot.add(identity);
            if (!seen.add(identity)) continue;
            Comment c = new Comment();
            c.setUid(uid); c.setUserDouyin(author);
            c.setUserUid(node.path("user_uid").asText()); c.setNickname(node.path("nickname").asText());
            c.setGender(node.path("gender").asText()); c.setAge(node.path("age").asText());
            c.setRealName(node.path("real_name").asText()); c.setSignature(node.path("signature").asText());
            c.setFansCount(node.path("fans_count").asText()); c.setFollowCount(node.path("follow_count").asText());
            c.setLikesCount(node.path("likes_count").asText()); c.setWorksCount(node.path("works_count").asText());
            c.setProfileText(node.path("profile_text").asText()); c.setIpLocation(node.path("ip_location").asText());
            c.setRegion(node.path("region").asText()); c.setTouxiang(node.path("touxiang").asText());
            c.setZhuye(node.path("zhuye").asText()); c.setCommentText(node.path("text").asText());
            c.setReplyTo(node.path("replyTo").asText()); c.setCommentTime(node.path("time").asText());
            c.setStatus("0");
            if (comments.insertComment(c) != 1) throw new IllegalStateException("评论写入失败 uid=" + uid);
        }
        JsonNode old = metadata == null || metadata.isEmpty() ? null : mapper.readTree(metadata);
        ObjectNode updated = old != null && old.isObject() ? (ObjectNode) old : mapper.createObjectNode();
        updated.put("comments_imported", true);
        updated.put("comments_imported_count", snapshot.size());
        List<Object> args = new ArrayList<>();
        args.add(mapper.writeValueAsString(updated));
        args.addAll(Arrays.asList(key));
        args.add(content);
        if (metadata != null) args.add(metadata);
        // Update only archival fields on the matching snapshot, never publisher/address/time.
        int changed = jdbc.update("update " + table + " set content = '', json_string = ? where " + predicate
                + " and content = ? and json_string " + (metadata == null ? "is null" : "= ?"), args.toArray());
        if (changed == 0) throw new IllegalStateException("评论源在转存时改变 uid=" + uid);
        return true;
    }

    private static List<String> identity(String user, String nickname, String text, String reply, String time) {
        return Arrays.asList(user == null || user.isEmpty() ? "name:" + value(nickname) : "id:" + user,
                value(text), value(reply), value(time));
    }

    private static String value(String value) { return value == null ? "" : value; }
}

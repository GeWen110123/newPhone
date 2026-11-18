package com.phone.module.service;

import com.phone.module.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublisherTaskTest {
    DouyinTaskService service;
    IVideoTagsService tasks;
    IVideoService videos;
    IAccountService accounts;
    IAccountIpService ips;
    Map<String, Object> info;

    @BeforeEach void setup() {
        service = new DouyinTaskService();
        tasks = mock(IVideoTagsService.class); videos = mock(IVideoService.class);
        accounts = mock(IAccountService.class); ips = mock(IAccountIpService.class);
        ReflectionTestUtils.setField(service, "videoTagsService", tasks);
        ReflectionTestUtils.setField(service, "videoService", videos);
        ReflectionTestUtils.setField(service, "accountService", accounts);
        ReflectionTestUtils.setField(service, "accountIpService", ips);
        info = Map.of("id", "publisher", "works_count", "184", "region", "IP：中国台湾");
    }

    @Test void completedTaskWithZeroVideosIsRequeuedAndIpStillSaved() {
        VideoTags old = task("2", "视频,基本信息,评论,前六条");
        old.setRunNum(30L);
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(old));
        service.storeAccountAsync("device", "publisher", info, "地址,基本信息,单用户前六条视频");
        assertEquals("0", old.getStatus()); assertEquals(0L, old.getRunNum());
        verify(tasks).updateVideoTags(old);
        verify(ips).insertAccountIp(any());
        ArgumentCaptor<VideoTags> query = ArgumentCaptor.forClass(VideoTags.class);
        verify(tasks).selectVideoTagsList(query.capture());
        assertEquals("device", query.getValue().getDevId());
        verify(videos, never()).selectVideoList(any());
    }

    @Test void allVideoUpgradeKeepsDataAcquisitionFlags() {
        VideoTags old = task("2", "视频,基本信息,评论,前六条");
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(old));
        service.enqueuePublisher("device", "publisher", info, "单用户所有视频");
        assertEquals("视频,基本信息,评论", old.getTags());
        assertEquals("0", old.getStatus());
    }

    @Test void runningTaskIsNotResetWhileAnotherWorkerUsesIt() {
        VideoTags old = task("1", "视频,基本信息,评论,前六条");
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(old));
        service.enqueuePublisher("device", "publisher", info, "单用户前六条视频");
        verify(tasks, never()).updateVideoTags(any());
    }

    @Test void newTasksHaveExplicitPendingStatusAndInvalidIdentityIsRejected() {
        service.enqueuePublisher("device", "publisher", info, "单用户前六条视频");
        ArgumentCaptor<VideoTags> created = ArgumentCaptor.forClass(VideoTags.class);
        verify(tasks).insertVideoTags(created.capture());
        assertEquals("0", created.getValue().getStatus()); assertEquals(0L, created.getValue().getRunNum());
        assertThrows(IllegalArgumentException.class, () -> service.enqueuePublisher("device", "NICKNAME_test",
                Map.of("id", "NICKNAME_test"), "单用户前六条视频"));
    }

    @Test void successfulSixVideoTaskIsNotRepeated() {
        VideoTags old = task("2", "视频,基本信息,评论,前六条");
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(old));
        when(videos.countVideos("publisher")).thenReturn(6L);
        service.enqueuePublisher("device", "publisher", info, "单用户前六条视频");
        verify(tasks, never()).updateVideoTags(any());
    }

    @Test void databaseFailureMustReachTheCaller() {
        when(accounts.insertAccount(any())).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> service.storeAccountAsync("device", "publisher", info, "基本信息"));
    }

    private VideoTags task(String status, String tags) {
        VideoTags task = new VideoTags(); task.setId(932L); task.setDevId("device");
        task.setDouyinId("publisher"); task.setTags(tags); task.setStatus(status); return task;
    }
}

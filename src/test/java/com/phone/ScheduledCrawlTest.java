package com.phone;

import com.phone.module.domain.*;
import com.phone.module.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledCrawlTest {
    @Test void deviceScopeCannotChangeOrDispatchOtherDevices() {
        ScheduledTask scheduler = new ScheduledTask();
        IVideoTagsService tasks = mock(IVideoTagsService.class);
        VideoTags other = new VideoTags(); other.setDevId("old-phone"); other.setStatus("0"); other.setRunNum(30L);
        VideoTags orphan = new VideoTags(); orphan.setDevId("old-phone"); orphan.setStatus("1");
        when(tasks.selectVideoTagsListASC(any())).thenReturn(List.of(other));
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(orphan));
        ReflectionTestUtils.setField(scheduler, "schedulerDeviceId", "emulator-5554");
        ReflectionTestUtils.setField(scheduler, "videoTagsService", tasks);
        ReflectionTestUtils.invokeMethod(scheduler, "douyinTasks");
        verify(tasks, never()).updateVideoTags(any());
        verify(tasks).selectVideoTagsListASC(argThat(q -> "emulator-5554".equals(q.getDevId())));
        assertEquals("0", other.getStatus());
        assertEquals("1", orphan.getStatus());
    }

    @Test void scopedQueueDoesNotRunGlobalMaintenance() throws Exception {
        ScheduledTask scheduler = new ScheduledTask();
        ReflectionTestUtils.setField(scheduler, "schedulerDeviceId", "emulator-5554");
        ICommentService comments = mock(ICommentService.class);
        CommentArchiveService archive = mock(CommentArchiveService.class);
        ReflectionTestUtils.setField(scheduler, "commentService", comments);
        ReflectionTestUtils.setField(scheduler, "commentArchiveService", archive);
        scheduler.runOnStartup();
        scheduler.getJson();
        ReflectionTestUtils.invokeMethod(scheduler, "configureTasks");
        verifyNoInteractions(comments, archive);
    }
    @Test void stoppedWorkerIsRequeuedWhenItsShortLeaseExpires() {
        ScheduledTask scheduler = new ScheduledTask();
        IVideoTagsService tasks = mock(IVideoTagsService.class);
        RedisTemplate redis = mock(RedisTemplate.class);
        VideoTags task = new VideoTags(); task.setStatus("1"); task.setDevId("device"); task.setRunNum(1L);
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(task));
        when(redis.hasKey(any())).thenReturn(false);
        ReflectionTestUtils.setField(scheduler, "videoTagsService", tasks);
        ReflectionTestUtils.setField(scheduler, "redisTemplate", redis);
        ReflectionTestUtils.invokeMethod(scheduler, "recoverAbnormalTasks");
        assertEquals("0", task.getStatus());
        verify(tasks).updateVideoTags(task);
    }
    @Test void failedPublisherCanRetryWhileAddressTaskIsStillRunning() {
        ScheduledTask scheduler = new ScheduledTask();
        IVideoTagsService tasks = mock(IVideoTagsService.class);
        RedisTemplate redis = mock(RedisTemplate.class);
        VideoTags address = new VideoTags(); address.setStatus("1"); address.setDevId("device");
        VideoTags publisher = new VideoTags(); publisher.setStatus("3"); publisher.setRunNum(1L);
        publisher.setUpdateTime(new Date(System.currentTimeMillis() - 600000));
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(address, publisher));
        when(redis.hasKey(any())).thenReturn(true);
        ReflectionTestUtils.setField(scheduler, "videoTagsService", tasks);
        ReflectionTestUtils.setField(scheduler, "redisTemplate", redis);
        ReflectionTestUtils.invokeMethod(scheduler, "recoverAbnormalTasks");
        assertEquals("0", publisher.getStatus());
        assertEquals("1", address.getStatus());
    }
    @Test void orphanCommentsDoNotLoopForeverOrPreventLaterRepairs() throws Exception {
        ScheduledTask scheduler = new ScheduledTask();
        ICommentService comments = mock(ICommentService.class);
        IVideoService videos = mock(IVideoService.class);
        ReflectionTestUtils.setField(scheduler, "commentService", comments);
        ReflectionTestUtils.setField(scheduler, "videoService", videos);
        ReflectionTestUtils.setField(scheduler, "addressVideoService", mock(IAddressVideoService.class));
        Comment orphan = new Comment(); orphan.setId(2L); orphan.setUid("missing");
        Comment valid = new Comment(); valid.setId(3L); valid.setUid("present");
        Video video = new Video(); video.setDouyinId("author");
        when(videos.selectVideoByUId("present")).thenReturn(video);
        when(comments.selectCommentList200(any())).thenAnswer(i -> {
            Long cursor = ((Comment)i.getArgument(0)).getId();
            return cursor == 0 ? List.of(orphan, valid) : Collections.emptyList();
        });
        scheduler.runOnStartup();
        verify(comments, times(2)).selectCommentList200(any());
        verify(comments, never()).updateComment(orphan);
        verify(comments).updateComment(valid);
    }
    @Test void marksRunningBeforeDispatchAndCannotOverwriteFastCompletion() {
        ScheduledTask scheduler = new ScheduledTask();
        IVideoTagsService tasks = mock(IVideoTagsService.class);
        DouyinVideoService worker = mock(DouyinVideoService.class);
        DeviceLeaseService leases = mock(DeviceLeaseService.class);
        IAccountService accounts = mock(IAccountService.class);
        ReflectionTestUtils.setField(scheduler, "videoTagsService", tasks);
        ReflectionTestUtils.setField(scheduler, "douyinVideoService", worker);
        ReflectionTestUtils.setField(scheduler, "deviceLeases", leases);
        ReflectionTestUtils.setField(scheduler, "accountService", accounts);
        ReflectionTestUtils.setField(scheduler, "runLogService", mock(IRunLogService.class));
        ReflectionTestUtils.setField(scheduler, "redisTemplate", mock(RedisTemplate.class));
        VideoTags task = new VideoTags(); task.setId(927L); task.setDevId("test-device");
        task.setDouyinId("圆山"); task.setStatus("0");
        when(tasks.selectVideoTagsListASC(any())).thenReturn(List.of(task));
        when(leases.acquire(anyString())).thenReturn("token");
        List<String> writtenStatuses = new ArrayList<>();
        doAnswer(i -> { writtenStatuses.add(((VideoTags)i.getArgument(0)).getStatus()); return 1; })
                .when(tasks).updateVideoTags(any());
        doAnswer(i -> {
            assertEquals("1", task.getStatus());
            assertEquals(List.of("1"), writtenStatuses);
            task.setStatus("2"); return null;
        }).when(worker).asyncCrawlTask(anyString(), anyString(), any(), anyString(), anyString());
        ReflectionTestUtils.invokeMethod(scheduler, "douyinTasks");
        assertEquals("2", task.getStatus());
        assertEquals(List.of("1"), writtenStatuses);
        verify(leases, never()).release(anyString(), anyString());
    }

    @Test void retryLimitRemainsFailedRatherThanSuccessful() {
        ScheduledTask scheduler = new ScheduledTask();
        IVideoTagsService tasks = mock(IVideoTagsService.class);
        VideoTags task = new VideoTags(); task.setStatus("0"); task.setRunNum(30L);
        when(tasks.selectVideoTagsListASC(any())).thenReturn(List.of(task));
        ReflectionTestUtils.setField(scheduler, "videoTagsService", tasks);
        ReflectionTestUtils.invokeMethod(scheduler, "douyinTasks");
        assertEquals("3", task.getStatus());
    }
}

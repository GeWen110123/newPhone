package com.phone.module.controller;

import com.phone.module.domain.VideoTags;
import com.phone.module.service.IRunLogService;
import com.phone.module.service.IVideoTagsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoTagsCreateTest {
    private final IVideoTagsService tasks = mock(IVideoTagsService.class);
    private final IRunLogService logs = mock(IRunLogService.class);

    private VideoTagsController controller() {
        var controller = new VideoTagsController();
        ReflectionTestUtils.setField(controller, "videoTagsService", tasks);
        ReflectionTestUtils.setField(controller, "runLogService", logs);
        return controller;
    }

    private VideoTags request() {
        var task = new VideoTags();
        task.setDevId("127.0.0.1:5555");
        task.setDouyinId("台湾圆山大饭店");
        task.setTags("地址,视频,基本信息,单用户前六条视频");
        task.setTaskName("地址搜索：台湾圆山大饭店");
        task.setRemark("使用模拟器执行地址搜索");
        task.setAddress("台湾圆山大饭店");
        return task;
    }

    @Test void createsAddressTaskOnSelectedEmulatorAndPreservesMetadata() {
        var request = request();
        assertEquals(200, controller().add(request).get("code"));
        verify(tasks).selectVideoTagsList(argThat(q ->
                request.getDevId().equals(q.getDevId()) && request.getTags().equals(q.getTags())
                        && request.getDouyinId().equals(q.getDouyinId()) && Long.valueOf(3).equals(q.getTaskId())));
        verify(tasks).insertVideoTags(argThat(t -> request.getTaskName().equals(t.getTaskName())
                && request.getAddress().equals(t.getAddress()) && request.getRemark().equals(t.getRemark())
                && "0".equals(t.getStatus()) && Long.valueOf(0).equals(t.getRunNum())));
    }

    @Test void explicitRetryResetsExhaustionAndKeepsTaskIdentity() {
        var existing = request(); existing.setId(928L); existing.setStatus("3"); existing.setRunNum(30L);
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(existing));
        controller().add(request());
        assertEquals(928L, existing.getId());
        assertEquals(0L, existing.getRunNum());
        assertEquals("0", existing.getStatus());
        verify(tasks).updateVideoTags(existing);
        verify(tasks, never()).insertVideoTags(any());
    }

    @Test void runningTaskCannotBeRequeuedOrModifiedByDuplicateSubmission() {
        var existing = request(); existing.setStatus("1"); existing.setRunNum(2L);
        when(tasks.selectVideoTagsList(any())).thenReturn(List.of(existing));
        controller().add(request());
        assertEquals("1", existing.getStatus());
        assertEquals(2L, existing.getRunNum());
        verify(tasks, never()).updateVideoTags(any());
        verifyNoInteractions(logs);
    }

    @Test void missingDeviceOrTaskTypeCannotCreateAnUnrunnableTask() {
        var request = request(); request.setDevId(" ");
        assertEquals(500, controller().add(request).get("code"));
        request.setDevId("127.0.0.1:5555"); request.setTags(" ");
        assertEquals(500, controller().add(request).get("code"));
        verifyNoInteractions(tasks, logs);
    }
}

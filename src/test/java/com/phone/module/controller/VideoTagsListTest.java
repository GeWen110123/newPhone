package com.phone.module.controller;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.phone.module.domain.VideoTags;
import com.phone.module.service.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoTagsListTest {
    @Test void mixedExecutionPageCountsVideosWithoutLoadingGroupedPublisherLists() {
        var controller = new VideoTagsController();
        var tasks = mock(IVideoTagsService.class);
        var accounts = mock(IAccountService.class);
        var comments = mock(IAccountContentService.class);
        var videos = mock(IVideoService.class);
        var addressVideos = mock(IAddressVideoService.class);
        ReflectionTestUtils.setField(controller, "videoTagsService", tasks);
        ReflectionTestUtils.setField(controller, "accountService", accounts);
        ReflectionTestUtils.setField(controller, "accountContentService", comments);
        ReflectionTestUtils.setField(controller, "videoService", videos);
        ReflectionTestUtils.setField(controller, "addressVideoService", addressVideos);
        Page<VideoTags> page = new Page<>(1, 10);
        for (String label : new String[]{"地址视频", "附近视频", "综合视频", "基本信息,视频"}) {
            VideoTags task = new VideoTags();
            task.setTags(label);
            task.setDouyinId(label);
            page.add(task);
            when(addressVideos.countAddressVideos(label)).thenReturn(315L);
            when(videos.countVideos(label)).thenReturn(6L);
        }
        page.setTotal(550);
        when(tasks.selectVideoTagsList(any())).thenReturn(page);
        when(accounts.selectAccountList(any())).thenReturn(Collections.emptyList());
        when(comments.selectAccountContentCountSum(anyString())).thenReturn(12L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            var result = controller.list(new VideoTags());
            assertEquals(200, result.getCode());
            assertEquals(550, result.getTotal());
            assertSame(page, result.getRows());
            assertEquals(315, page.get(0).getVideoCount());
            assertEquals(315, page.get(1).getVideoCount());
            assertEquals(315, page.get(2).getVideoCount());
            assertEquals(6, page.get(3).getVideoCount());
            assertEquals(12L, page.get(3).getCounts());
            verify(addressVideos, never()).selectAddressVideoList(any());
            verify(videos, never()).selectVideoList(any());
        } finally {
            RequestContextHolder.resetRequestAttributes();
            PageHelper.clearPage();
        }
    }
}

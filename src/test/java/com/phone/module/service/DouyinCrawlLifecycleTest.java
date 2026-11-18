package com.phone.module.service;

import com.phone.module.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DouyinCrawlLifecycleTest {
    DouyinVideoService service;
    DeviceLeaseService leases;
    IVideoTagsService tasks;
    IAccountService accounts;
    IAddressVideoService videos;
    IRunLogService logs;
    VideoTags task;

    @BeforeEach void setup() throws Exception {
        service = spy(new DouyinVideoService());
        leases = mock(DeviceLeaseService.class);
        tasks = mock(IVideoTagsService.class);
        accounts = mock(IAccountService.class);
        videos = mock(IAddressVideoService.class);
        logs = mock(IRunLogService.class);
        ReflectionTestUtils.setField(service, "deviceLeases", leases);
        ReflectionTestUtils.setField(service, "videoTagsService", tasks);
        ReflectionTestUtils.setField(service, "accountService", accounts);
        ReflectionTestUtils.setField(service, "addressVideoService", videos);
        ReflectionTestUtils.setField(service, "runLogService", logs);
        when(leases.keepAlive("lock", "token")).thenReturn(() -> {});
        task = new VideoTags(); task.setId(927L); task.setTags("地址,评论"); task.setDouyinId("圆山");
        doReturn(Collections.emptyList()).when(service).crawlSingleAccount(anyString(), anyString(), anyString(), nullable(String.class));
        Account account = new Account(); account.setJsonString("{\"works_count\":\"2182\"}");
        when(accounts.selectAccountList(any())).thenReturn(List.of(account));
        when(videos.countAddressVideos("圆山")).thenReturn(446L);
    }

    @Test void partialAddressDataIsNotMarkedCompleteByOldDividedThreshold() {
        run();
        assertEquals("3", task.getStatus());
        verify(videos, never()).selectAddressVideoList(any());
        var order = inOrder(tasks, leases);
        order.verify(leases).keepAlive("lock", "token");
        order.verify(tasks).updateVideoTags(task);
        order.verify(leases).release("lock", "token");
    }

    @Test void knownFullCountCanComplete() {
        when(videos.countAddressVideos("圆山")).thenReturn(2182L);
        run(); assertEquals("2", task.getStatus());
    }

    @Test void missingCountCannotProduceFalseSuccess() {
        when(accounts.selectAccountList(any())).thenReturn(Collections.emptyList());
        run(); assertEquals("3", task.getStatus());
    }

    @Test void savedBatchYieldsToPublisherWithoutConsumingFailureBudget() {
        task.setRunNum(3L);
        doReturn(new com.phone.adb.CrawlBatch(List.of(Map.of("uid", "saved"))))
                .when(service).crawlSingleAccount(anyString(), anyString(), anyString(), nullable(String.class));
        run();
        assertEquals("0", task.getStatus());
        assertEquals(2L, task.getRunNum());
        verify(leases).release("lock", "token");
    }

    @Test void nullCrawlResultPersistsFailureAndReleasesLease() {
        doReturn(null).when(service).crawlSingleAccount(anyString(), anyString(), anyString(), nullable(String.class));
        run(); assertEquals("3", task.getStatus());
        verify(tasks).updateVideoTags(task);
        verify(leases).release("lock", "token");
    }

    @Test void databaseFailureDuringFinalStatusStillReleasesLease() {
        doThrow(new IllegalStateException("DB down")).when(tasks).updateVideoTags(task);
        assertThrows(IllegalStateException.class, this::run);
        verify(leases).release("lock", "token");
    }

    @Test void lostLeaseNeverStartsDriver() {
        when(leases.keepAlive("lock", "token")).thenThrow(new IllegalStateException("expired"));
        run(); assertEquals("3", task.getStatus());
        verify(service, never()).crawlSingleAccount(anyString(), anyString(), anyString(), nullable(String.class));
    }

    private void run() { service.asyncCrawlTask("device", "圆山", task, "lock", "token"); }
}

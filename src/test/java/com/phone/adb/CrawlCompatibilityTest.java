package com.phone.adb;

import com.alibaba.fastjson.JSON;
import com.phone.module.service.*;
import com.phone.module.domain.AddressVideo;
import io.appium.java_client.MobileElement;
import io.appium.java_client.android.AndroidDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.springframework.test.util.ReflectionTestUtils;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CrawlCompatibilityTest {
    @TempDir Path temp;

    @Test void aRejectedSwipeMustNotBeReportedAsSuccessful() {
        AndroidDriver<MobileElement> driver = mock(AndroidDriver.class, RETURNS_DEEP_STUBS);
        when(driver.manage().window().getSize()).thenReturn(new org.openqa.selenium.Dimension(720, 1280));
        doThrow(new org.openqa.selenium.WebDriverException("device disconnected"))
                .when(driver).perform(anyCollection());
        assertFalse(new DouyinAddressCrawler(driver).swipeUp());
        verify(driver).perform(anyCollection());
    }

    @Test void searchControlSupportsImageAndTextRolesWithoutSelectingTheInputOrSuggestions() throws Exception {
        String xml = "<hierarchy><android.widget.ImageView clickable=\"true\" content-desc=\"搜索\" package=\"com.ss.android.ugc.aweme\"/>"
                + "<android.widget.TextView clickable=\"true\" text=\"搜索\" package=\"com.ss.android.ugc.aweme\"/>"
                + "<android.widget.EditText clickable=\"true\" content-desc=\"搜索\" package=\"com.ss.android.ugc.aweme\"/>"
                + "<android.widget.TextView clickable=\"true\" text=\"搜索推荐\" package=\"com.ss.android.ugc.aweme\"/></hierarchy>";
        assertEquals(2, matches(xml, DouyinAddressCrawler.SEARCH_CONTROL));
    }

    @Test void addressSearchReplacesThePreviousKeyword() {
        AndroidDriver<MobileElement> driver = mock(AndroidDriver.class, RETURNS_DEEP_STUBS);
        when(driver.manage().window().getSize()).thenReturn(new org.openqa.selenium.Dimension(1080, 2340));
        MobileElement input = mock(MobileElement.class);
        when(driver.findElementsByXPath("//android.widget.EditText")).thenReturn(List.of(input));
        DouyinAddressCrawler crawler = new DouyinAddressCrawler(driver);
        assertEquals(true, ReflectionTestUtils.invokeMethod(crawler, "inputSearchText", "台北圆山大饭店"));
        var order = inOrder(input);
        order.verify(input).clear();
        order.verify(input).sendKeys("台北圆山大饭店");
    }

    @Test void searchWaitsForResultsInsteadOfFailingWhileTabsAreStillLoading() {
        AndroidDriver<MobileElement> driver = mock(AndroidDriver.class, RETURNS_DEEP_STUBS);
        when(driver.manage().window().getSize()).thenReturn(new org.openqa.selenium.Dimension(1080, 2340));
        MobileElement tab = mock(MobileElement.class);
        String xpath = "//*[@text='综合']";
        when(driver.findElementsByXPath(xpath)).thenReturn(Collections.emptyList(), List.of(tab));
        DouyinAddressCrawler crawler = new DouyinAddressCrawler(driver);
        assertEquals(true, ReflectionTestUtils.invokeMethod(crawler, "waitAndClickTab", (Object) new String[]{xpath}));
        verify(tab).click();
        verify(driver, atLeast(2)).findElementsByXPath(xpath);
    }

    @Test void countsHandleVersionChangedAccessibilityLabelsAndUnknownValues() {
        assertEquals(12000, VideoCommentsFetcher.parseDouyinCount("评论1.2万，按钮"));
        assertEquals(15000, VideoCommentsFetcher.parseDouyinCount("1.5w+条评论"));
        assertEquals(1000, VideoCommentsFetcher.parseDouyinCount("评论 1,000"));
        assertEquals(0, VideoCommentsFetcher.parseDouyinCount("评论0，按钮"));
        assertEquals(-1, VideoCommentsFetcher.parseDouyinCount("评论，按钮"));
        assertEquals(-1, VideoCommentsFetcher.parseDouyinCount(null));
        assertEquals(Integer.MAX_VALUE, VideoCommentsFetcher.parseDouyinCount("99亿"));
    }

    @Test void accountSearchMustMatchTheWholeIdRatherThanRankOrPrefix() {
        assertTrue(DouyinCrawler.matchesSearchAccount("抖音号：LCK.", "LCK."));
        assertTrue(DouyinCrawler.matchesSearchAccount("抖音号: publisher_123", "publisher_123"));
        assertFalse(DouyinCrawler.matchesSearchAccount("抖音号：publisher_1234", "publisher_123"));
        assertFalse(DouyinCrawler.matchesSearchAccount("昵称 publisher_123", "publisher_123"));
    }

    @Test void canvasSearchUsesAvatarNotFollowButtonAndVerifiesProfileIdentity() throws Exception {
        String xml = "<hierarchy><node><node class=\"android.view.ViewGroup\" bounds=\"[42,367][210,535]\"/>"
                + "<node content-desc=\"关注按钮\" bounds=\"[828,414][1038,487]\"/></node></hierarchy>";
        assertEquals(1, matches(xml, DouyinCrawler.SEARCH_RESULT_AVATARS));
        assertTrue(DouyinCrawler.isSearchAvatar(new org.openqa.selenium.Rectangle(42,367,168,168),1080,2400));
        assertFalse(DouyinCrawler.isSearchAvatar(new org.openqa.selenium.Rectangle(828,414,73,210),1080,2400));
        assertFalse(DouyinCrawler.isSearchAvatar(new org.openqa.selenium.Rectangle(0,0,2400,1080),1080,2400));
        assertTrue(DouyinCrawler.profileHasExpectedId("<node text=\"抖音号：28091944646\"/><node text=\"粉丝\"/><node text=\"作品 17\"/>","28091944646"));
        assertFalse(DouyinCrawler.profileHasExpectedId("<node text=\"抖音号：28091944647\"/><node text=\"粉丝\"/><node text=\"作品 17\"/>","28091944646"));
        assertFalse(DouyinCrawler.profileHasExpectedId("<node text=\"抖音号：28091944646\"/><node text=\"粉丝\"/>","28091944646"));
    }

    @Test void repeatedTextWithDifferentTimeOrReplyTargetIsNotCollapsed() {
        String first = VideoCommentsFetcher.commentKey("同名用户", "谢谢", "10-16", "甲");
        assertNotEquals(first, VideoCommentsFetcher.commentKey("同名用户", "谢谢", "10-15", "甲"));
        assertNotEquals(first, VideoCommentsFetcher.commentKey("同名用户", "谢谢", "10-16", "乙"));
        assertNotEquals(VideoCommentsFetcher.commentKey("a|b", "c", "", ""),
                VideoCommentsFetcher.commentKey("a", "b|c", "", ""));
    }

    @Test void durationUsesTotalInsteadOfFirstElapsedLabel() {
        assertEquals(165, AccountInfoFetcherVoid.parseDurationFromXml("<node text=\"00:03 / 02:45\"/>"));
        assertEquals(83, AccountInfoFetcherVoid.parseDurationFromXml(
                "<node text=\"00:06\"/><node text=\"01:23\"/>"));
        assertNull(AccountInfoFetcherVoid.parseDurationFromXml("<node text=\"00:00\"/>"));
        assertNull(AccountInfoFetcherVoid.parseDurationFromXml("<node text=\"00:99\"/>"));
    }

    @Test void historicalEmptyOrPartialAddressRowsMustBeCollectedAgain() {
        AddressVideo video = new AddressVideo();
        video.setJsonString("{\"comments\":\"124\"}");
        video.setContent("");
        assertFalse(AccountInfoFetcherVoid.hasRequiredAddressData(video, "地址,评论"));
        video.setContent("[{\"text\":\"one comment\"}]");
        assertFalse(AccountInfoFetcherVoid.hasRequiredAddressData(video, "地址,评论"));
        video.setJsonString("{\"comments\":\"124\",\"comments_complete\":true}");
        assertTrue(AccountInfoFetcherVoid.hasRequiredAddressData(video, "地址,评论"));
        assertFalse(AccountInfoFetcherVoid.hasRequiredAddressData(video, "地址,评论,视频"));
        video.setVideoPath("/profile/video/test.mp4");
        assertTrue(AccountInfoFetcherVoid.hasRequiredAddressData(video, "地址,评论,视频"));
    }

    @Test void archivedCommentsAreNotMistakenForMissingData() {
        AddressVideo video = new AddressVideo();
        video.setContent("");
        video.setJsonString("{\"comments\":\"124\",\"comments_complete\":true,\"comments_imported\":true,\"comments_imported_count\":85}");
        assertTrue(AccountInfoFetcherVoid.hasRequiredAddressData(video, "地址,评论"));
        video.setJsonString("{\"comments\":\"124\",\"comments_imported\":true,\"comments_imported_count\":85}");
        assertFalse(AccountInfoFetcherVoid.hasRequiredAddressData(video, "地址,评论"));
    }

    @Test void profileCountsFollowObserved4030OrderAndKeepRepeatedValues() {
        AccountInfoFetcher profile = new AccountInfoFetcher(mock(AndroidDriver.class), temp.toString(),
                mock(IAccountContentService.class), mock(IAddressAccountContentService.class),
                mock(IAccountService.class), mock(DouyinTaskService.class));
        String xml = "<hierarchy><node text=\"13.2万\"/><node text=\"获赞\"/><node text=\"67\"/>"
                + "<node text=\"关注\"/><node text=\"67\"/><node text=\"粉丝\"/><node text=\"作品 184\"/></hierarchy>";
        assertEquals("132000", ReflectionTestUtils.invokeMethod(profile, "getLikesCountV2", xml));
        assertEquals("67", ReflectionTestUtils.invokeMethod(profile, "getFollowCountV2", xml));
        assertEquals("67", ReflectionTestUtils.invokeMethod(profile, "getFansCountV2", xml));
        assertEquals("184", ReflectionTestUtils.invokeMethod(profile, "getWorksCountV2", xml));
        assertEquals("real_123", ReflectionTestUtils.invokeMethod(profile, "getDouyinIdV2",
                "<node text=\"抖音号：\"/><node text=\"real_123\"/>", "nick"));
        assertEquals("", ReflectionTestUtils.invokeMethod(profile, "getDouyinIdV2", "<node text=\"昵称\"/>", "昵称"));
        assertFalse(AccountInfoFetcher.isRealDouyinId("NICKNAME_昵称"));
        assertEquals("测试昵称", ReflectionTestUtils.invokeMethod(profile, "getNicknameV2",
                "<hierarchy><node content-desc=\"用户头像\"/><node text=\"测试昵称\"/>"
                        + "<node text=\"抖音号：real_123\"/></hierarchy>"));
        String reversed = "<hierarchy><group><node text=\"关注\"/><node text=\"12\"/></group>"
                + "<group><node text=\"粉丝\"/><node text=\"34\"/></group></hierarchy>";
        assertEquals("12", ReflectionTestUtils.invokeMethod(profile, "getFollowCountV2", reversed));
        assertEquals("34", ReflectionTestUtils.invokeMethod(profile, "getFansCountV2", reversed));
        String bio = "<hierarchy><node content-desc=\"用户头像\"/><node text=\"名字&#129419;\"/>"
                + "<node text=\"抖音号：real_123\"/><node text=\"13.2万\"/><node text=\"获赞\"/>"
                + "<node text=\"67\"/><node text=\"关注\"/><node text=\"12000\"/><node text=\"粉丝\"/>"
                + "<node text=\"这里才是简介&#10;第二行\"/><node text=\"IP：中国台湾\"/><node text=\"作品 184,按钮\"/></hierarchy>";
        assertEquals("名字🦋", ReflectionTestUtils.invokeMethod(profile, "getNicknameV2", bio));
        assertEquals("这里才是简介\n第二行", ReflectionTestUtils.invokeMethod(profile, "getProfileTextV2", bio));
        assertEquals("这里才是简介\n第二行", ReflectionTestUtils.invokeMethod(profile, "getSignatureV2", bio, "名字🦋"));
        String labels = "<hierarchy><node text=\"IP：福建\"/><node text=\"男·36岁\"/><node text=\"作品 20\"/>"
                + "<node text=\"来自路人的视角，什么时候可以继续参观厦门大学\"/></hierarchy>";
        assertEquals("男", ReflectionTestUtils.invokeMethod(profile, "getGenderV2", labels));
        assertEquals("36岁", ReflectionTestUtils.invokeMethod(profile, "getAgeV2", labels));
        assertEquals("", ReflectionTestUtils.invokeMethod(profile, "getSchoolV2", labels));
    }

    @Test void observed4030StructureAndRenamedContainerRemainReadable() throws Exception {
        String fixture = Files.readString(Path.of("src/test/resources/crawl/douyin-40.3-comments.xml"));
        assertEquals(2, matches(fixture, VideoCommentsFetcher.COMMENT_ROWS));
        String upgraded = fixture.replace("id/flq", "id/new_container_42");
        assertEquals(0, matches(upgraded, VideoCommentsFetcher.COMMENT_ROWS));
        assertEquals(2, matches(upgraded, VideoCommentsFetcher.COMMENT_ROWS_FALLBACK));
    }

    @Test void observedProfileGridUsesFrameLayoutInsteadOfOldViewGroup() throws Exception {
        String xml = "<hierarchy><android.widget.FrameLayout resource-id=\"com.ss.android.ugc.aweme:id/container\">"
                + "<android.view.View resource-id=\"com.ss.android.ugc.aweme:id/qbt\"/></android.widget.FrameLayout>"
                + "<android.widget.FrameLayout resource-id=\"com.ss.android.ugc.aweme:id/container\"/></hierarchy>";
        assertEquals(0, matches(xml, "//android.view.ViewGroup[@resource-id='com.ss.android.ugc.aweme:id/container']"));
        assertEquals(1, matches(xml, AccountInfoFetcherVoid.PROFILE_VIDEO_CARDS));
    }

    private int matches(String xml, String xpath) throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return ((NodeList) XPathFactory.newInstance().newXPath().evaluate(xpath, document, XPathConstants.NODESET)).getLength();
    }

    @Test void missingCommentEntryReturnsIncompleteAndPreservesEarlierCheckpoint() throws Exception {
        Files.writeString(temp.resolve("comments_device_video.json"),
                "{\"complete\":false,\"comments\":[{\"nickname\":\"用户\",\"text\":\"已抓到\"}]}");
        AndroidDriver<MobileElement> driver = mock(AndroidDriver.class);
        when(driver.findElements(any(By.class))).thenReturn(Collections.emptyList());
        Map<String, Object> result = fetcher(driver, mock(DouyinTaskService.class))
                .fetchAllComments("2", "圆山", "video", "device", 1, temp.toString(), "124");
        assertEquals(false, result.get("complete"));
        assertEquals(1, ((List<?>) result.get("comments")).size());
        assertTrue(Files.readString(Path.of((String) result.get("json_path"))).contains("已抓到"));
        verify(driver, never()).navigate();
    }

    @Test void profileStorageFailureStillReturnsToCommentPanel() {
        AndroidDriver<MobileElement> driver = mock(AndroidDriver.class);
        WebDriver.Navigation navigation = mock(WebDriver.Navigation.class);
        when(driver.navigate()).thenReturn(navigation);
        when(driver.findElements(any(By.class))).thenReturn(Collections.emptyList());
        when(driver.findElements(By.xpath(VideoCommentsFetcher.COMMENT_PANEL)))
                .thenReturn(Collections.emptyList(), List.of(mock(MobileElement.class)));
        MobileElement row = mock(MobileElement.class);
        MobileElement nickname = mock(MobileElement.class);
        MobileElement avatar = mock(MobileElement.class);
        when(nickname.getText()).thenReturn("评论用户");
        when(avatar.isDisplayed()).thenReturn(true);
        when(row.findElement(any(By.class))).thenAnswer(invocation ->
                invocation.getArgument(0).toString().contains("id/title") ? nickname : avatar);
        DouyinCrawler crawler = mock(DouyinCrawler.class);
        when(crawler.fetchAccountInfo("2", "2", "device", "评论用户"))
                .thenReturn(new HashMap<>(Map.of("id", "user-id")));
        DouyinTaskService service = mock(DouyinTaskService.class);
        doThrow(new DouyinTaskService.ProfileStorageException("database unavailable", null)).when(service)
                .storeAccountByAddressAccount(anyString(), anyString(), anyString(), anyString(), anyMap());
        assertThrows(DouyinTaskService.ProfileStorageException.class, () -> ReflectionTestUtils.invokeMethod(
                fetcher(driver, service), "fetchUserFromAvatar", "2", "圆山", "video", row, driver, crawler, "device"));
        verify(nickname).getText();
        verify(navigation).back();
        verify(crawler).fetchAccountInfo("2", "2", "device", "评论用户");
    }

    @Test void longCommentBatchYieldsWithCheckpointInsteadOfReportingCompletion() throws Exception {
        Files.writeString(temp.resolve("comments_device_video.json"),
                "{\"complete\":false,\"comments\":[{\"nickname\":\"用户\",\"text\":\"保留评论\"}]}");
        AndroidDriver<MobileElement> driver = mock(AndroidDriver.class);
        WebDriver.Navigation navigation = mock(WebDriver.Navigation.class);
        when(driver.navigate()).thenReturn(navigation);
        MobileElement button = mock(MobileElement.class);
        when(driver.findElements(any(By.class))).thenReturn(List.of(button));
        DouyinTaskService service = mock(DouyinTaskService.class);
        when(service.hasWaitingTasks("device")).thenReturn(true);
        VideoCommentsFetcher fetcher = fetcher(driver, service);
        java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
        ReflectionTestUtils.setField(fetcher,"nanoClock", (java.util.function.LongSupplier)
                () -> ticks.getAndIncrement() == 0 ? 0L : java.util.concurrent.TimeUnit.MINUTES.toNanos(4));
        Map<String,Object> result = fetcher.fetchAllComments("1","person","video","device",0,temp.toString(),"869");
        assertEquals(true,result.get("deferred"));
        assertEquals(false,result.get("complete"));
        assertEquals(1,((List<?>)result.get("comments")).size());
        assertTrue(Files.readString(Path.of((String)result.get("json_path"))).contains("保留评论"));
        verify(navigation).back();
    }

    @Test void partialVideoIsPersistedAsIncompleteAndRecordingCanBeReused() throws Exception {
        Class<?> config = com.phone.common.config.RuoYiConfig.class;
        Object oldRoot = ReflectionTestUtils.getField(config,"profile");
        ReflectionTestUtils.setField(config,"profile",temp.toString());
        try {
            IVideoService videos = mock(IVideoService.class);
            IAddressVideoService addresses = mock(IAddressVideoService.class);
            AccountInfoFetcherVoid saver = new AccountInfoFetcherVoid(mock(AndroidDriver.class),videos,addresses,
                    mock(DouyinTaskService.class),mock(IAccountContentService.class),mock(IAddressAccountContentService.class),mock(IAccountService.class));
            Path recording = temp.resolve("video/segment.mp4");
            Files.write(recording,new byte[]{1,2,3});
            Map<String,Object> data = new HashMap<>(Map.of("uid","video","video_index",1,"comments_requested",true,
                    "comments_complete",false,"comments_count","869","comments",List.of(Map.of("text","保留评论")),
                    "video_path",recording.toString()));
            saver.saveSingleVideo(data,"device","person");
            saver.saveSingleVideoByAddress(data,"device","person","圆山");
            var person = org.mockito.ArgumentCaptor.forClass(com.phone.module.domain.Video.class);
            var address = org.mockito.ArgumentCaptor.forClass(AddressVideo.class);
            verify(videos).insertVideo(person.capture());
            verify(addresses).insertAddressVideo(address.capture());
            assertFalse(JSON.parseObject(person.getValue().getJsonString()).getBooleanValue("comments_complete"));
            assertFalse(AccountInfoFetcherVoid.hasRequiredVideoData(person.getValue(),"视频,评论"));
            assertFalse(AccountInfoFetcherVoid.hasRequiredAddressData(address.getValue(),"视频,评论"));
            assertEquals(recording.toString(),AccountInfoFetcherVoid.reusableRecording(person.getValue().getVideoPath()));
            assertNull(AccountInfoFetcherVoid.reusableRecording("/profile/missing.mp4"));
            assertNull(AccountInfoFetcherVoid.reusableRecording("/profile/../outside.mp4"));
        } finally { ReflectionTestUtils.setField(config,"profile",oldRoot); }
    }

    private VideoCommentsFetcher fetcher(AndroidDriver<MobileElement> driver, DouyinTaskService service) {
        return new VideoCommentsFetcher(driver, service, mock(IAccountContentService.class),
                mock(IAddressAccountContentService.class), mock(IAccountService.class));
    }
}

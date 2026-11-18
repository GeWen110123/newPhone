package com.phone.module.service;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phone.adb.AccountInfoFetcherVoid;
import com.phone.adb.AndroidCollector;
import com.phone.adb.CrawlBatch;
import com.phone.adb.DouyinAddressCrawler;
import com.phone.adb.DouyinCrawler;
import com.phone.adb.PlaceMockLocationHelper;
import com.phone.adb.XPathRegistry;
import com.phone.common.utils.StringUtils;
import com.phone.module.domain.*;
import io.appium.java_client.MobileElement;
import io.appium.java_client.TouchAction;
import io.appium.java_client.android.Activity;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.touch.WaitOptions;
import io.appium.java_client.touch.offset.PointOption;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Rectangle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

@Service
public class DouyinVideoService {

    private static final Logger logger = Logger.getLogger(DouyinVideoService.class.getName());

    @Autowired
    private IVideoTagsService videoTagsService;
    @Autowired
    private DouyinTaskService douyinTaskService;
    @Autowired
    private IAddressVideoService addressVideoService;
    @Autowired
    private IVideoService videoService;
    @Autowired
    private IAccountContentService accountContentService;
    @Autowired
    private IAccountService accountService;
    @Autowired
    private IAddressAccountContentService addressAccountContentService;
    @Autowired
    private IRunLogService runLogService;
    /**
     * 用于 DB 写入
     */

    /**
     * 异步入口
     */
    @Autowired
    private DeviceLeaseService deviceLeases;

    @Async
    public void asyncCrawlTask(String devId, String accountName, VideoTags tags, String lockKey, String token) {
        RunLog runLog = new RunLog();
        runLog.setDevId(devId);
        runLog.setDouyinId(accountName);
        runLog.setType("采集");
        logger.info("开始采集 task=" + tags.getId() + " device=" + devId + " target=" + accountName);
        try (AutoCloseable lease = deviceLeases.keepAlive(lockKey, token)) {
            List<Map<String, Object>> result = crawlSingleAccount(devId, accountName,
                    tags.getTags(), tags.getAddress());
            if (result == null || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("采集未完成，保留已保存数据等待重试");
            }
            if (result instanceof CrawlBatch) {
                tags.setStatus("0");
                tags.setRunNum(Math.max(0L, (tags.getRunNum() == null ? 1L : tags.getRunNum()) - 1L));
                runLog.setRuningDetail(accountName + "本轮已保存=" + result.size() + "，让出设备处理排队任务，稍后继续");
                logger.info(runLog.getRuningDetail());
                return;
            }
            Account query = new Account();
            query.setDouyinId(accountName);
            List<Account> accounts = accountService.selectAccountList(query);
            int expected = accounts.isEmpty() ? 0 : getUserWorksCount(accounts.get(0).getJsonString());
            String options = tags.getTags() == null ? "" : tags.getTags();
            long saved;
            if (options.contains("地址") || options.contains("附近") || options.contains("综合视频")) {
                saved = addressVideoService.countAddressVideos(accountName);
            } else {
                saved = videoService.countVideos(accountName);
                if (options.contains("前六条") && expected > 0) expected = Math.min(expected, 6);
            }
            // Unknown counts and partial runs must never be reported as successful.
            boolean complete = expected > 0 && saved >= expected;
            tags.setStatus(complete ? "2" : "3");
            runLog.setRuningDetail(accountName + "本轮新增=" + result.size() + "，已保存=" + saved
                    + "，页面目标=" + expected + (complete ? "，采集完成" : "，未确认完整，等待重试"));
            logger.info("采集结果 task=" + tags.getId() + " device=" + devId + " " + runLog.getRuningDetail());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            tags.setStatus("3");
            runLog.setRuningDetail(accountName + "采集中断: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "采集失败 task=" + tags.getId() + " device=" + devId, e);
        } finally {
            try {
                if (tags.getId() != null) videoTagsService.updateVideoTags(tags);
                runLogService.insertRunLog(runLog);
            } finally {
                deviceLeases.release(lockKey, token);
            }
        }
    }

    public static int getRealWorksCount(String jsonString) {
        return getUserWorksCount(jsonString);
    }

    public static int getUserWorksCount(String jsonString) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> map = mapper.readValue(
                    jsonString,
                    new TypeReference<Map<String, Object>>() {
                    }
            );

            // ① 先拿 works_count
            int worksCount = Optional.ofNullable(map.get("works_count"))
                    .map(Object::toString)
                    .map(Integer::parseInt)
                    .orElse(0);

            return worksCount;

        } catch (Exception e) {
            logger.warning("解析 works_count 失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 单设备抓取（修复版：解决 socket hang up 错误）
     */
    public List<Map<String, Object>> crawlSingleAccount(String devId, String accountName, String tags, String address) {
        AndroidDriver<MobileElement> driver = null;
        tags = tags == null ? "" : tags;

        try {
            AndroidCollector collector = AndroidCollector.current();
            if (!collector.isOnline(devId)) throw new IllegalStateException("设备未在线或未授权: " + devId);
            int retryCount = 0;
            while (retryCount < 3) { // 最多重试3次
                try {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("驱动创建已取消");
                    retryCount++;
                    driver = collector.connect(devId);
                    break;
                } catch (Exception e) {
                    if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("驱动创建已取消", e);
                    }
                    if (retryCount >= 3) {
                        throw new RuntimeException("驱动创建失败（已重试3次）", e);
                    }
                    logger.warning("驱动连接失败，将重试 device=" + devId + " attempt=" + retryCount
                            + " error=" + e.getClass().getSimpleName());
                    Thread.sleep(2000);
                }
            }
//            // 隐式等待，提升元素查找稳定性
//            driver.manage().timeouts().implicitlyWait(10, java.util.concurrent.TimeUnit.SECONDS);

            // ========== 原有业务逻辑：完全保留，无修改 ==========
            if (tags.contains("地址")) {
                DouyinAddressCrawler crawler = new DouyinAddressCrawler(driver);
                if (!crawler.startDouyin()) return null;
                if (!crawler.searchAndEnterAddress(accountName)) return null;

                AccountInfoFetcherVoid fetcher = new AccountInfoFetcherVoid(driver,
                        videoService,
                        addressVideoService,
                        douyinTaskService,
                        accountContentService,
                        addressAccountContentService,
                        accountService
                );

                Account account = new Account();
                account.setDouyinId(accountName);
                List<Account> lists = accountService.selectAccountList(account);
                String workesCount = "15"; // 兜底默认值，避免空值

                if (lists != null && lists.size() > 0) {
                    account = lists.get(0);
                    String json = account.getJsonString();
                    if (StringUtils.isNotBlank(json)) {
                        JSONObject obj = JSONObject.parseObject(json);
                        workesCount = obj.getString("works_count");
                    }
                } else {
                    // 基础信息查询
                    Map<String, Object> result = crawler.fetchAccountInfo();
                    douyinTaskService.storeAccountAsync(devId, accountName, result, tags);
                    workesCount = StringUtils.isNotBlank((String) result.get("works_count"))
                            ? (String) result.get("works_count")
                            : "15";
                }

                Thread.sleep(800); // 页面稳定
                // 判断并点击“地点内容数量”
                if (clickAddressContentIfPresent(driver)) {
                    logger.info("已进入地点内容列表页");
                    Thread.sleep(800); // 页面稳定
                } else {
                    // 未找到“地点内容数量”时不能连续返回：
                    // 当前仍可能在地点页/地点视频列表，后面的 addressVideosAndComments
                    // 会继续通过新版 rej/dp7/cover 定位进入第一条视频。
                    logger.warning("未找到地点内容数量，保留当前页面并使用视频卡片兜底定位");
                    Thread.sleep(800);
                }

                return fetcher.addressVideosAndComments(workesCount, devId, accountName, tags);

            } else if (tags.contains("综合视频")) {
                DouyinAddressCrawler crawler = new DouyinAddressCrawler(driver);
                if (!crawler.startDouyin()) return null;
                if (!crawler.searchAndEnterVideoTab(accountName)) return null;

                AccountInfoFetcherVoid fetcher = new AccountInfoFetcherVoid(driver,
                        videoService,
                        addressVideoService,
                        douyinTaskService,
                        accountContentService,
                        addressAccountContentService,
                        accountService
                );

                String workesCount = getStoredWorksCount(accountName);
                if (!StringUtils.isNotBlank(workesCount)) {
                    int[] videoCountResult = countSearchVideosUntilNoMore(driver);
                    workesCount = String.valueOf(videoCountResult[0] > 0 ? videoCountResult[0] : 100);
                    logger.info("综合视频列表统计数量: " + workesCount);

                    Map<String, Object> result = buildComprehensiveVideoAccountInfo(accountName, workesCount);
                    douyinTaskService.storeAccountAsync(devId, accountName, result, tags);
                } else {
                    logger.info("使用已保存的综合视频数量: " + workesCount);
                }

                driver.navigate().back();
                Thread.sleep(1000);
                if (!crawler.searchAndEnterVideoTab(accountName)) return null;
                Thread.sleep(800); // 页面稳定

                if (!clickFirstSearchVideoIfPresent(driver)) {
                    logger.warning("click first search video failed");
                    return null;
                }
                Thread.sleep(1500);


                return fetcher.addressZHongHeComments(workesCount, devId, accountName, tags);

            } else if (tags.contains("附近")) {

                if (StringUtils.isNotEmpty(address)) {
                    if (!startPlaceMockLocation(driver, devId, address)) {
                        logger.warning("全国模拟定位启动失败，address=" + address);
                        return null;
                    }
                }

                DouyinAddressCrawler crawler = new DouyinAddressCrawler(driver);
                if (!crawler.startDouyin()) return null;

                AccountInfoFetcherVoid fetcher = new AccountInfoFetcherVoid(driver,
                        videoService,
                        addressVideoService,
                        douyinTaskService,
                        accountContentService,
                        addressAccountContentService,
                        accountService
                );

                Thread.sleep(1000);
                if (!swipeLeftToNearbyVideoFeed(driver)) {
                    logger.warning("swipe left to nearby video feed failed");
                    return null;
                }
                Thread.sleep(1000);

                Dimension size = driver.manage().window().getSize();
                tapPoint(driver, size.getWidth() / 2, size.getHeight() / 2);
                Thread.sleep(1000);
                return fetcher.addressVideosAndComments(String.valueOf(Integer.MAX_VALUE), devId, accountName, tags);

            } else {
                DouyinCrawler crawler = new DouyinCrawler(driver,
                        accountContentService,
                        addressAccountContentService,
                        accountService, douyinTaskService);
                if (!crawler.startDouyin()) return null;
                if (!crawler.searchAndEnterAccount(accountName)) return null;

                AccountInfoFetcherVoid fetcher = new AccountInfoFetcherVoid(driver,
                        videoService,
                        addressVideoService,
                        douyinTaskService,
                        accountContentService,
                        addressAccountContentService,
                        accountService);

                // 基础信息查询
                if (tags.contains("基本信息")) {
                    Map<String, Object> result = crawler.fetchAccountInfo("1", "1", devId, accountName);
                    douyinTaskService.storeAccountAsync(devId, accountName, result, tags);
                }

                return fetcher.recordAllVideosAndComments(devId, accountName, tags);
            }

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.log(java.util.logging.Level.SEVERE, "crawlSingleAccount 失败 device=" + devId, e);
            return null;
        } finally {
            // ========== 修复：优雅关闭驱动，避免端口残留 ==========
            closeDriverQuietly(driver);
        }
    }

    /**
     * 如果存在“地点内容数量”，则点击进入地点内容页
     */
    private boolean clickAddressContentIfPresent(AndroidDriver<MobileElement> driver) {
        for (String xp : XPathRegistry.ADDRESS_COUNT_XPATHS) {
            try {
                List<MobileElement> elements = driver.findElements(By.xpath(xp));
                if (elements.isEmpty()) continue;

                MobileElement countText = elements.get(0);
                tapCenter(driver, countText);
                logger.warning("⚠ 使用兜底方式点击地点内容文本");
                return true;

            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void closeDriverQuietly(AndroidDriver<MobileElement> driver) {
        if (driver == null) {
            return;
        }

        try {
            driver.stopRecordingScreen();
        } catch (Exception e) {
            logger.fine("stop recording ignored: " + e.getMessage());
        }

        try {
            driver.quit();
        } catch (Exception e) {
            logger.warning("关闭驱动时异常：" + e.getMessage());
        }
    }

    private MobileElement findClickableParent(AndroidDriver<MobileElement> driver, MobileElement el, int maxLevel) {
        MobileElement current = el;
        for (int i = 0; i < maxLevel; i++) {
            try {
                if ("true".equals(current.getAttribute("clickable"))) return current;
                current = (MobileElement) current.findElement(By.xpath(".."));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private boolean startPlaceMockLocation(AndroidDriver<MobileElement> driver, String devId, String address) {
        try {
            if (!PlaceMockLocationHelper.configureMockLocationApp(devId)) {
                return false;
            }

            driver.startActivity(new Activity(PlaceMockLocationHelper.PACKAGE_NAME, PlaceMockLocationHelper.MAIN_ACTIVITY));
            Thread.sleep(2500);
            clickByAnyText(driver, "允许", "同意", "确定", "始终允许");

            if (!inputPlaceMockAddress(driver, address)) {
                logger.warning("全国模拟定位输入地址失败: " + address);
                return false;
            }

            if (!clickByAnyText(driver, "搜索")) {
                logger.warning("全国模拟定位点击搜索失败");
                return false;
            }

            Thread.sleep(2000);
            if (!clickPlaceMockSearchResult(driver, address)) {
                logger.warning("全国模拟定位选择搜索结果失败: " + address);
                return false;
            }

            Thread.sleep(1000);
            if (!clickByAnyText(driver, "开始模拟", "开始", "模拟")) {
                logger.warning("全国模拟定位点击开始模拟失败");
                return false;
            }

            Thread.sleep(1200);
            logger.info("全国模拟定位已启动: " + address);
            return true;
        } catch (Exception e) {
            logger.warning("全国模拟定位流程异常: " + e.getMessage());
            return false;
        }
    }

    private boolean inputPlaceMockAddress(AndroidDriver<MobileElement> driver, String address) {
        String[] xpaths = {
                "//android.widget.EditText",
                "//*[@class='android.widget.EditText']",
                "//*[contains(@text,'输入') or contains(@text,'地名') or contains(@content-desc,'输入') or contains(@content-desc,'地名')]"
        };

        for (String xpath : xpaths) {
            try {
                List<MobileElement> elements = driver.findElements(By.xpath(xpath));
                for (MobileElement element : elements) {
                    if (!element.isDisplayed()) continue;

                    tapCenter(driver, element);
                    try {
                        element.clear();
                    } catch (Exception ignored) {
                    }
                    element.sendKeys(address);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        try {
            Dimension size = driver.manage().window().getSize();
            tapPoint(driver, size.getWidth() / 2, (int) (size.getHeight() * 0.16));
            driver.getKeyboard().sendKeys(address);
            return true;
        } catch (Exception e) {
            logger.warning("全国模拟定位兜底输入失败: " + e.getMessage());
            return false;
        }
    }

    private boolean clickPlaceMockSearchResult(AndroidDriver<MobileElement> driver, String address) {
        String addressLiteral = xpathLiteral(address);
        String[] xpaths = {
                "//*[contains(@text," + addressLiteral + ") or contains(@content-desc," + addressLiteral + ")]",
                "//android.widget.TextView[@clickable='true']",
                "//*[@clickable='true' and not(contains(@text,'搜索')) and not(contains(@text,'开始'))]"
        };

        for (String xpath : xpaths) {
            try {
                List<MobileElement> elements = driver.findElements(By.xpath(xpath));
                for (MobileElement element : elements) {
                    if (!element.isDisplayed()) continue;

                    Rectangle rect = element.getRect();
                    if (rect.getY() < 180 || rect.getHeight() <= 0 || rect.getWidth() <= 0) {
                        continue;
                    }

                    MobileElement clickable = findClickableParent(driver, element, 4);
                    tapCenter(driver, clickable != null ? clickable : element);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        try {
            Dimension size = driver.manage().window().getSize();
            tapPoint(driver, size.getWidth() / 2, (int) (size.getHeight() * 0.36));
            return true;
        } catch (Exception e) {
            logger.warning("全国模拟定位兜底选择结果失败: " + e.getMessage());
            return false;
        }
    }

    private boolean clickByAnyText(AndroidDriver<MobileElement> driver, String... texts) {
        StringBuilder xpath = new StringBuilder("//*[");
        for (int i = 0; i < texts.length; i++) {
            if (i > 0) {
                xpath.append(" or ");
            }
            String literal = xpathLiteral(texts[i]);
            xpath.append("contains(@text,").append(literal).append(") or contains(@content-desc,").append(literal).append(")");
        }
        xpath.append("]");

        try {
            List<MobileElement> elements = driver.findElements(By.xpath(xpath.toString()));
            for (MobileElement element : elements) {
                if (!element.isDisplayed()) continue;

                Rectangle rect = element.getRect();
                if (rect.getHeight() <= 0 || rect.getWidth() <= 0) {
                    continue;
                }

                MobileElement clickable = findClickableParent(driver, element, 4);
                tapCenter(driver, clickable != null ? clickable : element);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String xpathLiteral(String value) {
        if (value == null) {
            return "''";
        }
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        if (!value.contains("\"")) {
            return "\"" + value + "\"";
        }

        String[] parts = value.split("'");
        StringBuilder builder = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append(",\"'\",");
            }
            builder.append("'").append(parts[i]).append("'");
        }
        builder.append(")");
        return builder.toString();
    }

    private String getStoredWorksCount(String accountName) {
        try {
            Account account = new Account();
            account.setDouyinId(accountName);
            List<Account> lists = accountService.selectAccountList(account);
            if (lists == null || lists.isEmpty()) {
                return "";
            }

            String json = lists.get(0).getJsonString();
            if (!StringUtils.isNotBlank(json)) {
                return "";
            }

            JSONObject obj = JSONObject.parseObject(json);
            String worksCount = obj.getString("works_count");
            return StringUtils.isNotBlank(worksCount) ? worksCount : "";
        } catch (Exception e) {
            logger.warning("读取已保存作品数失败: " + e.getMessage());
            return "";
        }
    }

    private Map<String, Object> buildComprehensiveVideoAccountInfo(String accountName, String worksCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", accountName);
        result.put("nickname", accountName);
        result.put("search_keyword", accountName);
        result.put("type", "综合视频");
        result.put("works", "综合视频");
        result.put("works_count", worksCount);
        result.put("video_count", StringUtils.isNotBlank(worksCount) ? worksCount : "0");
        return result;
    }

    private int[] countSearchVideosUntilNoMore(AndroidDriver<MobileElement> driver) {
        Set<String> videoSigns = new LinkedHashSet<>();
        int swipeCount = 0;
        int sameCountTimes = 0;
        int maxSwipe = 100;

        while (swipeCount <= maxSwipe) {
            int before = videoSigns.size();
            collectVisibleSearchVideos(driver, videoSigns);

            if (hasNoMoreSearchVideos(driver)) {
                logger.info("综合视频列表已到底，统计数量: " + videoSigns.size());
                break;
            }

            if (videoSigns.size() == before) {
                sameCountTimes++;
            } else {
                sameCountTimes = 0;
            }

            if (sameCountTimes >= 5) {
                logger.warning("综合视频列表连续多次没有新增，停止统计，当前数量: " + videoSigns.size());
                break;
            }

            if (!swipeSearchResults(driver, true)) {
                break;
            }

            swipeCount++;
            sleepQuietly(1000);
        }

        collectVisibleSearchVideos(driver, videoSigns);
        return new int[]{videoSigns.size(), swipeCount};
    }

    private void collectVisibleSearchVideos(AndroidDriver<MobileElement> driver, Set<String> videoSigns) {
        try {
            Dimension size = driver.manage().window().getSize();
            List<MobileElement> cards = driver.findElements(By.xpath(
                    "//android.widget.LinearLayout[@resource-id='com.ss.android.ugc.aweme:id/c7f']"
            ));

            for (MobileElement card : cards) {
                if (!card.isDisplayed()) continue;

                Rectangle rect = card.getRect();
                if (rect.getY() < 320 || rect.getY() > size.getHeight() - 80) {
                    continue;
                }

                String sign = buildSearchVideoSign(card);
                if (StringUtils.isNotBlank(sign)) {
                    videoSigns.add(sign);
                }
            }
        } catch (Exception e) {
            logger.warning("collect search videos failed: " + e.getMessage());
        }
    }

    private String buildSearchVideoSign(MobileElement card) {
        StringBuilder sign = new StringBuilder();
        String[] xpaths = {
                ".//*[contains(@resource-id,'desc')]",
                ".//*[contains(@resource-id,'title')]",
                ".//android.widget.TextView"
        };

        for (String xpath : xpaths) {
            try {
                List<MobileElement> texts = card.findElements(By.xpath(xpath));
                for (MobileElement text : texts) {
                    String value = Optional.ofNullable(text.getText()).orElse("").trim();
                    if (StringUtils.isNotBlank(value) && sign.indexOf(value) < 0) {
                        sign.append(value).append("|");
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return sign.toString();
    }

    private boolean hasNoMoreSearchVideos(AndroidDriver<MobileElement> driver) {
        try {
            return !driver.findElements(By.xpath(
                    "//*[contains(@text,'暂时没有更多') or contains(@text,'没有更多') " +
                            "or contains(@content-desc,'暂时没有更多') or contains(@content-desc,'没有更多')]"
            )).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean swipeSearchResults(AndroidDriver<MobileElement> driver, boolean up) {
        try {
            Dimension size = driver.manage().window().getSize();
            int x = size.getWidth() / 2;
            int startY = up ? (int) (size.getHeight() * 0.78) : (int) (size.getHeight() * 0.35);
            int endY = up ? (int) (size.getHeight() * 0.35) : (int) (size.getHeight() * 0.78);

            new TouchAction<>(driver)
                    .press(PointOption.point(x, startY))
                    .waitAction(WaitOptions.waitOptions(Duration.ofMillis(350)))
                    .moveTo(PointOption.point(x, endY))
                    .release()
                    .perform();
            return true;
        } catch (Exception e) {
            logger.warning("swipe search results failed: " + e.getMessage());
            return false;
        }
    }

    private boolean swipeLeftToNearbyVideoFeed(AndroidDriver<MobileElement> driver) {
        try {
            Dimension size = driver.manage().window().getSize();
            int y = size.getHeight() / 2;
            int startX = (int) (size.getWidth() * 0.18);
            int endX = (int) (size.getWidth() * 0.82);

            new TouchAction<>(driver)
                    .press(PointOption.point(startX, y))
                    .waitAction(WaitOptions.waitOptions(Duration.ofMillis(450)))
                    .moveTo(PointOption.point(endX, y))
                    .release()
                    .perform();
            return true;
        } catch (Exception e) {
            logger.warning("swipe left failed: " + e.getMessage());
            return false;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private boolean clickFirstSearchVideoIfPresent(AndroidDriver<MobileElement> driver) {
        String[] xpaths = {
                "//android.widget.LinearLayout[@resource-id='com.ss.android.ugc.aweme:id/c7f' and @clickable='true']",
                "//android.view.View[@resource-id='com.ss.android.ugc.aweme:id/p=1' and @clickable='true']",
                "//android.widget.ImageView[@resource-id='com.ss.android.ugc.aweme:id/cover']"
        };

        for (String xpath : xpaths) {
            try {
                List<MobileElement> elements = driver.findElements(By.xpath(xpath));
                for (MobileElement element : elements) {
                    if (!element.isDisplayed()) continue;

                    Rectangle rect = element.getRect();
                    if (rect.getY() < 320 || rect.getHeight() <= 0 || rect.getWidth() <= 0) {
                        continue;
                    }

                    MobileElement clickable = findClickableParent(driver, element, 3);
                    tapCenter(driver, clickable != null ? clickable : element);
                    logger.info("clicked first search video by xpath: " + xpath);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private void tapCenter(AndroidDriver<MobileElement> driver, MobileElement el) {
        Rectangle r = el.getRect();
        int x = r.getX() + r.getWidth() / 2;
        int y = r.getY() + r.getHeight() / 2;

        new TouchAction<>(driver)
                .tap(PointOption.point(x, y))
                .perform();
    }

    private void tapPoint(AndroidDriver<MobileElement> driver, int x, int y) {
        new TouchAction<>(driver)
                .tap(PointOption.point(x, y))
                .perform();
    }
}

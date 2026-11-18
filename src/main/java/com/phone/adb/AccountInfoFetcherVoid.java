package com.phone.adb;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.phone.common.constant.Constants;
import com.phone.common.utils.StringUtils;
import com.phone.module.domain.Account;
import com.phone.module.domain.AddressVideo;
import com.phone.module.domain.Video;
import com.phone.module.mapper.VideoMapper;
import com.phone.module.service.*;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.MobileElement;
import io.appium.java_client.TouchAction;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.touch.WaitOptions;
import io.appium.java_client.touch.offset.PointOption;
import org.apache.commons.codec.digest.DigestUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⭐⭐⭐
 * 抖音账号作品采集器（录制视频、解析统计数据、抓取评论）
 * — 全程基于 Appium + ADB —
 * — 所有方法逻辑保持原样，仅进行排版/注释整理 —
 * ⭐⭐⭐
 */
public class AccountInfoFetcherVoid {

    private final AppiumDriver<MobileElement> driver;
    private final Logger logger = Logger.getLogger(AccountInfoFetcherVoid.class.getName());

    private final String videoOutputFolder = DouyinPathUtils.resolve("video");
    private final String screenshotFolder = DouyinPathUtils.resolve("images");

    private static final String ADB = "C:\\Android\\sdk\\platform-tools\\adb.exe";

    private final IAddressVideoService addressVideoService;
    private final IVideoService videoService;

    private final DouyinTaskService douyinTaskService;

    private final IAccountContentService accountContentService;
    private final IAddressAccountContentService addressAccountContentService;
    private final IAccountService accountService;

    public AccountInfoFetcherVoid(AppiumDriver<MobileElement> driver,
                                  IVideoService videoService,
                                  IAddressVideoService addressVideoService,
                                  DouyinTaskService douyinTaskService,
                                  IAccountContentService accountContentService,
                                  IAddressAccountContentService addressAccountContentService,
                                  IAccountService accountService
    ) {
        this.driver = driver;
        this.videoService = videoService;
        this.addressVideoService = addressVideoService;
        this.douyinTaskService = douyinTaskService;
        this.accountContentService = accountContentService;
        this.addressAccountContentService = addressAccountContentService;
        this.accountService = accountService;
        try {
            Files.createDirectories(Paths.get(videoOutputFolder));
            Files.createDirectories(Paths.get(screenshotFolder));
        } catch (Exception e) {
            logger.warning("创建输出目录失败: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // 数字字符串清洗工具（1.2w、3k → 12000 / 3000）
    // ---------------------------------------------------------
// ---------------------------------------------------------
//  数字字符串清洗工具（最强版）
// ---------------------------------------------------------
    public static String cleanNumberString(String text) {
        if (text == null) return "0";

        // 去除空格、逗号
        text = text.replace(",", "").trim();

        // 去掉前后的非数字字符（如：点赞1.2w）
        text = text.replaceAll("^[^0-9]+", "");
        text = text.replaceAll("[^0-9a-zA-Z万亿\\.]+$", "");

        try {
            // w / W 处理（默认 *10000）
            if (text.endsWith("w") || text.endsWith("W")) {
                double v = Double.parseDouble(text.substring(0, text.length() - 1));
                return String.valueOf((long) (v * 10000));
            }

            // 万 处理
            if (text.endsWith("万")) {
                double v = Double.parseDouble(text.replace("万", ""));
                return String.valueOf((long) (v * 10000));
            }

            // k / K 处理
            if (text.endsWith("k") || text.endsWith("K")) {
                double v = Double.parseDouble(text.substring(0, text.length() - 1));
                return String.valueOf((long) (v * 1000));
            }

            // 亿 处理
            if (text.endsWith("亿")) {
                double v = Double.parseDouble(text.replace("亿", ""));
                return String.valueOf((long) (v * 100000000));
            }

            // b / B（海外常用 billion）
            if (text.endsWith("b") || text.endsWith("B")) {
                double v = Double.parseDouble(text.substring(0, text.length() - 1));
                return String.valueOf((long) (v * 1000000000));
            }

            // 纯数字
            return String.valueOf(Long.parseLong(text));

        } catch (Exception e) {
            // fallback：保留数字
            String digits = text.replaceAll("\\D", "");
            return digits.isEmpty() ? "0" : digits;
        }
    }

    // ---------------------------------------------------------
    // 页面元素查找封装
    // ---------------------------------------------------------
    private List<MobileElement> findAll(String xpath) {
        try {
            return driver.findElements(By.xpath(xpath));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Optional<MobileElement> findFirst(String xpath) {
        List<MobileElement> list = findAll(xpath);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private boolean exists(String xpath) {
        return !findAll(xpath).isEmpty();
    }

    private boolean anyExists(String[] xps) {
        if (xps == null) return false;
        for (String xp : xps)
            if (xp != null && exists(xp)) return true;
        return false;
    }

    private String safeText(MobileElement e) {
        try {
            return Optional.ofNullable(e.getText()).orElse("").trim();
        } catch (Exception ex) {
            try {
                return Optional.ofNullable(e.getAttribute("text")).orElse("").trim();
            } catch (Exception ignore) {
                return "";
            }
        }
    }

    // ---------------------------------------------------------
    // 滑动逻辑 (Appium 滑动 + 统一坐标)
    // ---------------------------------------------------------
    private void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        try {
            PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger1");
            Sequence swipe = new Sequence(finger, 1);

            swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
            swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
            swipe.addAction(finger.createPointerMove(Duration.ofMillis(durationMs), PointerInput.Origin.viewport(), endX, endY));
            swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

            driver.perform(Collections.singletonList(swipe));
            Thread.sleep(200);
        } catch (Exception e) {
            logger.fine("swipe failed: " + e.getMessage());
        }
    }

    private boolean swipeUp() {
        try {
            int w = driver.manage().window().getSize().width;
            int h = driver.manage().window().getSize().height;
            swipe(w / 2, (int) (h * 0.75), w / 2, (int) (h * 0.25), 500);
            return true;
        } catch (Exception e) {
            logger.fine("swipeUp error: " + e.getMessage());
            return false;
        }
    }

    private boolean swipeToNextVideo() {
        return swipeUp();
    }

    // ---------------------------------------------------------
    // 页面判断 & 点击进入作品区
    // ---------------------------------------------------------
    private boolean isOnProfilePage() {
        String[] xps = {
                "//*[contains(@resource-id, 'nickname')]",
                "//*[contains(@resource-id, 'user_profile')]",
                "//*[contains(@text, '关注') and contains(@text, '粉丝')]",
                "//*[contains(@text, '获赞')]"
        };
        return anyExists(xps);
    }


    private boolean enterAuthorProfile() {
        String[] authorXpaths = {
                // 新版详情页：作者名仍在 title，但必须限定 @ 开头。
                "//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/title' and starts-with(@text,'@')]",
                // 搜索/地址视频流新版：作者名使用混淆 resource-id -+
                "//*[@resource-id='com.ss.android.ugc.aweme:id/-+' and string-length(@text)>0]",
                "//*[contains(@resource-id,'nickname') and string-length(@text)>0]",
                // 作者名文本没暴露时，点击可点击头像也能进入主页。
                "//*[@resource-id='com.ss.android.ugc.aweme:id/user_avatar' and @clickable='true']",
                "//*[contains(@content-desc,'@') and @clickable='true']"
        };

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            for (String xpath : authorXpaths) {
                for (MobileElement element : findAll(xpath)) {
                    try {
                        if (!element.isDisplayed()) {
                            continue;
                        }
                        String text = normalize(safeText(element));
                        logger.info("尝试进入作者主页，定位方式: " + xpath + ", 文本: " + text);
                        tapCenter(element);
                        Thread.sleep(500);
                        return true;
                    } catch (Exception ignored) {
                        // 当前节点可能在抖音重绘时失效，继续尝试下一种定位。
                    }
                }
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        logger.warning("enterAuthorProfile failed: 未找到作者名称/头像定位节点");
        return false;
    }

    private boolean waitUntilProfilePage() {
        long end = System.currentTimeMillis() + 8000;

        while (System.currentTimeMillis() < end) {
            boolean inProfile = !driver.findElements(
                    By.xpath("//*[contains(@text,'作品') or contains(@text,'粉丝')]")
            ).isEmpty();

            if (inProfile) {
                return true;
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
        }
        return false;
    }


    private void tapCenter(MobileElement el) {
        Rectangle r = el.getRect();
        int x = r.getX() + r.getWidth() / 2;
        int y = r.getY() + r.getHeight() / 2;

        new TouchAction<>(driver)
                .tap(PointOption.point(x, y))
                .waitAction(WaitOptions.waitOptions(Duration.ofMillis(150)))
                .perform();
    }
    static final String PROFILE_VIDEO_CARDS = "//*[@resource-id='com.ss.android.ugc.aweme:id/qbt']/parent::*[@resource-id='com.ss.android.ugc.aweme:id/container']";

    private boolean clickFirstVideo() {
        try {
            List<MobileElement> elements = findAll(PROFILE_VIDEO_CARDS);
            if (elements.isEmpty()) elements = findAll("//*[@resource-id='com.ss.android.ugc.aweme:id/container' and @clickable='true']");
            int width = driver.manage().window().getSize().width;
            int height = driver.manage().window().getSize().height;
            for (MobileElement element : elements) {
                if (!element.isDisplayed()) continue;
                Rectangle box = element.getRect();
                // Exclude full-screen overlays and header/avatar containers sharing the same ID.
                if (box.getWidth() < width * 0.15 || box.getWidth() > width * 0.65
                        || box.getHeight() < height * 0.08 || box.getY() < height * 0.2
                        || box.getY() >= height) continue;
                tapCenter(element);
                for (int attempt = 0; attempt < 8; attempt++) {
                    ensureCrawlActive();
                    Thread.sleep(500);
                    if (isOnVideoDetailPage()) {
                        logger.info("已进入第一个人员作品详情页");
                        return true;
                    }
                }
                logger.warning("作品卡片点击后未进入视频详情页");
                return false;
            }
            logger.warning("未找到可见的人员作品卡片");
            return false;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.log(java.util.logging.Level.WARNING, "点击人员第一个作品失败", e);
            return false;
        }
    }


    private boolean goVideoList() {
        // 新版地点内容页可能已经直接打开第一条内容，页面计数器显示为“1/125”。
        // 此时不应再寻找旧版视频卡片，也不能返回上一页。
        if (isOnVideoDetailPage()) {
            logger.info("当前已在地点内容详情页，跳过再次点击视频卡片");
            return true;
        }

        String[] videoXpaths = {
                // 新版地点内容页是 RecyclerView 双列卡片，卡片本身 clickable=false，
                // 但标题/作者 ViewGroup 的 content-desc 可作为点击入口。
                "//*[@resource-id='com.ss.android.ugc.aweme:id/gxr']//*[@content-desc]",
                // 兼容部分版本不暴露 RecyclerView resource-id 的情况。
                "//*[@content-desc]",
                // 旧版地址/搜索结果。
                "//android.view.View[@resource-id='com.ss.android.ugc.aweme:id/rej' and @clickable='true']",
                "//android.widget.LinearLayout[@resource-id='com.ss.android.ugc.aweme:id/dp7' and @clickable='true']",
                "//*[@resource-id='com.ss.android.ugc.aweme:id/rej' and @clickable='true']",
                "//*[@resource-id='com.ss.android.ugc.aweme:id/dp7' and @clickable='true']",
                "//com.lynx.tasm.ui.image.UIImage[@clickable='true']",
                "//*[@resource-id='com.ss.android.ugc.aweme:id/cover']"
        };

        Set<String> seen = new HashSet<>();
        List<MobileElement> candidates = new ArrayList<>();
        for (String xpath : videoXpaths) {
            for (MobileElement element : findAll(xpath)) {
                try {
                    Rectangle rect = element.getRect();
                    int centerY = rect.getY() + rect.getHeight() / 2;
                    String contentDescription = safeContentDescription(element);
                    if (!element.isDisplayed()
                            || rect.getWidth() <= 0
                            || rect.getHeight() <= 0
                            || rect.getY() < 320
                            || rect.getY() + rect.getHeight() > driver.manage().window().getSize().getHeight() - 80
                            || contentDescription.length() < 10) {
                        continue;
                    }

                    String key = rect.getX() + ":" + rect.getY() + ":"
                            + rect.getWidth() + ":" + rect.getHeight();
                    if (seen.add(key)) {
                        candidates.add(element);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        logger.info("地址视频页找到 " + candidates.size() + " 个视频候选");
        // 以“第一行最左侧”为第一条，而不是简单按 y 排序。
        // 双列卡片的标题 y 坐标可能相差几十像素。
        candidates.sort((left, right) -> {
            try {
                Rectangle l = left.getRect();
                Rectangle r = right.getRect();
                return Integer.compare(l.getY(), r.getY());
            } catch (Exception e) {
                return 0;
            }
        });

        List<MobileElement> firstRow = new ArrayList<>();
        int firstY = Integer.MAX_VALUE;
        for (MobileElement candidate : candidates) {
            try {
                int y = candidate.getRect().getY();
                if (firstY == Integer.MAX_VALUE) firstY = y;
                if (y <= firstY + 180) firstRow.add(candidate);
            } catch (Exception ignored) {
            }
        }
        firstRow.sort((left, right) -> {
            try {
                return Integer.compare(left.getRect().getX(), right.getRect().getX());
            } catch (Exception e) {
                return 0;
            }
        });

        for (MobileElement element : firstRow.isEmpty() ? candidates : firstRow) {
            try {
                Rectangle rect = element.getRect();
                logger.info("尝试点击第一条地点内容: " + rect + ", " + safeContentDescription(element));
                tapCenter(element);

                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    if (isOnVideoDetailPage()) {
                        return true;
                    }
                    Thread.sleep(300);
                }

                if (isOnVideoDetailPage()) {
                    return true;
                }
                driver.navigate().back();
                Thread.sleep(800);
            } catch (Exception e) {
                logger.fine("地址视频候选点击失败: " + e.getMessage());
            }
        }

        logger.warning("地址视频页未找到可进入详情页的视频卡片");
        return false;
    }

    private boolean isOnVideoDetailPage() {
        // 新版详情页仍然保留顶部搜索入口 et_search_kw，不能把它作为单独的否定条件。
        // root_layout/guy 是详情页自身的稳定标识，优先级最高。
        if (exists("//*[@resource-id='com.ss.android.ugc.aweme:id/root_layout']")
                || exists("//*[@resource-id='com.ss.android.ugc.aweme:id/guy']")
                // 新版地点内容详情页使用 3ac/3ap 显示当前内容序号，例如 1/125。
                || (exists("//*[@resource-id='com.ss.android.ugc.aweme:id/3ac']")
                && exists("//*[@resource-id='com.ss.android.ugc.aweme:id/3ap']")
                && !exists("//*[@resource-id='com.ss.android.ugc.aweme:id/gxr']"))) {
            return true;
        }

        if (exists("//*[@resource-id='com.ss.android.ugc.aweme:id/et_search_kw']")) {
            return false;
        }

        String[] xps = {
                "//*[@resource-id='com.ss.android.ugc.aweme:id/desc']",
                "//*[contains(@content-desc,'喜欢')]",
                "//*[contains(@content-desc,'评论')]"
        };
        return anyExists(xps);
    }


    // ---------------------------------------------------------
    // 单个视频数据读取：UID/描述/计数
    // ---------------------------------------------------------
    /**
     * UI层视频唯一指纹（非 aweme_id）
     * 规则：author + desc + publishTime → MD5
     */
    /**
     * UI层视频唯一指纹（非 aweme_id）
     * 规则：author + desc + publishTime → MD5
     */
    private Map<String, Object> getVideoUniqueId() {
        Map<String, Object> map = new HashMap<>();
        MobileElement authorEl = null;
        MobileElement descEl = null;
        MobileElement timeEl = null;

        // 抖音升级后 title 不再只承载作者，也可能承载“相关搜索”等标题。
        // 必须优先取文本以 @ 开头的作者节点。
        authorEl = firstAuthorElement();

        try {
            descEl = driver.findElement(
                    By.id("com.ss.android.ugc.aweme:id/desc")
            );
        } catch (Exception ignored) {
        }

        try {
            timeEl = driver.findElement(
                    By.id("com.ss.android.ugc.aweme:id/bxh")
            );
        } catch (Exception ignored) {
        }

        // ---------- author ----------
        String author = normalize(safeText(authorEl));


        // ---------- desc（主来源 + 兜底） ----------
        String desc = normalize(safeText(descEl));
        if (desc.isEmpty()) {
            // 使用你原来的 XPath 兜底方案
            desc = getVideoDescription();
        }

        // ---------- publish time ----------
        String rawTime = normalize(safeText(timeEl));
        String publishTime = rawTime;


        if (!rawTime.isEmpty() && rawTime.contains("发布时间")) {
            publishTime = rawTime
                    .replace("发布时间：", "")
                    .split("IP属地")[0]
                    .trim();
        }

        publishTime = normalize(publishTime);

        // ---------- 稳态兜底 ----------
        // 三个关键字段全空，说明页面尚未稳定，不生成 hash
        if (author.isEmpty() && desc.isEmpty() && publishTime.isEmpty()) {
            return new HashMap<>();
        }


        // ---------- 组合唯一键 ----------
        String videoKey = author + "|" + desc + "|" + publishTime;
        String uid = DigestUtils.md5Hex(videoKey);
        map.put("author", author);
        map.put("rawTime", rawTime);
        map.put("uid", uid);
        map.put("desc", desc);

        return map;
    }

    private MobileElement firstAuthorElement() {
        String[] authorXpaths = {
                "//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/title' and starts-with(@text,'@')]",
                "//*[@resource-id='com.ss.android.ugc.aweme:id/-+' and string-length(@text)>0]",
                "//*[contains(@resource-id,'nickname') and string-length(@text)>0]",
                "//*[@resource-id='com.ss.android.ugc.aweme:id/user_avatar' and @clickable='true']"
        };

        for (String xpath : authorXpaths) {
            List<MobileElement> elements = findAll(xpath);
            for (MobileElement element : elements) {
                String text = normalize(safeText(element));
                if (xpath.contains("user_avatar") || !text.isEmpty()) {
                    return element;
                }
            }
        }
        return null;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s
                .replace('\u200b', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String safeContentDescription(MobileElement element) {
        try {
            String value = element.getAttribute("content-desc");
            if (value == null || value.trim().isEmpty()) {
                value = element.getAttribute("contentDescription");
            }
            return normalize(value);
        } catch (Exception e) {
            return "";
        }
    }


    private String getVideoDescription() {
        String[] xps = {
                "//*[contains(@resource-id,'desc')]",
                "//*[contains(@text,'#')]",
                "//*[contains(@resource-id,'title')]",
                // 新版详情页正文可能是无 resource-id 的 ViewGroup content-desc。
                "//*[string-length(normalize-space(@content-desc)) > 20]"
        };
        for (String xp : xps) {
            List<MobileElement> eles = findAll(xp);
            if (!eles.isEmpty()) {
                logger.fine("description xpath: " + xp);
                return safeText(eles.get(0));
            }
        }
        return "";
    }

    private String getVideoStat(String statType) {
        Map<String, String[]> xpathMap = new HashMap<>();

        xpathMap.put("赞", new String[]{
                "//*[@resource-id='com.ss.android.ugc.aweme:id/f9g']",
                "//*[@resource-id='com.ss.android.ugc.aweme:id/f94']",
                "//*[contains(@resource-id,'like_count')]"
        });

        xpathMap.put("评论", new String[]{
                "//*[@resource-id='com.ss.android.ugc.aweme:id/d+_']//android.widget.TextView",
                "//*[contains(@content-desc, '评论')]",
                "//*[contains(@resource-id, 'comment')]"
        });

        String[] xpaths = xpathMap.getOrDefault(statType, new String[]{});
        for (String xp : xpaths) {
            List<MobileElement> eles = findAll(xp);
            for (MobileElement e : eles) {
                String text = safeText(e);
                // 抖音新版统计按钮通常没有 text，数字放在 content-desc：
                // 例如“评论15，按钮”。
                if (text == null || text.trim().isEmpty()) {
                    text = safeContentDescription(e);
                }
                if (text != null && text.matches(".*\\d.*")) {
                    logger.fine("stat xpath: " + xp);
                    String value = extractStatNumber(text);
                    if (!"0".equals(value) || text.contains("0")) {
                        logger.info("读取到" + statType + "数量: " + value + "，原始值: " + text);
                        return value;
                    }
                }
            }
        }
        return "评论".equals(statType) ? "" : "0";
    }

    private String extractStatNumber(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "0";
        }

        String value = raw.replaceAll("\\s+", "");
        Matcher compact = Pattern.compile("(\\d+(?:\\.\\d+)?(?:万|亿|[kK]))").matcher(value);
        if (compact.find()) {
            return cleanNumberString(compact.group(1));
        }

        Matcher number = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(value);
        return number.find() ? cleanNumberString(number.group(1)) : "0";
    }

    //    收藏
    public String getFavoriteCount() {
        try {
            MobileElement ele = driver.findElement(
                    By.xpath("//*[contains(@content-desc,'收藏')]")
            );
            String desc = ele.getAttribute("content-desc");

            Matcher m = Pattern.compile("收藏(\\d+)").matcher(desc);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
        }

        // fallback：UI 上的数字 TextView
        try {
            MobileElement tv = driver.findElement(By.xpath("//*[@resource-id='com.ss.android.ugc.aweme:id/d8d']"));
            return cleanNumberString(tv.getText());
        } catch (Exception ignored) {
        }

        return "0";
    }

    //    分享
    public String getShareCount() {
        try {
            MobileElement ele = driver.findElement(
                    By.xpath("//*[contains(@content-desc,'分享')]")
            );
            String desc = ele.getAttribute("content-desc");

            Matcher m = Pattern.compile("分享(\\d+)").matcher(desc);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
        }

        // fallback
        try {
            MobileElement tv = driver.findElement(
                    By.xpath("//*[@resource-id='com.ss.android.ugc.aweme:id/ybe']")
            );
            return cleanNumberString(tv.getText());
        } catch (Exception ignored) {
        }

        return "0";
    }


    // ---------------------------------------------------------
    // 评论区抓取
    // ---------------------------------------------------------
    private boolean openComments() {
        String[] xps = {
                "//*[@resource-id='com.ss.android.ugc.aweme:id/ap_']",
                "//*[contains(@resource-id,'comments')]"
        };
        for (String xp : xps) {
            Optional<MobileElement> opt = findFirst(xp);
            if (opt.isPresent()) {
                try {
                    opt.get().click();
                    Thread.sleep(600);
                    return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private void closeComments() {
        try {
            driver.navigate().back();
            Thread.sleep(400);
        } catch (Exception ignored) {
        }
    }

    private List<String> getAllComments() {
        List<String> out = new ArrayList<>();
        String xp = "//*[contains(@resource-id,'comment')]";

        for (int i = 0; i < 20; i++) {
            List<MobileElement> eles = findAll(xp);
            for (MobileElement e : eles) {
                String t = safeText(e);
                if (!t.isEmpty()) out.add(t);
            }
            if (!swipeUp()) break;
        }
        return out;
    }

    // ---------------------------------------------------------
    // ⭐ 视频时长解析（线程版）— 滑动保持进度条可见 + pagSource 解析 mm:ss
    // ---------------------------------------------------------
    private int extractVideoDuration(String deviceId) {
        Process swipe = null;
        try {
            ensureCrawlActive();
            String visible = driver.getPageSource();
            // Photo carousels have no video duration; scrubbing here changes the selected photo.
            if (visible.contains("text=\"图文\"") || visible.contains("content-desc=\"图文\"")
                    || visible.contains("text=\"动图\"")) return 0;
            Integer readable = parseDurationFromXml(visible);
            if (readable != null) return readable;
            adbTap(deviceId, 540, 1200);
            // Only this worker calls the driver. A timed-out helper thread must not keep using it.
            swipe = new ProcessBuilder(ADB, "-s", deviceId, "shell", "input", "swipe",
                    "300", "2088", "900", "2088", "3000")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            for (int attempt = 0; attempt < 3; attempt++) {
                Thread.sleep(450);
                Integer duration = parseDurationFromXml(driver.getPageSource());
                if (duration != null && duration > 0) return duration;
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取视频时长被中断", e);
        } catch (Exception e) {
            logger.log(java.util.logging.Level.WARNING, "读取视频时长失败", e);
            return 0;
        } finally {
            if (swipe != null) {
                try {
                    if (!swipe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) swipe.destroyForcibly();
                } catch (InterruptedException e) {
                    swipe.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void adbTap(String deviceId, int x, int y) throws Exception {
        Process process = new ProcessBuilder(ADB, "-s", deviceId, "shell", "input", "tap",
                String.valueOf(x), String.valueOf(y))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("ADB tap 超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("ADB tap 失败");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    static Integer parseDurationFromXml(String xml) {
        if (xml == null) return null;
        Matcher attributes = Pattern.compile("(?:text|content-desc)=\"([^\"]*)\"").matcher(xml);
        int maximum = 0;
        while (attributes.find()) {
            Matcher times = Pattern.compile("(?<!\\d)(\\d{1,3}):(\\d{2})(?!\\d)").matcher(attributes.group(1));
            while (times.find()) {
                int seconds = Integer.parseInt(times.group(2));
                if (seconds < 60) maximum = Math.max(maximum, Integer.parseInt(times.group(1)) * 60 + seconds);
            }
        }
        return maximum > 0 ? maximum : null;
    }

    private Map<String, Object> videoSummary(Map<String, Object> video) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("uid", video.get("uid"));
        summary.put("video_index", video.get("video_index"));
        return summary;
    }

    private static void ensureCrawlActive() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("采集已取消");
    }

    private String recordVideoToFile(int index, int durationSec, String deviceId, String douyinId, String uid, String address) {
        String previousPath;
        if (address == null) {
            Video previous = videoService.selectVideoByUId(uid);
            previousPath = previous == null ? null : previous.getVideoPath();
        } else {
            AddressVideo previous = addressVideoService.selectAddressVideo(uid, address);
            previousPath = previous == null ? null : previous.getVideoPath();
        }
        String reusable = reusableRecording(previousPath);
        if (reusable != null) {
            logger.info("续采评论，复用已保存录屏: " + reusable);
            return reusable;
        }

        int segmentLength = 30; // 每段30秒
        int totalSegments = (int) Math.ceil(durationSec / (double) segmentLength);

        String timestamp = String.valueOf(System.currentTimeMillis());

        // === 1️⃣ 构建 douyinId 目录 ===
        Path douyinDir = Paths.get(videoOutputFolder, douyinId);

        try {
            if (!Files.exists(douyinDir)) {
                Files.createDirectories(douyinDir);
                logger.info("创建视频目录: " + douyinDir);
            }

            // 最终合并文件
            String finalFilename = douyinDir.resolve(
                    String.format("video_%03d_%s.mp4", index + 1, timestamp)
            ).toString();

            List<String> segmentFiles = new ArrayList<>();

            AndroidDriver<?> androidDriver = (AndroidDriver<?>) driver;
            AdbVideoRecorder recorder = new AdbVideoRecorder(ADB);

            for (int part = 1; part <= totalSegments; part++) {

                int startTime = (part - 1) * segmentLength;
                int thisDuration = Math.min(segmentLength, durationSec - startTime);
                if (thisDuration <= 0) break;

                String partFile = douyinDir.resolve(
                        String.format("video_%03d_%s_part%02d.mp4",
                                index + 1, timestamp, part)
                ).toString();

                logger.info("开始录制分段 " + part + "/" + totalSegments +
                        " → " + thisDuration + " 秒 → " + partFile);

                ensureCrawlActive();
                // Also keep the Appium session alive during longer recordings over ADB.
                if (!"com.ss.android.ugc.aweme".equals(androidDriver.getCurrentPackage())) {
                    throw new IllegalStateException("录制时已离开抖音页面");
                }
                recorder.recordSegment(deviceId, thisDuration, Paths.get(partFile));

                segmentFiles.add(partFile);
            }

            // 只有一段，直接返回
            if (segmentFiles.size() == 1) {
                return segmentFiles.get(0);
            }

            // 多段合并
            VideoMergeTest.mergeVideoSegments(segmentFiles, finalFilename);

            return finalFilename;

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("分段录制失败", e);
        }
    }

    // ---------------------------------------------------------
    // 截图保存
    // ---------------------------------------------------------
    private String saveScreenshot(int index) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String filename = String.format("%s\\screenshot_%03d_%s.png",
                    screenshotFolder, index + 1, timestamp);

            File src = driver.getScreenshotAs(org.openqa.selenium.OutputType.FILE);
            File dest = new File(filename);

            if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
            Files.move(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return filename;

        } catch (Exception e) {
            logger.warning("截图保存失败: " + e.getMessage());
            return "";
        }
    }


    public static final String[] WORKS_COUNT_XPATHS = {
            "//*[contains(@text, '作品')]",
            "//*[contains(@content-desc, '作品')]",
            "//*[contains(@text, '作品') and not(contains(@resource-id, 'tab'))]/preceding-sibling::android.widget.TextView[1]",
            "//*[contains(@text, '作品') and not(contains(@resource-id, 'tab'))]/following-sibling::android.widget.TextView[1]",
            "//*[contains(@resource-id, 'works') and not(contains(@resource-id, 'tab'))]",
            "//*[contains(@resource-id, 'video') and not(contains(@resource-id, 'tab'))]",
            "//*[@resource-id='com.ss.android.ugc.aweme:id/f73']",
            "//*[contains(@resource-id, 'tab_title') and contains(@text, '作品')]/../preceding-sibling::*[1]",
            "//*[@text='作品']/../android.widget.TextView[1]",
            "//*[contains(@text, '作品')]/..//android.widget.TextView[1]",
            "//*[contains(@text, '作品')]/ancestor::*[1]/android.widget.TextView[1]"
    };


    // ---------------------------------------------------------
    // ⭐ 主流程：录制所有视频 + 获取评论 + 截图 + 保存数据
    // ---------------------------------------------------------
    public List<Map<String, Object>> recordAllVideosAndComments(String deviceId, String accountName, String tags) {

        logger.info("开始录制所有视频并获取评论");

        List<Map<String, Object>> allVideoData = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        int index = 0;
        int maxVideos = 0;

        // 必须进入主页 & 作品区
        if (!isOnProfilePage()) {
            logger.warning("不在个人主页");
            throw new IllegalStateException("未能进入预期采集页面，停止本轮等待重试");
        }


        for (String xpath : WORKS_COUNT_XPATHS) {
            try {
                MobileElement el = (MobileElement) driver.findElement(By.xpath(xpath));
                String text = el.getText().trim();

                if (!text.isEmpty()) {
                    try {
                        // 去掉非数字字符，只取数字
                        int parsedCount = VideoCommentsFetcher.parseDouyinCount(text);
                        if (parsedCount >= 0) {
                            maxVideos = parsedCount;
                            logger.info("解析到作品数量: " + maxVideos);
                            break; // 找到第一个有效的就用
                        }
                    } catch (NumberFormatException ignored) {
                        logger.warning("无法解析作品数量");
                    }
                }
            } catch (Exception e) {
                // 找不到元素就跳到下一个 xpath
            }
        }

        if (maxVideos <= 0) {
            logger.warning("无法解析作品数量，使用默认 100");
            maxVideos = 100;
        }

        logger.info("总共需要处理的视频数量: " + maxVideos);

        if (!clickFirstVideo()) {
            logger.warning("无法进入作品区");
            throw new IllegalStateException("未能进入预期采集页面，停止本轮等待重试");
        }
        if (tags.contains("前六条")){
            maxVideos=Math.min(maxVideos, 6);
        }

        while (index < maxVideos) {
            try {
                ensureCrawlActive();
                // 确保最新 PageSource
                driver.getPageSource();
                logger.info("处理第 " + (index + 1) + " 个视频");

                MobileElement descEl = null;
                try {

                    descEl = driver.findElement(
                            By.id("com.ss.android.ugc.aweme:id/desc")
                    );
                } catch (Exception ignored) {
                }

                // ---------- desc（主来源 + 兜底） ----------
                String desc = normalize(safeText(descEl));
                if (desc.contains("广告")) {
                    logger.info("取到视频为 广告 ，跳过");
                    index++;
                    if (!swipeToNextVideo()) break;
                    continue;
                }


                Map<String, Object> uidMap = getVideoUniqueId();
                String uid = (String) uidMap.get("uid");
                String author = (String) uidMap.get("author");
                String rawTime = (String) uidMap.get("rawTime");
                String descCon = (String) uidMap.get("desc");

// UID 为空，直接跳过（非常重要）
                if (uid == null || uid.isEmpty()) {
                    logger.info("未获取到视频 UID，跳过");
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }
// 内存去重
                if (processed.contains(uid)) {
                    logger.info("视频已在本轮处理过，跳过");
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }
// 数据库去重
                if (hasRequiredVideoData(videoService.selectVideoByUId(uid), tags)) {
                    logger.info("视频已存在数据库，跳过");
                    processed.add(uid); // 可选：避免后面再次查询 DB
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }
// Persist successfully before marking a video as processed.

// ↓↓↓ 这里才是真正的“新视频处理逻辑” ↓↓↓


//                ===========================================

                // ◆ 保存视频信息
                Map<String, Object> video = new LinkedHashMap<>();
                video.put("video_index", index + 1);
                video.put("uid", uid);
                video.put("comments_requested", tags != null && tags.contains("评论"));
                video.put("author", author);
                video.put("rawTime", rawTime);
                video.put("descCon", descCon);

                video.put("timestamp", LocalDateTime.now().toString());
                video.put("description", getVideoDescription());
                video.put("likes_count", getVideoStat("赞"));
                video.put("comments_count", getVideoStat("评论"));
                video.put("share_count", getShareCount());
                video.put("collect_count", getFavoriteCount());


                if (tags.contains("视频")) {
                    //                 ◆ 线程版解析视频时长（增加重试，确保获取到）
                    int duration = 0;
                    int maxRetry = 5;
                    for (int attempt = 1; attempt <= maxRetry; attempt++) {
                        duration = extractVideoDuration(deviceId);
                        Thread.sleep(1000);
                        if (duration > 0) break;
                        logger.info("⚠️ 视频时长获取失败，重试 " + attempt + "/" + maxRetry);
                        Thread.sleep(500); // 等待0.5秒再试
                    }
                    video.put("duration_known", duration > 0);
                    if (duration <= 0) duration = 30; // 防止解析失败时，给默认50秒
                    if (duration > 600) duration = 600; // 防止解析失败时，给默认50秒
                    video.put("recording_duration_seconds", duration);
                    logger.info("本次录屏时长: " + duration + " 秒，页面时长已识别=" + video.get("duration_known"));

//                // ◆ 录制视频
                    String videoPath = recordVideoToFile(index, duration, deviceId, accountName, uid, null);
                    video.put("video_path", videoPath);
                } else {
                    video.put("video_path", "");
                }

                // ◆ 截图
                String screenshotPath = saveScreenshot(index);
                video.put("screenshot_path", screenshotPath);

                if (tags.contains("评论")) {

                    if (!"0".equals(video.get("comments_count"))) {
                        // 指定保存评论的目录
                        String videosDir = DouyinPathUtils.resolve("videosDir");

                        // 初始化抓取器
                        VideoCommentsFetcher fetcher = new VideoCommentsFetcher(driver, douyinTaskService,
                                accountContentService,
                                addressAccountContentService,
                                accountService);
// 抓取当前视频的全部评论（包含 totalComments + comments + json_path）
                        Map<String, Object> commentResult = fetcher.fetchAllComments("1", accountName, uid, deviceId, index, videosDir, (String) video.get("comments_count"));
                        if (!Boolean.TRUE.equals(commentResult.get("complete")) && !Boolean.TRUE.equals(commentResult.get("deferred"))) {
                            throw new IllegalStateException("评论采集未完成，已保留断点: " + commentResult.get("json_path"));
                        }
// 取出评论数组
                        List<Map<String, Object>> comments = (List<Map<String, Object>>) commentResult.get("comments");
// 写入视频数据
                        video.put("comments", comments);
                        video.put("json_path", commentResult.get("json_path"));       // ✔ 正确：保存路径字符串
                        video.put("comments_complete", Boolean.TRUE.equals(commentResult.get("complete")));
                        video.put("comments_deferred", Boolean.TRUE.equals(commentResult.get("deferred")));
                        video.put("totalComments", commentResult.get("totalComments")); // ✔ 正确：真实总评论数

                        Thread.sleep(1000); // 等待0.5秒确保下一个视频加载完成
                    }

                } else {
                    List<Map<String, Object>> comments = new ArrayList<>();
                    video.put("comments", comments);
                    video.put("json_path", "");       // ✔ 正确：保存路径字符串
                    video.put("totalComments", ""); // ✔ 正确：真实总评论数

                }
                ensureCrawlActive();
                saveSingleVideo(video, deviceId, accountName);
                processed.add(uid);
                allVideoData.add(videoSummary(video));


                logger.info("第 " + (index + 1) + " 个视频" + (Boolean.TRUE.equals(video.get("comments_deferred")) ? "已保存部分评论，稍后续采" : "处理完成"));
                if (Boolean.TRUE.equals(video.get("comments_deferred")) || douyinTaskService.hasWaitingTasks(deviceId)) {
                    logger.info("当前视频已落库，释放设备处理排队的地址/发布者任务");
                    return new CrawlBatch(allVideoData);
                }

                index++;

                // ◆ 滑到下一个视频，确保 UI 渲染完成
                if (!swipeToNextVideo()) break;
                Thread.sleep(500); // 等待0.5秒确保下一个视频加载完成

            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                logger.log(java.util.logging.Level.SEVERE, "处理视频失败 index=" + index + " device=" + deviceId, e);
                throw new IllegalStateException("视频未保存完成，停止本轮等待重试", e);
            }
        }

        // Driver lifecycle is owned by DouyinVideoService; do not restart shared Appium.

        logger.info("总共处理视频数量: " + allVideoData.size());
        return allVideoData;
    }


    public void saveSingleVideo(Map<String, Object> map, String devId, String douyinId) {

        Video video = new Video();

        video.setVideoIndex(map.get("video_index") == null ? null :
                Long.valueOf(map.get("video_index").toString()));

//      录入视频uid
        video.setUid(map.get("uid") == null ? null :
                map.get("uid").toString());

        video.setDevId(devId);
        video.setDouyinId(douyinId);
        video.setCreateTime(new Date());

        // ==========================
        // comments → JSON 字符串存储
        // ==========================
        List<Map<String, Object>> comments = (List<Map<String, Object>>) map.get("comments");

        String commentsJson = (comments != null)
                ? JSONObject.toJSONString(comments)
                : "[]";

        video.setContent(commentsJson);


        // ==========================
        // 其他字段保持不变
        // ==========================
        video.setVideoPath(toWebPath((String) map.get("video_path")));
        video.setImagePath(toWebPath((String) map.get("screenshot_path")));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("uid", map.get("uid"));
        if (Boolean.TRUE.equals(map.get("comments_requested"))) {
            json.put("comments_complete", Boolean.TRUE.equals(map.get("comments_complete"))
                    || "0".equals(String.valueOf(map.get("comments_count"))));
        }
        json.put("captured_at", map.get("timestamp"));
        json.put("duration_known", map.get("duration_known"));
        json.put("recording_duration_seconds", map.get("recording_duration_seconds"));
        json.put("likes", map.get("likes_count"));
        json.put("comments", map.get("comments_count"));
        json.put("shares", map.get("share_count"));
        json.put("collects", map.get("collect_count"));
        json.put("author", map.get("author"));
        json.put("rawTime", map.get("rawTime"));
        json.put("descCon", map.get("descCon"));


        video.setJsonPath(toWebPath((String) map.get("json_path")));
        video.setJsonString(JSONObject.toJSONString(json));

        Video previous = videoService.selectVideoByUId(video.getUid());
        if (previous == null) {
            videoService.insertVideo(video);
        } else {
            video.setId(previous.getId());
            if (!Boolean.TRUE.equals(map.get("comments_requested"))) {
                video.setContent(previous.getContent());
                JSONObject old = JSON.parseObject(previous.getJsonString());
                if (old != null) {
                    for (String key : Arrays.asList("comments", "comments_complete", "comments_imported", "comments_imported_count")) {
                        if (old.containsKey(key)) json.put(key, old.get(key));
                    }
                }
                video.setJsonString(JSON.toJSONString(json));
                video.setJsonPath(previous.getJsonPath());
            }
            if (StringUtils.isEmpty(video.getVideoPath())) video.setVideoPath(previous.getVideoPath());
            videoService.updateVideo(video);
        }
    }


    public void saveSingleVideoByAddress(Map<String, Object> map, String devId, String douyinId, String address) {

        AddressVideo video = new AddressVideo();

        video.setVideoIndex(map.get("video_index") == null ? null :
                Long.valueOf(map.get("video_index").toString()));

        video.setUid(map.get("uid") == null ? null : map.get("uid").toString());
        video.setDevId(devId);
        video.setAddress(address);
        video.setDouyinId(douyinId);
        video.setCreateTime(new Date());

        // 评论 JSON
        List<Map<String, Object>> comments = (List<Map<String, Object>>) map.get("comments");
        video.setContent(comments != null ? JSONObject.toJSONString(comments) : "[]");

        video.setVideoPath(toWebPath((String) map.get("video_path")));
        video.setImagePath(toWebPath((String) map.get("screenshot_path")));
        video.setJsonPath(toWebPath((String) map.get("json_path")));

        // 视频基础 JSON
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("uid", map.get("uid"));
        if (Boolean.TRUE.equals(map.get("comments_requested"))) {
            json.put("comments_complete", Boolean.TRUE.equals(map.get("comments_complete"))
                    || "0".equals(String.valueOf(map.get("comments_count"))));
        }
        json.put("captured_at", map.get("timestamp"));
        json.put("duration_known", map.get("duration_known"));
        json.put("recording_duration_seconds", map.get("recording_duration_seconds"));
        json.put("likes", map.get("likes_count"));
        json.put("comments", map.get("comments_count"));
        json.put("shares", map.get("share_count"));
        json.put("collects", map.get("collect_count"));
        json.put("author", map.get("author"));
        json.put("rawTime", map.get("rawTime"));
        json.put("descCon", map.get("descCon"));
        video.setJsonString(JSONObject.toJSONString(json));

        // =========================
        // 补账号粉丝数据（安全写法）
        // =========================
        try {
            Account account = accountService.selectByDouyinId(douyinId);
            if (account != null && account.getJsonString() != null) {

                Map<String, Object> accMap = JSON.parseObject(account.getJsonString());

                long followCount = parseLong(accMap.get("follow_count"));
                long fansCount = parseLong(accMap.get("fans_count"));
                long likesCount = parseLong(map.get("likes_count")); // 视频点赞数在原 map

                video.setFollowCount(followCount);
                video.setFansCount(fansCount);
                video.setLikesCount(likesCount);
            } else {
                video.setFollowCount(0l);
                video.setFansCount(0l);
                video.setLikesCount(0l);
            }
        } catch (Exception ignored) {
        }

        AddressVideo previous = addressVideoService.selectAddressVideo(video.getUid(), address);
        if (previous == null) {
            addressVideoService.insertAddressVideo(video);
        } else {
            if (!Boolean.TRUE.equals(map.get("comments_requested"))) {
                video.setContent(previous.getContent());
                video.setJsonPath(previous.getJsonPath());
                if (StringUtils.isNotEmpty(previous.getJsonString())) {
                    JSONObject oldMetadata = JSON.parseObject(previous.getJsonString());
                    for (String key : Arrays.asList("comments", "comments_complete", "comments_imported", "comments_imported_count")) {
                        if (oldMetadata != null && oldMetadata.containsKey(key)) json.put(key, oldMetadata.get(key));
                    }
                    video.setJsonString(JSONObject.toJSONString(json));
                }
            }
            if (StringUtils.isEmpty(video.getVideoPath())) video.setVideoPath(previous.getVideoPath());
            if (StringUtils.isEmpty(video.getDouyinId())) video.setDouyinId(previous.getDouyinId());
            addressVideoService.updateCrawledAddressVideo(video);
        }
    }

    static boolean hasRequiredAddressData(AddressVideo video, String tags) {
        if (video == null) return false;
        if (tags.contains("视频") && StringUtils.isEmpty(video.getVideoPath())) return false;
        if (tags.contains("基本信息") && !AccountInfoFetcher.isRealDouyinId(video.getDouyinId())) return false;
        if (!tags.contains("评论")) return true;
        try {
            JSONObject metadata = JSON.parseObject(video.getJsonString());
            if (metadata == null) return false;
            int expected = VideoCommentsFetcher.parseDouyinCount(metadata.getString("comments"));
            if (expected == 0) return true;
            if (StringUtils.isEmpty(video.getContent())) {
                // The maintenance job moves comments into the comment table.
                // Only explicit archival metadata establishes completeness of an empty source.
                return Boolean.TRUE.equals(metadata.getBoolean("comments_imported"))
                        && (Boolean.TRUE.equals(metadata.getBoolean("comments_complete"))
                        || (expected > 0 && metadata.getIntValue("comments_imported_count") >= expected));
            }
            Object comments = JSON.parse(video.getContent());
            if (!(comments instanceof List<?>)) return false;
            // A recorded panel end/limit may have fewer visible comments than its public badge.
            if (Boolean.TRUE.equals(metadata.getBoolean("comments_complete"))) return true;
            return expected > 0 && ((List<?>) comments).size() >= expected;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean hasRequiredVideoData(Video video, String tags) {
        if (video == null) return false;
        AddressVideo data = new AddressVideo();
        data.setVideoPath(video.getVideoPath());
        data.setDouyinId(video.getDouyinId());
        data.setContent(video.getContent());
        data.setJsonString(video.getJsonString());
        return hasRequiredAddressData(data, tags);
    }

    private void saveAddressVideoCopy(AddressVideo source, long videoIndex, String devId, String address) {
        AddressVideo video = new AddressVideo();
        video.setUid(source.getUid());
        video.setVideoIndex(videoIndex);
        video.setDevId(devId);
        video.setAddress(address);
        video.setDouyinId(source.getDouyinId());
        video.setCreateTime(new Date());
        video.setContent(source.getContent());
        video.setVideoPath(source.getVideoPath());
        video.setImagePath(source.getImagePath());
        video.setJsonPath(source.getJsonPath());
        video.setJsonString(source.getJsonString());
        video.setFollowCount(source.getFollowCount());
        video.setFansCount(source.getFansCount());
        video.setLikesCount(source.getLikesCount());
        video.setIpAddress(source.getIpAddress());
        addressVideoService.insertAddressVideo(video);
    }

    private Map<String, Object> buildVideoMapFromAddressVideo(AddressVideo source, int index) {
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("video_index", index + 1);
        video.put("uid", source.getUid());
        video.put("timestamp", LocalDateTime.now().toString());
        video.put("video_path", source.getVideoPath());
        video.put("screenshot_path", source.getImagePath());
        video.put("json_path", source.getJsonPath());

        try {
            if (StringUtils.isNotEmpty(source.getJsonString())) {
                JSONObject json = JSON.parseObject(source.getJsonString());
                video.put("likes_count", json.get("likes"));
                video.put("comments_count", json.get("comments"));
                video.put("share_count", json.get("shares"));
                video.put("collect_count", json.get("collects"));
                video.put("author", json.get("author"));
                video.put("rawTime", json.get("rawTime"));
                video.put("descCon", json.get("descCon"));
            }
        } catch (Exception ignored) {
        }

        List<Map<String, Object>> comments = new ArrayList<>();
        try {
            if (StringUtils.isNotEmpty(source.getContent())) {
                Object parsed = JSON.parse(source.getContent());
                if (parsed instanceof List) {
                    for (Object item : (List<?>) parsed) {
                        if (item instanceof Map) {
                            comments.add((Map<String, Object>) item);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        video.put("comments", comments);
        video.put("totalComments", video.get("comments_count") == null ? comments.size() : video.get("comments_count"));
        return video;
    }

    private long parseLong(Object value) {
        if (value == null) return 0L;

        String str = value.toString().trim();
        if (str.isEmpty()) return 0L;

        try {
            // 纯数字
            if (str.matches("^\\d+$")) {
                return Long.parseLong(str);
            }

            // 万
            if (str.contains("万")) {
                str = str.replace("万", "");
                double num = Double.parseDouble(str);
                return (long) (num * 10_000);
            }

            // 亿
            if (str.contains("亿")) {
                str = str.replace("亿", "");
                double num = Double.parseDouble(str);
                return (long) (num * 100_000_000);
            }

            // 兜底
            return (long) Double.parseDouble(str);

        } catch (Exception e) {
            return 0L;
        }
    }


    static String reusableRecording(String saved) {
        if (saved == null || saved.isBlank()) return null;
        try {
            Path root = Paths.get(DouyinPathUtils.root()).toAbsolutePath().normalize();
            Path path = saved.startsWith("/profile/") ? root.resolve(saved.substring(9)) : Paths.get(saved);
            path = path.toAbsolutePath().normalize();
            return path.startsWith(root) && Files.isRegularFile(path) && Files.size(path) > 0 ? path.toString() : null;
        } catch (Exception e) { return null; }
    }

    private String toWebPath(String fullPath) {
        if (fullPath == null) return null;

        return DouyinPathUtils.webPath(fullPath);
    }

    private int parseMaxVideos(String count) {
        if (StringUtils.isEmpty(count)) {
            return 0;
        }

        try {
            int maxVideos = Integer.parseInt(count.trim());
            logger.info("解析到作品数量: " + maxVideos);
            return maxVideos;
        } catch (Exception e) {
            logger.warning("作品数量解析失败: " + count);
            return 0;
        }
    }

    // ---------------------------------------------------------
    // ⭐ 主流程：录制所有视频 + 获取评论 + 截图 + 保存数据
    // ---------------------------------------------------------
    private String collectPublisher(String deviceId, String tags) throws InterruptedException {
        if (!enterAuthorProfile()) throw new IllegalStateException("进入发布者主页失败");
        try {
            if (!waitUntilProfilePage() || !isOnProfilePage()) {
                throw new IllegalStateException("发布者主页未加载完成");
            }
            AccountInfoFetcher fetcher = new AccountInfoFetcher((AndroidDriver<MobileElement>) driver,
                    DouyinPathUtils.root(), accountContentService, addressAccountContentService,
                    accountService, douyinTaskService);
            Map<String, Object> result = fetcher.getAccountBasicInfo("2", "0", deviceId, "");
            String id = (String) result.get("id");
            if (!AccountInfoFetcher.isRealDouyinId(id)) {
                throw new IllegalStateException("未识别到发布者真实抖音号，保留视频等待重试");
            }
            douyinTaskService.storeAccountAsync(deviceId, id, result, tags);
            logger.info("已保存发布者主页信息 douyinId=" + id);
            return id;
        } finally {
            driver.navigate().back();
            Thread.sleep(1000);
        }
    }

    public List<Map<String, Object>> addressVideosAndComments(String count, String deviceId, String address, String tags) {

        logger.info("开始录制所有视频并获取评论");

        List<Map<String, Object>> allVideoData = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        String safeTags = tags == null ? "" : tags;
//        boolean comprehensiveVideo = safeTags.contains("综合视频");

        int index = 0;
        int maxVideos = parseMaxVideos(count);


        if (maxVideos <= 0) {
            logger.warning("无法解析作品数量，使用默认 100");
            maxVideos = 100;
        }

        logger.info("总共需要处理的视频数量: " + maxVideos);


//        点击进入第一条视频

        if (!isOnVideoDetailPage() && !goVideoList()) {
            logger.warning("无法进入作品区");
            throw new IllegalStateException("未能进入预期采集页面，停止本轮等待重试");
        }
        String uidByCopy = null;
        int sameUidCount = 0;

        while (index < maxVideos) {
            try {
                ensureCrawlActive();
                // 确保最新 PageSource
                driver.getPageSource();
                logger.info("处理第 " + (index + 1) + " 个视频");

                MobileElement descEl = null;
                try {

                    descEl = driver.findElement(
                            By.id("com.ss.android.ugc.aweme:id/desc")
                    );
                } catch (Exception ignored) {
                }

                // ---------- desc（主来源 + 兜底） ----------
                String desc = normalize(safeText(descEl));
                if (desc.contains("广告")) {
                    logger.info("取到视频为 广告 ，跳过");
                    index++;
                    if (!swipeToNextVideo()) break;
                    continue;
                }

                Map<String, Object> uidMap = getVideoUniqueId();
                String uid = (String) uidMap.get("uid");
                String author = (String) uidMap.get("author");
                String rawTime = (String) uidMap.get("rawTime");
                String descCon = (String) uidMap.get("desc");


// UID 为空，直接跳过（非常重要）
                if (uid == null || uid.isEmpty()) {
                    logger.info("未获取到视频 UID，跳过");
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }

                // ✅ UI 是否变化（必须无条件执行）
                if (uid.equals(uidByCopy)) {
                    sameUidCount++;
                } else {
                    sameUidCount = 0;
                    uidByCopy = uid;
                }

                if (sameUidCount >= 10) {
                    logger.warning("连续 10 次 UID 未变化，判定到底部或卡死");
                    break;
                }

// 内存去重
                if (processed.contains(uid)) {
                    logger.info("视频已在本轮处理过，跳过");
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }
// 数据库去重
//                boolean existsInDb = comprehensiveVideo
//                        ? videoService.selectVideoByUId(uid) != null
//                        : addressVideoService.selectAddressVideoByUid(uid) != null;
                if (hasRequiredAddressData(addressVideoService.selectAddressVideo(uid, address), safeTags)) {
                    logger.info("视频已存在数据库，跳过");
                    processed.add(uid); // 可选：避免后面再次查询 DB
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }

// Persist successfully before marking a video as processed.

// ↓↓↓ 这里才是真正的“新视频处理逻辑” ↓↓↓    进入人员主页 进行人员信息获取


                String douyinId = "";
                if (safeTags.contains("基本信息")) {
                    douyinId = collectPublisher(deviceId, safeTags);
                }

//                ===========================================

                // ◆ 保存视频信息
                Map<String, Object> video = new LinkedHashMap<>();
                video.put("video_index", index + 1);
                video.put("uid", uid);
                video.put("comments_requested", tags != null && tags.contains("评论"));
                video.put("author", author);
                video.put("rawTime", rawTime);
                video.put("descCon", descCon);
                video.put("timestamp", LocalDateTime.now().toString());
                video.put("description", getVideoDescription());
                video.put("likes_count", getVideoStat("赞"));
                video.put("comments_count", getVideoStat("评论"));
                video.put("share_count", getShareCount());
                video.put("collect_count", getFavoriteCount());

                if (safeTags.contains("视频")) {
                    //                 ◆ 线程版解析视频时长（增加重试，确保获取到）
                    int duration = 0;
                    int maxRetry = 5;
                    for (int attempt = 1; attempt <= maxRetry; attempt++) {
                        duration = extractVideoDuration(deviceId);
                        Thread.sleep(1000);
                        if (duration > 0) break;
                        logger.info("⚠️ 视频时长获取失败，重试 " + attempt + "/" + maxRetry);
                        Thread.sleep(500); // 等待0.5秒再试
                    }
                    video.put("duration_known", duration > 0);
                    if (duration <= 0) duration = 30; // 防止解析失败时，给默认50秒
                    if (duration > 600) duration = 600; // 防止解析失败时，给默认50秒
                    video.put("recording_duration_seconds", duration);
                    logger.info("本次录屏时长: " + duration + " 秒，页面时长已识别=" + video.get("duration_known"));

//                // ◆ 录制视频
                    String videoPath = recordVideoToFile(index, duration, deviceId, douyinId, uid, address);
                    video.put("video_path", videoPath);
                } else {
                    video.put("video_path", "");
                }

                // ◆ 截图
                String screenshotPath = saveScreenshot(index);
                video.put("screenshot_path", screenshotPath);

                if (safeTags.contains("评论")) {
                    String commentsCount = (String) video.get("comments_count");
                    logger.info("评论采集开关已开启，当前视频评论数: " + commentsCount);

                    if (!"0".equals(commentsCount)) {
                        // 指定保存评论的目录
                        String videosDir = DouyinPathUtils.resolve("videosDir");

                        // 初始化抓取器
                        VideoCommentsFetcher fetcher = new VideoCommentsFetcher(driver, douyinTaskService,
                                accountContentService,
                                addressAccountContentService,
                                accountService);
// 抓取当前视频的全部评论（包含 totalComments + comments + json_path）
                        Map<String, Object> commentResult = fetcher.fetchAllComments("2", address, uid, deviceId, index, videosDir, (String) video.get("comments_count"));
                        if (!Boolean.TRUE.equals(commentResult.get("complete")) && !Boolean.TRUE.equals(commentResult.get("deferred"))) {
                            throw new IllegalStateException("评论采集未完成，已保留断点: " + commentResult.get("json_path"));
                        }
// 取出评论数组
                        List<Map<String, Object>> comments = (List<Map<String, Object>>) commentResult.get("comments");
// 写入视频数据
                        video.put("comments", comments);
                        video.put("json_path", commentResult.get("json_path"));       // ✔ 正确：保存路径字符串
                        video.put("comments_complete", Boolean.TRUE.equals(commentResult.get("complete")));
                        video.put("comments_deferred", Boolean.TRUE.equals(commentResult.get("deferred")));
                        video.put("totalComments", commentResult.get("totalComments")); // ✔ 正确：真实总评论数

                        Thread.sleep(1000); // 等待0.5秒确保下一个视频加载完成
                    } else {
                        // 评论数为 0 或页面暂时未读到评论数时，仍补齐字段，
                        // 避免后续保存逻辑误以为评论处理没有执行。
                        video.put("comments", new ArrayList<Map<String, Object>>());
                        video.put("json_path", "");
                        video.put("totalComments", "0");
                        logger.info("当前视频无可抓取评论，跳过评论详情页");
                    }

                } else {
                    List<Map<String, Object>> comments = new ArrayList<>();
                    video.put("comments", comments);
                    video.put("json_path", "");       // ✔ 正确：保存路径字符串
                    video.put("totalComments", ""); // ✔ 正确：真实总评论数

                }
                ensureCrawlActive();
                saveSingleVideoByAddress(video, deviceId, douyinId, address);
                processed.add(uid);
                allVideoData.add(videoSummary(video));


                logger.info("第 " + (index + 1) + " 个视频" + (Boolean.TRUE.equals(video.get("comments_deferred")) ? "已保存部分评论，稍后续采" : "处理完成"));
                if (Boolean.TRUE.equals(video.get("comments_deferred")) || douyinTaskService.hasWaitingTasks(deviceId)) {
                    logger.info("当前视频已落库，释放设备处理排队的地址/发布者任务");
                    return new CrawlBatch(allVideoData);
                }

                index++;

                // ◆ 滑到下一个视频，确保 UI 渲染完成
                if (!swipeToNextVideo()) break;
                Thread.sleep(500); // 等待0.5秒确保下一个视频加载完成

            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                logger.log(java.util.logging.Level.SEVERE, "处理视频失败 index=" + index + " device=" + deviceId, e);
                throw new IllegalStateException("视频未保存完成，停止本轮等待重试", e);
            }
        }

        // Driver lifecycle is owned by DouyinVideoService; do not restart shared Appium.

        logger.info("总共处理视频数量: " + allVideoData.size());
        return allVideoData;
    }


    // ---------------------------------------------------------
    // ⭐ 主流程：录制所有视频 + 获取评论 + 截图 + 保存数据
    // ---------------------------------------------------------
    public List<Map<String, Object>> addressZHongHeComments(String count, String deviceId, String address, String tags) {

        logger.info("开始录制所有视频并获取评论");

        List<Map<String, Object>> allVideoData = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        String safeTags = tags == null ? "" : tags;
        boolean comprehensiveVideo = safeTags.contains("综合视频");

        int index = 0;
        int maxVideos = parseMaxVideos(count);


        if (maxVideos <= 0) {
            logger.warning("无法解析作品数量，使用默认 100");
            maxVideos = 100;
        }

        logger.info("总共需要处理的视频数量: " + maxVideos);


//        点击进入第一条视频

        if (!isOnVideoDetailPage() && !goVideoList()) {
            logger.warning("无法进入作品区");
            throw new IllegalStateException("未能进入预期采集页面，停止本轮等待重试");
        }
        String uidByCopy = null;
        int sameUidCount = 0;

        while (index < maxVideos) {
            try {
                ensureCrawlActive();
                // 确保最新 PageSource
                driver.getPageSource();
                logger.info("处理第 " + (index + 1) + " 个视频");

                MobileElement descEl = null;
                try {

                    descEl = driver.findElement(
                            By.id("com.ss.android.ugc.aweme:id/desc")
                    );
                } catch (Exception ignored) {
                }

                // ---------- desc（主来源 + 兜底） ----------
                String desc = normalize(safeText(descEl));
                if (desc.contains("广告")) {
                    logger.info("取到视频为 广告 ，跳过");
                    index++;
                    if (!swipeToNextVideo()) break;
                    continue;
                }

                Map<String, Object> uidMap = getVideoUniqueId();
                String uid = (String) uidMap.get("uid");
                String author = (String) uidMap.get("author");
                String rawTime = (String) uidMap.get("rawTime");
                String descCon = (String) uidMap.get("desc");


// UID 为空，直接跳过（非常重要）
                if (uid == null || uid.isEmpty()) {
                    logger.info("未获取到视频 UID，跳过");
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }

                // ✅ UI 是否变化（必须无条件执行）
                if (uid.equals(uidByCopy)) {
                    sameUidCount++;
                } else {
                    sameUidCount = 0;
                    uidByCopy = uid;
                }

                if (sameUidCount >= 10) {
                    logger.warning("连续 10 次 UID 未变化，判定到底部或卡死");
                    break;
                }

// 内存去重
                if (processed.contains(uid)) {
                    logger.info("视频已在本轮处理过，跳过");
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }
// 数据库去重
                if (hasRequiredAddressData(addressVideoService.selectAddressVideo(uid, address), safeTags)) {
                    logger.info("视频已存在数据库，跳过");
                    processed.add(uid); // 可选：避免后面再次查询 DB
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }

                AddressVideo existingAddressVideo = addressVideoService.selectAddressVideoByUid(uid);
                if (hasRequiredAddressData(existingAddressVideo, safeTags)) {
                    Map<String, Object> video = buildVideoMapFromAddressVideo(existingAddressVideo, index);
                    allVideoData.add(videoSummary(video));
                    saveAddressVideoCopy(existingAddressVideo, index + 1L, deviceId, address);
                    logger.info("视频已存在其他地址，复用已有数据保存到当前地址");
                    processed.add(uid);
                    if (!swipeToNextVideo()) break;
                    index++;
                    continue;
                }

// Persist successfully before marking a video as processed.

// ↓↓↓ 这里才是真正的“新视频处理逻辑” ↓↓↓    进入人员主页 进行人员信息获取


                String douyinId = "";
                if (safeTags.contains("基本信息")) {
                    douyinId = collectPublisher(deviceId, safeTags);
                }

//                ===========================================

                // ◆ 保存视频信息
                Map<String, Object> video = new LinkedHashMap<>();
                video.put("video_index", index + 1);
                video.put("uid", uid);
                video.put("comments_requested", tags != null && tags.contains("评论"));
                video.put("author", author);
                video.put("rawTime", rawTime);
                video.put("descCon", descCon);
                video.put("timestamp", LocalDateTime.now().toString());
                video.put("description", getVideoDescription());
                video.put("likes_count", getVideoStat("赞"));
                video.put("comments_count", getVideoStat("评论"));
                video.put("share_count", getShareCount());
                video.put("collect_count", getFavoriteCount());

                if (safeTags.contains("视频")) {
                    //                 ◆ 线程版解析视频时长（增加重试，确保获取到）
                    int duration = 0;
                    int maxRetry = 5;
                    for (int attempt = 1; attempt <= maxRetry; attempt++) {
                        duration = extractVideoDuration(deviceId);
                        Thread.sleep(1000);
                        if (duration > 0) break;
                        logger.info("⚠️ 视频时长获取失败，重试 " + attempt + "/" + maxRetry);
                        Thread.sleep(500); // 等待0.5秒再试
                    }
                    video.put("duration_known", duration > 0);
                    if (duration <= 0) duration = 30; // 防止解析失败时，给默认50秒
                    if (duration > 600) duration = 600; // 防止解析失败时，给默认50秒
                    video.put("recording_duration_seconds", duration);
                    logger.info("本次录屏时长: " + duration + " 秒，页面时长已识别=" + video.get("duration_known"));

//                // ◆ 录制视频
                    String videoPath = recordVideoToFile(index, duration, deviceId,
                            comprehensiveVideo ? address : douyinId, uid, address);
                    video.put("video_path", videoPath);
                } else {
                    video.put("video_path", "");
                }

                // ◆ 截图
                String screenshotPath = saveScreenshot(index);
                video.put("screenshot_path", screenshotPath);

                if (safeTags.contains("评论")) {

                    if (!"0".equals(video.get("comments_count"))) {
                        // 指定保存评论的目录
                        String videosDir = DouyinPathUtils.resolve("videosDir");

                        // 初始化抓取器
                        VideoCommentsFetcher fetcher = new VideoCommentsFetcher(driver, douyinTaskService,
                                accountContentService,
                                addressAccountContentService,
                                accountService);
// 抓取当前视频的全部评论（包含 totalComments + comments + json_path）
                        Map<String, Object> commentResult = fetcher.fetchAllComments(comprehensiveVideo ? "1" : "2", address, uid, deviceId, index, videosDir, (String) video.get("comments_count"));
                        if (!Boolean.TRUE.equals(commentResult.get("complete")) && !Boolean.TRUE.equals(commentResult.get("deferred"))) {
                            throw new IllegalStateException("评论采集未完成，已保留断点: " + commentResult.get("json_path"));
                        }
// 取出评论数组
                        List<Map<String, Object>> comments = (List<Map<String, Object>>) commentResult.get("comments");
// 写入视频数据
                        video.put("comments", comments);
                        video.put("json_path", commentResult.get("json_path"));       // ✔ 正确：保存路径字符串
                        video.put("comments_complete", Boolean.TRUE.equals(commentResult.get("complete")));
                        video.put("comments_deferred", Boolean.TRUE.equals(commentResult.get("deferred")));
                        video.put("totalComments", commentResult.get("totalComments")); // ✔ 正确：真实总评论数

                        Thread.sleep(1000); // 等待0.5秒确保下一个视频加载完成
                    }

                } else {
                    List<Map<String, Object>> comments = new ArrayList<>();
                    video.put("comments", comments);
                    video.put("json_path", "");       // ✔ 正确：保存路径字符串
                    video.put("totalComments", ""); // ✔ 正确：真实总评论数

                }
                ensureCrawlActive();
                saveSingleVideoByAddress(video, deviceId, douyinId, address);
                processed.add(uid);
                allVideoData.add(videoSummary(video));


                logger.info("第 " + (index + 1) + " 个视频" + (Boolean.TRUE.equals(video.get("comments_deferred")) ? "已保存部分评论，稍后续采" : "处理完成"));
                if (Boolean.TRUE.equals(video.get("comments_deferred")) || douyinTaskService.hasWaitingTasks(deviceId)) {
                    logger.info("当前视频已落库，释放设备处理排队的地址/发布者任务");
                    return new CrawlBatch(allVideoData);
                }

                index++;

                // ◆ 滑到下一个视频，确保 UI 渲染完成
                if (!swipeToNextVideo()) break;
                Thread.sleep(500); // 等待0.5秒确保下一个视频加载完成

            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                logger.log(java.util.logging.Level.SEVERE, "处理视频失败 index=" + index + " device=" + deviceId, e);
                throw new IllegalStateException("视频未保存完成，停止本轮等待重试", e);
            }
        }

        // Driver lifecycle is owned by DouyinVideoService; do not restart shared Appium.

        logger.info("总共处理视频数量: " + allVideoData.size());
        return allVideoData;
    }


}

package com.phone.adb;

import io.appium.java_client.MobileElement;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Point;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.support.ui.WebDriverWait;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

public class DouyinAddressCrawler {
    // 新版抖音搜索入口 content-desc 带后缀（如 "搜索，[unsupported type]"），desc 必须模糊匹配；
    // text 属性则用精确匹配，避免误点"搜索推荐"/"搜索历史"等文本；
    // 且必须限定在抖音包内，否则桌面的 Google 搜索栏（content-desc="搜索"）会被误点。
    static final String SEARCH_CONTROL = "//*[@clickable='true' and not(self::android.widget.EditText)"
            + " and @package='com.ss.android.ugc.aweme'"
            + " and (@text = '搜索' or contains(@content-desc, '搜索'))]";

    private static final Logger logger = Logger.getLogger(DouyinAddressCrawler.class.getName());
    private AndroidDriver<MobileElement> driver;

    private final int screenWidth;
    private final int screenHeight;

    public DouyinAddressCrawler(AndroidDriver<MobileElement> driver) {
        this.driver = driver;
        this.screenWidth = driver.manage().window().getSize().width;
        this.screenHeight = driver.manage().window().getSize().height;
    }

    // ===========================
    // 启动抖音
    // ===========================
    public boolean startDouyin() {
        try {
            logger.info("启动抖音应用...");
            // activateApp 只把进程切到前台，App 若在后台被回收后会先落到桌面（Launcher），
            // 此时 getCurrentPackage() 可能是桌面。必须确认真正回到抖音主页再继续。
            if (!isDouyinHomeVisible()) {
                driver.activateApp("com.ss.android.ugc.aweme");
                Thread.sleep(6000);
            }
            if (!isDouyinHomeVisible()) {
                // 主页仍未出现（可能停在 Splash/详情页），强制回到主 Activity。
                driver.startActivity(new io.appium.java_client.android.Activity(
                        "com.ss.android.ugc.aweme",
                        "com.ss.android.ugc.aweme.splash.SplashActivity"));
                Thread.sleep(8000);
            }

            boolean success = isDouyinHomeVisible();
            logger.info(success ? "抖音启动成功" : "抖音启动失败：未回到主页");
            return success;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.severe("启动抖音失败: " + e.getMessage());
            return false;
        }
    }

    /** 团购/商品列表页标志：出现"去抢购"按钮或团购搜索框，说明点错了地点词条。 */
    private boolean isOnGroupBuyPage() {
        try {
            return !driver.findElementsByXPath(
                    "//*[@text='去抢购' or @content-desc='去抢购']").isEmpty()
                    || !driver.findElementsByXPath(
                    "//*[contains(@text,'团购') and @resource-id='com.ss.android.ugc.aweme:id/et_search_kw']").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 抖音主页标志：底部"首页"tab 或顶部搜索按钮，且当前包就是抖音。 */
    private boolean isDouyinHomeVisible() {
        try {
            if (!"com.ss.android.ugc.aweme".equals(driver.getCurrentPackage())) return false;
            // 主页标志：搜索按钮（新版 desc 带后缀，模糊匹配）或底部"首页"tab；
            // 同时必须无评论区残留（"评论 N"面板标题 / "最新/最热"排序弹窗 / "没有更多了"），
            // 否则上一任务的评论弹层会污染本轮搜索。
            boolean hasOverlay = !driver.findElementsByXPath(
                    "//*[contains(@text,'没有更多了')]").isEmpty()
                    || !driver.findElementsByXPath(
                    "//*[@text='最新' and @text!='']/../..//*[@text='最热']").isEmpty()
                    || !driver.findElementsByXPath(
                    "//*[starts-with(@text,'评论 ') or starts-with(@content-desc,'评论 ') and not(contains(@content-desc,'按钮'))]").isEmpty();
            if (hasOverlay) return false;
            return !driver.findElementsByXPath(
                    "//*[@package='com.ss.android.ugc.aweme' and contains(@content-desc,'搜索') and @clickable='true' and not(self::android.widget.EditText)]").isEmpty()
                    || !driver.findElementsByXPath(
                    "//*[@package='com.ss.android.ugc.aweme' and contains(@text,'首页')]").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ===========================
    // 搜索账号并进入主页
    // ===========================
    public boolean searchAndEnterAccount(String accountName) {
        logger.info("开始搜索账号: " + accountName);

        if (!isDeviceConnected()) return false;
        if (!clickSearchButton()) return false;
        if (!inputSearchText(accountName)) return false;
        if (!clickSearchSubmit()) return false;
        if (!selectUserTab()) return false;
        if (!clickFirstUser()) return false;

//        return verifyProfilePage();
        return true;
    }

    // ===========================
    // 搜索地址并进入视频
    // ===========================
    public boolean searchAddressVoid(String accountName) {
        return searchAndEnterVideoTab(accountName);
    }

    public boolean searchAndEnterVideoTab(String keyword) {
        logger.info("开始搜索综合视频: " + keyword);

        if (!isDeviceConnected()) return false;
        if (!clickSearchButton()) return false;
        if (!inputSearchText(keyword)) return false;
        if (!clickSearchSubmit()) return false;
        if (!selectVideoTab()) return false;
        return true;
    }

    // ===========================
    // 搜索账号并进入地址视频分类
    // ===========================
    public boolean searchAndEnterAddress(String accountName) {
        logger.info("开始搜索地址: " + accountName);

        if (!isDeviceConnected()) return false;
        if (!clickSearchButton()) return false;
        if (!inputSearchText(accountName)) return false;
        if (!clickSearchSubmit()) return false;
        sleep(2000);
        // 综合结果会混入图文，地点任务从视频分类进入带地点标签的视频。
        if (!selectVideoTab()) return false;
        if (!clickFirstAddress()) return false;


        int maxSwipe = 40;          // ✅ 最大滑动次数（你可调）
        int swipeCount = 0;

        while (swipeCount <= maxSwipe) {

            logger.info("第 " + (swipeCount + 1) + " 次尝试匹配地址");
            // 抖音搜索结果页在模拟器上偶发 ANR 冻结 UI，检测到弹窗先点"等待"恢复
            AnrGuard.recover(driver);

            if (findAndClickAddress(accountName)) {
                // 校验落地页：新版搜索结果里"XX团购"词条也含地点名，点进去是团购列表，
                // 不是地点视频页。若误入团购页则回退，继续寻找真正的地点标签。
                if (isOnGroupBuyPage()) {
                    logger.warning("误入团购页，返回继续寻找地点视频标签");
                    backToSearchResults();
                    swipeUp();
                    sleep(800);
                    swipeCount++;
                    continue;
                }
                logger.info("成功进入地址视频页: " + accountName);
                swipeUp();
                sleep(800);
                return true;
            }

            // ===== 未匹配，向下滑动加载更多 =====
            logger.info("未匹配到地址，向下滑动加载更多");
            swipeUp();
            sleep(800);

            swipeCount++;
        }

        logger.warning("滑动达到上限，仍未找到地址: " + accountName);
        return false;
    }

    public boolean swipeUp() {
        try {
            int w = driver.manage().window().getSize().width;
            int h = driver.manage().window().getSize().height;
            swipe(w / 2, (int) (h * 0.75), w / 2, (int) (h * 0.25), 500);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.fine("swipeUp error: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------
    // 滑动逻辑 (Appium 滑动 + 统一坐标)
    // ---------------------------------------------------------
    private void swipe(int startX, int startY, int endX, int endY, int durationMs) throws InterruptedException {
        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger1");
        Sequence swipe = new Sequence(finger, 1);

        swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
        swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        swipe.addAction(finger.createPointerMove(Duration.ofMillis(durationMs), PointerInput.Origin.viewport(), endX, endY));
        swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));

        driver.perform(Collections.singletonList(swipe));
        Thread.sleep(200);
    }

    /**
     * 根据 "台湾省|台北101大楼" 匹配地址并点击【点击字段本身】
     */
    public boolean findAndClickAddress(String accountName) {

        if (accountName == null || accountName.trim().isEmpty()) {
            logger.info("地址为空");
            return false;
        }

        String region = null;
        String place;

        // ⭐ 情况1：包含 |
        if (accountName.contains("|")) {
            String[] parts = accountName.split("\\|");

            if (parts.length < 2) {
                logger.info("地址格式异常: " + accountName);
                return false;
            }

            region = parts[0].trim();
            place  = parts[1].trim();

            logger.info("匹配地址(区域+地点) -> 区域: " + region + ", 地点: " + place);

        }
        // ⭐ 情况2：只有地点
        else {
            place = accountName.trim();
            logger.info("匹配地址(仅地点) -> " + place);
        }
        // 抖音视频里的地点标签常用简称（如"#圆山大饭店"、"圆山"），
        // 不含城市前缀。构造从完整到宽松的候选词，提高命中率。
        List<String> placeTokens = new ArrayList<>();
        placeTokens.add(place);
        String core = place;
        // 去掉常见城市/地区前缀：台北、北京市、上海 等
        String stripped = place.replaceAll("(?i)^(台北市?|北京市?|上海市?|广州市?|深圳市?|高雄市?|香港|澳门)", "");
        if (!stripped.isEmpty() && !stripped.equals(place)) {
            core = stripped;
            placeTokens.add(core);
        }
        // 再取核心词前4字（如"圆山大饭店"→"圆山大饭"）作为兜底
        if (core.length() > 4) {
            placeTokens.add(core.substring(0, 4));
        }

        MobileElement placeTv = null;
        for (String token : placeTokens) {
            try {
                // 直接找含地点词的 TextView（不限父节点，视频标签可能是 #话题 或 POI 文字）
                List<MobileElement> hits = driver.findElements(By.xpath(
                        "//android.widget.TextView[contains(@text,'" + token + "')"
                                + " or contains(@content-desc,'" + token + "')]"));
                if (!hits.isEmpty()) {
                    placeTv = hits.get(0);
                    logger.info("按地点词命中: " + token);
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        if (placeTv == null) {
            logger.info("页面中未找到匹配地址");
            return false;
        }

        try {
            MobileElement clickTarget = placeTv;
            // 若命中的是 #话题 文字，点击其可点击父节点更可靠
            try {
                List<MobileElement> clickableParents = clickTarget.findElements(
                        By.xpath("./ancestor::*[@clickable='true'][1]"));
                if (!clickableParents.isEmpty()) {
                    clickTarget = clickableParents.get(0);
                }
            } catch (Exception ignored) {
            }
            clickTarget.click();
            logger.info("已点击地址字段: " + place);
            sleep(800);
            return true;

        } catch (Exception e) {
            logger.warning("点击地址字段失败: " + e.getMessage());
            return false;
        }
    }


    // ===========================
    // 核心 UI 操作封装
    // ===========================
    private boolean isDeviceConnected() {
        try {
            driver.getPageSource();
            return true;
        } catch (Exception e) {
            logger.severe("设备未连接: " + e.getMessage());
            return false;
        }
    }

    private void clickByCoordinates(int x, int y) {
        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence tap = new Sequence(finger, 1);
        tap.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, y));
        tap.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        tap.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        driver.perform(Arrays.asList(tap));
    }

    private boolean clickSearchButton() {
        String[] searchButtonXpaths = {
                SEARCH_CONTROL,
                "//android.widget.Button[@content-desc='搜索' and @clickable='true']",
                "//android.widget.ImageView[contains(@resource-id, 'search')]"
        };
        for (String xp : searchButtonXpaths) {
            if (clickIfExists(xp)) return true;
        }
        logger.warning("未识别到搜索入口，保留当前页面");
        return false;
    }

    private boolean inputSearchText(String accountName) {
        String[] inputXpaths = {
                "//*[contains(@resource-id, 'search_edit_text')]",
                "//android.widget.EditText"
        };
        for (String xp : inputXpaths) {
            try {
                List<MobileElement> elements = driver.findElementsByXPath(xp);
                if (!elements.isEmpty()) {
                    elements.get(0).clear();
                    elements.get(0).sendKeys(accountName);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        logger.severe("搜索输入框未找到");
        return false;
    }

    private boolean clickSearchSubmit() {
        if (clickIfExists(SEARCH_CONTROL)) return true;
        try {
            driver.findElement(By.id("com.ss.android.ugc.aweme:id/3i9")).click();
            return true;
        } catch (Exception e) {
            try {
                driver.findElement(By.xpath("//android.widget.TextView[@content-desc='搜索']")).click();
                return true;
            } catch (Exception ex) {
                logger.warning("搜索提交失败: " + ex.getMessage());
                return false;
            }
        }
    }

    private boolean selectUserTab() {
        String[] tabXpaths = {
                "//*[contains(@resource-id, 'tab_user')]",
                "//*[contains(@text,'用户')]",
                "//android.widget.TextView[@clickable='true' and contains(@text,'用户')]"
        };
        if (waitAndClickTab(tabXpaths)) {
            sleep(2000);
            return true;
        }
        logger.warning("用户分类Tab未找到");
        return false;
    }
    private boolean selectVideoTab() {
        String[] tabXpaths = {
                "//*[contains(@resource-id, 'tab_video')]",
                "//*[@text='视频']",
                "//*[contains(@content-desc,'视频')]",
                "//android.widget.TextView[@clickable='true' and contains(@text,'视频')]"
        };
        if (waitAndClickTab(tabXpaths)) {
            sleep(2000);
            return true;
        }
        logger.warning("视频分类Tab未找到");
        return false;
    }

    private boolean selectZongHeTab() {
        String[] tabXpaths = {

                "//*[@text='综合' or @content-desc='综合' or starts-with(@content-desc,'综合，')]",
                "//android.widget.TextView[@clickable='true' and contains(@text,'综合')]"
        };
        if (waitAndClickTab(tabXpaths)) {
            sleep(2000);
            return true;
        }
        logger.warning("综合分类Tab未找到");
        return false;
    }

    private boolean waitAndClickTab(String[] xpaths) {
        try {
            return new WebDriverWait(driver, 20, 250).until(ignored -> {
                for (String xpath : xpaths) {
                    if (clickIfExists(xpath)) return true;
                }
                return false;
            });
        } catch (org.openqa.selenium.TimeoutException e) {
            return false;
        }
    }

    private boolean clickFirstUser() {
        clickByCoordinates(250, 520);
        sleep(3000);
        return true;
    }

    /**
     * 点击搜索结果里的第一个【视频】
     * 自动跳过小程序 / 卡片
     */
//    private boolean clickFirstAddress() {
//        try {
//
//            System.out.println(driver.getPageSource());
//            // 查找当前页面所有“可播放的视频层”
//            List<MobileElement> videoPlays = driver.findElements(
//                    By.xpath("//android.view.View[@resource-id='com.ss.android.ugc.aweme:id/p=1']")
//            );
//
//            if (videoPlays == null || videoPlays.isEmpty()) {
//                logger.warning("当前页面未找到可播放视频（p=1）");
//                return false;
//            }
//
//            // 点击第一个视频
//            MobileElement firstVideo = videoPlays.get(0);
//            firstVideo.click();
//
//            logger.info("已点击第一个视频");
//            sleep(3000);
//
//            return true;
//
//        } catch (Exception e) {
//            logger.severe("点击第一个视频失败: " + e.getMessage());
//            return false;
//        }
//    }

    /**
     * 点击搜索结果里的第一个【视频】
     * 自动跳过小程序 / 卡片
     */
    private boolean clickFirstAddress() {
        try {
            // 旧版搜索结果使用 p=1，新版抖音改成了 dp7/rej/cover。
            // 按“可点击的视频容器”优先，避免把 Tab、文本或小程序卡片当成视频。
            String[] videoXpaths = {
                    "//android.view.View[@resource-id='com.ss.android.ugc.aweme:id/p=1']",
                    "//android.view.View[@resource-id='com.ss.android.ugc.aweme:id/rej' and @clickable='true']",
                    "//android.widget.LinearLayout[@resource-id='com.ss.android.ugc.aweme:id/dp7' and @clickable='true']",
                    "//*[@resource-id='com.ss.android.ugc.aweme:id/rej' and @clickable='true']",
                    "//*[@resource-id='com.ss.android.ugc.aweme:id/dp7' and @clickable='true']",
                    "//android.widget.ImageView[@resource-id='com.ss.android.ugc.aweme:id/cover']"
            };

            List<MobileElement> candidates = new WebDriverWait(driver, 20, 500).until(ignored -> {
                List<MobileElement> visible = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (String xpath : videoXpaths) {
                    try {
                        for (MobileElement element : driver.findElements(By.xpath(xpath))) {
                            if (element == null || !isVisibleVideoCandidate(element)) {
                                continue;
                            }
                            String key = elementKey(element);
                            if (seen.add(key)) {
                                visible.add(element);
                            }
                        }
                    } catch (Exception e) {
                        logger.fine("查找视频候选失败: " + xpath + ", " + e.getMessage());
                    }
                }
                return visible.isEmpty() ? null : visible;
            });

            if (candidates.isEmpty()) {
                logger.warning("当前页面未找到可点击的视频卡片（已兼容 p=1、rej、dp7、cover）");
                return false;
            }

            logger.info("找到 " + candidates.size() + " 个视频候选卡片");
            for (int i = 0; i < candidates.size(); i++) {
                MobileElement candidate = candidates.get(i);
                try {
                    logger.info("尝试点击第 " + (i + 1) + " 个视频卡片: " + elementKey(candidate));
                    candidate.click();

                    // 新版详情页进入速度不稳定，轮询而不是固定等待一次。
                    if (waitForVideoPage(5000)) {
                        logger.info("确认已进入视频详情页");
                        return true;
                    }

                    logger.info("当前卡片未进入视频详情页，返回搜索结果继续尝试");
                    backToSearchResults();
                } catch (Exception inner) {
                    logger.warning("当前视频卡片点击异常，跳过: " + inner.getMessage());
                    backToSearchResults();
                }
            }

            logger.warning("未找到可进入详情页的视频卡片");
            return false;

        } catch (Exception e) {
            logger.severe("点击视频过程失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 判断当前页面是否为【真正的视频页】
     */
    private boolean isVideoPage() {
        try {
            // 这是新版详情页最稳定的标识。搜索页也可能存在“喜欢”文本，
            // 所以不能再只凭点赞按钮位置判定。
            String activity = driver.currentActivity();
            if (activity != null && activity.contains("DetailActivity")) {
                return true;
            }

            if (!driver.findElements(By.xpath(
                    "//*[@resource-id='com.ss.android.ugc.aweme:id/root_layout']"
            )).isEmpty()) {
                return true;
            }

            if (!driver.findElements(By.xpath(
                    "//*[@resource-id='com.ss.android.ugc.aweme:id/guy']"
            )).isEmpty()
                    && driver.findElements(By.xpath(
                    "//*[@resource-id='com.ss.android.ugc.aweme:id/et_search_kw']"
            )).isEmpty()) {
                return true;
            }

            Dimension size = driver.manage().window().getSize();
            int currentScreenHeight = size.getHeight();
            int currentScreenWidth = size.getWidth();

            // 1️⃣ 查找所有“点赞按钮”（不限定 id）
            List<MobileElement> likeButtons = driver.findElements(
                    By.xpath("//*[contains(@content-desc,'喜欢')]")
            );

            if (likeButtons == null || likeButtons.isEmpty()) {
                return false;
            }

            for (MobileElement btn : likeButtons) {

                if (!btn.isDisplayed()) continue;

                Point p = btn.getLocation();

                boolean isRightSide = p.getX() > currentScreenWidth * 0.6;
                boolean notBottomBar = p.getY() < currentScreenHeight * 0.75;

                // ✅ 视频页点赞按钮特征
                if (isRightSide && notBottomBar) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 过滤掉不可见、越界以及顶部 Tab 区域的元素。
     * dp7/rej 在不同版本中可能是整个卡片，也可能是卡片内部的点击区域。
     */
    private boolean isVisibleVideoCandidate(MobileElement element) {
        try {
            if (!element.isDisplayed()) {
                return false;
            }

            Point location = element.getLocation();
            Dimension size = element.getSize();
            int x = location.getX();
            int y = location.getY();
            int width = size.getWidth();
            int height = size.getHeight();

            if (width <= 0 || height <= 0) {
                return false;
            }

            Dimension window = driver.manage().window().getSize();
            boolean insideScreen = x < window.getWidth()
                    && y < window.getHeight()
                    && x + width > 0
                    && y + height > 0;
            if (!insideScreen) {
                return false;
            }

            // 搜索结果顶部通常是“综合/用户/视频”等 Tab，避免误点。
            return y > window.getHeight() * 0.16;
        } catch (Exception e) {
            return false;
        }
    }

    private String elementKey(MobileElement element) {
        try {
            Point p = element.getLocation();
            Dimension s = element.getSize();
            return p.getX() + ":" + p.getY() + ":" + s.getWidth() + ":" + s.getHeight();
        } catch (Exception e) {
            return String.valueOf(System.identityHashCode(element));
        }
    }

    private boolean waitForVideoPage(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isVideoPage()) {
                return true;
            }
            sleep(300);
        }
        return isVideoPage();
    }

    private void backToSearchResults() {
        try {
            if (isVideoPage()) {
                driver.navigate().back();
                sleep(1200);
            }
        } catch (Exception e) {
            logger.fine("返回搜索结果失败: " + e.getMessage());
        }
    }

//
//    private boolean clickFirstAddress() {
//        clickByCoordinates(250, 520);
//        sleep(3000);
//        return true;
//    }

    private boolean verifyProfilePage() {
        String[] profileXpaths = new String[]{
                "//*[contains(@resource-id, 'nickname')]",
                "//android.widget.TextView[contains(@resource-id, 'nickname')]",
                "//*[contains(@resource-id, 'follow')]",
                "//*[contains(@resource-id, 'fans')]",
                "//*[contains(@resource-id, 'like')]",
                "//android.view.ViewGroup[contains(@resource-id, 'user_profile')]",
                "//*[contains(@resource-id, 'title')]",
                "//android.widget.ImageView[contains(@resource-id, 'avatar')]",
                "//android.view.ViewGroup[contains(@resource-id, 'header')]"
        };
        for (String xp : profileXpaths) {
            try {
                if (!driver.findElements(By.xpath(xp)).isEmpty()) {
                    logger.info("成功进入主页, XPath: " + xp);
                    Map<String, Object> info = fetchAccountInfo();
                    info.forEach((k, v) -> logger.info(k + " : " + v));
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        logger.warning("未能验证进入主页");
        return false;
    }

    private boolean clickIfExists(String xpath) {
        try {
            List<MobileElement> elements = driver.findElementsByXPath(xpath);
            if (!elements.isEmpty()) {
                elements.get(0).click();
                sleep(1500);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    // ===========================
    // 获取地址信息
    // ===========================
    public Map<String, Object> fetchAccountInfo() {
        AddressInfoFetcher fetcher = new AddressInfoFetcher(driver, DouyinPathUtils.root());
        Map<String, Object> info = fetcher.getAccountBasicInfo();

        System.out.println("========== 抖音地址解析结果 ==========");
        info.forEach((k, v) -> System.out.println(k + " : " + v));
        System.out.println("===================================");

        return info;
    }

    public void quit() {
        if (driver != null) driver.quit();
    }

    // ===========================
    // 静态入口：爬取账号信息
    // ===========================
    public static Map<String, Object> crawlAccount(String devId, String accountName) {
        Map<String, Object> info = new HashMap<>();
        AndroidDriver<MobileElement> driver = null;

        try {
            driver = AndroidCollector.current().connect(devId);

            DouyinAddressCrawler crawler = new DouyinAddressCrawler(driver);

            if (!crawler.startDouyin()) return info;
            if (!crawler.searchAndEnterAccount(accountName)) return info;

            info = crawler.fetchAccountInfo();
            return info;

        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.log(java.util.logging.Level.WARNING, "无法连接采集设备 " + devId, e);
            return info;
        } finally {
            if (driver != null) driver.quit();
        }
    }

//    public static void main(String[] args) {
//        String devId = "emulator-5668";
//        String accountName = "Becarefulleea";
//
//        Map<String,Object> info = crawlAccount(devId, accountName);
//        System.out.println(info);
//    }
//
//    public static void main(String[] args) {
//        String devId = "emulator-5668";
//
//        // 想抓多个账号，填在这里
//        String[] accountNames = new String[]{
//                "40691348894",
//                "EQLQ",
//                "37912855169",
//                "chunbuwanyes"
//        };
//
//        AndroidDriver<MobileElement> driver = null;
//
//        try {
//            DesiredCapabilities caps = new DesiredCapabilities();
//            caps.setCapability("platformName", "Android");
//            caps.setCapability("deviceName", "Android Device");
//            caps.setCapability("udid", devId);
//            caps.setCapability("appPackage", "com.ss.android.ugc.aweme");
//            caps.setCapability("appActivity", ".main.MainActivity");
//            caps.setCapability("noReset", true);
//            caps.setCapability("automationName", "UiAutomator2"); // 必填
//
//            driver = new AndroidDriver<>(new URL("http://127.0.0.1:4723/wd/hub"), caps);
//
//            DouyinCrawler crawler = new DouyinCrawler(driver);
//
//            if (!crawler.startDouyin()) return;
//
//            for (String accountName : accountNames) {
//
//                System.out.println("\n==============================");
//                System.out.println("开始处理账号: " + accountName);
//                System.out.println("==============================");
//
//                if (!crawler.searchAndEnterAccount(accountName)) {
//                    System.out.println("❌ 无法进入账号: " + accountName + "，跳过");
//                    continue;
//                }
//
//                AccountInfoFetcherVoid fetcher = new AccountInfoFetcherVoid(driver);
//
//                // 开始录屏
//                System.out.println("开始录屏...");
//                driver.startRecordingScreen();
//
//                List<Map<String, Object>> allVideos = fetcher.recordAllVideosAndComments(devId, accountName);
//
//                // 你需要的话也可以打印
//                // allVideos.forEach(System.out::println);
//
//                System.out.println("⭐ 账号完成: " + accountName);
//            }
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        } finally {
//            if (driver != null) driver.quit();
//        }
//    }


}

package com.phone.adb;

import com.phone.module.service.DouyinTaskService;
import com.phone.module.service.IAccountContentService;
import com.phone.module.service.IAccountService;
import com.phone.module.service.IAddressAccountContentService;
import io.appium.java_client.MobileElement;
import io.appium.java_client.android.Activity;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.io.File;
import java.io.FileOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Logger;

public class DouyinCrawler {

    private static final Logger logger = Logger.getLogger(DouyinCrawler.class.getName());
    private AndroidDriver<MobileElement> driver;

    private final int screenWidth;
    private final int screenHeight;

    public final IAccountContentService accountContentService;
    public final IAddressAccountContentService addressAccountContentService;
    public final IAccountService accountService;

    private final DouyinTaskService douyinTaskService;
    public DouyinCrawler(AndroidDriver<MobileElement> driver,
                         IAccountContentService accountContentService,
                         IAddressAccountContentService addressAccountContentService,
                         IAccountService accountService,DouyinTaskService douyinTaskService) {
        this.driver = driver;
        this.screenWidth = driver.manage().window().getSize().width;
        this.screenHeight = driver.manage().window().getSize().height;
        this.accountContentService = accountContentService;
        this.addressAccountContentService = addressAccountContentService;
        this.accountService = accountService;
        this.douyinTaskService = douyinTaskService;
    }

    // ===========================
    // 启动抖音
    // ===========================
    public boolean startDouyin() {
        try {
            logger.info("启动抖音应用...");
            // 任务间页面状态可能残留（评论区/排序弹窗叠加层），先检测是否在干净的主页；
            // 不干净则 force-stop 冷启动，避免上一任务残留页面污染搜索流程
            if (isDouyinHomeVisible()) {
                logger.info("抖音已在干净主页，直接使用");
                return true;
            }
            Activity activity = new Activity("com.ss.android.ugc.aweme",
                    "com.ss.android.ugc.aweme.main.MainActivity");
            driver.startActivity(activity);
            Thread.sleep(5000);
            if (!isDouyinHomeVisible()) {
                logger.warning("页面仍有残留，执行冷启动复位");
                driver.terminateApp("com.ss.android.ugc.aweme");
                Thread.sleep(2000);
                driver.startActivity(activity);
                Thread.sleep(8000);
            }

            boolean success = "com.ss.android.ugc.aweme".equals(driver.getCurrentPackage());
            logger.info(success ? "抖音启动成功" : "抖音启动失败");
            return success;
        } catch (Exception e) {
            logger.severe("启动抖音失败: " + e.getMessage());
            return false;
        }
    }

    /** 抖音主页且无评论区残留：底部"首页"tab 可见，且不存在"评论 N"面板标题或"最新/最热"排序弹窗。 */
    private boolean isDouyinHomeVisible() {
        try {
            if (!"com.ss.android.ugc.aweme".equals(driver.getCurrentPackage())) return false;
            String src = driver.getPageSource();
            boolean hasHomeTab = src.contains("首页");
            boolean hasOverlay = src.contains("最新") && src.contains("最热")
                    || src.contains("没有更多了")
                    || java.util.regex.Pattern.compile("评论\\s*\\d+").matcher(src).find();
            return hasHomeTab && !hasOverlay;
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
        // 抖音在模拟器上偶发 ANR 冻结 UI（"应用没有响应"弹窗），每步前检测恢复
        AnrGuard.recover(driver);
        if (!clickSearchButton()) return false;
        if (!inputSearchText(accountName)) return false;
        AnrGuard.recover(driver);
        if (!clickSearchSubmit()) return false;
        // 新版/720x1600：数字ID精确搜索会直接跳到用户主页、部分入口没有"用户"分类Tab，
        // 因此 Tab 未命中不再直接判失败：先校验当前页是否已是目标主页，再退回点击首个用户结果。
        boolean userTabClicked = selectUserTab();
        AnrGuard.recover(driver);
        if (!userTabClicked) {
            logger.info("未找到用户分类Tab，先校验当前页是否已直达主页");
            if (verifyProfilePage()) {
                logger.info("当前页已是目标主页（搜索直达），跳过用户Tab");
                return true;
            }
        }
        if (!clickFirstUser(accountName)) return false;
        return verifyProfilePage();
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
        // ✅ 新版40.9.0适配：desc 带后缀（"搜索，[unsupported type]"），精确匹配失效；
        //    与 DouyinAddressCrawler.SEARCH_CONTROL 对齐：模糊 contains + 限定抖音包名（避免误点桌面搜索）
        String[] searchButtonXpaths = {
                "//*[@clickable='true' and @package='com.ss.android.ugc.aweme' and not(self::android.widget.EditText)"
                        + " and (contains(@text,'搜索') or contains(@content-desc,'搜索'))]",
                "//*[@content-desc='搜索' and @clickable='true']",
                "//android.widget.ImageView[contains(@resource-id, 'search')]"
        };
        for (String xp : searchButtonXpaths) {
            if (clickIfExists(xp)) return true;
        }
        clickByCoordinates((int) (screenWidth * 0.9), (int) (screenHeight * 0.05));
        sleep(2000);
        return true;
    }

    private boolean inputSearchText(String accountName) {
        // 等待搜索页加载，EditText 是通用语义控件，新旧版均可命中；
        // 先清空历史文本再输入，失败时重试一次（点首页搜索入口后重找）
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Thread.sleep(2500);
            } catch (Exception ignored) {
            }
            String[] inputXpaths = {
                    "//*[@package='com.ss.android.ugc.aweme' and contains(@resource-id, 'search_edit_text')]",
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
            // 第二次尝试前，重新点一次搜索入口（可能停留在首页而非搜索页）
            if (attempt == 0) {
                logger.warning("搜索输入框未找到，尝试重新点击搜索入口");
                clickSearchButton();
            }
        }
        logger.severe("搜索输入框未找到");
        return false;
    }

    private boolean clickSearchSubmit() {
        if (clickIfExists("//*[@text='搜索' and not(@class='android.widget.EditText')]")) return true;
        if (clickIfExists("//*[@content-desc='搜索' and @clickable='true']")) return true;
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
        try {
            Thread.sleep(2000);
        } catch (Exception e) {

        }
        // 720x1600：搜索结果页加载重，ANR 弹窗可能在此刻出现并吃掉全部节点，先恢复再查找
        AnrGuard.recover(driver);
        String[] tabXpaths = {
                "//*[@text='用户' or @content-desc='用户']",
                "//*[contains(@resource-id, 'tab_user')]",
        };
        for (String xp : tabXpaths) {
            if (clickIfExists(xp)) {
                sleep(2000);
                return true;
            }
        }
        // 一次未命中：可能 ANR 弹窗刚弹出，恢复后重试一轮再判失败
        if (AnrGuard.recover(driver)) {
            for (String xp : tabXpaths) {
                if (clickIfExists(xp)) {
                    sleep(2000);
                    return true;
                }
            }
        }
        logger.warning("用户分类Tab未找到");
        return false;
    }

    private boolean clickFirstUser(String accountName) {
        // Search ranking and display density change; never assume (250,520) is the target user.
        for (MobileElement element : driver.findElements(By.xpath(
                "//*[contains(@text,'抖音号') or contains(@content-desc,'抖音号')]"))) {
            String text = element.getText();
            if (text == null || !text.contains("抖音号")) text = element.getAttribute("content-desc");
            if (matchesSearchAccount(text, accountName)) {
                element.click();
                sleep(3000);
                if (profileHasExpectedId(driver.getPageSource(), accountName)) return true;
                driver.navigate().back();
                sleep(1000);
                break;
            }
        }
        // 40.3 renders names/IDs on canvas: only the avatar and follow button remain in XML.
        // Open a bounded set of avatar cards, then verify the actual profile ID before storing anything.
        for (int attempt = 0; attempt < 8 && driver.findElements(By.xpath(SEARCH_RESULT_AVATARS)).isEmpty(); attempt++) {
            sleep(500);
        }
        for (int index = 0; index < 5; index++) {
            // ANR 弹窗会吃掉搜索结果节点，逐轮恢复
            AnrGuard.recover(driver);
            List<MobileElement> avatars = driver.findElements(By.xpath(SEARCH_RESULT_AVATARS));
            if (index >= avatars.size()) break;
            org.openqa.selenium.Rectangle bounds = avatars.get(index).getRect();
            if (!isSearchAvatar(bounds, screenWidth, screenHeight)) continue;
            clickByCoordinates(bounds.getX() + bounds.getWidth() / 2, bounds.getY() + bounds.getHeight() / 2);
            for (int wait = 0; wait < 16; wait++) {
                sleep(500);
                if (profileHasExpectedId(driver.getPageSource(), accountName)) return true;
                // 720x1600 软渲染慢：骨架页（粉丝已出、作品区未渲染）时轻滑一次触发懒加载
                if (wait == 8) {
                    try {
                        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
                        Sequence swipe = new Sequence(finger, 1);
                        swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), screenWidth / 2, (int) (screenHeight * 0.75)));
                        swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
                        swipe.addAction(finger.createPointerMove(Duration.ofMillis(300), PointerInput.Origin.viewport(), screenWidth / 2, (int) (screenHeight * 0.58)));
                        swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
                        driver.perform(Arrays.asList(swipe));
                    } catch (Exception ignored) {
                    }
                }
            }
            String page = driver.getPageSource();
            // 半加载骨架页（粉丝/获赞已出但没有作品区和抖音号）再宽限一轮，避免误判
            if (page.contains("粉丝") && !page.contains("作品")) {
                logger.info("候选主页骨架页加载中，追加等待一轮: " + accountName);
                for (int extra = 0; extra < 10; extra++) {
                    sleep(1000);
                    page = driver.getPageSource();
                    if (profileHasExpectedId(page, accountName)) return true;
                    if (page.contains("作品")) break;
                }
            }
            if (!page.contains("抖音号") || !page.contains("作品")) {
                logger.warning("候选卡片未进入可验证主页，停止匹配: " + accountName);
                saveSearchDiag(accountName, page);
                return false;
            }
            driver.navigate().back();
            sleep(1000);
        }
        logger.warning("搜索结果未识别到目标抖音号: " + accountName);
        saveSearchDiag(accountName, driver.getPageSource());
        return false;
    }

    /** 搜索结果页识别失败时留存源码，便于离线诊断新版 UI 结构（同 no_comment_entry 机制）。 */
    private void saveSearchDiag(String accountName, String page) {
        try {
            String dir = DouyinPathUtils.resolve("videosDir");
            java.nio.file.Path diag = java.nio.file.Paths.get(dir, "no_search_user_"
                    + accountName.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + System.currentTimeMillis() + ".xml");
            java.nio.file.Files.writeString(diag, page == null ? "" : page);
            logger.warning("搜索结果页源码已留存: " + diag);
        } catch (Exception ignore) {
        }
    }

    static final String SEARCH_RESULT_AVATARS = "//*[@content-desc='关注按钮']/../*[not(@content-desc='关注按钮')][1]";

    static boolean isSearchAvatar(org.openqa.selenium.Rectangle bounds, int width, int height) {
        return bounds.getX() >= 0 && bounds.getX() < width / 2 && bounds.getY() > height / 10
                && bounds.getWidth() >= 40 && bounds.getWidth() <= width / 3
                && bounds.getHeight() >= 40 && bounds.getHeight() <= height / 5
                && bounds.getY() + bounds.getHeight() <= height;
    }

    static boolean profileHasExpectedId(String source, String accountName) {
        if (source == null) return false;
        if (!source.contains("粉丝") || !source.contains("作品")) return false;
        if (matchesSearchAccount(source, accountName)) return true;
        // 纯数字 UID（如 1871138163）：抖音主页不展示"抖音号：xxx"文本，搜索 UID 时
        // 首个用户卡片即目标；主页骨架成立（粉丝+作品+封面层 qbb）且页面无抖音号时接受。
        boolean numericId = accountName != null && accountName.matches("\\d{6,}");
        return numericId && !source.contains("抖音号") && source.contains("qbb");
    }

    static boolean matchesSearchAccount(String label, String accountName) {
        if (label == null || accountName == null) return false;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("抖音号\\s*[:：]?\\s*([A-Za-z0-9_.-]+)").matcher(label);
        return match.find() && accountName.equalsIgnoreCase(match.group(1));
    }

    private boolean verifyProfilePage() {
        for (int attempt = 0; attempt < 6; attempt++) {
            AnrGuard.recover(driver);
            String source = driver.getPageSource();
            if (source.contains("抖音号") && source.contains("粉丝") && source.contains("作品")) return true;
            // 纯数字 UID 主页无"抖音号"文本：作品网格封面层 qbb 是主页独有强特征，
            // 与粉丝/作品标签同时成立即判定已进入主页（搜索页不含 qbb）。
            if (source.contains("粉丝") && source.contains("作品") && source.contains("qbb")) {
                logger.info("主页验证通过（无抖音号文本，按作品网格骨架判定）");
                return true;
            }
            // 720x1600 软渲染较慢：主页骨架已出（粉丝/获赞）但作品区/抖音号未渲染时，
            // 轻微上滑一次触发懒加载，继续等待，避免半加载页误判失败。
            if (attempt == 2 && source.contains("粉丝") && !source.contains("作品")) {
                try {
                    int w = driver.manage().window().getSize().width;
                    int h = driver.manage().window().getSize().height;
                    PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
                    Sequence swipe = new Sequence(finger, 1);
                    swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), w / 2, (int) (h * 0.75)));
                    swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
                    swipe.addAction(finger.createPointerMove(Duration.ofMillis(300), PointerInput.Origin.viewport(), w / 2, (int) (h * 0.58)));
                    swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
                    driver.perform(Arrays.asList(swipe));
                    logger.info("主页骨架已渲染但作品区缺失，轻滑触发懒加载");
                } catch (Exception ignored) {
                }
            }
            sleep(1000);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("人员采集已取消", e);
        }
    }

    // ===========================
    // 获取账号信息
    // ===========================
    public Map<String, Object> fetchAccountInfo(String type ,String status,String devId,String douyinId) {
        AccountInfoFetcher fetcher = new AccountInfoFetcher(driver, DouyinPathUtils.root()
                , accountContentService,
                addressAccountContentService,
                accountService,douyinTaskService);
        Map<String, Object> info = fetcher.getAccountBasicInfo(type,status,devId,douyinId);

        System.out.println("========== 抖音主页解析结果 ==========");
        info.forEach((k, v) -> System.out.println(k + " : " + v));
        System.out.println("===================================");

        return info;
    }

//    public void quit() {
//        if (driver != null) driver.quit();
//    }

//    // ===========================
//    // 静态入口：爬取账号信息
//    // ===========================
//    public static Map<String, Object> crawlAccount(String devId, String accountName) {
//        Map<String, Object> info = new HashMap<>();
//        AndroidDriver<MobileElement> driver = null;
//
//        try {
//            DesiredCapabilities capabilities = new DesiredCapabilities();
//            capabilities.setCapability("platformName", "Android");
//            capabilities.setCapability("deviceName", "Android Device");
//            capabilities.setCapability("udid", devId);
//            capabilities.setCapability("appPackage", "com.ss.android.ugc.aweme");
//            capabilities.setCapability("appActivity", ".main.MainActivity");
//            capabilities.setCapability("noReset", true);
//
//            driver = new AndroidDriver<>(new URL("http://127.0.0.1:4723/wd/hub"), capabilities);
//
//            DouyinCrawler crawler = new DouyinCrawler(driver,
//
//                    accountContentService,
//                    addressAccountContentService,
//                    accountService);
//
//            if (!crawler.startDouyin()) return info;
//            if (!crawler.searchAndEnterAccount(accountName)) return info;
//
//            info = crawler.fetchAccountInfo();
//            return info;
//
//        } catch (MalformedURLException e) {
//            e.printStackTrace();
//            return info;
//        } finally {
//            if (driver != null) driver.quit();
//        }
//    }

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

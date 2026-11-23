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
            Activity activity = new Activity("com.ss.android.ugc.aweme",
                    "com.ss.android.ugc.aweme.main.MainActivity");
            driver.startActivity(activity);
            Thread.sleep(5000);

            boolean success = "com.ss.android.ugc.aweme".equals(driver.getCurrentPackage());
            logger.info(success ? "抖音启动成功" : "抖音启动失败");
            return success;
        } catch (Exception e) {
            logger.severe("启动抖音失败: " + e.getMessage());
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
        String[] searchButtonXpaths = {
                "//*[@content-desc='搜索' and @clickable='true']",
                "//android.widget.Button[@content-desc='搜索' and @clickable='true']",
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
            List<MobileElement> avatars = driver.findElements(By.xpath(SEARCH_RESULT_AVATARS));
            if (index >= avatars.size()) break;
            org.openqa.selenium.Rectangle bounds = avatars.get(index).getRect();
            if (!isSearchAvatar(bounds, screenWidth, screenHeight)) continue;
            clickByCoordinates(bounds.getX() + bounds.getWidth() / 2, bounds.getY() + bounds.getHeight() / 2);
            for (int wait = 0; wait < 6; wait++) {
                sleep(500);
                if (profileHasExpectedId(driver.getPageSource(), accountName)) return true;
            }
            String page = driver.getPageSource();
            if (!page.contains("抖音号") || !page.contains("作品")) {
                logger.warning("候选卡片未进入可验证主页，停止匹配: " + accountName);
                return false;
            }
            driver.navigate().back();
            sleep(1000);
        }
        logger.warning("搜索结果未识别到目标抖音号: " + accountName);
        return false;
    }

    static final String SEARCH_RESULT_AVATARS = "//*[@content-desc='关注按钮']/../*[not(@content-desc='关注按钮')][1]";

    static boolean isSearchAvatar(org.openqa.selenium.Rectangle bounds, int width, int height) {
        return bounds.getX() >= 0 && bounds.getX() < width / 2 && bounds.getY() > height / 10
                && bounds.getWidth() >= 40 && bounds.getWidth() <= width / 3
                && bounds.getHeight() >= 40 && bounds.getHeight() <= height / 5
                && bounds.getY() + bounds.getHeight() <= height;
    }

    static boolean profileHasExpectedId(String source, String accountName) {
        return source != null && source.contains("粉丝") && source.contains("作品")
                && matchesSearchAccount(source, accountName);
    }

    static boolean matchesSearchAccount(String label, String accountName) {
        if (label == null || accountName == null) return false;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("抖音号\\s*[:：]?\\s*([A-Za-z0-9_.-]+)").matcher(label);
        return match.find() && accountName.equalsIgnoreCase(match.group(1));
    }

    private boolean verifyProfilePage() {
        for (int attempt = 0; attempt < 4; attempt++) {
            String source = driver.getPageSource();
            if (source.contains("抖音号") && source.contains("粉丝") && source.contains("作品")) return true;
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

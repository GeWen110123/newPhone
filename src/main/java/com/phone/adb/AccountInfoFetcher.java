package com.phone.adb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phone.common.constant.Constants;
import com.phone.module.domain.Account;
import com.phone.module.domain.AccountContent;
import com.phone.module.domain.AddressAccountContent;
import com.phone.module.service.DouyinTaskService;
import com.phone.module.service.IAccountContentService;
import com.phone.module.service.IAccountService;
import com.phone.module.service.IAddressAccountContentService;
import io.appium.java_client.TouchAction;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.touch.WaitOptions;
import io.appium.java_client.touch.offset.PointOption;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AccountInfoFetcher {

    /**
     * 获取主页基本信息
     */
    private final AndroidDriver driver;
    private final String outputDir;
    private final Logger logger;
    private final ElementFinder finder;

    private final IAccountContentService accountContentService;
    private final IAddressAccountContentService addressAccountContentService;
    private final IAccountService accountService;
    private final DouyinTaskService douyinTaskService;

    public AccountInfoFetcher(AndroidDriver driver,
                              String outputDir,
                              IAccountContentService accountContentService,
                              IAddressAccountContentService addressAccountContentService,
                              IAccountService accountService,DouyinTaskService douyinTaskService) {

        this.driver = driver;
        this.outputDir = outputDir;
        this.logger = Logger.getLogger("DouyinParser");
        this.finder = new ElementFinder(driver, logger);
        this.douyinTaskService = douyinTaskService;
        this.accountContentService = accountContentService;
        this.addressAccountContentService = addressAccountContentService;
        this.accountService = accountService;
    }


    private String toWebPath(String fullPath) {
        return DouyinPathUtils.webPath(fullPath);
    }


    private Map<String, Object> getCacheFromDB(String douyinId) {

        ObjectMapper mapper = new ObjectMapper();

        try {
            // 1️⃣ account_content 表
            List<AccountContent> listFollows = accountContentService.selectFollowListByUid(douyinId);
            if (listFollows != null && !listFollows.isEmpty() && listFollows.get(0).getJsonString() != null) {
                return mapper.readValue(listFollows.get(0).getJsonString(), Map.class);
            }
            List<AccountContent> listFans = accountContentService.selectFansListByUid(douyinId);
            if (listFans != null && !listFans.isEmpty() && listFans.get(0).getJsonString() != null) {
                return mapper.readValue(listFans.get(0).getJsonString(), Map.class);
            }
            // 1️⃣ account_content 表
            AccountContent ac = new AccountContent();
            ac.setDouyinId(douyinId);
            List<AccountContent> list1 = accountContentService.selectAccountContentList(ac);
            if (list1 != null && !list1.isEmpty() && list1.get(0).getJsonString() != null) {
                return mapper.readValue(list1.get(0).getJsonString(), Map.class);
            }

            // 2️⃣ address_account_content 表
            AddressAccountContent aac = new AddressAccountContent();
            aac.setDouyinId(douyinId);
            List<AddressAccountContent> list2 = addressAccountContentService.selectAddressAccountContentList(aac);
            if (list2 != null && !list2.isEmpty() && list2.get(0).getJsonString() != null) {
                return mapper.readValue(list2.get(0).getJsonString(), Map.class);
            }

            // 3️⃣ account 表
            Account acc = new Account();
            acc.setDouyinId(douyinId);
            List<Account> list3 = accountService.selectAccountList(acc);
            if (list3 != null && !list3.isEmpty() && list3.get(0).getJsonString() != null) {
                return mapper.readValue(list3.get(0).getJsonString(), Map.class);
            }

        } catch (Exception e) {
            logger.warning("缓存读取异常: " + e.getMessage());
        }

        return null;
    }

    public Map<String, Object> getAccountBasicInfo(String type, String status, String devId, String accountName) {
        Map<String, Object> info = new HashMap<>();
        initEmptyInfo(info);

        if (!ensureDeviceConnected()) return info;

        String pageSource;
        try {
            sleep(800);
            pageSource = driver.getPageSource();
        } catch (Exception e) {
            logger.severe("获取页面源码失败: " + e.getMessage());
            return info;
        }

        if (pageSource == null || pageSource.trim().isEmpty()) {
            logger.warning("获取到的主页UI源码为空");
            return info;
        }

        // 新版抖音：优先从整棵 UI 树解析，旧 XPath 作为兜底。
        String nickname = getNicknameV2(pageSource);
        String douyinId = getDouyinIdV2(pageSource, nickname);

        if ("1".equals(type) && "1".equals(status)) {
            String inputDouyinId = normalizeDouyinIdForCompare(accountName);
            String fetchedDouyinId = normalizeDouyinIdForCompare(douyinId);
            if (!inputDouyinId.isEmpty()
                    && !fetchedDouyinId.isEmpty()
                    && !inputDouyinId.equals(fetchedDouyinId)) {
                logger.severe("获取人员与输入抖音号不一致，输入抖音号: " + accountName
                        + "，页面抖音号: " + douyinId);
                return info;
            }
        }

        info.put("nickname", nickname);
        info.put("id", douyinId);
        if (!isRealDouyinId(douyinId)) {
            logger.warning("当前主页未读取到真实抖音号，不使用昵称替代身份");
            return info;
        }
        // The profile is already open. Refresh its visible fields instead of presenting
        // historical commenter/follower cache as a new observation.
        info.put("collected_at", java.time.Instant.now().toString());

        String accountImage = "zhuye/" + UUID.randomUUID() + "_profile_screen.png";
        takeScreenshot(accountImage);
        info.put("zhuye", toWebPath(DouyinPathUtils.resolve(accountImage)));

        info.put("follow_count", getFollowCountV2(pageSource));
        info.put("fans_count", getFansCountV2(pageSource));
        info.put("likes_count", getLikesCountV2(pageSource));
        info.put("works_count", getWorksCountV2(pageSource));
        info.put("signature", getSignatureV2(pageSource, nickname));
        info.put("region", getRegionV2(pageSource));
        info.put("gender", getGenderV2(pageSource));
        info.put("age", getAgeV2(pageSource));
        info.put("school", getSchoolV2(pageSource));

        info.put("profile_text", getProfileTextV2(pageSource));

        String ipLocation = getIpLocationV2(pageSource);
        info.put("ip_location", ipLocation);

        String regionFull = getRegionFullV2(pageSource);
        info.put("region_full", regionFull);
        parseRegion(info, regionFull);

        // 仅保留公开页面可见信息；不主动点击实名认证/实名入口。
        info.put("real_name", getPublicRealName(pageSource));

        if (openUserProfileFromAvatarV2()) {
            String screenshotFileName = "touxiang/" + UUID.randomUUID() + "_profile_screen.png";
            takeScreenshot(screenshotFileName);
            info.put("touxiang", toWebPath(DouyinPathUtils.resolve(screenshotFileName)));
            try {
                driver.navigate().back();
            } catch (Exception ignored) {
                try { driver.navigate().back(); } catch (Exception ignoredAgain) { }
            }
        }

        return info;
    }


    private void collectRelationList(String type, String devId, String douyinId,int count) {

        logger.info("进入" + (type.equals("follow") ? "关注" : "粉丝") + "列表页");

        if (count / 3 > 1) {
            int loops = count / 3;
            for (int i = 0; i < loops; i++) {  // 条件 i < loops
                scrollList();
                try {
                    Thread.sleep(500); // 可以适当加个短延时
                } catch (InterruptedException ignored) {}
            }
        }

        Set<String> processedSet = new HashSet<>();
        int noChangeCount = 0;
        String lastHash = "";

        while (true) {

            List<WebElement> items = driver.findElements(
                    By.id("com.ss.android.ugc.aweme:id/root_layout")
            );

            boolean hasNew = false;

            for (int i = 0; i < items.size(); i++) {

                try {
                    List<WebElement> currentItems = driver.findElements(
                            By.id("com.ss.android.ugc.aweme:id/root_layout")
                    );

                    if (i >= currentItems.size()) break;

                    WebElement item = currentItems.get(i);

                    String nicknameItem = item.findElement(
                            By.id("com.ss.android.ugc.aweme:id/337")
                    ).getText();

                    if (nicknameItem == null || nicknameItem.isEmpty()) continue;
                    if (processedSet.contains(nicknameItem)) continue;

                    processedSet.add(nicknameItem);
                    hasNew = true;

                    // ⭐ 点击头像进入子主页
                    clickAvatar(item);
                    Thread.sleep(1500);

//                    // ⭐ 子主页采集（status=0 防止递归）
//                    Map<String, Object> subInfo = getAccountBasicInfo("1", "0");
//
//                    AccountContent content = convertToAccount(subInfo);
//
//                    if ("follow".equals(type)) {
//                        accountContentService.insertFollow(content);
//                    } else {
//                        accountContentService.insertFans(content);
//                    }

                    Map<String, Object> subInfo = getAccountBasicInfo("1", "0", devId, douyinId);

                    String subDouyinId = String.valueOf(subInfo.get("id"));

                    saveRelationAccount(
                            type,
                            devId,
                            douyinId,        // 当前主页id
                            subDouyinId,     // 子账号id
                            subInfo
                    );

                    driver.navigate().back();
                    Thread.sleep(1500);

                } catch (Exception e) {
                    logger.warning("采集异常，跳过当前条目：" + e.getMessage());
                    try {
                        driver.navigate().back();
                    } catch (Exception ignored) {
                    }
                }
            }

// ⭐ 判断是否出现隐私提示
            if (isPrivacyLimitShown()) {
                logger.info(type + " 列表因隐私限制结束采集");
                break;
            }

// ⭐ 判断是否到底（页面无变化）
            String currentHash = String.valueOf(driver.getPageSource().hashCode());

            if (!hasNew || currentHash.equals(lastHash)) {
                noChangeCount++;
            } else {
                noChangeCount = 0;
            }

            if (noChangeCount >= 5) {
                logger.info(type + " 列表采集完成");
                break;
            }

            lastHash = currentHash;

            scrollList();
            sleep(1500);
        }
    }


    private boolean isPrivacyLimitShown() {
        try {
            List<WebElement> elements = driver.findElements(
                    By.xpath("//*[contains(@text,'隐私设置') or contains(@text,'暂不支持查看')]")
            );
            return !elements.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }



    private void saveRelationAccount(String relationType,
                                     String devId,
                                     String douyinId,
                                     String accountName,
                                     Map<String, Object> resultMap) {

        try {

            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(resultMap);

            AccountContent account = new AccountContent();
            account.setDevId(devId);
            account.setDouyinId(douyinId);       // 主账号id
            account.setAccountName(douyinId);    // 主账号
            account.setvUid(accountName);        // 子账号id（被关注/粉丝）
            account.setJsonString(json);
            account.setCreateTime(new Date());

            if ("follow".equals(relationType)) {
                accountContentService.insertFollow(account);
            } else {
                accountContentService.insertFans(account);
            }

        } catch (Exception e) {
            logger.severe("保存关系数据失败：" + e.getMessage());
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }


    private AccountContent convertToAccount(Map<String, Object> info) {

        AccountContent content = new AccountContent();

        // ⭐ 可选：记录采集时间
        content.setCreateTime(new Date());

        return content;
    }


    private void scrollList() {

        int startX = 540;
        int startY = 1700;
        int endY = 600;

        new TouchAction<>(driver)
                .press(PointOption.point(startX, startY))
                .waitAction(WaitOptions.waitOptions(Duration.ofMillis(800)))
                .moveTo(PointOption.point(startX, endY))
                .release()
                .perform();
    }

    public boolean clickFollowButtonAndCheck() {
        try {

            // ⭐ 等待关注按钮出现
            WebDriverWait wait = new WebDriverWait(driver, 10);

            List<WebElement> elements = wait.until(driver ->
                    driver.findElements(By.xpath("//*[contains(@text,'关注')]"))
            );

            boolean clicked = false;

            for (WebElement element : elements) {
                String text = element.getText();

                // 避免点到“已关注”
                if ("关注".equals(text) || text.matches("关注\\s*\\d+")) {
                    element.click();
                    clicked = true;
                    break;
                }
            }

            if (!clicked) {
                logger.warning("未找到真正的关注按钮");
                return false;
            }

            // ⭐ 等待页面跳转
            Thread.sleep(1500);


            boolean inFansPage = isInFansPage();

            if (inFansPage) {
                logger.info("成功进入关注列表页");
                return true;
            } else {
                logger.warning("点击后未成功进入关注页");
                return false;
            }

        } catch (Exception e) {
            logger.severe("点击关注按钮异常: " + e.getMessage());
            return false;
        }
    }

    public boolean clickFansButtonAndCheck() {
        try {

            WebDriverWait wait = new WebDriverWait(driver, 10);

            // ⭐ 等待“粉丝”元素出现
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//*[contains(@text,'粉丝')]")
            ));

            List<WebElement> elements = driver.findElements(
                    By.xpath("//*[contains(@text,'粉丝')]")
            );

            boolean clicked = false;

            for (WebElement element : elements) {

                String text = element.getText();

                // 精准匹配：避免误点其它包含“粉丝”的文案
                if ("粉丝".equals(text) || text.matches("粉丝\\s*\\d+")) {
                    element.click();
                    clicked = true;
                    break;
                }
            }

            if (!clicked) {
                logger.warning("未找到真正的粉丝按钮");
                return false;
            }

            // ⭐ 等待页面跳转
            Thread.sleep(1500);

            boolean inFansPage = isInFansPage();

            if (inFansPage) {
                logger.info("成功进入粉丝列表页");
                return true;
            } else {
                logger.warning("点击后未成功进入粉丝页");
                return false;
            }

        } catch (Exception e) {
            logger.severe("点击粉丝按钮异常: " + e.getMessage());
            return false;
        }
    }

    private boolean isInFansPage() {
        try {
            // 查找顶部 Tab
            List<WebElement> tabs = driver.findElements(By.id("android:id/text1"));
            boolean hasFansTab = false;
            for (WebElement tab : tabs) {
                String text = tab.getText();
                if (text != null && text.contains("粉丝")) {
                    hasFansTab = true;
                    break;
                }
            }

            // 查找粉丝列表 RecyclerView
            List<WebElement> listView = driver.findElements(By.id("com.ss.android.ugc.aweme:id/sp2"));

            if (hasFansTab && !listView.isEmpty()) {
                logger.info("确认进入粉丝列表页");
                return true;
            }
        } catch (Exception e) {
            logger.warning("粉丝页校验异常: " + e.getMessage());
        }

        logger.warning("未进入粉丝列表页");
        return false;
    }


    public void clickAvatar(WebElement item) {

        try {

            // 在当前条目里找头像 ImageView
            WebElement avatar = item.findElement(
                    By.xpath(".//android.widget.ImageView")
            );

            avatar.click();
            Thread.sleep(2000);

            System.out.println("已点击头像进入主页");

        } catch (Exception e) {
            System.out.println("点击头像失败，改为点击整条记录");
            item.click();
        }
    }

    private int parseCount(String countStr) {
        if (countStr == null || countStr.trim().isEmpty()) {
            return 0;
        }

        countStr = countStr.replaceAll("[^0-9.万]", "");

        try {
            if (countStr.contains("万")) {
                double num = Double.parseDouble(countStr.replace("万", ""));
                return (int) (num * 10000);
            }
            return Integer.parseInt(countStr);
        } catch (Exception e) {
            return 0;
        }
    }

    private void initEmptyInfo(Map<String, Object> info) {
        info.put("id", "");
        info.put("nickname", "");
        info.put("avatar", "");
        info.put("touxiang", "");
        info.put("likes_count", "");
        info.put("follow_count", "");
        info.put("fans_count", "");
        info.put("works_count", "");
        info.put("region", "");
        info.put("gender", "");
        info.put("age", "");
        info.put("school", "");
        info.put("signature", "");
        info.put("custom_bio_items", new HashMap<>());
        info.put("real_name", "");

        // ⭐ 新增字段（只加，不动原有）
        info.put("profile_text", "");
        info.put("ip_location", "");
        info.put("province", "");
        info.put("city", "");
        info.put("region_full", "");
    }

    private String normalizeDouyinIdForCompare(String douyinId) {
        if (douyinId == null) {
            return "";
        }
        String cleaned = TextCleaner.cleanDouyinId(douyinId.trim());
        return cleaned == null ? "" : cleaned.trim();
    }


    /**
     * 从新版抖音 UI XML 中提取 text/content-desc。
     * 不再限制 android.widget.TextView，兼容新版自定义 View。
     */
    private List<String> extractUiTexts(String pageSource) {
        List<String> values = new ArrayList<>();
        if (pageSource == null || pageSource.isEmpty()) return new ArrayList<>();

        try {
            // Preserve node order and repeated numbers. Deduplicating 67/67 shifts
            // the following labels and can attribute fans to the following count.
            Pattern p = Pattern.compile("<[^>]+>");
            Matcher m = p.matcher(pageSource);
            while (m.find()) {
                Matcher text = Pattern.compile("\\btext=\"([^\"]*)\"").matcher(m.group());
                Matcher desc = Pattern.compile("\\bcontent-desc=\"([^\"]*)\"").matcher(m.group());
                String value = text.find() ? decodeXmlText(text.group(1)) : "";
                if (value.isEmpty() && desc.find()) value = decodeXmlText(desc.group(1));
                if (!value.isEmpty()) values.add(value);
            }
        } catch (Exception e) {
            logger.warning("解析UI文本失败: " + e.getMessage());
        }
        return new ArrayList<>(values);
    }

    private String decodeXmlText(String text) {
        if (text == null) return "";
        return org.apache.commons.lang3.StringEscapeUtils.unescapeXml(text).trim();
    }

    private String normalizeUiValue(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isCountText(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String value = normalizeUiValue(text)
                .replace(",", "")
                .replace("，", "");
        return value.matches("[0-9]+(?:\\.[0-9]+)?[万亿wWkK]?\\+?");
    }

    private String findStatFromUi(String pageSource, String... labels) {
        // Current versions group each number and its label in a two-child container.
        // This also handles label-first variants without crossing into the next statistic.
        for (String label : labels) {
            String sibling = "//*[@text='" + label + "' or @content-desc='" + label
                    + "']/parent::*[count(*)=2]/*[not(@text='" + label + "' or @content-desc='" + label + "')]";
            String local = finder.findTextFromSource(pageSource, new String[]{sibling}, this::isCountText, label);
            if (!local.isEmpty()) return cleanNumberString(local);
        }
        List<String> texts = extractUiTexts(pageSource);

        for (int i = 0; i < texts.size(); i++) {
            String current = normalizeUiValue(texts.get(i));
            if (current.isEmpty()) continue;

            for (String label : labels) {
                if (current.equals(label) && i > 0) {
                    String previous = normalizeUiValue(texts.get(i - 1));
                    if (isCountText(previous)) return cleanNumberString(previous);
                }
                if (current.equals(label) && i + 1 < texts.size()) {
                    String next = normalizeUiValue(texts.get(i + 1));
                    if (isCountText(next)) return cleanNumberString(next);
                }

                if (current.startsWith(label)) {
                    String value = normalizeUiValue(current.substring(label.length()));
                    if (isCountText(value)) return cleanNumberString(value);
                }
            }
        }

        // 最后再走原 XPathRegistry，保证旧版本兼容。
        return "";
    }

    private String getNicknameV2(String src) {
        try {
            String old = finder.findTextFromSource(src, XPathRegistry.NICKNAME_XPATHS,
                    txt -> txt != null && !txt.trim().isEmpty(), "nickname");
            if (old != null && !old.trim().isEmpty()) return old.trim();
        } catch (Exception ignored) { }

        List<String> texts = extractUiTexts(src);
        String fallback = "";

        for (int i = 1; i < texts.size(); i++) {
            if (normalizeUiValue(texts.get(i)).startsWith("抖音号")) {
                String candidate = normalizeUiValue(texts.get(i - 1));
                if (!candidate.isEmpty() && !candidate.contains("头像") && !candidate.contains("抖音号")
                        && candidate.length() <= 60) return candidate;
            }
        }

        for (String raw : texts) {
            String text = normalizeUiValue(raw);
            if (text.isEmpty()) continue;
            if (text.contains("头像") || text.equals("返回") || text.equals("搜索") || text.equals("背景图片")) continue;
            if (text.startsWith("抖音号") || text.startsWith("IP") || text.startsWith("IP属地")) continue;
            if (text.matches("[0-9]+(?:\\.[0-9]+)?[万亿]?[关注粉丝作品获赞]*")) continue;
            if (text.equals("关注") || text.equals("粉丝") || text.equals("作品") || text.equals("获赞")) continue;
            if (text.contains("编辑资料") || text.contains("私信") || text.equals("更多")) continue;
            if (text.length() <= 30 && !text.contains("：") && !text.contains(":")) {
                if (fallback.isEmpty()) fallback = text;
            }
        }
        return fallback;
    }

    private String getDouyinIdV2(String src, String nickname) {
        List<String> texts = extractUiTexts(src);
        for (int i = 0; i < texts.size(); i++) {
            String text = normalizeUiValue(texts.get(i));
            if (text.startsWith("抖音号")) {
                String id = text.substring(3)
                        .replace("：", "")
                        .replace(":", "")
                        .trim();
                if (id.isEmpty() && i + 1 < texts.size()) id = normalizeUiValue(texts.get(i + 1));
                if (isRealDouyinId(id)) return id;
            }
        }

        try {
            String id = finder.findTextFromSource(src, XPathRegistry.DOUYIN_ID_XPATHS,
                    txt -> txt != null && !txt.trim().isEmpty(), "douyin_id");
            if (id != null) {
                id = TextCleaner.cleanDouyinId(id);
                if (isRealDouyinId(id)) return id;
            }
        } catch (Exception ignored) { }

        return "";
    }

    public static boolean isRealDouyinId(String id) {
        return id != null && !id.startsWith("NICKNAME_") && id.matches("[A-Za-z0-9_.-]{1,64}");
    }

    private String getFollowCountV2(String src) {
        String value = findStatFromUi(src, "关注");
        if (!value.isEmpty()) return value;
        return getFollowCountLegacy(src);
    }

    private String getFansCountV2(String src) {
        String value = findStatFromUi(src, "粉丝");
        if (!value.isEmpty()) return value;
        return getFansCountLegacy(src);
    }

    private String getLikesCountV2(String src) {
        String value = findStatFromUi(src, "获赞", "获赞数");
        if (!value.isEmpty()) return value;
        return getLikesCountLegacy(src);
    }

    private String getWorksCountV2(String src) {
        String value = findStatFromUi(src, "作品", "作品数");
        if (!value.isEmpty()) return value;
        return getWorksCountLegacy(src);
    }

    private String getSignatureV2(String src, String nickname) {
        List<String> texts = extractUiTexts(src);
        boolean afterStats = false;
        List<String> bio = new ArrayList<>();
        for (String raw : texts) {
            String text = normalizeUiValue(raw);
            if (text.equals("粉丝")) { afterStats = true; continue; }
            if (!afterStats) continue;
            if (text.startsWith("IP") || text.startsWith("作品") || text.equals("关注") || text.equals("私信")) break;
            if (isProfileBio(text, nickname)) bio.add(raw);
        }
        if (!bio.isEmpty()) return String.join("\n", bio);
        try {
            String old = finder.findTextFromSource(src, XPathRegistry.SIGNATURE_XPATHS,
                    txt -> isProfileBio(normalizeUiValue(txt), nickname), "signature");
            if (old != null && !old.trim().isEmpty()) return old.trim();
        } catch (Exception ignored) { }
        return "";
    }

    private boolean isProfileBio(String text, String nickname) {
        return text != null && text.length() >= 3 && text.length() <= 1000 && !text.equals(nickname)
                && !isCountText(text) && !text.startsWith("抖音号") && !text.startsWith("IP")
                && !text.startsWith("作品") && !text.contains("按钮") && !text.contains("头像")
                && !Arrays.asList("编辑资料", "背景图片", "探店足迹", "求更新", "刚刚看过").contains(text);
    }

    private String getRegionV2(String src) {
        String ip = getIpLocationV2(src);
        if (!ip.isEmpty()) return ip;
        try {
            String old = finder.findTextFromSource(src, XPathRegistry.IP_XPATHS,
                    txt -> txt != null && !txt.isEmpty(), "region");
            return old == null ? "" : old.trim();
        } catch (Exception ignored) { return ""; }
    }

    private List<String> profileHeaderTexts(String src) {
        List<String> header = new ArrayList<>();
        for (String raw : extractUiTexts(src)) {
            String text = normalizeUiValue(raw);
            if (text.startsWith("作品") || text.equals("日常")) break;
            header.add(text);
        }
        return header;
    }

    private String getGenderV2(String src) {
        for (String text : profileHeaderTexts(src)) {
            Matcher match = Pattern.compile("^(男|女)(?:[·•\\s]+(?:[0-9]{1,3}岁|[0-9]{2}后))?$").matcher(text);
            if (match.matches()) return match.group(1);
        }
        return "";
    }

    private String getAgeV2(String src) {
        for (String text : profileHeaderTexts(src)) {
            Matcher match = Pattern.compile("^(?:(?:男|女)[·•\\s]+)?([0-9]{1,3}岁|[0-9]{2}后)$").matcher(text);
            if (match.matches()) return match.group(1);
        }
        return "";
    }

    private String getSchoolV2(String src) {
        boolean profileTags = false;
        for (String text : profileHeaderTexts(src)) {
            if (text.startsWith("IP")) { profileTags = true; continue; }
            if (profileTags && text.matches("[\\p{IsHan}A-Za-z· ]{2,40}(?:大学|学院|学校)")) return text;
        }
        return "";
    }

    private String getProfileTextV2(String src) {
        return getSignatureV2(src, getNicknameV2(src));
    }

    private String getIpLocationV2(String src) {
        for (String raw : extractUiTexts(src)) {
            String text = normalizeUiValue(raw);
            if (text.startsWith("IP属地：")) return text.substring(4).trim();
            if (text.startsWith("IP属地:")) return text.substring(4).trim();
            if (text.startsWith("IP：")) return text.substring(3).trim();
            if (text.startsWith("IP:")) return text.substring(3).trim();
        }

        Matcher m = Pattern.compile("IP(?:属地)?[：:]([^<>\\\"]+)").matcher(src);
        return m.find() ? decodeXmlText(m.group(1)).trim() : "";
    }

    private String getRegionFullV2(String src) {
        Pattern p = Pattern.compile("([\\u4e00-\\u9fa5]{2,12}(?:省|自治区|市)?(?:·|-|—)[\\u4e00-\\u9fa5]{2,12}(?:市|区|县)?)");
        for (String raw : extractUiTexts(src)) {
            Matcher m = p.matcher(normalizeUiValue(raw));
            if (m.find()) return m.group(1);
        }
        return "";
    }

    private void parseRegion(Map<String, Object> info, String regionFull) {
        if (regionFull == null || regionFull.trim().isEmpty()) return;
        String value = regionFull.trim();
        String[] parts = value.split("[·\\-—]", 2);
        if (parts.length > 0) info.put("province", parts[0].trim());
        if (parts.length > 1) info.put("city", parts[1].trim());
    }

    private String getPublicRealName(String src) {
        // 仅读取页面当前已经公开显示的姓名字段，不点击实名认证入口。
        String[] patterns = {
                "真实姓名[：:]([^\\s<>\"]+)",
                "姓名[：:]([^\\s<>\"]+)"
        };
        for (String regex : patterns) {
            Matcher m = Pattern.compile(regex).matcher(src);
            if (m.find()) return decodeXmlText(m.group(1)).trim();
        }
        return "";
    }

    // 旧版 XPath 解析作为兼容兜底。
    private String getFollowCountLegacy(String src) {
        try { return cleanNumberString(finder.findTextFromSource(src, XPathRegistry.FOLLOW_COUNT_XPATHS, null, "follow_count")); }
        catch (Exception e) { return ""; }
    }

    private String getFansCountLegacy(String src) {
        try { return cleanNumberString(finder.findTextFromSource(src, XPathRegistry.FANS_COUNT_XPATHS, null, "fans_count")); }
        catch (Exception e) { return ""; }
    }

    private String getLikesCountLegacy(String src) {
        try { return cleanNumberString(finder.findTextFromSource(src, XPathRegistry.LIKES_COUNT_XPATHS, null, "likes_count")); }
        catch (Exception e) { return ""; }
    }

    private String getWorksCountLegacy(String src) {
        try { return cleanNumberString(finder.findTextFromSource(src, XPathRegistry.WORKS_COUNT_XPATHS, null, "works_count")); }
        catch (Exception e) { return ""; }
    }

    private boolean ensureDeviceConnected() {
        try {
            driver.getPageSource();
            return true;
        } catch (Exception e) {
            logger.severe("设备连接失败: " + e.getMessage());
            return false;
        }
    }

    private void takeScreenshot(String filename) {
        try {
            File screenshot = driver.getScreenshotAs(org.openqa.selenium.OutputType.FILE);
            Files.copy(screenshot.toPath(), Paths.get(outputDir, filename), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warning("保存截图失败: " + e.getMessage());
        }
    }

    // -------------------------
    // 各字段单独方法
    // -------------------------
//    private String getNickname() {
//        return finder.findTextWithRetry(XPathRegistry.NICKNAME_XPATHS, 3,
//                txt -> txt != null && !txt.trim().isEmpty(), "nickname");
//    }
    private String getNickname(String src) {
        return finder.findTextFromSource(
                src,
                XPathRegistry.NICKNAME_XPATHS,
                txt -> !txt.isEmpty(),
                "nickname");
    }

    private String getDouyinId(String src, String nickname) {
        String id = finder.findTextFromSource(
                src,
                XPathRegistry.DOUYIN_ID_XPATHS,
                txt -> !txt.isEmpty(),
                "douyin_id");

        return id.isEmpty() ? "NICKNAME_" + nickname : TextCleaner.cleanDouyinId(id);
    }
//    private String getDouyinId(String nickname) {
//        String id = finder.findTextWithRetry(XPathRegistry.DOUYIN_ID_XPATHS, 3,
//                txt -> txt != null && !txt.trim().isEmpty(), "douyin_id");
//        if (id == null || id.isEmpty()) return "NICKNAME_" + nickname;
//        return TextCleaner.cleanDouyinId(id);
//    }

    private String getFollowCount(String src) {
        return cleanNumberString(
                finder.findTextFromSource(src, XPathRegistry.FOLLOW_COUNT_XPATHS, null, "follow_count"));
    }

    private String getFansCount(String src) {
        return cleanNumberString(
                finder.findTextFromSource(src, XPathRegistry.FANS_COUNT_XPATHS, null, "fans_count"));
    }

    private String getLikesCount(String src) {
        return cleanNumberString(
                finder.findTextFromSource(src, XPathRegistry.LIKES_COUNT_XPATHS, null, "likes_count"));
    }

    private String getWorksCount(String src) {
        return cleanNumberString(
                finder.findTextFromSource(src, XPathRegistry.WORKS_COUNT_XPATHS, null, "works_count"));
    }


    private String getSignature(String src, String nickname) {
        return finder.findTextFromSource(
                src,
                XPathRegistry.SIGNATURE_XPATHS,
                txt -> txt != null && txt.length() > 2 && !txt.equals(nickname),
                "signature");
    }

    private String getRegion(String src) {
        return finder.findTextFromSource(
                src,
                XPathRegistry.IP_XPATHS,
                txt -> txt != null && !txt.isEmpty(),
                "region");
    }

    private String getGender(String src) {
        return finder.findTextFromSource(
                src,
                XPathRegistry.GENDER_XPATHS,
                txt -> txt != null && (txt.contains("男") || txt.contains("女")),
                "gender");
    }

    private String getAge(String src) {
        return finder.findTextFromSource(
                src,
                XPathRegistry.AGE_XPATHS,
                txt -> txt != null && !txt.isEmpty(),
                "age");
    }

    private String getSchool(String src) {
        return finder.findTextFromSource(
                src,
                XPathRegistry.SCHOOL_XPATHS,
                txt -> txt != null && !txt.isEmpty(),
                "school");
    }




    private boolean openUserProfileFromAvatarV2() {
        String[] avatarXPs = new String[]{
                "//*[@content-desc='用户头像']",
                "//*[contains(@content-desc,'头像')]",
                "//*[contains(@content-desc,'avatar')]",
                "//*[contains(@resource-id,'avatar') and @clickable='true']",
                "//android.widget.ImageView[@clickable='true']"
        };

        for (String xp : avatarXPs) {
            try {
                List<WebElement> elements = driver.findElements(By.xpath(xp));
                for (WebElement element : elements) {
                    if (!element.isDisplayed()) continue;
                    element.click();
                    sleep(1200);
                    logger.info("已点击用户头像，进入个人主页");
                    return true;
                }
            } catch (Exception e) {
                logger.fine("头像候选定位失败: " + e.getMessage());
            }
        }

        logger.warning("未找到可点击的用户头像元素");
        return false;
    }



    // ======================
    // Normalize display units before persisting numeric profile fields.
    // ======================
    private String cleanNumberString(String txt) {
        if (txt == null) return "";
        Matcher count = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([亿万wWkK]?)")
                .matcher(txt.replace(",", "").replace("，", ""));
        if (!count.find()) return "";
        long multiplier = 1;
        String unit = count.group(2);
        if ("亿".equals(unit)) multiplier = 100000000L;
        else if ("万".equals(unit) || "w".equalsIgnoreCase(unit)) multiplier = 10000L;
        else if ("k".equalsIgnoreCase(unit)) multiplier = 1000L;
        return new java.math.BigDecimal(count.group(1)).multiply(java.math.BigDecimal.valueOf(multiplier))
                .setScale(0, java.math.RoundingMode.DOWN).toPlainString();
    }
}

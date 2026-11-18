package com.phone.adb;

import com.phone.module.service.DouyinTaskService;
import com.phone.module.service.IAccountContentService;
import com.phone.module.service.IAccountService;
import com.phone.module.service.IAddressAccountContentService;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.MobileElement;
import io.appium.java_client.TouchAction;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.touch.WaitOptions;
import io.appium.java_client.touch.offset.PointOption;
import org.openqa.selenium.By;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

public class VideoCommentsFetcher {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(VideoCommentsFetcher.class.getName());
    static final String COMMENT_ROWS = "//*[@resource-id='com.ss.android.ugc.aweme:id/flq']";
    // Fallback uses the semantic avatar/content structure, independent of obfuscated row IDs.
    static final String COMMENT_ROWS_FALLBACK = "//androidx.recyclerview.widget.RecyclerView//*[@resource-id='com.ss.android.ugc.aweme:id/content']/parent::*[.//*[@resource-id='com.ss.android.ugc.aweme:id/avatar' or contains(@content-desc,'头像')]]";
    static final String COMMENT_PANEL = "//*[@resource-id='com.ss.android.ugc.aweme:id/e4l' and contains(@text,'评论')] | //*[contains(@text,'暂无评论') or contains(@text,'还没有评论')] | " + COMMENT_ROWS;

    private List<MobileElement> commentRows() {
        List<MobileElement> rows = driver.findElements(By.xpath(COMMENT_ROWS));
        return rows.isEmpty() ? driver.findElements(By.xpath(COMMENT_ROWS_FALLBACK)) : rows;
    }

    private boolean onCommentPanel() {
        return !driver.findElements(By.xpath(COMMENT_PANEL)).isEmpty() || !commentRows().isEmpty();
    }

    private void ensureActive() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("采集已取消");
    }

    private java.util.function.LongSupplier nanoClock = System::nanoTime;
    private static class YieldComments extends RuntimeException { }

    private void yieldIfWaiting(String device, long started) {
        if (nanoClock.getAsLong() - started >= java.util.concurrent.TimeUnit.MINUTES.toNanos(3)
                && douyinTaskService.hasWaitingTasks(device)) throw new YieldComments();
    }

    static String commentKey(String nickname, String text, String time, String replyTo) {
        return JSON.toJSONString(Arrays.asList(nickname, text, time, replyTo));
    }

    private AppiumDriver<MobileElement> driver;
    private final IAccountContentService accountContentService;
    private final IAddressAccountContentService addressAccountContentService;
    private final IAccountService accountService;
    public VideoCommentsFetcher(AppiumDriver<MobileElement> driver,DouyinTaskService douyinTaskService,
                                IAccountContentService accountContentService,
                                IAddressAccountContentService addressAccountContentService,
                                IAccountService accountService) {
        this.douyinTaskService = douyinTaskService;
        this.driver = driver;
        this.accountContentService = accountContentService;
        this.addressAccountContentService = addressAccountContentService;
        this.accountService = accountService;
    }
    @Autowired
    private DouyinTaskService douyinTaskService;


    private Map<String, Object> fetchUserFromAvatar(String type, String accountName, String vUid,
            MobileElement commentNode, AndroidDriver driver, DouyinCrawler crawler, String deviceId) {
        Map<String, Object> userInfo = new HashMap<>();
        boolean entered = false;
        try {
            ensureActive();
            // Read the nickname before navigating: the comment element is stale on a profile page.
            String commenterName = commentNode.findElement(By.xpath(
                    ".//*[@resource-id='com.ss.android.ugc.aweme:id/title']")).getText().trim();
            MobileElement avatar = commentNode.findElement(By.xpath(
                    ".//*[@resource-id='com.ss.android.ugc.aweme:id/avatar' or contains(@content-desc,'头像')]"));
            if (!avatar.isDisplayed()) return userInfo;
            avatar.click();
            entered = true;
            Thread.sleep(1500);
            if (onCommentPanel()) {
                entered = false;
                return userInfo;
            }
            userInfo = crawler.fetchAccountInfo("2", "2", deviceId, commenterName);
            String userId = Objects.toString(userInfo.get("id"), "");
            if (!userId.isEmpty()) {
                if ("1".equals(type)) {
                    douyinTaskService.storeAccountByAccount(vUid, deviceId, userId, accountName, userInfo);
                } else {
                    douyinTaskService.storeAccountByAddressAccount(vUid, deviceId, userId, accountName, userInfo);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("评论者采集被中断", e);
        } catch (Exception e) {
            if (e instanceof DouyinTaskService.ProfileStorageException) throw (DouyinTaskService.ProfileStorageException) e;
            LOG.log(java.util.logging.Level.WARNING, "评论者资料获取失败 video=" + vUid, e);
        } finally {
            if (entered) {
                // A DB/profile exception must not leave subsequent scrolls on the user's profile.
                driver.navigate().back();
                try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (!onCommentPanel()) throw new IllegalStateException("未能恢复评论区，停止本轮避免误采");
            }
        }
        return userInfo;
    }

    public static int parseDouyinCount(String text) {
        if (text == null) return -1;
        if (text.contains("暂无评论") || text.contains("还没有评论")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|亿|[wWkK])?")
                .matcher(text.replace(",", "").replace("，", ""));
        if (!m.find()) return -1;
        double value = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        if ("万".equals(unit) || "w".equalsIgnoreCase(unit)) value *= 10000;
        else if ("亿".equals(unit)) value *= 100000000;
        else if ("k".equalsIgnoreCase(unit)) value *= 1000;
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private String saveCheckpoint(String videosDir, String uid, String deviceId, int total,
            List<Map<String, Object>> comments, boolean complete) throws IOException {
        java.nio.file.Path dir = java.nio.file.Paths.get(videosDir);
        java.nio.file.Files.createDirectories(dir);
        String name = (deviceId + "_" + uid).replaceAll("[^a-zA-Z0-9_-]", "_");
        java.nio.file.Path path = dir.resolve("comments_" + name + ".json");
        java.nio.file.Path temp = dir.resolve("comments_" + name + ".tmp");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("totalComments", total);
        output.put("comments", comments);
        output.put("complete", complete);
        output.put("json_path", path.toString());
        java.nio.file.Files.writeString(temp, JSON.toJSONString(output), java.nio.charset.StandardCharsets.UTF_8);
        try {
            java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return path.toString();
    }

    public Map<String, Object> fetchAllComments(String type,String accountName,String uid ,String devId ,int videoCount, String videosDir, String count) {
        List<Map<String, Object>> commentsList = new ArrayList<>();
        Set<String> existingComments = new HashSet<>();
        int totalComments = -1;
        boolean opened = false;
        boolean complete = false;
        boolean deferred = false;
        long started = nanoClock.getAsLong();
        String commentsFile = null;

        try {
            java.nio.file.Path previous = java.nio.file.Paths.get(videosDir, "comments_"
                    + (devId + "_" + uid).replaceAll("[^a-zA-Z0-9_-]", "_") + ".json");
            if (java.nio.file.Files.exists(previous)) {
                Map<String, Object> checkpoint = JSON.parseObject(java.nio.file.Files.readString(previous));
                Object saved = checkpoint.get("comments");
                if (saved instanceof List<?>) {
                    for (Object item : (List<?>) saved) {
                        if (!(item instanceof Map<?, ?>)) continue;
                        Map<String, Object> comment = (Map<String, Object>) item;
                        String key = commentKey(Objects.toString(comment.get("source_nickname"), Objects.toString(comment.get("nickname"), "")),
                                Objects.toString(comment.get("text"), ""), Objects.toString(comment.get("time"), ""),
                                Objects.toString(comment.get("replyTo"), ""));
                        if (existingComments.add(key)) commentsList.add(comment);
                        if (commentsList.size() >= 1000) break;
                    }
                }
            }
            //          打开评论区
            // ✅ 新增兜底 XPath：content-desc 含"评论"的入口（排除"放大评论区"）
            String[] commentsBtnXpaths = {
                    "//*[starts-with(@content-desc,'评论') and not(contains(@content-desc,'放大'))]",
                    "//*[@resource-id='com.ss.android.ugc.aweme:id/d+_']",
                    "//*[contains(@resource-id,'comment') and @clickable='true']"};
            for (String xpath : commentsBtnXpaths) {
                List<MobileElement> btns = driver.findElements(By.xpath(xpath));
                if (!btns.isEmpty()) {
                    btns.get(0).click();
                    Thread.sleep(2000);
                    opened = onCommentPanel();
                    if (!opened) continue;
                    System.out.println("成功打开视频 " + (videoCount + 1) + " 的评论区");
                    break;
                }
            }
            if (!opened) {
                System.err.println("无法打开视频 " + (videoCount + 1) + " 的评论区");
                throw new IllegalStateException("评论入口或面板未识别，不能当作零评论");
            }
            try {
                totalComments = parseDouyinCount(count);
                if (totalComments < 0) throw new IllegalArgumentException("未知评论数量");
                System.out.println("视频总评论数: " + totalComments);
            } catch (Exception e) {
                try {
                    // ✅ 新版适配：评论总数节点由 title 改为 e4l（文本如"评论 11"）。
                    //    注意：新版 title 已被评论者昵称占用，不能再用来取总数
                    MobileElement totalCommentsEl = driver.findElement(By.xpath(
                            "//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/e4l']"
                    ));
                    String totalText = totalCommentsEl.getText().trim();

                    totalComments = parseDouyinCount(totalText);
                } catch (Exception e1) {
                    if (!driver.findElements(By.xpath("//*[contains(@text,'暂无评论') or contains(@text,'还没有评论')]")).isEmpty()) {
                        totalComments = 0;
                    } else {
                        LOG.warning("评论数量未知，将按页面实际内容采集 video=" + uid);
                    }
                }
            }

            // 3. 滑动加载评论循环
            int maxScrolls = 500;
            int commentLimit = 1000;
            int scrollCount = 0;

//           开始获取评论
            int noNewCount = 0; // 连续滑动页面未变化计数
            String previousViewport = "";
            while (scrollCount < maxScrolls && commentsList.size() < commentLimit) {
                ensureActive();
                yieldIfWaiting(devId, started);
                boolean hasNewThisScroll = false;
                Set<String> seenRepliesThisScroll = new HashSet<>();

                // --------------------------
                // 1. 抓取主评论（FrameLayout/flq）
                // --------------------------
                // ✅ 新版适配：主评论容器由 e1-xxx 改为 flq（RecyclerView rkp 的直接子级）
                List<MobileElement> commentElements = commentRows();
                StringBuilder viewport = new StringBuilder();
                for (MobileElement row : commentElements) {
                    for (MobileElement content : row.findElements(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/title' or @resource-id='com.ss.android.ugc.aweme:id/content']"))) {
                        viewport.append(content.getText()).append('|');
                    }
                }
                boolean viewportChanged = !previousViewport.equals(viewport.toString());
                previousViewport = viewport.toString();

                for (int rowIndex = 0; rowIndex < commentElements.size() && commentsList.size() < commentLimit; rowIndex++) {
                    ensureActive();
                    yieldIfWaiting(devId, started);
                    List<MobileElement> currentRows = commentRows();
                    if (rowIndex >= currentRows.size()) break;
                    MobileElement el = currentRows.get(rowIndex);
                    String nickname = "", text = "", time = "", replyTo = "";

                    try {
                        nickname = el.findElement(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/title']")).getText().trim();
                    } catch (Exception ignored) {
                    }
                    try {
                        text = el.findElement(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/content']")).getText().trim();
                    } catch (Exception ignored) {
                    }
                    try {
                        // ✅ 新版适配：时间 id 由 egj 改为 e4t
                        time = el.findElement(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/e4t']")).getText().trim();
                    } catch (Exception ignored) {
                    }

                    try {
                        replyTo = el.findElement(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/02w']")).getText().trim();
                    } catch (Exception ignored) { }
                    if (!text.isEmpty()) {
                        String key = commentKey(nickname, text, time, replyTo);


                        if (!existingComments.contains(key)) {
                            DouyinCrawler crawler =
                                    new DouyinCrawler((AndroidDriver<MobileElement>) driver,
                                            accountContentService,
                                            addressAccountContentService,
                                            accountService,douyinTaskService);
                            Map<String, Object> commentMap = new HashMap<>();
                            commentMap.put("id", commentsList.size());
                            commentMap.put("text", text);
                            commentMap.put("source_nickname", nickname);
                            commentMap.put("time", time);
                            commentMap.put("replyTo", replyTo);
                            commentMap.put("observed_at", java.time.Instant.now().toString());

                            // ==============================
                            // ⭐ 点击头像 → 抓用户主页信息
                            // ==============================
                            Map<String, Object> userInfo =
                                    fetchUserFromAvatar(type,accountName,uid,el, (AndroidDriver<MobileElement>) driver, crawler,devId);

                            // 你要的两个字段
                            commentMap.put("nickname", userInfo.getOrDefault("nickname", nickname)); // 用户真实昵称覆盖评论昵称
                            commentMap.put("touxiang", userInfo.getOrDefault("touxiang", ""));

                            // 如果你后续要分析用户，也可以带上
                            commentMap.put("user_uid", userInfo.getOrDefault("id", ""));
                            commentMap.put("fans_count", userInfo.getOrDefault("fans_count", ""));
                            commentMap.put("works_count", userInfo.getOrDefault("works_count", ""));
                            commentMap.put("region", userInfo.getOrDefault("region", ""));
                            commentMap.put("follow_count", userInfo.getOrDefault("follow_count", ""));
                            commentMap.put("likes_count", userInfo.getOrDefault("likes_count", ""));


                            commentMap.put("zhuye", userInfo.getOrDefault("zhuye", ""));
                            commentMap.put("signature", userInfo.getOrDefault("signature", ""));
                            commentMap.put("gender", userInfo.getOrDefault("gender", ""));
                            commentMap.put("age", userInfo.getOrDefault("age", ""));
                            commentMap.put("profile_text", userInfo.getOrDefault("profile_text", ""));
                            commentMap.put("ip_location", userInfo.getOrDefault("ip_location", ""));
                            commentMap.put("real_name", userInfo.getOrDefault("real_name", ""));



                            commentsList.add(commentMap);
                            existingComments.add(key);
                            hasNewThisScroll = true;
                        }

                    }
                }

                // ----------------------------------------
                // 2. 展开回复 & 抓取子回复
                // ----------------------------------------
                boolean hasExpand = true;
                Set<String> clickedExpandNodes = new HashSet<>();

                int expandRounds = 0;
                while (hasExpand && expandRounds++ < 20 && commentsList.size() < commentLimit) {
                    ensureActive();
                    yieldIfWaiting(devId, started);
                    hasExpand = false;

                    try {
                        // ✅ 新版适配：展开按钮 id 3f7 已失效，改为纯文本/desc 匹配（"展开回复"等）
                        String[] EXPAND_REPLY_XPATHS = {
                                "//*[contains(@text,'展开') and contains(@text,'回复')]",
                                "//*[contains(@content-desc,'展开') and contains(@content-desc,'回复')]"
                        };


                        for (String xpath : EXPAND_REPLY_XPATHS) {
                            List<MobileElement> expandBtns = driver.findElements(By.xpath(xpath));

                            for (MobileElement expandBtn : expandBtns) {
                                try {
                                    if (!expandBtn.isDisplayed()) continue;

                                    // 防止重复点击
                                    String pos = expandBtn.getCenter().toString();
                                    if (clickedExpandNodes.contains(pos)) continue;

                                    expandBtn.click();
                                    Thread.sleep(500);

                                    clickedExpandNodes.add(pos);
                                    hasExpand = true;
                                    // A click alone is not evidence of new comments.

                                } catch (Exception ignore2) {
                                }
                            }
                        }


                        String[] FOLDED_REPLY_XPATHS = {

                                // ① 直接匹配文本（不要求 clickable）
                                "//*[contains(@text,'已折叠部分评论')]",

                                // ② 匹配父节点（TextView 的外层 FrameLayout / LinearLayout）
                                "//*[contains(@text,'已折叠部分评论')]/..",

                                // ③ 「展开更多评论」
                                "//*[contains(@text,'展开更多评论')]",

                                // ④ 抖音常见的继续往下按钮
                                "//*[contains(@text,'继续往下')]",
                                "//*[contains(@content-desc,'继续往下')]",
                                "//android.widget.Button[contains(@content-desc,'展开')]"
                        };

                        // ================================
                        // 第二步：处理「已折叠部分评论/继续往下」展开按钮
                        // ================================
                        for (String xpath : FOLDED_REPLY_XPATHS) {
                            List<MobileElement> foldedBtns = driver.findElements(By.xpath(xpath));

                            for (MobileElement btn : foldedBtns) {
                                try {
//                                    // 不可见 → 跳过
//                                    if (!btn.isDisplayed()) continue;
//
//                                    // 真实点击对象应该是可以点击的父节点
//                                    MobileElement clickableNode = btn;
//                                    if (!btn.isEnabled() || !btn.getAttribute("clickable").equals("true")) {
//                                        // 找父节点
//                                        try {
//                                            clickableNode = btn.findElement(By.xpath(".."));
//                                        } catch(Exception ignored) {}
//                                    }
//
//                                    // 父节点仍然不可点击 → 跳过
//                                    if (!clickableNode.getAttribute("clickable").equals("true")) continue;
//
//                                    String pos = clickableNode.getCenter().toString();
//                                    if (clickedExpandNodes.contains(pos)) continue;

                                    // 执行点击
//                                    clickableNode.click();
//                                    Thread.sleep(500);
//
//                                    clickedExpandNodes.add(pos);
//                                    hasExpand = true;
//                                    // A click alone is not evidence of new comments.

                                    if (!btn.isDisplayed()) continue;

                                    // 防止重复点击
                                    String pos = btn.getCenter().toString();
                                    if (clickedExpandNodes.contains(pos)) continue;

                                    btn.click();
                                    Thread.sleep(500);

                                    clickedExpandNodes.add(pos);
                                    hasExpand = true;
                                    // A click alone is not evidence of new comments.

                                } catch (Exception ignore2) {
                                }
                            }
                        }

                        // ================================
                        // 抓取全部子回复（新版与主评论共用 flq 容器）
                        // ================================
                        // ✅ 新版适配：子回复容器 e1 已失效，新版回复与主评论共用 flq 容器；
                        //    取全部 flq 后按 bounds 减去已抓取的主评论，剩余即为展开后的子回复
                        List<MobileElement> replyElements = commentRows();

                        for (int replyIndex = 0; replyIndex < replyElements.size() && commentsList.size() < commentLimit; replyIndex++) {
                            ensureActive();
                            yieldIfWaiting(devId, started);
                            List<MobileElement> currentRows = commentRows();
                            if (replyIndex >= currentRows.size()) break;
                            MobileElement replyEl = currentRows.get(replyIndex);

                            String rNickname = "", rText = "", rTime = "", rReplyTo = "";

                            try {
                                rNickname = replyEl.findElement(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/title']")).getText().trim();
                            } catch (Exception ignored) {
                            }

                            try {
                                rText = replyEl.findElement(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/content']")).getText().trim();
                            } catch (Exception ignored) {
                            }

                            // ✅ 新版页面暂未发现 02w 节点，找不到时保持空字符串，不影响其他字段
                            try {
                                rReplyTo = replyEl.findElement(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/02w']")).getText().trim();
                            } catch (Exception ignored) {
                            }

                            try {
                                // ✅ 新版适配：时间 id 由 egj 改为 e4t
                                rTime = replyEl.findElement(By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/e4t']")).getText().trim();
                            } catch (Exception ignored) {
                            }

                            if (rText.isEmpty()) continue;

                            // 回复的查重逻辑
//                            String key = rNickname + rReplyTo + rText;
//                            String viewPos = replyEl.getCenter().toString();
//                            String key = "REPLY|" + rNickname + "|"  + rText + "|" + viewPos;
// ================= 子评论抓取 =================
                            String tempKey = commentKey(rNickname, rText, rTime, rReplyTo);
                            if (seenRepliesThisScroll.contains(tempKey)
                                    || existingComments.contains(tempKey)) continue;

// ⭐ 先抓用户主页信息
                            DouyinCrawler crawler = new DouyinCrawler((AndroidDriver<MobileElement>) driver,
                                    accountContentService,
                                    addressAccountContentService,
                                    accountService,douyinTaskService);
                            Map<String, Object> userInfo =
                                    fetchUserFromAvatar(type,accountName,uid,replyEl, (AndroidDriver<MobileElement>) driver, crawler,devId);

// 用户唯一ID（核心）
                            String userUid = String.valueOf(userInfo.getOrDefault("id", ""));

// ================= 真正唯一Key =================
                            String key = tempKey;

// 已存在则跳过
                            if (existingComments.contains(key)) continue;

                            Map<String, Object> replyMap = new HashMap<>();
                            replyMap.put("id", commentsList.size());

// 用主页真实昵称覆盖评论区昵称
                            replyMap.put("nickname", userInfo.getOrDefault("nickname", rNickname));
                            replyMap.put("replyTo", rReplyTo);
                            replyMap.put("text", rText);
                            replyMap.put("source_nickname", rNickname);
                            replyMap.put("time", rTime);
                            replyMap.put("observed_at", java.time.Instant.now().toString());

// 头像
                            replyMap.put("touxiang", userInfo.getOrDefault("touxiang", ""));

// 用户信息
                            replyMap.put("user_uid", userUid);
                            replyMap.put("fans_count", userInfo.getOrDefault("fans_count", ""));
                            replyMap.put("works_count", userInfo.getOrDefault("works_count", ""));
                            replyMap.put("region", userInfo.getOrDefault("region", ""));
                            replyMap.put("follow_count", userInfo.getOrDefault("follow_count", ""));
                            replyMap.put("likes_count", userInfo.getOrDefault("likes_count", ""));


                            replyMap.put("zhuye", userInfo.getOrDefault("zhuye", ""));
                            replyMap.put("signature", userInfo.getOrDefault("signature", ""));
                            replyMap.put("gender", userInfo.getOrDefault("gender", ""));
                            replyMap.put("age", userInfo.getOrDefault("age", ""));
                            replyMap.put("profile_text", userInfo.getOrDefault("profile_text", ""));
                            replyMap.put("ip_location", userInfo.getOrDefault("ip_location", ""));
                            replyMap.put("real_name", userInfo.getOrDefault("real_name", ""));



                            commentsList.add(replyMap);
                            existingComments.add(key);
                            seenRepliesThisScroll.add(tempKey);

                            hasNewThisScroll = true;
                        }

                    } catch (Exception e) {
                        if (e instanceof YieldComments || e instanceof DouyinTaskService.ProfileStorageException
                                || Thread.currentThread().isInterrupted() || !onCommentPanel()) throw e;
                        LOG.log(java.util.logging.Level.WARNING, "展开回复失败 video=" + uid, e);
                    }
                }
                commentsFile = saveCheckpoint(videosDir, uid, devId, totalComments, commentsList, false);
                LOG.info("评论进度 video=" + uid + " page=" + scrollCount + " collected=" + commentsList.size() + " expected=" + totalComments);
                if ((totalComments > 0 && commentsList.size() >= totalComments) || (totalComments == 0 && commentRows().isEmpty())) { complete = true; break; }
                noNewCount = hasNewThisScroll || viewportChanged ? 0 : noNewCount + 1;
                if (noNewCount >= 3) {
                    LOG.warning("连续三次页面未变化且无新增评论，停止并保留断点 video=" + uid);
                    break;
                }

                // ---------------------------------------------------
                // 3. 判断是否到底（"暂时没有更多了" 仍可匹配）
                // ---------------------------------------------------
                try {
                    List<MobileElement> endTips = driver.findElements(
                            By.xpath("//*[@text='没有更多了' or @text='暂时没有更多了'][not(@resource-id='com.ss.android.ugc.aweme:id/content')][not(ancestor::*[@resource-id='com.ss.android.ugc.aweme:id/flq'])]")
                    );

                    if (!endTips.isEmpty()) {
                        complete = !commentsList.isEmpty() || totalComments == 0;
                        System.out.println(">>> 已到底部，停止滚动");
                        break;
                    }
                } catch (Exception ignore) {
                }

                // ======================
                // 4. 滑动加载更多
                // ======================
                int startX = driver.manage().window().getSize().width / 2;
                int startY = (int) (driver.manage().window().getSize().height * 0.55);
                int endY = (int) (driver.manage().window().getSize().height * 0.45);

                new TouchAction<>(driver)
                        .press(PointOption.point(startX, startY))
                        .waitAction(WaitOptions.waitOptions(Duration.ofMillis(300)))
                        .moveTo(PointOption.point(startX, endY))
                        .release()
                        .perform();

                Thread.sleep(1500);
                scrollCount++;
            }


            if (commentsList.size() >= commentLimit) {
                LOG.info("达到每视频评论采集上限 " + commentLimit + " video=" + uid);
                complete = true;
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            deferred = e instanceof YieldComments;
            if (deferred) LOG.info("评论本轮达到三分钟，保存断点后让出设备 video=" + uid + " collected=" + commentsList.size());
            else LOG.log(java.util.logging.Level.SEVERE, "评论采集中断 video=" + uid + " collected=" + commentsList.size(), e);
            complete = false;
        } finally {
            try {
                commentsFile = saveCheckpoint(videosDir, uid, devId, totalComments, commentsList, complete);
            } catch (IOException e) {
                complete = false;
                deferred = false;
                LOG.log(java.util.logging.Level.SEVERE, "保存评论断点失败 video=" + uid, e);
            }
            if (opened) {
                try {
                    if (onCommentPanel()) driver.navigate().back();
                    else { complete = false; deferred = false; }
                } catch (Exception e) {
                    complete = false;
                    deferred = false;
                    LOG.log(java.util.logging.Level.WARNING, "退出评论区失败 video=" + uid, e);
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("totalComments", totalComments);
        result.put("comments", commentsList);
        result.put("json_path", commentsFile);
        result.put("complete", complete);
        result.put("deferred", deferred);
        return result;
    }
}

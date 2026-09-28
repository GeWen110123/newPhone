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

import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.text.SimpleDateFormat;
import java.util.*;

public class VideoCommentsFetcher {
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

    // uid → 用户信息缓存
    private final Map<String, Map<String, Object>> userCache = new HashMap<>();

    private Map<String, Object> fetchUserFromAvatar(String type ,String accountName ,String vUid ,MobileElement commentNode,
                                                    AndroidDriver driver,
                                                    DouyinCrawler crawler,
                                                    String deviceId
    ) {
        Map<String, Object> userInfo = new HashMap<>();

        try {
            // ✅ 新版适配：头像节点 resource-id 已改为 avatar（class 为自定义"按钮"），
            //    用 resource-id + content-desc 双条件定位，比只匹配 content-desc 更稳
            MobileElement avatar = commentNode.findElement(
                    By.xpath(".//*[@resource-id='com.ss.android.ugc.aweme:id/avatar' and contains(@content-desc,'头像')]")
            );

            if (avatar != null && avatar.isDisplayed()) {
                avatar.click();
                Thread.sleep(1500); // ✅ 适当加长等待，避免主页未加载完就抓取
            }

            // ✅ 评论者昵称：从评论节点实时读取（新版仍是 title），
            //    替代外层传入的视频作者 accountName，避免抓错主页对象
            String commenterName = "";
            try {
                commenterName = commentNode.findElement(
                        By.xpath(".//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/title']")
                ).getText().trim();
            } catch (Exception ignored) {
            }

            // ⭐⭐⭐ 直接调用你已有的主页抓取方法
            userInfo = crawler.fetchAccountInfo("2", "2", "", commenterName.isEmpty() ? accountName : commenterName);

            String uid = String.valueOf(userInfo.getOrDefault("id", ""));
            String douyinId = (String) userInfo.get("id");
            if (type.equals("1")){
                douyinTaskService.storeAccountByAccount(vUid,deviceId, douyinId , accountName , userInfo);

            }else {
                douyinTaskService.storeAccountByAddressAccount(vUid,deviceId, douyinId , accountName, userInfo);
            }


            // 已抓过该用户 → 用缓存
            if (userCache.containsKey(uid)) {
                driver.navigate().back();
                return userCache.get(uid);
            }

            userCache.put(uid, userInfo);

            driver.navigate().back();
            Thread.sleep(800);

        } catch (Exception ignored) {
        }

        return userInfo;
    }


    public static int parseDouyinCount(String text) {

        if (text == null || text.isEmpty()) return 0;

        text = text.replace("条评论", "").trim();

        // 含 “万”（10.5万 或 3万）
        if (text.contains("万") || text.contains("w") || text.contains("W")) {
            text = text.replace("万", "").replace("w", "").replace("W", "");
            double value = Double.parseDouble(text);
            return (int) (value * 10000);
        }

        // 处理 "9999+"
        if (text.contains("+")) {
            text = text.replace("+", "");
        }

        // 常规数字（"评论 11" → 11）
        return Integer.parseInt(text.replaceAll("\\D+", ""));
    }


    public Map<String, Object> fetchAllComments(String type,String accountName,String uid ,String devId ,int videoCount, String videosDir, String count) {
        List<Map<String, Object>> commentsList = new ArrayList<>();
        Set<String> existingComments = new HashSet<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int totalComments = 0;

        try {
            //          打开评论区
            // ✅ 新增兜底 XPath：content-desc 含"评论"的入口（排除"放大评论区"）
            String[] commentsBtnXpaths = {"//*[contains(@resource-id, 'comment') or contains(@text, '评论')][1]",
                    "//*[@content-desc='评论']",
                    "//*[contains(@content-desc,'评论') and not(contains(@content-desc,'放大'))]"};
            boolean opened = false;
            for (String xpath : commentsBtnXpaths) {
                List<MobileElement> btns = driver.findElements(By.xpath(xpath));
                if (!btns.isEmpty()) {
                    btns.get(0).click();
                    Thread.sleep(2000);
                    opened = true;
                    System.out.println("成功打开视频 " + (videoCount + 1) + " 的评论区");
                    break;
                }
            }
            if (!opened) {
                System.err.println("无法打开视频 " + (videoCount + 1) + " 的评论区");
                return null;
            }
            try {
                totalComments = parseDouyinCount(count);
                if (totalComments > 1000) {
                    totalComments = 1000;
                }
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
                    System.err.println("获取总评论数失败: " + e.getMessage());
                }
            }

            // 3. 滑动加载评论循环
            int estimatedCommentsPerScroll = 2;
            int maxScrolls = (int) Math.ceil(totalComments / (double) estimatedCommentsPerScroll);
            int scrollCount = 0;
            boolean hasNewComments = true;

//           开始获取评论
            int noNewCount = 0; // 连续滑动没有新评论计数
            while (scrollCount < maxScrolls && commentsList.size() < totalComments * 2) {
                boolean hasNewThisScroll = false;
                Set<String> seenRepliesThisScroll = new HashSet<>();

                // --------------------------
                // 1. 抓取主评论（FrameLayout/flq）
                // --------------------------
                // ✅ 新版适配：主评论容器由 e1-xxx 改为 flq（RecyclerView rkp 的直接子级）
                List<MobileElement> commentElements = driver.findElements(By.xpath(
                        "//androidx.recyclerview.widget.RecyclerView[@resource-id='com.ss.android.ugc.aweme:id/rkp']"
                                + "/android.widget.FrameLayout[@resource-id='com.ss.android.ugc.aweme:id/flq']"
                ));
                // ✅ 兜底：部分机型层级有差异导致直接子级匹配为空时，退回全量 flq
                if (commentElements.isEmpty()) {
                    commentElements = driver.findElements(By.xpath(
                            "//android.widget.FrameLayout[@resource-id='com.ss.android.ugc.aweme:id/flq']"
                    ));
                }

                for (MobileElement el : commentElements) {
                    String nickname = "", text = "", time = "";

                    try {
                        nickname = el.findElement(By.xpath(".//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/title']")).getText().trim();
                    } catch (Exception ignored) {
                    }
                    try {
                        text = el.findElement(By.xpath(".//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/content']")).getText().trim();
                    } catch (Exception ignored) {
                    }
                    try {
                        // ✅ 新版适配：时间 id 由 egj 改为 e4t
                        time = el.findElement(By.xpath(".//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/e4t']")).getText().trim();
                    } catch (Exception ignored) {
                    }

                    if (!text.isEmpty()) {
//                        String key = nickname + text;
//                        String viewPos = el.getCenter().toString();
//                        + "|" + viewPos
//                        if (!existingComments.contains(key)) {
//                            Map<String, Object> commentMap = new HashMap<>();
//                            commentMap.put("id", commentsList.size());
//                            commentMap.put("nickname", nickname);
//                            commentMap.put("text", text);
//                            commentMap.put("time", time.isEmpty() ? sdf.format(new Date()) : time);
//
//                            commentsList.add(commentMap);
//                            existingComments.add(key);
//                            hasNewThisScroll = true;
//                        }
                        String key = "REPLY|" + nickname + "|" + text;


                        if (!existingComments.contains(key)) {
                            DouyinCrawler crawler =
                                    new DouyinCrawler((AndroidDriver<MobileElement>) driver,
                                            accountContentService,
                                            addressAccountContentService,
                                            accountService,douyinTaskService);
                            Map<String, Object> commentMap = new HashMap<>();
                            commentMap.put("id", commentsList.size());
                            commentMap.put("text", text);
                            commentMap.put("time", time.isEmpty() ? sdf.format(new Date()) : time);

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

                while (hasExpand) {
                    hasExpand = false;

                    try {
                        // ✅ 新版适配：展开按钮 id 3f7 已失效，改为纯文本/desc 匹配（"展开回复"等）
                        String[] EXPAND_REPLY_XPATHS = {
                                "//android.widget.TextView[contains(@text,'展开')]",
                                "//android.widget.TextView[contains(@content-desc,'展开')]"
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
                                    hasNewThisScroll = true;

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
//                                    hasNewThisScroll = true;

                                    if (!btn.isDisplayed()) continue;

                                    // 防止重复点击
                                    String pos = btn.getCenter().toString();
                                    if (clickedExpandNodes.contains(pos)) continue;

                                    btn.click();
                                    Thread.sleep(500);

                                    clickedExpandNodes.add(pos);
                                    hasExpand = true;
                                    hasNewThisScroll = true;

                                } catch (Exception ignore2) {
                                }
                            }
                        }

                        // ================================
                        // 抓取全部子回复（新版与主评论共用 flq 容器）
                        // ================================
                        // ✅ 新版适配：子回复容器 e1 已失效，新版回复与主评论共用 flq 容器；
                        //    取全部 flq 后按 bounds 减去已抓取的主评论，剩余即为展开后的子回复
                        List<MobileElement> replyElements = driver.findElements(By.xpath(
                                "//android.widget.FrameLayout[@resource-id='com.ss.android.ugc.aweme:id/flq']"
                        ));

                        // 过滤掉本轮已抓取的主评论（按 bounds 去重），避免重复抓取
                        Set<String> mainCommentBounds = new HashSet<>();
                        for (MobileElement ce : commentElements) {
                            mainCommentBounds.add(ce.getAttribute("bounds"));
                        }
                        replyElements.removeIf(re -> mainCommentBounds.contains(re.getAttribute("bounds")));

                        for (MobileElement replyEl : replyElements) {

                            String rNickname = "", rText = "", rTime = "", rReplyTo = "";

                            try {
                                rNickname = replyEl.findElement(By.xpath(".//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/title']")).getText().trim();
                            } catch (Exception ignored) {
                            }

                            try {
                                rText = replyEl.findElement(By.xpath(".//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/content']")).getText().trim();
                            } catch (Exception ignored) {
                            }

                            // ✅ 新版页面暂未发现 02w 节点，找不到时保持空字符串，不影响其他字段
                            try {
                                rReplyTo = replyEl.findElement(By.xpath(".//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/02w']")).getText().trim();
                            } catch (Exception ignored) {
                            }

                            try {
                                // ✅ 新版适配：时间 id 由 egj 改为 e4t
                                rTime = replyEl.findElement(By.xpath(".//android.widget.TextView[@resource-id='com.ss.android.ugc.aweme:id/e4t']")).getText().trim();
                            } catch (Exception ignored) {
                            }

                            if (rText.isEmpty()) continue;

                            // 回复的查重逻辑
//                            String key = rNickname + rReplyTo + rText;
//                            String viewPos = replyEl.getCenter().toString();
//                            String key = "REPLY|" + rNickname + "|"  + rText + "|" + viewPos;
// ================= 子评论抓取 =================
                            String tempKey = "REPLY_TMP|" + rNickname + "|" + rText;
                            if (seenRepliesThisScroll.contains(tempKey)) continue;

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
                            String key = "REPLY|" + userUid + "|" + rText;

// 已存在则跳过
                            if (existingComments.contains(key)) continue;

                            Map<String, Object> replyMap = new HashMap<>();
                            replyMap.put("id", commentsList.size());

// 用主页真实昵称覆盖评论区昵称
                            replyMap.put("nickname", userInfo.getOrDefault("nickname", rNickname));
                            replyMap.put("replyTo", rReplyTo);
                            replyMap.put("text", rText);
                            replyMap.put("time", rTime.isEmpty() ? sdf.format(new Date()) : rTime);

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

                    } catch (Exception ignored) {
                    }
                }

                // ---------------------------------------------------
                // 3. 判断是否到底（"暂时没有更多了" 仍可匹配）
                // ---------------------------------------------------
                try {
                    List<MobileElement> endTips = driver.findElements(
                            By.xpath("//android.widget.TextView[contains(@text,'没有更多') or contains(@text,'暂时没有更多了')]")
                    );

                    if (!endTips.isEmpty()) {
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


            // 生成 JSON 文件
            String commentsFile = null;
            if (!commentsList.isEmpty()) {
                String timestamp = String.valueOf(System.currentTimeMillis());
                commentsFile = videosDir + "/video_" + timestamp + videoCount + "_comments.json";

                try (FileWriter file = new FileWriter(commentsFile)) {
                    Map<String, Object> output = new HashMap<>();
                    output.put("totalComments", totalComments);
                    output.put("comments", commentsList);
                    output.put("json_path", commentsFile);

                    file.write(JSON.toJSONString(output, true));
                    System.out.println("评论信息已保存至: " + commentsFile);
                } catch (IOException e) {
                    System.err.println("保存评论时出错: " + e.getMessage());
                }
            }

            // 返回完整结构
            Map<String, Object> result = new HashMap<>();
            result.put("totalComments", totalComments);
            result.put("comments", commentsList);
            result.put("json_path", commentsFile);

            // ---- 退出评论区，返回视频页面 ----
            try {
                System.out.println("⏳ 正在退出评论区返回视频界面...");

                // 连续按两次返回键，确保退出所有评论层
                driver.navigate().back();
                Thread.sleep(800);

                System.out.println("✅ 已成功回到视频页面");
            } catch (Exception e) {
                System.err.println("⚠ 返回视频页面失败：" + e.getMessage());
            }

            return result;

        } catch (Exception e) {
            System.err.println("获取评论时出错: " + e.getMessage());
        }

        // 发生异常仍返回结构
        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("totalComments", totalComments);
        errorResult.put("comments", commentsList);
        errorResult.put("json_path", null);

        return errorResult;
    }


}

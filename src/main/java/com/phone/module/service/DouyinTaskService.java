package com.phone.module.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phone.adb.AndroidCollector;
import com.phone.adb.DouyinCrawler;
import com.phone.common.utils.StringUtils;
import com.phone.module.domain.*;
import io.appium.java_client.MobileElement;
import io.appium.java_client.android.AndroidDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Service
public class DouyinTaskService {
    public static class ProfileStorageException extends RuntimeException {
        public ProfileStorageException(String message, Throwable cause) { super(message, cause); }
    }

    private static final Logger logger = Logger.getLogger(DouyinTaskService.class.getName());

    @Autowired
    private IAccountService accountService;
    @Autowired
    private DeviceLeaseService deviceLeases;
    @Autowired
    @Lazy
    private DouyinTaskService douyinTaskService;

    @Autowired
    private IAccountContentService accountContentService;
    @Autowired
    private IAddressAccountContentService addressAccountContentService;
    @Autowired
    private IVideoService videoService;
    @Autowired
    private IVideoTagsService videoTagsService;
    @Autowired
    private IAccountIpService accountIpService;


    // =====================================================
    // ⭐ 改写 runTask —— 自动检测设备在线和授权状态
    // =====================================================
    @Async
    public void runTask(String devId, String accountName) {

        String lockKey = DeviceLeaseService.key(devId);
        String token = deviceLeases.acquire(lockKey);
        if (token == null) {
            logger.warning("设备占用中: " + devId);
            return;
        }
        Map<String, Object> result = new HashMap<>();
        AndroidDriver<MobileElement> driver = null;

        try (AutoCloseable lease = deviceLeases.keepAlive(lockKey, token)) {
            driver = AndroidCollector.current().connect(devId);

            DouyinCrawler crawler = new DouyinCrawler(driver,
                    accountContentService,
                    addressAccountContentService,
                    accountService, douyinTaskService);

            if (!crawler.startDouyin()) {
                logger.warning("启动抖音失败: " + accountName);
            } else if (!crawler.searchAndEnterAccount(accountName)) {
                logger.warning("进入账号失败: " + accountName);
            } else {
                result = crawler.fetchAccountInfo("1","1", devId, accountName);
            }
            // 写入数据库
            storeAccountAsync(devId, accountName, result, "");

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.log(java.util.logging.Level.SEVERE, "爬取账号信息失败 device=" + devId, e);
        } finally {
            try { closeDriverQuietly(driver); } finally { deviceLeases.release(lockKey, token); }
        }


    }

    private void closeDriverQuietly(AndroidDriver<MobileElement> driver) {
        if (driver == null) {
            return;
        }

        try {
            driver.quit();
        } catch (Exception e) {
            logger.warning("关闭驱动时异常：" + e.getMessage());
        }
    }


    // =====================================================
    // 抖音账号信息对象 数据库写入（异步）
    // =====================================================
    @Transactional(rollbackFor = Exception.class)
    public void storeAccountAsync(String devId, String accountName, Map<String, Object> resultMap, String tags) {
        if (resultMap == null || resultMap.isEmpty() || StringUtils.isBlank(accountName)) {
            throw new IllegalArgumentException("未读取到有效账号信息，不能写入或创建任务");
        }
        String options = tags == null ? "" : tags;
        if (options.contains("基本信息") && !options.contains("地址") && !options.contains("附近")
                && !options.contains("综合视频") && !accountName.equalsIgnoreCase(String.valueOf(resultMap.get("id")))) {
            throw new IllegalArgumentException("页面抖音号与任务账号不一致: " + accountName);
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(resultMap);

            Account account = new Account();
            account.setDouyinId(accountName);
            List<Account> list = accountService.selectAccountList(account);

            if (list.size() > 0) {
                account = list.get(0);
                account.setJsonString(json);
                account.setUpdateTime(new Date());
                accountService.updateAccount(account);

            } else {
                account.setDevId(devId);
                account.setJsonString(json);
                account.setCreateTime(new Date());


// ⭐ 把视频数量放进去（字段名你自己定）
                resultMap = new HashMap<>(resultMap);
                resultMap.put("video_count", videoService.countVideos(accountName));
// Map → JSON 字符串
                String newJson = mapper.writeValueAsString(resultMap);
// 如果需要回存
                account.setOldString(newJson); //存储上次获取到的信息
                account.setOldTime(account.getUpdateTime()); //存储上次获取到的时间


                accountService.insertAccount(account);

            }

            enqueuePublisher(devId, accountName, resultMap, tags);



//          用户归属地单独表存储
            AccountIp ip = new AccountIp();
            ip.setDouyinId(accountName);
            String regionRaw = (String) resultMap.get("region");

            String region = "";
            if (regionRaw != null && !regionRaw.trim().isEmpty()) {
                region = regionRaw.replace("IP：", "").replace("IP:", "").trim();
            }

            ip.setIpAddress(region);
            if (!region.isEmpty() && accountIpService.selectAccountIpList(ip).isEmpty()) {
                accountIpService.insertAccountIp(ip);
            }

            logger.info("账号数据写入成功: " + accountName);

        } catch (Exception ex) {
            throw new IllegalStateException("账号及发布者任务写入失败: " + accountName, ex);
        }
    }

    void enqueuePublisher(String devId, String accountName, Map<String, Object> info, String tags) {
        String options = tags == null ? "" : tags;
        if (!options.contains("单用户前六条视频") && !options.contains("单用户所有视频")) return;
        String id = String.valueOf(info.getOrDefault("id", ""));
        if (!id.equals(accountName) || !com.phone.adb.AccountInfoFetcher.isRealDouyinId(id)) {
            throw new IllegalArgumentException("发布者抖音号未确认: " + id);
        }
        VideoTags query = new VideoTags();
        query.setDevId(devId);
        query.setDouyinId(id);
        query.setTaskId(3L);
        List<VideoTags> existing = videoTagsService.selectVideoTagsList(query);
        VideoTags task = existing.isEmpty() ? new VideoTags() : existing.get(0);
        // A six-video request must not downgrade an existing all-video task.
        boolean all = options.contains("单用户所有视频") || (!existing.isEmpty()
                && task.getTags() != null && !task.getTags().contains("前六条"));
        String desired = "视频,基本信息,评论" + (all ? "" : ",前六条");
        boolean upgraded = !desired.equals(task.getTags());
        int expected = DouyinVideoService.getUserWorksCount(com.alibaba.fastjson.JSON.toJSONString(info));
        if (!all && expected > 0) expected = Math.min(expected, 6);
        boolean incomplete = expected <= 0 || videoService.countVideos(id) < expected;
        if (existing.isEmpty()) {
            task.setDevId(devId);
            task.setDouyinId(id);
            task.setTaskId(3L);
            task.setTags(desired);
            task.setStatus("0");
            task.setRunNum(0L);
            task.setUpdateTime(new Date());
            videoTagsService.insertVideoTags(task);
            logger.info("发布者任务已入队: " + id);
        } else if (!"1".equals(task.getStatus()) && (upgraded || (incomplete && "2".equals(task.getStatus())))) {
            task.setTags(desired);
            task.setStatus("0");
            task.setRunNum(0L);
            videoTagsService.updateVideoTags(task);
            logger.info("发布者历史任务缺少数据，已重新入队: " + id);
        }
    }

    public boolean hasWaitingTasks(String devId) {
        VideoTags query = new VideoTags();
        query.setDevId(devId);
        query.setStatus("0");
        return !videoTagsService.selectVideoTagsList(query).isEmpty();
    }


    // =====================================================
    // 视频评论账号信息对象 数据库写入（异步）
    // =====================================================
    public void storeAccountByAccount(String uid, String devId, String douyinId, String accountName, Map<String, Object> resultMap) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(resultMap);

            AccountContent account = new AccountContent();
            account.setDevId(devId);
            account.setDouyinId(douyinId);
            account.setAccountName(accountName);

            account.setvUid(uid);
            List<AccountContent> list = accountContentService.selectAccountContentList(account);

            if (list.size() > 0) {
                account = list.get(0);
                account.setJsonString(json);
                account.setCount((account.getCount() == null ? 0L : account.getCount()) + 1);
                account.setUpdateTime(new Date());
                accountContentService.updateAccountContent(account);
            } else {
                account.setJsonString(json);
                account.setCount(1l);
                account.setCreateTime(new Date());
                accountContentService.insertAccountContent(account);

            }

            logger.info("账号数据写入成功: " + accountName);

        } catch (Exception ex) {
            throw new ProfileStorageException("评论者资料写入失败: " + douyinId, ex);
        }
    }


    // =====================================================
    // 地址评论账号信息对象 数据库写入（异步）
    // =====================================================
    public void storeAccountByAddressAccount(String uid, String devId, String douyinId, String accountName, Map<String, Object> resultMap) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(resultMap);

            AddressAccountContent account = new AddressAccountContent();
            account.setDevId(devId);
            account.setDouyinId(douyinId);
            account.setAccountName(accountName);
            account.setaVId(uid);
            List<AddressAccountContent> list = addressAccountContentService.selectAddressAccountContentList(account);

            if (list.size() > 0) {
                account = list.get(0);
                account.setJsonString(json);
                account.setCount((account.getCount() == null ? 0L : account.getCount()) + 1);
                account.setUpdateTime(new Date());
                addressAccountContentService.updateAddressAccountContent(account);
            } else {
                account.setJsonString(json);
                account.setCount(1l);
                account.setCreateTime(new Date());
                addressAccountContentService.insertAddressAccountContent(account);

            }

            logger.info("账号数据写入成功: " + accountName);

        } catch (Exception ex) {
            throw new ProfileStorageException("评论者资料写入失败: " + douyinId, ex);
        }
    }


    // =====================================================
    // 视频评论账号信息对象 数据库写入（异步）   主页点击查看到的关注人员
    // =====================================================
    public void storeAccountByFollow(String uid, String devId, String douyinId, String accountName, Map<String, Object> resultMap) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(resultMap);

            AccountContent account = new AccountContent();
            account.setDevId(devId);
            account.setDouyinId(douyinId);//查找的抖音id
            account.setAccountName(douyinId);//查找的抖音id
            account.setvUid(accountName);//关注人员id

            account.setJsonString(json);
            account.setCreateTime(new Date());
            accountContentService.insertFollow(account);

            logger.info("账号数据写入成功: " + accountName);

        } catch (Exception ex) {
            logger.severe("DB 写入失败: " + ex.getMessage());
        }
    }

    public void storeAccountByFans(String uid, String devId, String douyinId, String accountName, Map<String, Object> resultMap) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(resultMap);

            AccountContent account = new AccountContent();
            account.setDevId(devId);
            account.setDouyinId(douyinId);//查找的抖音id
            account.setAccountName(douyinId);//查找的抖音id
            account.setvUid(accountName);//关注人员id

            account.setJsonString(json);
            account.setCreateTime(new Date());
            accountContentService.insertFans(account);

            logger.info("账号数据写入成功: " + accountName);

        } catch (Exception ex) {
            logger.severe("DB 写入失败: " + ex.getMessage());
        }
    }


}

package com.phone;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phone.common.core.redis.RedisCache;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.phone.common.utils.StringUtils;
import com.phone.module.domain.*;
import com.phone.module.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.annotation.PostConstruct;


@EnableScheduling
@Configuration
@ConditionalOnProperty(prefix = "phone", name = "scheduler-enabled", havingValue = "true")
public class ScheduledTask {
    @Autowired
    private IStrategyTemplateService strategyTemplateService;
    @Autowired
    private IStrategyLogService strategyLogService;
    @Autowired
    private IDeviceService deviceService;
    @Autowired
    private IVideoTagsService videoTagsService;
    @Autowired
    private DouyinVideoService douyinVideoService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private DeviceLeaseService deviceLeases;
    @Autowired
    private IAccountService accountService;
    @Autowired
    private IVideoService videoService;
    @Autowired
    private IRunLogService runLogService;
    @Autowired
    private ICommentService commentService;
    @Autowired
    private CommentArchiveService commentArchiveService;
    @Autowired
    private IAddressVideoService addressVideoService;

    /**
     * 策略执行表
     */
    @Scheduled(cron = "0 0 0 * * ?")
    private void configureTasks() {
        StrategyTemplate strategyTemplate = strategyTemplateService.selectStrategyTemplateById(1l);
        Set<String> devIdList = new HashSet<>();
        if (StringUtils.isNotEmpty(strategyTemplate.getAllDevIds())) {
            devIdList.addAll(Arrays.asList(strategyTemplate.getAllDevIds().split(",")));
        }

        // 将 List<String> 转换为 List<Long>
        List<Long> devIdLongList = devIdList.stream()
                .map(devId -> Long.parseLong(devId.trim()))  // 转换为 Long
                .collect(Collectors.toList());

        // 遍历 List<Long> 执行操作
        for (Long devId : devIdLongList) {
            // 处理 Long 类型的 devId
            System.out.println("设备 ID: " + devId);


            // 处理 Long 类型的 devId
            Device device = deviceService.selectDeviceById(devId);
            System.out.println("设备 ID: " + devId);
            StrategyLog taskLog = new StrategyLog();
            taskLog.setExecuteTime(new Date());
            taskLog.setStrategyId(strategyTemplate.getId());
            taskLog.setTargetId(devId);
            taskLog.setStatus("success");//为返回结果数据
            taskLog.setLogDetail("设备" + device.getDeviceName() + "进行:" + strategyTemplate.getStrategyName() + "任务操作");

            strategyLogService.insertStrategyLog(taskLog);

        }
    }


    /**
     * 视频执行表
     */
    @Scheduled(cron = "0 */1 * * * ?")
    private void douyinTasks() throws JsonProcessingException {
        recoverAbnormalTasks();
        VideoTags videoTags = new VideoTags();
        videoTags.setStatus("0");
        List<VideoTags> list = videoTagsService.selectVideoTagsListASC(videoTags);

        for (VideoTags tags : list) {
            if (!"0".equals(tags.getStatus())) {
                continue;
            }
            if (tags.getRunNum() != null && tags.getRunNum() >= 30) {
                // Retry exhaustion is a failure, not successful completion.
                tags.setStatus("3");
                videoTagsService.updateVideoTags(tags);
                continue;
            }

            String devId = tags.getDevId();
            String lockKey = "douyin:device:lock:" + devId;
            String lockValue;

            try {
                // 🔒 只做一次 Redis 原子操作
                lockValue = deviceLeases.acquire(lockKey);

            } catch (Exception e) {
                // Redis 异常不能影响调度线程
                System.out.println("⚠ Redis异常，跳过设备: " + devId);
                continue;
            }

            if (lockValue == null) {
                System.out.println("⛔ 设备占用中: " + devId);
                continue;   // ❗ 不能 return
            }

            System.out.println("🚀 设备锁成功，开始任务 tagsId=" + tags.getId());
            System.out.println("🔒 已使用 RedisTemplate 对设备加锁：" + devId);


            try {
                // Persist before dispatch: a fast worker must not be overwritten as running.
                tags.setStatus("1");
                tags.setRunNum((tags.getRunNum() == null ? 0L : tags.getRunNum()) + 1);
                if (tags.getTaskId() == null) tags.setTaskId(3L);
                videoTagsService.updateVideoTags(tags);
                RunLog runLog = new RunLog();
                runLog.setDevId(devId);
                runLog.setDouyinId(tags.getDouyinId());
                runLog.setType("启动抖音获取数据");
                runLog.setRuningDetail("启动抖音获取" + tags.getDouyinId() + "数据");
                runLogService.insertRunLog(runLog);
                douyinVideoService.asyncCrawlTask(devId, tags.getDouyinId(), tags, lockKey, lockValue);
            } catch (Exception e) {
                try {
                    tags.setStatus("3");
                    videoTagsService.updateVideoTags(tags);
                } finally {
                    deviceLeases.release(lockKey, lockValue);
                }
                continue;
            }
            // Cache refresh must not release a lease already handed to a worker.
            try {
                updateAccountCache(tags.getDevId(), tags.getDouyinId());
            } catch (Exception e) {
                java.util.logging.Logger.getLogger(ScheduledTask.class.getName()).log(
                        java.util.logging.Level.WARNING, "更新账号缓存失败 task=" + tags.getId(), e);
            }

        }

        // =============================
        // ⭐ 统一兜底机制（循环外）
        // =============================
        recoverAbnormalTasks();
    }

    /**
     * 无可执行任务，恢复异常任务为待执行
     */
    private void recoverAbnormalTasks() {

        List<VideoTags> listStatus = videoTagsService.selectVideoTagsList(new VideoTags());

        // A stopped JVM cannot update status; reclaim only after its device lease expires.
        for (VideoTags task : listStatus) {
            if ("1".equals(task.getStatus())
                    && Boolean.FALSE.equals(redisTemplate.hasKey(DeviceLeaseService.key(task.getDevId())))) {
                task.setStatus(task.getRunNum() != null && task.getRunNum() >= 30 ? "3" : "0");
                videoTagsService.updateVideoTags(task);
            }
        }
        // Other long-running address tasks must not permanently starve a failed publisher.
        long retryBefore = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5);
        for (VideoTags task : listStatus) {
            if ("3".equals(task.getStatus()) && (task.getRunNum() == null || task.getRunNum() < 30)
                    && (task.getUpdateTime() == null || task.getUpdateTime().getTime() <= retryBefore)) {
                task.setStatus("0");
                videoTagsService.updateVideoTags(task);
            }
        }
    }

    /**
     * 回存 把视频数量放进去
     */
    private void updateAccountCache(String devId, String douyinId) throws JsonProcessingException {


        Account account = new Account();
        account.setDevId(devId);
        account.setDouyinId(douyinId);
        List<Account> listAcc = accountService.selectAccountList(account);

        if (listAcc.size() > 0) {
            account = listAcc.get(0);
            ObjectMapper mapper = new ObjectMapper();

            Map<String, Object> map = mapper.readValue(
                    account.getJsonString(),
                    new TypeReference<Map<String, Object>>() {
                    }
            );
// 把视频数量放进去
            map.put("video_count", videoService.countVideos(douyinId));
// Map → JSON 字符串
            String newJson = mapper.writeValueAsString(map);
// 如果需要回存
            account.setOldString(newJson); //存储上次获取到的信息
            account.setOldTime(account.getUpdateTime()); //存储上次获取到的时间
            accountService.updateAccount(account);
        }
    }


    // Defer repair until the application is ready; never block web/server startup.
    @Scheduled(initialDelay = 30000, fixedDelay = 1800000)
    public void runOnStartup() throws JsonProcessingException {
        getDouyinidToComment();
    }

    // 30分钟执行一次
    @Scheduled(cron = "0 */30 * * * ?")
    public void getJson() throws JsonProcessingException {
        getJsonToMysql();
    }

    /**
     * 视频执行表
     */
    private void getDouyinidToComment() throws JsonProcessingException {
        long cursor = 0;
        for (int batch = 0; batch < 100; batch++) {
            Comment query = new Comment();
            query.setId(cursor);
            List<Comment> list = commentService.selectCommentList200(query);

            if (list == null || list.size() == 0) {
                break;
            }

            for (Comment c : list) {
                cursor = Math.max(cursor, c.getId());
                Video v = videoService.selectVideoByUId(c.getUid());

                if (StringUtils.isNotNull(v)) {
                    c.setUserDouyin(v.getDouyinId());
                } else {
                    AddressVideo av = addressVideoService.selectAddressVideoByUid(c.getUid());
                    if (StringUtils.isNotNull(av)) {
                        c.setUserDouyin(av.getDouyinId());
                    }
                }
                if (StringUtils.isNotEmpty(c.getUserDouyin())) commentService.updateComment(c);
            }
        }

    }

    private void getJsonToMysql() {
        // Bound each maintenance pass; failures keep their original raw JSON for retry.
        for (int batch = 0; batch < 100; batch++) {
            List<Video> list = videoService.selectVideoList200(new Video());
            if (list == null || list.isEmpty()) break;
            boolean progress = false;
            for (Video video : list) {
                try { progress |= commentArchiveService.archive(video); }
                catch (Exception e) { archiveFailure(video.getUid(), e); }
            }
            if (!progress) break;
        }
        for (int batch = 0; batch < 100; batch++) {
            List<AddressVideo> list = addressVideoService.selectAddressVideoList200(new AddressVideo());
            if (list == null || list.isEmpty()) break;
            boolean progress = false;
            for (AddressVideo video : list) {
                try { progress |= commentArchiveService.archive(video); }
                catch (Exception e) { archiveFailure(video.getUid(), e); }
            }
            if (!progress) break;
        }
    }

    private void archiveFailure(String uid, Exception e) {
        java.util.logging.Logger.getLogger(ScheduledTask.class.getName()).log(
                java.util.logging.Level.WARNING, "评论转存失败，原数据已保留 uid=" + uid, e);
    }
}

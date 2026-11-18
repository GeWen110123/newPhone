package com.phone.module.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A device lease belongs to one worker; renewal/release must compare its token. */
@Service
public class DeviceLeaseService {
    private static final Logger LOG = Logger.getLogger(DeviceLeaseService.class.getName());
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('expire',KEYS[1],300) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    private final RedisTemplate<Object, Object> redis;

    public DeviceLeaseService(RedisTemplate<Object, Object> redis) { this.redis = redis; }

    public static String key(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) throw new IllegalArgumentException("设备 ID 不能为空");
        return "douyin:device:lock:" + deviceId;
    }

    public String acquire(String key) {
        String token = UUID.randomUUID().toString();
        // Renew while alive; an abruptly stopped JVM must not block this device for two hours.
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, 5, TimeUnit.MINUTES)) ? token : null;
    }

    public boolean renew(String key, String token) {
        return token != null && Long.valueOf(1).equals(redis.execute(RENEW, Collections.singletonList(key), token));
    }

    public void release(String key, String token) {
        if (token != null) redis.execute(RELEASE, Collections.singletonList(key), token);
    }

    public AutoCloseable keepAlive(String key, String token) {
        if (!renew(key, token)) throw new IllegalStateException("设备锁已失效，取消采集: " + key);
        Thread worker = Thread.currentThread();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "device-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                if (!renew(key, token)) {
                    LOG.severe("设备锁所有权丢失，停止当前采集: " + key);
                    worker.interrupt();
                    timer.shutdown();
                }
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "设备锁续期失败，停止当前采集: " + key, e);
                worker.interrupt();
                timer.shutdown();
            }
        }, 1, 1, TimeUnit.MINUTES);
        return () -> {
            timer.shutdownNow();
            timer.awaitTermination(10, TimeUnit.SECONDS);
        };
    }
}

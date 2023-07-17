package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author zrc
 * @date 2023/07/11
 * 每天一个key，可以方便统计每天的订单量
 * 构造方式；时间戳 + 计数器
 * 还可以尝试采用雪花算法写ID
 */
@Component
public class RedisID {
    /**
     * 开始时间戳（从2022/1/1 0：0：0开始）
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;
    @Autowired
    private StringRedisTemplate redisTemplate;

    public long nextId(String keyPrefix){
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowS = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowS - BEGIN_TIMESTAMP;

        // 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 将时间戳和序列号拼接，生成全局ID
        return timestamp << COUNT_BITS | count;
    }

}

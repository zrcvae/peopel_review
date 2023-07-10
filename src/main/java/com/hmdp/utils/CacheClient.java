package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author zrc
 * @date 2023/07/10
 * 封装Redis工具类
 */
@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate redisTemplate;
    // 创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 尝试获取锁的方法
    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 释放互斥锁锁
    private void unlock(String key){
        redisTemplate.delete(key);
    }

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 将任意java对象序列化为json并储存在String类型key中，设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 将任意java对象序列化为json并储存在String类型key中，设置逻辑过期时间，同于处理缓存击穿
    public void setLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 根据指定的key查询缓存，反序列化指定类型，利用缓存控制解决缓存穿透问题
    public <R, ID> R queryPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        // 从redis中根据id查询商铺信息
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        // 经过上面的判断，如果不为null，一定为空值，直接返回错误信息
        if (json != null){
            return null;
        }
        // 如果不存在，从数据库中查询
        R r = dbFallback.apply(id);
        // 判断数据库中查询结果是否存在
        if (r == null){
            // 缓存空对象，防止缓存击穿(失效时间设置短一些)
            redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 如果不存返回错误信息，如果存在将查询信息存入redis中,设置失效时间
        this.setLogicalExpire(key, r, time, unit);
        return r;
    }

    // 根据指定key查询缓存，反序列化指定类型，利用逻辑过期解决缓存击穿问题
    public <R, ID> R queryLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        // 从redis中根据id查询商铺信息
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(json)){
            // 如果为空直接返回
            return null;
        }
        // 反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期,如果没有过期直接返回shop
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        // 过期进行缓存重建，首先判断是否可以获取互斥锁，如果没法获取，直接返回过期数据
        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        if (flag){
            // 开辟新线程，进行缓存重建(查询数据库，写入Redis)
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 这里过期时间普遍设置为30分钟
                    R r1 = dbFallback.apply(id);
                    this.setLogicalExpire(key, r1, time, unit);
                } catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        return r;
    }
}

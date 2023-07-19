package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.concurrent.TimeUnit;

/**
 * @author zrc
 * @date 2023/07/18
 */
public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    // 配置lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SRCIPT;
    static {
        UNLOCK_SRCIPT = new DefaultRedisScript<>();
        UNLOCK_SRCIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SRCIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    // 使用UUID避免线程名重复
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 这里如果直接返回success会有一个拆箱的过程，如果success会出现空指针异常
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 判断当前线程标识和锁的标识是否相同，如果相同就释放，不相同不做操作
        // 获取Redis中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 当前线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        if (threadId.equals(id)){
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }

        /**
         * 可以通过调用lua脚本的方式释放锁，不会出现识别后线程阻塞的问题
         */
//        stringRedisTemplate.execute(UNLOCK_SRCIPT,
//                Collections.singletonList(KEY_PREFIX + name),
//                ID_PREFIX + Thread.currentThread().getId());
    }
}

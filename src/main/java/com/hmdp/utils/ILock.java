package com.hmdp.utils;

/**
 * @author zrc
 * @date 2023/07/18
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec
     * @return true表示获取锁成功，false表示获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}

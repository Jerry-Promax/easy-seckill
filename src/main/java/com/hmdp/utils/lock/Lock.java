package com.hmdp.utils.lock;

/**
 * @author ghp
 * @title
 * @description
 */
public interface Lock {
    /**
     * 获取锁
     *
     * @param timeout 超时时间
     * @return
     */
    boolean tryLock(long timeout);

    /**
     * 释放锁
     */
    void unlock();
}

package com.hmdp.lock;

import com.hmdp.utils.lock.impl.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

/**
 * @title
 * @description
 */
@SpringBootTest
@Slf4j
public class ReentrantLockTest {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ReentrantLock lock;

    /**
     * 方法1获取一次锁
     */
    @Test
    void method1() {
        boolean isLock = false;
        // 创建锁对象
        lock = new ReentrantLock(stringRedisTemplate, "order:" + 1);
        try {
            isLock = lock.tryLock(1200);
            if (!isLock) {
                log.error("获取锁失败，1");
                return;
            }
            log.info("获取锁成功，1");
            method2();
        } finally {
            if (isLock) {
                log.info("释放锁，1");
                lock.unlock();
            }
        }
    }

    /**
     * 方法二再获取一次锁
     */
    void method2() {
        boolean isLock = false;
        try {
            isLock = lock.tryLock(1200);
            if (!isLock) {
                log.error("获取锁失败, 2");
                return;
            }
            log.info("获取锁成功，2");
        } finally {
            if (isLock) {
                log.info("释放锁，2");
                lock.unlock();
            }
        }
    }
}

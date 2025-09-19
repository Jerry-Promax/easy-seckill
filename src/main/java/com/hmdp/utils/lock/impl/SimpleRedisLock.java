package com.hmdp.utils.lock.impl;

import cn.hutool.core.lang.UUID;
import com.hmdp.utils.lock.Lock;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author ghp
 * @title 简单锁
 * @description
 */
public class SimpleRedisLock implements Lock {

    /**
     * RedisTemplate
     */
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 锁的名称
     */
    private String name;
    /**
     * key前缀
     */
    private static final String KEY_PREFIX = "lock:";
    /**
     * ID前缀
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }


    /**
     * 获取锁
     *
     * @param timeoutSec 超时时间
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId() + "";
        // SET lock:name id EX timeoutSec NX
        Boolean result = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 加载Lua脚本
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        // 执行lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
    /**
     * 自己写的
     *
     */
//    private StringRedisTemplate stringRedisTemplate;
//
//    private String name;

//    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
//        this.stringRedisTemplate = stringRedisTemplate;
//        this.name = name;
//    }

//    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
//    private static final String KEY_PREFIX = "lock:";
//    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
//    static {
//        UNLOCK_SCRIPT = new DefaultRedisScript<>();
//        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock.lua"));
//        UNLOCK_SCRIPT.setResultType(Long.class);
//    }
//    @Override
//    public boolean tryLock(long timeout) {
//        String id = ID_PREFIX+Thread.currentThread().getId();
//        // 获取锁
//        Boolean success = stringRedisTemplate.opsForValue()
//                .setIfAbsent(KEY_PREFIX+name,id+" ",timeout,TimeUnit.SECONDS);
//
//        return Boolean.TRUE.equals(success);
//    }

//    @Override
//    public void unlock() {
////        // 获取线程标识
////        String id = ID_PREFIX+Thread.currentThread().getId();
////        // 获取锁中的标识
////        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
////        // id一致才可以释放锁，为了避免线程删了其他线程的锁
////        if (id.equals(lockId)) {
////            stringRedisTemplate.delete(KEY_PREFIX + name);
////        }
//        stringRedisTemplate.execute(UNLOCK_SCRIPT,Collections.singletonList(KEY_PREFIX+name),ID_PREFIX+Thread.currentThread().getId());
//    }
}

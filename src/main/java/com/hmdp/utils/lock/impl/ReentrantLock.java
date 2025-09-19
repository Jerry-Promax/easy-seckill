package com.hmdp.utils.lock.impl;

import cn.hutool.core.lang.UUID;
import com.hmdp.utils.lock.Lock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

/**
 * @author ghp
 * @title 可重入锁
 * @description
 */
public class ReentrantLock implements Lock {


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
    /**
     * 锁的有效期
     */
    public long timeoutSec;

    public ReentrantLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 加载获取锁的Lua脚本
     */
    private static final DefaultRedisScript<Long> TRYLOCK_SCRIPT;

    static {
        TRYLOCK_SCRIPT = new DefaultRedisScript<>();
        TRYLOCK_SCRIPT.setLocation(new ClassPathResource("lua/re-trylock.lua"));
        TRYLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 获取锁
     *
     * @param timeoutSec 超时时间
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        this.timeoutSec = timeoutSec;
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(
                TRYLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId(),
                Long.toString(timeoutSec)
        );
        return result != null && result.equals(1L);
    }

    /**
     * 加载释放锁的Lua脚本
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/re-unlock.lua"));
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
                ID_PREFIX + Thread.currentThread().getId(),
                Long.toString(this.timeoutSec)
        );
    }

}

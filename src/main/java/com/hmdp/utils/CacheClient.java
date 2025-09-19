package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.hash.BloomFilter;
import com.hmdp.model.RedisData;
import com.hmdp.model.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.constants.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.constants.RedisConstants.LOCK_SHOP_KEY;

/**
 * @author ghp
 * @title
 * @description
 */
@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    
//    @Resource
//    private BloomFilter<String> idBloomFilter;

    @Resource
    private BloomFilterUtil bloomFilterUtil;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    /**
     * 将数据加入Redis，并设置有效期
     *
     * @param key
     * @param value
     * @param timeout
     * @param unit
     */
    public void set(String key, Object value, Long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), timeout, unit);
    }

    /**
     * 将数据加入Redis，并设置逻辑过期时间
     *
     * @param key
     * @param value
     * @param timeout
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long timeout, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // unit.toSeconds()是为了确保计时单位是秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(timeout)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), timeout, unit);
    }


//    /**
//     * 根据id查询数据（处理缓存穿透）
//     *
//     * @param keyPrefix  key前缀
//     * @param id         查询id
//     * @param type       查询的数据类型
//     * @param dbFallback 根据id查询数据的函数
//     * @param timeout    有效期
//     * @param unit       有效期的时间单位
//     * @param <T>
//     * @param <ID>
//     * @return
//     */
//    public <T, ID> T handleCachePenetration(String keyPrefix, ID id, Class<T> type,
//                                            Function<ID, T> dbFallback, Long timeout, TimeUnit unit) {
//        String key = keyPrefix + id;
//        // 1、从Redis中查询店铺数据
//        String jsonStr = stringRedisTemplate.opsForValue().get(key);
//
//        T t = null;
//        // 2、判断缓存是否命中
//        if (StrUtil.isNotBlank(jsonStr)) {
//            // 2.1 缓存命中，直接返回店铺数据
//            t = JSONUtil.toBean(jsonStr, type);
//            return t;
//        }
//
//        // 2.2 缓存未命中，判断缓存中查询的数据是否是空字符串(isNotBlank把null和空字符串给排除了)
//        if (Objects.nonNull(jsonStr)) {
//            // 2.2.1 当前数据是空字符串（说明该数据是之前缓存的空对象），直接返回失败信息
//            return null;
//        }
//        // 2.2.2 当前数据是null，则从数据库中查询店铺数据
//        t = dbFallback.apply(id);
//
//        // 4、判断数据库是否存在店铺数据
//        if (Objects.isNull(t)) {
//            // 4.1 数据库中不存在，缓存空对象（解决缓存穿透），返回失败信息
//            this.set(key, "", CACHE_NULL_TTL, TimeUnit.SECONDS);
//            return null;
//        }
//        // 4.2 数据库中存在，重建缓存，并返回店铺数据
//        this.set(key, t, timeout, unit);
//        return t;
//    }
    /**
     * 根据id查询数据（处理缓存穿透）
     * 使用布隆过滤器替代缓存空值方案
     *
     * @param keyPrefix  key前缀
     * @param id         查询id
     * @param type       查询的数据类型
     * @param dbFallback 根据id查询数据的函数
     * @param timeout    有效期
     * @param unit       有效期的时间单位
     * @param <T>
     * @param <ID>
     * @return
     */
    public <T, ID> T handleCachePenetration(String keyPrefix, ID id, Class<T> type,
                                            Function<ID, T> dbFallback, Long timeout, TimeUnit unit) {
        String key = keyPrefix + id;
        String idStr = id.toString();

        // 1、使用布隆过滤器检查ID是否可能存在
        // 如果布隆过滤器判断ID不存在，则直接返回null，避免查询数据库
        if (!bloomFilterUtil.mightContain(id.toString())) {
            log.info("布隆过滤器判断ID不存在，直接返回null，避免缓存穿透");
            return null;
        }

        // 2、从Redis中查询数据
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        T t = null;
        // 3、判断缓存是否命中
        if (StrUtil.isNotBlank(jsonStr)) {
            // 3.1 缓存命中，直接返回数据
            t = JSONUtil.toBean(jsonStr, type);
            return t;
        }

        // 3.2 缓存未命中，从数据库中查询数据
        t = dbFallback.apply(id);

        // 4、判断数据库是否存在数据
        if (Objects.isNull(t)) {
            // 4.1 数据库中不存在，记录到日志，返回null
            // 注意：这里不再缓存空对象，因为布隆过滤器已经阻止了大部分穿透
            log.info("数据库中不存在ID:{}的数据", id);
            return null;
        }

        // 4.2 数据库中存在，重建缓存，并将ID添加到布隆过滤器
        this.set(key, t, timeout, unit);
        this.addIdToBloomFilter(id);
        return t;
    }
//
    /**
     * 将ID添加到布隆过滤器
     * 用于数据写入数据库时同步更新布隆过滤器
     *
     * @param id 要添加的ID
     */
    public void addIdToBloomFilter(Object id) {
        if (id != null) {
            bloomFilterUtil.put(id.toString());
        }
    }


    /**
     * 缓存重建线程池
     */
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询数据（处理缓存击穿）
     *
     * @param keyPrefix  key前缀
     * @param id         查询id
     * @param type       查询的数据类型
     * @param dbFallback 根据id查询数据的函数
     * @param timeout    有效期
     * @param unit       有效期的时间单位
     * @param <T>
     * @param <ID>
     * @return
     */
    public <T, ID> T handleCacheBreakdown(String keyPrefix, ID id, Class<T> type,
                                          Function<ID, T> dbFallback, Long timeout, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1、从Redis中查询店铺数据，并判断缓存是否命中
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(jsonStr)) {
            // 1.1 缓存未命中，直接返回失败信息
            return null;
        }
        // 1.2 缓存命中，将JSON字符串反序列化未对象，并判断缓存数据是否逻辑过期
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        JSONObject data = (JSONObject) redisData.getData();
        T t = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 当前缓存数据未过期，直接返回
            return t;
        }

        // 2、缓存数据已过期，获取互斥锁，并且重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 获取锁成功，开启一个子线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    T t1 = dbFallback.apply(id);
                    // 将查询到的数据保存到Redis
                    this.setWithLogicalExpire(key, t1, timeout, unit);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        // 3、获取锁失败，再次查询缓存，判断缓存是否重建（这里双检是有必要的）
        jsonStr = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(jsonStr)) {
            // 3.1 缓存未命中，直接返回失败信息
            return null;
        }
        // 3.2 缓存命中，将JSON字符串反序列化未对象，并判断缓存数据是否逻辑过期
        redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        data = (JSONObject) redisData.getData();
        t = JSONUtil.toBean(data, type);
        expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 当前缓存数据未过期，直接返回
            return t;
        }

        // 4、返回过期数据
        return t;

    }

    /**
     * 获取锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 拆箱要判空，防止NPE
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}

package com.hmdp.service;

import com.hmdp.model.entity.Shop;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.constants.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.constants.RedisConstants.SHOP_GEO_KEY;

/**
 * @title
 * @description
 */
@SpringBootTest
public class ShopServiceTest {

    @Resource
    private IShopService shopService;
    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 预热热点店铺数据，用于测试逻辑过期解决缓存击穿
     */
    @Test
    public void loadShopToCache() {
        Shop shop = shopService.getById(1L);
        // 为了测试缓存击穿，我们把热点key的有效期设置的短一点
        // 而在正常开发中，是不为热点key设置有效期的，而是设置逻辑过期，然后在某段时间手动删除缓存中逻辑过期的key
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    /**
     * 预热店铺数据，按照typeId进行分组，用于实现附近商户搜索功能
     */
    @Test
    public void loadShopListToCache() {
        // 1、获取店铺数据
        List<Shop> shopList = shopService.list();
        // 2、根据 typeId 进行分类
//        Map<Long, List<Shop>> shopMap = new HashMap<>();
//        for (Shop shop : shopList) {
//            Long shopId = shop.getId();
//            if (shopMap.containsKey(shopId)){
//                // 已存在，添加到已有的集合中
//                shopMap.get(shopId).add(shop);
//            }else{
//                // 不存在，直接添加
//                shopMap.put(shopId, Arrays.asList(shop));
//            }
//        }
        // 使用 Lambda 表达式，更加优雅（优雅永不过时）
        Map<Long, List<Shop>> shopMap = shopList.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        // 3、将分好类的店铺数据写入redis
        for (Map.Entry<Long, List<Shop>> shopMapEntry : shopMap.entrySet()) {
            // 3.1 获取 typeId
            Long typeId = shopMapEntry.getKey();
            List<Shop> values = shopMapEntry.getValue();
            // 3.2 将同类型的店铺的写入同一个GEO ( GEOADD key 经度 维度 member )
            String key = SHOP_GEO_KEY + typeId;
            // 方式一：单个写入(这种方式，一个请求一个请求的发送，十分耗费资源，我们可以进行批量操作)
//            for (Shop shop : values) {
//                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()),
//                shop.getId().toString());
//            }
            // 方式二：批量写入
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : values) {
               locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                       new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }


}

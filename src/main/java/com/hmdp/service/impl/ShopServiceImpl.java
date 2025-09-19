package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constants.SystemConstants;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisCommands;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.domain.geo.Metrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    /**
     * 根据id查询商铺数据
     *
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 调用解决缓存穿透的方法
        Shop shop = cacheClient.handleCachePenetration(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (Objects.isNull(shop)) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }


    /**
     * 更新商铺数据（采用删除缓存模式，并且采用先操作数据库，后操作缓存）
     *
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result updateShop(Shop shop) {
        // 参数校验, 略

        // 1、更新数据库中的店铺数据
        boolean f = this.updateById(shop);
        if (!f) {
            // 缓存更新失败，抛出异常，事务回滚
            throw new RuntimeException("数据库更新失败");
        }
        // 2、删除缓存
        f = stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        if (!f) {
            // 缓存删除失败，抛出异常，事务回滚
            throw new RuntimeException("缓存删除失败");
        }
        return Result.ok();
    }
    
//     /**
//      * 新增店铺数据
//      *
//      * @param shop 店铺数据
//      * @return 操作结果
//      */
//     @Transactional
//     @Override
//     public boolean save(Shop shop) {
//         boolean result = super.save(shop);
//         if (result) {
//             // 数据保存成功后，将店铺ID添加到布隆过滤器
//             cacheClient.addIdToBloomFilter(shop.getId());
//         }
//         return result;
//     }

    /**
     * 分页查询店铺数据???
     *
     * @param typeId
     * @param current(滚动查询很好奇)
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
//        x = null;
//        y = null;
        // 1、判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = this.page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE),
                    new LambdaQueryWrapper<Shop>().eq(Shop::getTypeId, typeId));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2、需要查询坐标，则需要到Redis中进行查询
        // 2.1 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 2.2 根据经纬度从redis中查询店铺数据，并按照距离排序、分页
        String key = SHOP_GEO_KEY + typeId;
        // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE 结果: shopId、distance
//        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate
//                .opsForGeo().search(
//                        key,
//                        GeoReference.fromCoordinate(x, y),
//                        // 默认搜索范围是5km
//                        new Distance(5000),
//                        // 查询从0到end，所以后面还需要截取from到end之间的数据
//                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate
                .opsForGeo()
                .radius(
                        key,
                        new Circle(new Point(x,y),new Distance(5000, Metrics.METERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .limit(end)
                );
        // 4、解析出店铺id
        // 4.1 健壮性判断，防止skip出现NPE
        if (results == null) {
            // 缓存中不存在店铺数据
            return Result.ok(Collections.emptyList());
        }
        // 4.2 缓存中存在店铺数据，则需要截取 from ~ end的部分，需要判断from到end之间的数据是否存在
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 当前数据比起始索引还要小，说明没有我们要查询页的数据
            return Result.ok(Collections.emptyList());
        }
        // 4.3 from到end之间的数据存在，则解析出店铺id
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        // skip表示直接从第from个数据开始遍历
        list.stream().skip(from).forEach(result -> {
            // 获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取店铺距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // 5、根据店铺ids查询出店铺数据
        String idStr = StrUtil.join(",", ids);
        // 5.1 查寻出所有符合条件的店铺数据（这里需要利用ORDER BY FIELD确保id的有序性）
        List<Shop> shopList = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
//        List<Shop> shopList = this.list(new LambdaQueryWrapper<Shop>()
//                .in(Shop::getId, ids)
//                .last("ORDER BY FIELD(id," + idStr + ")"));
        // 5.2 为店铺的距离属性进行赋值
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        // 6、返回
        return Result.ok(shopList);
    }
}

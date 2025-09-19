package com.hmdp.runner;

/**
 * 类功能描述
 * <p>
 * 作者：jerry
 * 日期：2025-09-15
 */
import com.hmdp.model.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.BloomFilterUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class BloomFilterInitializer implements ApplicationRunner {

    @Autowired
    private BloomFilterUtil bloomFilterUtil;

    @Autowired
    private IShopService shopService;

    @Override
    public void run(ApplicationArguments args) {
        // 1. 初始化布隆过滤器
        bloomFilterUtil.init();
        // 2. 查询所有店铺ID，加载到布隆过滤器
        List<Shop> shops = shopService.list();
        List<String> ids = shops.stream()
                .map(shop -> shop.getId().toString())
                .collect(Collectors.toList());
        ids.forEach(bloomFilterUtil::put);
    }
}
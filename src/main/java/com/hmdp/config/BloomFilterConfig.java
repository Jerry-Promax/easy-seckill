//package com.hmdp.config;
//
//import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
//import com.google.common.hash.BloomFilter;
//import com.google.common.hash.Funnels;
//import com.hmdp.model.entity.Shop;
//import com.hmdp.service.IShopService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Lazy;
//
//import javax.annotation.PostConstruct;
//import java.nio.charset.Charset;
//import java.util.List;
//import java.util.stream.Collectors;
//
///**
// * 布隆过滤器配置类
// * 用于初始化Guava布隆过滤器，解决缓存穿透问题
// */
//@Configuration
//public class BloomFilterConfig {
//
//    /**
//     * 布隆过滤器配置参数
//     * expectedInsertions: 预期插入的元素数量
//     * fpp: 预期的误判率（false positive probability）
//     */
//    private static final int EXPECTED_INSERTIONS = 1000000;
//    private static final double FPP = 0.01;
//
//    @Autowired
//    @Lazy
//    private IShopService iShopService;
//    /**
//     * 创建布隆过滤器Bean，用于检查ID是否存在
//     * 使用字符串形式存储ID，以便适用于不同类型的ID
//     */
//    @Bean
//    public BloomFilter<String> idBloomFilter() {
//        return BloomFilter.create(
//                Funnels.stringFunnel(Charset.defaultCharset()),
//                EXPECTED_INSERTIONS,
//                FPP);
//
//    }
//    @PostConstruct
//    private void init(){
//        LambdaQueryWrapper<Shop> lambdaQueryWrapper = new LambdaQueryWrapper<>();
//
//        List<Long> ids = iShopService.list(lambdaQueryWrapper).stream()
//                .map(Shop::getId)
//                .collect(Collectors.toList());
//        for (Long id : ids) {
//            idBloomFilter().put(id.toString());
//        }
//    }
//}
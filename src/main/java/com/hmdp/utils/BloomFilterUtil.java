package com.hmdp.utils;

import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.hmdp.model.entity.Shop;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.nio.charset.Charset;
import java.util.List;
import java.util.stream.Collectors;
/**
 * 类功能描述
 * <p>
 * 作者：jerry
 * 日期：2025-09-15
 */
@Component
public class BloomFilterUtil {
    private static final int EXPECTED_INSERTIONS = 1000000;
    private static final double FPP = 0.01;

    private BloomFilter<String> idBloomFilter;

    // 会导致循环依赖
//    @Autowired
//    private IShopService shopService;

    @PostConstruct
    public void init() {
        idBloomFilter = BloomFilter.create(
                Funnels.stringFunnel(Charset.defaultCharset()),
                EXPECTED_INSERTIONS,
                FPP);
    }
    public boolean mightContain(String id) {
        return idBloomFilter.mightContain(id);
    }

    // 对外提供添加ID到布隆过滤器的方法
    public void put(String id) {
        idBloomFilter.put(id);
    }
}
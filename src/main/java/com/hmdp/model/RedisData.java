package com.hmdp.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author ghp
 * @title
 * @description 用于封装逻辑过期数据
 */
@Data
public class RedisData {
    /**
     * 过期时间
     */
    private LocalDateTime expireTime;
    /**
     * 缓存数据
     */
    private Object data;
}

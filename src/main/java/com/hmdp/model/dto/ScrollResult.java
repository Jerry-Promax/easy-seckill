package com.hmdp.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 滚动分页
 */
@Data
public class ScrollResult {
    /**
     * 分页数据
     */
    private List<?> list;
    /**
     * 最小时间（上一次时间）
     */
    private Long minTime;
    /**
     * 偏移量
     */
    private Integer offset;
}

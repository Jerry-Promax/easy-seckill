package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    /**
     * 新增秒杀券
     * @param voucher
     */
    void addSeckillVoucher(Voucher voucher);
}

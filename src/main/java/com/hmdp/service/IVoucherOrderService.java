package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.VoucherOrder;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 抢购秒杀券
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建订单
     *
     * @param voucherOrder
     * @return
     */
    void createVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException;

}

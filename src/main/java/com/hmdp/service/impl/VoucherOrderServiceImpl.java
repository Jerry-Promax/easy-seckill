package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constants.RedisConstants;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.SeckillVoucher;
import com.hmdp.model.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.ThreadLocalUtls;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.constants.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 当前类初始化完毕就立马执行该方法
     */
    @PostConstruct
    private void init() {
        // 创建消息队列
        DefaultRedisScript<Long> mqScript = new DefaultRedisScript<>();
        mqScript.setLocation(new ClassPathResource("lua/stream-mq.lua"));
        mqScript.setResultType(Long.class);
        Long result = null;
        try {
            result = stringRedisTemplate.execute(mqScript,
                    Collections.emptyList(),
                    QUEUE_NAME,
                    GROUP_NAME);
        } catch (Exception e) {
            log.error("队列创建失败", e);
            return;
        }
        int r = result.intValue();
        String info = r == 1 ? "队列创建成功" : "队列已存在";
        log.debug(info);
        // 执行线程任务,循环消费Stream消息
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 线程任务: 不断从阻塞队列中获取订单
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1、从消息队列中获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 1000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(1)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    // 2、判断消息获取是否成功
                    if (messageList == null || messageList.isEmpty()) {
                        // 2.1 消息获取失败，说明没有消息，进入下一次循环获取消息
                        continue;
                    }
                    // 3、消息获取成功，可以下单
                    // 将消息转成VoucherOrder对象
                    MapRecord<String, Object, Object> record = messageList.get(0);
                    Map<Object, Object> messageMap = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(messageMap, new VoucherOrder(), true);
                    // 将对象传入创建订单的方法
                    handleVoucherOrder(voucherOrder);
                    // 4、ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 处理异常消息
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                // 1、从pendingList中获取订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 1000 STREAMS streams.order 0
                List<MapRecord<String, Object, Object>> messageList = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(1)),
                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
                );
                // 2、判断pendingList中是否有效性
                if (messageList == null || messageList.isEmpty()) {
                    // 2.1 pendingList中没有消息，直接结束循环
                    break;
                }
                // 3、pendingList中有消息
                // 将消息转成VoucherOrder对象
                MapRecord<String, Object, Object> record = messageList.get(0);
                Map<Object, Object> messageMap = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(messageMap, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                // 4、ACK确认 SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, GROUP_NAME, record.getId());
            } catch (Exception e) {
                log.error("处理订单异常", e);
                // 这里不用调自己，直接就进入下一次循环，再从pendingList中取，这里只需要休眠一下，防止获取消息太频繁
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    log.error("线程休眠异常", ex);
                }
            }
        }
    }

    /**
     * 创建订单
     *
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long shopId = voucherOrder.getShopId();
        // 获取分布式锁对象
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId + ":" + shopId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 索取锁失败，重试或者直接抛异常（这个业务是一人一单，所以直接返回失败信息）
            log.error("一人只能下一单");
            return;
        }
        try {
            // 创建订单（使用代理对象调用，是为了确保事务生效）
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 加载 判断秒杀券库存是否充足 并且 判断用户是否已下单 的Lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        // new一个redis的脚本对象
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 设置脚本存放路径
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/stream-seckill.lua"));
        // 设置脚本返回类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * VoucherOrderServiceImpl类的代理对象
     * 将代理对象的作用域进行提升，方面子线程取用
     */
    private IVoucherOrderService proxy;

    /**
     * 抢购秒杀券
     *
     * @param voucherId
     * @return
     */
    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = ThreadLocalUtls.getUser().getId();
        long orderId = redisIdWorker.nextId(SECKILL_VOUCHER_ORDER);

        // 获取店铺ID
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        Long shopId = seckillVoucher.getShopId();

        // 1、执行Lua脚本，判断用户是否具有秒杀资格
        Long result = null;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId),
                    shopId.toString()
            );
        } catch (Exception e) {
            log.error("Lua脚本执行失败");
            throw new RuntimeException(e);
        }
        if (result != null && !result.equals(0L)) {
            // result为1表示库存不足，result为2表示用户已下单
            int r = result.intValue();
            return Result.fail(r == 2 ? "不能重复下单" : "库存不足");
        }

        // 2、result为0，下单成功，直接返回ok
        // 索取锁成功，创建代理对象，使用代理对象调用第三方事务方法， 防止事务失效
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        this.proxy = proxy;
        return Result.ok();
    }

    /**
     * 创建订单
     *
     * @param voucherOrder
     * @return
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 1、判断当前用户在当前店铺是否是第一单
        Long shopId = voucherOrder.getShopId();
        log.info("店铺id为{}",shopId);
        int count = this.count(new LambdaQueryWrapper<VoucherOrder>()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getShopId, shopId));
        if (count >= 1) {
            // 当前用户不是第一单
            log.error("当前用户不是第一单");
            return;
        }
        // 2、用户是第一单，可以下单，秒杀券库存数量减一
        boolean flag = seckillVoucherService.update(new LambdaUpdateWrapper<SeckillVoucher>()
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .setSql("stock = stock -1"));
        if (!flag) {
            throw new RuntimeException("秒杀券扣减失败");
        }
        // 3、将订单保存到数据库
        log.info("订单信息为{}",voucherOrder);
        flag = this.save(voucherOrder);
        if (!flag) {
            throw new RuntimeException("创建秒杀券订单失败");
        }
    }
}

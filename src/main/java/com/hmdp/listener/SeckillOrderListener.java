package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.constants.RedisConstants;
import com.hmdp.model.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 类功能描述
 * <p>
 * 作者：jerry
 * 日期：2026-03-30
 */
@Component
@Slf4j
public class SeckillOrderListener {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE, ackMode = "MANUAL")
    public void onOrderMessages(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        VoucherOrder voucherOrder = null;
        try {
            byte[] body = message.getBody();
            String jsonStr = new String(body, StandardCharsets.UTF_8);
            voucherOrder = JSONUtil.toBean(jsonStr, VoucherOrder.class);
            log.info("订单信息：{}", voucherOrder);
            voucherOrderService.createVoucherOrder(voucherOrder);
            channel.basicAck(deliveryTag, false);
            log.info("订单处理成功");
        } catch (IOException | InterruptedException e) {
            // IO 异常（网络问题）
            log.error("【主队列】IO 异常：{}", e.getMessage());
            try {
                // 重新入队，立即重试
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException ex) {
                log.error("【主队列】重新入队失败", ex);
            }
        }catch (Exception e){
            // 其他异常
            log.error("处理订单异常：{}", e.getMessage());
            try {
                // 判断是否为业务异常
                if(isBusinessException(e)){
                    log.warn("【主队列】业务异常：{}", e.getMessage());
                    // 发送到死信队列
                    channel.basicNack(deliveryTag, false, false);
                    restoreStock(voucherOrder);
                }else{
                    Integer retryCount = getRetryCount(message);
                    if(retryCount < RabbitMQConfig.MAX_RETRY_COUNT){
                        log.warn("【主队列】业务异常，正在重试：{}", e.getMessage());
                        // 重新入队，稍后重试
                        sendToDelayQueue(message, retryCount + 1);
                        channel.basicNack(deliveryTag, false, false);
                    }else{
                        log.warn("【主队列】业务异常，已超过最大重试次数：{}", e.getMessage());
                        // 发送到死信队列
                        channel.basicNack(deliveryTag, false, false);
                    }
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
    /**
     * 判断是否为业务异常
     */
    private boolean isBusinessException(Exception e) {
        String msg = e.getMessage();
        return msg != null && (
                msg.contains("一人只能下一单") ||
                        msg.contains("重复下单") ||
                        msg.contains("库存不足") ||
                        msg.contains("秒杀券扣减失败")
        );
    }

    /**
     * 获取消息的重试次数
     */
    private Integer getRetryCount(Message message) {
        Integer retryCount = (Integer) message.getMessageProperties()
                .getHeaders().get("x-retry-count");
        return retryCount == null ? 0 : retryCount;
    }
    /**
     * 发送到延迟队列（延迟 5 秒重试）
     */
    private void sendToDelayQueue(Message message, int retryCount) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.DELAY_EXCHANGE,
                RabbitMQConfig.DELAY_ROUTING_KEY,
                message.getBody(),
                msg -> {
                    // 保留原始消息头
                    msg.getMessageProperties().getHeaders()
                            .putAll(message.getMessageProperties().getHeaders());

                    // 设置重试次数
                    msg.getMessageProperties().getHeaders()
                            .put("x-retry-count", retryCount);

                    log.info("【延迟队列】发送重试消息，重试次数：{}", retryCount);
                    return msg;
                }
        );
    }
    /**
     * 恢复库存
     */
    private void restoreStock(VoucherOrder voucherOrder) {
         stringRedisTemplate.opsForValue().increment(
             "seckill:stock:" + voucherOrder.getVoucherId()
         );
    }
}
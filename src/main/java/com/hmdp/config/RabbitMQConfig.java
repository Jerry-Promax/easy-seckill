package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 类功能描述
 * <p>
 * 作者：jerry
 * 日期：2026-03-30
 */
@Configuration
public class RabbitMQConfig {
    // ==================== 主队列 ====================
    public static final String SECKILL_ORDER_EXCHANGE = "seckill.order.exchange";
    public static final String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    public static final String SECKILL_ORDER_ROUTING_KEY = "seckill.order.create";

    // ==================== 延迟队列 ====================
    public static final String DELAY_EXCHANGE = "delay.exchange";
    public static final String DELAY_QUEUE = "delay.queue";
    public static final String DELAY_ROUTING_KEY = "delay.key";

    // 重试次数限制
    public static final int MAX_RETRY_COUNT = 3;

    // ==================== 死信队列 ====================
    public static final String DEAD_LETTER_EXCHANGE = "dead.letter.exchange";
    public static final String DEAD_LETTER_QUEUE = "dead.letter.queue";
    public static final String DEAD_LETTER_ROUTING_KEY = "dead.letter.key";


    // ==================== Bean 定义 ====================

    /**
     * 创建 Topic 交换机
     * Topic Exchange：根据 RoutingKey 进行模糊匹配路由
     */
    @Bean
    public Exchange seckillOrderExchange() {
        return ExchangeBuilder.topicExchange(SECKILL_ORDER_EXCHANGE)
                .durable(true)  // 持久化
                .build();
    }

    /**
     * 创建主队列
     */
    @Bean
    public Queue seckillOrderQueue() {
        Map<String, Object> args = new HashMap<>();
        // 创建死信队列参数
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY);
        return QueueBuilder.durable(SECKILL_ORDER_QUEUE)
                .withArguments(args)
                .build();
    }

    /**
     * 绑定队列到交换机
     * 当 RoutingKey 匹配时，消息会从交换机路由到队列
     */
    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, Exchange seckillOrderExchange) {
        return BindingBuilder.bind(seckillOrderQueue)
                .to(seckillOrderExchange)
                .with(SECKILL_ORDER_ROUTING_KEY)
                .noargs();
    }

    /**
     * 创建死信交换机
     * 死信 Exchange：当消息被拒绝（Basic.Reject/Basic.Nack）时，会发送到死信交换机
     */
    @Bean
    public Exchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }
    /**
     * 创建死信队列
     */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }
    /**
     * 绑定死信队列到死信交换机
     */
    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, Exchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY)
                .noargs();
    }

    /**
     * 延迟队列（用于重试，5 秒后过期）
     */
    @Bean
    public Queue delayQueue() {
        Map<String, Object> args = new HashMap<>();
        // 消息 TTL：5 秒后过期
        args.put("x-message-ttl", 5000);
        // 过期后发送到主队列（重试）
        args.put("x-dead-letter-exchange", SECKILL_ORDER_EXCHANGE);
        args.put("x-dead-letter-routing-key", SECKILL_ORDER_ROUTING_KEY);

        return QueueBuilder.durable(DELAY_QUEUE).withArguments(args).build();
    }

    /**
     * 延迟交换机
     */
    @Bean
    public Exchange delayExchange() {
        return ExchangeBuilder.directExchange(DELAY_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * 延迟队列绑定
     */
    @Bean
    public Binding delayBinding(Queue delayQueue, Exchange delayExchange) {
        return BindingBuilder.bind(delayQueue)
                .to(delayExchange)
                .with(DELAY_ROUTING_KEY)
                .noargs();
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
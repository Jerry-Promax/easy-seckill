> **技术栈**：JDK 1.8 · Spring Boot 2.6.4 · MyBatis-Plus 3.4.3 · MySQL · Redis（Lettuce + Redisson 3.13.6 + Lua + Guava BloomFilter）· RabbitMQ · Knife4j · Hutool
>
> **压测表现**：500 并发线程持续 60s，吞吐量 **2031.7 QPS**，平均响应 **217ms**，P99 **453ms**，**零超卖、零数据丢失**。

---

## 目录

- [1. 项目概述](#1-项目概述)
- [2. 功能地图](#2-功能地图)
- [3. 工程结构](#3-工程结构)
- [4. 快速开始](#4-快速开始)
- [5. 登录鉴权设计](#5-登录鉴权设计)
- [6. 缓存专题：穿透 / 击穿 / 雪崩](#6-缓存专题穿透--击穿--雪崩)
- [7. Redis 数据结构实战](#7-redis-数据结构实战)
- [8. 高并发秒杀专题](#8-高并发秒杀专题)
- [9. RabbitMQ 拓扑与消息可靠性](#9-rabbitmq-拓扑与消息可靠性)
- [10. Redis Key 一览](#10-redis-key-一览)
- [11. 接口一览](#11-接口一览)
- [12. 性能压测](#12-性能压测)
- [13. 已知问题与改进路线](#13-已知问题与改进路线)


---

## 1. 项目概述

项目包含两条主线：

| 主线 | 目标 | 关键手段 |
| :--- | :--- | :--- |
| **业务线** | 一个可运行的本地生活点评平台：用户、商铺、探店笔记、关注、优惠券、秒杀下单 | Spring MVC + MyBatis-Plus 分层架构 |
| **技术线** | 解决高并发下的 **缓存问题、超卖、重复下单、DB 压力、消息可靠性** | Redis + Lua + Redisson + RabbitMQ |

核心设计思想：**把 80%~99% 的流量拦在离数据库最远的地方**，写操作异步化，数据库只做最终一致性兜底。

---

## 2. 功能地图

### 2.1 业务模块

| 模块 | Controller | 说明 |
| :--- | :--- | :--- |
| 用户 | `UserController` | 验证码登录、登出、个人信息、签到 |
| 商铺 | `ShopController` / `ShopTypeController` | 详情缓存、类型列表、附近商户（GEO）、按名搜索 |
| 笔记 | `BlogController` / `BlogCommentsController` | 发布、详情、热门、点赞榜、Feed 流滚动分页 |
| 关注 | `FollowController` | 关注/取关、共同关注 |
| 优惠券 | `VoucherController` | 新增普通券 / 秒杀券（同步预热库存到 Redis） |
| 秒杀 | `VoucherOrderController` | 抢券下单 |
| 上传 | `UploadController` | 笔记图片上传（本地 Nginx 目录） |

### 2.2 技术专题（重点）

1. **缓存一致性**：更新数据库 + 删除缓存（先库后缓存）。
2. **缓存穿透**：布隆过滤器（Guava）前置拦截 + 空值缓存方案（代码保留但已注释切换）。
3. **缓存击穿**：互斥锁 + 逻辑过期 + 独立线程池异步重建（含双检）。
4. **分布式 ID**：Redis `INCR` + 时间戳位移拼接（`RedisIdWorker`，一天一个 key，序列号 32 位）。
5. **分布式锁**：手写不可重入锁（`SimpleRedisLock`）、手写可重入锁（`ReentrantLock` + Hash 计数）、Redisson 三级方案演进，Lua 保证解锁原子性。
6. **秒杀架构**：Redis + Lua 前置拦截 → RabbitMQ 削峰 → 分布式锁 + 乐观锁落库 → 异常回滚库存。
7. **数据结构实战**：GEO 附近商户、BitMap 签到、ZSet 点赞排行 / Feed 收件箱、Set 共同关注、Stream 异步队列（保留方案）。

---

## 3. 工程结构

```
src/main/java/com/hmdp
├── config          # Knife4j / Mybatis / RabbitMQ / Redisson / 全局异常 / WebMvc 拦截器注册
├── constants       # RedisConstants（key 前缀与 TTL）、RegexConstants、SystemConstants
├── controller      # REST 入口，共 9 个
├── interceptor     # RefreshTokenInterceptor(order=0) + LoginInterceptor(order=1)
├── listener        # SeckillOrderListener：RabbitMQ 手动 ACK / 重试 / 死信 / 库存回滚
├── mapper          # MyBatis-Plus BaseMapper
├── model
│   ├── dto         # Result、LoginFormDTO、UserDTO、ScrollResult
│   ├── entity      # Shop / Blog / Follow / Voucher / SeckillVoucher / VoucherOrder ...
│   └── RedisData   # 逻辑过期包装对象
├── runner          # BloomFilterInitializer：启动时全量预热店铺 ID 到布隆过滤器
├── service/impl    # 业务实现
└── utils
    ├── lock        # Lock 接口 + SimpleRedisLock + ReentrantLock
    ├── BloomFilterUtil / CacheClient / RedisIdWorker / PasswordEncoder / RegexUtils / ThreadLocalUtls

src/main/resources
├── application.yaml
├── db/hmdp.sql     # 11 张表 + 初始化数据
├── lua/            # seckill / stream-seckill / stream-mq / unlock / re-trylock / re-unlock
├── mapper/VoucherMapper.xml
├── tokens.txt      # JMeter 压测用的多用户 token 列表
└── token.csv
```

---

## 4. 快速开始

### 4.1 环境要求

| 组件 | 版本 | 备注 |
| :--- | :--- | :--- |
| JDK | 1.8 | 推荐 Amazon Corretto 8 |
| Maven | 3.6+ | 打包 `spring-boot-maven-plugin` |
| MySQL | 5.7 / 8.0 | 库名 `dianping`，字符集 utf8mb4 |
| Redis | 6.2+ | 需支持 `GEOSEARCH` / `BITFIELD` / `XADD`（本项目用 5000m 半径 GEO） |
| RabbitMQ | 3.8+ | 需管理插件（`rabbitmq-plugins enable rabbitmq_management`） |
| Nginx | 可选 | 用于图片回显，路径见 `SystemConstants.IMAGE_UPLOAD_DIR` |

### 4.2 配置

修改 `src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/dianping?...
    username: root
    password: 123456
  redis:
    host: 192.168.233.128      # 改成你的 Redis 地址
    port: 6379
  rabbitmq:
    host: 192.168.233.128      # 改成你的 MQ 地址
    username: guest
    password: guest
    listener:
      simple:
        acknowledge-mode: manual   # 秒杀消费者依赖手动 ACK，勿改 auto
        prefetch: 1
```

> Redisson 复用 `spring.redis.host/port`（见 `RedissonConfig`），无需重复配置。

### 4.3 初始化数据与启动

```bash
# 1. 建库导数据（含 11 张表与测试数据）
mysql -uroot -p -e "CREATE DATABASE dianping DEFAULT CHARSET utf8mb4;"
mysql -uroot -p dianping < src/main/resources/db/hmdp.sql

# 2. 构建并启动
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar      # 端口 8081

# 3. 首次运行需要的数据预热（可选，执行对应单测）
#    ShopServiceTest#initSeckillStock   预热秒杀库存到 Redis（seckill:stock:*）
#    ShopServiceTest#loadShopListToCache 按 typeId 分组写入 GEO，用于「附近商户」
```

### 4.4 接口文档

启动后访问 **http://localhost:8081/doc.html**（Knife4j，扫描 `com.hmdp.controller`）。

### 4.5 验证码说明

项目未接入短信服务商，`sendCode` 只生成 6 位随机码并写入 Redis（`login:code:{phone}`，TTL 200 分钟）同时打印到日志：

```
验证码:123456
```

从日志取值即可完成登录；压测脚本可用 `GenerateToken#login` 批量生成 `src/main/resources/tokens.txt`。

---

## 5. 登录鉴权设计

传统 Session 方案在多实例部署下需要粘滞会话，本项目改为 **Redis Token** 方案。

```
POST /user/code   → 生成验证码写入 login:code:{phone}
POST /user/login  → 校验验证码 → 查/建用户 → 生成 uuid token
                    → HMSET login:user:{token} (id/name/icon)  EXPIRE 3600min
                    → 返回 token

后续请求 Header: authorization: {token}
```

两级拦截器（`WebMvcConfig`）：

| 顺序 | 拦截器 | 作用范围 | 职责 |
| :--- | :--- | :--- | :--- |
| order 0 | `RefreshTokenInterceptor` | `/**` | 只要 token 有效就把用户写入 `ThreadLocal` 并续期 TTL |
| order 1 | `LoginInterceptor` | 受保护路径 | 判断 `ThreadLocal` 中是否有用户，无则返回 401 |

放行路径：`/user/code`、`/user/login`、`/blog/hot`、`/shop/**`、`/shop-type/**`、`/upload/**`、`/voucher/**` 及 knife4j 相关。

> **为什么存 Hash 而不是 String**：支持单字段修改（如改昵称只更新 `name`），无需整体反序列化。
> **为什么 UserDTO 而非 User**：避免手机号等敏感字段进入缓存，同时减小体积。

---

## 6. 缓存专题：穿透 / 击穿 / 雪崩

### 6.1 三类问题与对策

| 问题 | 现象 | 本项目方案 | 位置 |
| :--- | :--- | :--- | :--- |
| 雪崩 | 大量 key 同时过期 | 缓存层做集群 + 兜底限流；**当前 TTL 固定 30min 未加随机偏移，存在集中过期风险**（见第 13 节 #14） | `CacheClient#set` |
| 穿透 | 查询**不存在**的数据，缓存与库都没有 | **布隆过滤器**前置拦截（100w 容量，1% 误判率） | `BloomFilterUtil` + `CacheClient#handleCachePenetration` |
| 击穿 | **热点 key** 过期瞬间高并发打到 DB | 互斥锁 + 逻辑过期 + 线程池异步重建 + 双检 | `CacheClient#handleCacheBreakdown` |

### 6.2 穿透：布隆过滤器链路

```
ShopServiceImpl.queryById(id)
   ↓
CacheClient.handleCachePenetration()
   ├─ bloomFilterUtil.mightContain(id) == false → 直接返回 null（不查 Redis、不查 DB）
   ├─ 命中 Redis 缓存 → 返回
   ├─ 未命中 → 查 DB
   │     ├─ DB 无数据 → 返回 null（不再写空值缓存）
   │     └─ DB 有数据 → 写缓存 + put(id) 进布隆过滤器
```

- 启动时 `BloomFilterInitializer`（`ApplicationRunner`）全量加载 `tb_shop` 的 ID。
- 布隆过滤器特性：**判「无」一定无，判「有」可能有**，所以误判仅带来少量 DB 查询，不影响正确性。

### 6.3 击穿：逻辑过期 + 互斥锁

```
读缓存 → 反序列化 RedisData → 比较 expireTime（逻辑过期）
   ├─ 未过期 → 直接返回
   └─ 已过期 → tryLock(lock:shop:{id})  (SETNX EX 10)
         ├─ 抢到锁 → 提交 CACHE_REBUILD_EXECUTOR(10 线程) 异步回源重建，重建完释放锁
         └─ 没抢到 → 双检再读一次缓存
   最终：先返回「过期老数据」保证可用性（AP 取舍）
```

热点数据预热：`ShopServiceTest#loadShopToCache`。

### 6.4 缓存与数据库一致性

`ShopServiceImpl#updateShop` 采用 **Cache Aside 的「先更新 DB，再删除缓存」**，并放在同一事务中：DB 更新失败或缓存删除失败均抛异常回滚，避免脏缓存长期驻留。

---

## 7. Redis 数据结构实战

### 7.1 String — 库存与缓存对象

- `seckill:stock:{voucherId}`：Lua 中 `INCRBY -1` 原子扣减。
- `cache:shop:{id}`：店铺 JSON 缓存。

### 7.2 分布式 ID（`RedisIdWorker`）

```
id = (当前秒 - 2022-01-01 秒) << 32 | 当日自增序列
key: icr:{biz}:{yyyyMMdd}
```

31 位时间戳 + 32 位序列号，每天序列号重置，理论单日每业务 42 亿量级，且整体趋势递增、便于排序分页。

### 7.3 分布式锁三种实现

| 实现 | 文件 | 特性 |
| :--- | :--- | :--- |
| 原生 setnx | 早期版本 | 无过期时间 → 死锁；非原子 |
| `SimpleRedisLock` | `utils/lock/impl` | `SETNX + EX` 原子；value 存 `uuid-threadId` 防误删；Lua 解锁保证「判断 + 删除」原子 |
| `ReentrantLock` | `utils/lock/impl` | Hash 记录 `threadId → 重入次数`，`HINCRBY` 重入、`HDEL/EXPIRE` 释放（`re-trylock.lua` / `re-unlock.lua`） |
| **Redisson** | `RedissonConfig` | 生产使用；看门狗自动续期、可重入、公平锁、联锁等 |

### 7.4 GEO — 附近商户

```
写入：ShopServiceTest#loadShopListToCache → GEOADD shop:geo:{typeId} x y shopId（按类型分组批量写）
查询：/shop/of/type?typeId&current&x&y
      GEORADIUS shop:geo:{typeId} x y 5000 m WITHDIST COUNT n ASC
      → 取 shopId + distance → 截 from~end
      → SELECT ... WHERE id IN(...) ORDER BY FIELD(id, ...) 保持距离有序
```

无坐标时降级为普通数据库分页。

### 7.5 BitMap — 用户签到

```
POST /user/sign        SETBIT sign:{userId}:{yyyyMM} {dayOfMonth-1} 1
GET  /user/sign/count  BITFIELD sign:{userId}:{yyyyMM} GET u{day} 0
                       → 从低位逐位右移统计「从今天往前连续为 1」的天数
```

一个月每人仅 4 bit/天，30 天不到 4 字节，天然适合高基数签到统计。

### 7.6 ZSet — 点赞排行与 Feed 收件箱

- **点赞 Top5**：`blog:liked:{blogId}`，member = userId，score = 点赞时间戳。
  `likeBlog` 先判 `ZSCORE` 决定是否点赞/取消，同步 `liked ± 1`；`queryBlogLikes` 用 `ZRANGE 0 4` + `ORDER BY FIELD` 保序。
- **Feed 流（推模式）**：`saveBlog` 查出全部粉丝 → `ZADD feed:{followerId} {ts} {blogId}`。
- **滚动分页**：`queryBlogOfFollow` 用 `ZREVRANGEBYSCORE key max 0 LIMIT offset count`，
  以 `minTime + offset` 作为游标，处理「同一时间戳多条数据」的边界，返回 `ScrollResult{list, minTime, offset}`。

### 7.7 Set — 共同关注

```
关注时 SADD follows:{userId} {followUserId}
共同关注 SUNION follows:{me} follows:{target} 取交集 → 查用户信息
```

### 7.8 Stream — 备用 MQ 方案

`lua/stream-mq.lua`、`stream-seckill.lua` 与 `VoucherOrderHandler` 实现了「Redis Stream + 消费者组 + PendingList 重试」的异步下单方案，代码整体保留（已注释），便于与 RabbitMQ 方案做对比。

---

## 8. 高并发秒杀专题

### 8.1 三层防线

```
           用户请求 POST /voucher-order/seckill/{voucherId}
                          │
        ┌─────────────────▼──────────────────┐
        │ 第一层：Redis + Lua（拦掉 99% 非法流量）│
        │  lua/stream-seckill.lua            │
        │  ① GET seckill:stock:{id} > 0 ?    │  → 1: 库存不足
        │  ② SISMEMBER seckill:order:{u}:{s} │  → 2: 不能重复下单
        │  ③ INCRBY stock -1 + SADD + 投递    │  → 0: 抢购成功
        └─────────────────┬──────────────────┘
                          │  发 RabbitMQ（异步，主线程立即返回）
        ┌─────────────────▼──────────────────┐
        │ 第二层：SeckillOrderListener 消费     │
        │  · 手动 ACK / prefetch=1            │
        │  · IO 异常 → 重新入队                 │
        │  · 业务异常 → 死信队列 + 回滚库存        │
        │  · 其他异常 → 延迟队列（5s）重试 ≤ 3 次  │
        └─────────────────┬──────────────────┘
                          │
        ┌─────────────────▼──────────────────┐
        │ 第三层：落库（createVoucherOrder）     │
        │  · Redisson 锁 lock:order:{u}:{s}   │ ← 防「一人多单」
        │  · SELECT count(*) 幂等校验          │ ← 重复则 INCR 回滚 Redis 库存
        │  · UPDATE stock=stock-1             │
        │    WHERE stock > 0  ← 乐观锁防超卖    │
        │  · INSERT tb_voucher_order          │
        └────────────────────────────────────┘
```

### 8.2 各概念职责对照（易混点）

| 概念 | 解决的问题 | 层级 |
| :--- | :--- | :--- |
| Redis + Lua | 高性能 **前置拦截**：库存判断 + 一人一单判断 + 预扣减，全部原子 | 缓存层 |
| RabbitMQ | **削峰填谷 / 异步解耦**，保护 DB，提升接口吞吐 | 中间件层 |
| Redisson 分布式锁 | 解决「Lua 已扣减」与「消费者尚未落库」之间的 **时间差**，串行化同一 `userId+shopId` | 应用层 |
| 数据库乐观锁 `stock > 0` | **防超卖**的最终保障（行锁 + 原子更新，库存不可能为负） | 数据层 |
| `count(*)` 幂等校验 | **防重复下单**，消息重复投递时的兜底 | 数据层 |

> 常见误区：分布式锁不是用来防超卖的（防超卖靠乐观锁），它防的是**并发绕过「一人一单」校验**。

### 8.3 库存回滚点

出现「用户已下过单」或「DB 扣减失败」时，均执行 `INCR seckill:stock:{voucherId}` 把 Redis 预扣的库存补回，保证 Redis 与 DB 最终一致；消费端业务异常进死信时同样由 `SeckillOrderListener#restoreStock` 补偿。

### 8.4 事务代理

`seckillVoucher` / `createVoucherOrder` 均标注 `@Transactional`；历史版本通过 `AopContext.currentProxy()` 拿到代理对象再调用，避免同类内部方法调用导致事务失效（阻塞队列 + Stream 方案中体现）。

---

## 9. RabbitMQ 拓扑与消息可靠性

### 9.1 拓扑（`RabbitMQConfig`）

| 名称 | 类型 | 说明 |
| :--- | :--- | :--- |
| `seckill.order.exchange` | Topic，durable | 主交换机 |
| `seckill.order.queue` | durable | 主队列，绑定 routing key `seckill.order.create`，声明 `x-dead-letter-*` 指向死信 |
| `delay.exchange` / `delay.queue` | Direct，durable | 延迟队列，`x-message-ttl=5000`，过期后死信路由回 **主交换机** 实现重试 |
| `dead.letter.exchange` / `dead.letter.queue` | Direct，durable | 死信队列，人工介入排查 |
| `Jackson2JsonMessageConverter` | — | 消息体 JSON 序列化 |

```
        生产者
          │ seckill.order.create
          ▼
  seckill.order.exchange ──► seckill.order.queue ──► SeckillOrderListener
          ▲        │(nack 且 requeue=false，业务异常/超过重试上限)
          │        ▼
  delay.queue   dead.letter.exchange ──► dead.letter.queue
  (TTL 5s，x-retry-count+1)
```

### 9.2 消费端策略（`SeckillOrderListener`）

```java
@RabbitListener(queues = SECKILL_ORDER_QUEUE, ackMode = "MANUAL")
public void onOrderMessages(Message message, Channel channel) {
    try {
        解析 JSON → createVoucherOrder() → basicAck
    } catch (IOException | InterruptedException e) {
        basicNack(requeue = true)          // 网络类异常：立即重新入队
    } catch (Exception e) {
        if (isBusinessException(e)) {       // 一人一单 / 重复下单 / 库存不足 / 扣减失败
            basicNack(requeue = false);     // → 死信队列
            restoreStock(order);            // 回滚 Redis 库存
        } else if (retryCount < 3) {
            sendToDelayQueue(msg, retryCount + 1);  // 延迟 5s 后重回主队列
            basicNack(requeue = false);
        } else {
            basicNack(requeue = false);     // 超过上限 → 死信
        }
    }
}
```

重试次数通过消息头 `x-retry-count` 透传，避免无限重试拖垮消费者。

---

## 10. Redis Key 一览

| Key 前缀 | 类型 | 含义 | TTL |
| :--- | :--- | :--- | :--- |
| `login:code:{phone}` | String | 登录验证码 | 200 min |
| `login:user:{token}` | Hash | 登录用户（id/name/icon） | 3600 min |
| `cache:shop:{id}` | String(JSON) | 店铺详情 | 30 min |
| `cache:type` | String(JSON) | 店铺类型列表 | 30 min |
| `shop:geo:{typeId}` | GEO | 商铺坐标 | 永久 |
| `sign:{userId}:{yyyyMM}` | BitMap | 按月签到 | 永久 |
| `blog:liked:{blogId}` | ZSet | 点赞用户与时间 | 永久 |
| `follows:{userId}` | Set | 关注列表 | 永久 |
| `feed:{userId}` | ZSet | 笔记收件箱 | 永久 |
| `seckill:stock:{voucherId}` | String | 秒杀库存 | 永久 |
| `seckill:order:{userId}:{shopId}` | Set | 秒杀下单标记 | 永久 |
| `lock:order:{userId}:{shopId}` | Redisson | 下单分布式锁 | 看门狗 |
| `lock:shop:{id}` | String | 缓存重建互斥锁 | 10 s |
| `icr:{biz}:{yyyyMMdd}` | String | 分布式 ID 序列号 | 按天 |
| `stream.orders` | Stream | Redis Stream 备用 MQ 队列 | 永久 |

---

## 11. 接口一览

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| POST | `/user/code` | 发送验证码 |
| POST | `/user/login` | 登录，返回 token |
| POST | `/user/logout` | 登出，删除 Redis token |
| GET | `/user/me` / `/user/{id}` / `/user/info/{id}` | 当前用户 / 用户详情 |
| POST | `/user/sign` | 签到 |
| GET | `/user/sign/count` | 连续签到天数 |
| GET | `/shop/{id}` | 店铺详情（布隆过滤器 + 缓存） |
| POST/PUT | `/shop` | 新增 / 更新店铺（更新后删缓存） |
| GET | `/shop/of/type` | 按类型分页，带 `x/y` 走 GEO 距离排序 |
| GET | `/shop/of/name` | 名称模糊分页 |
| GET | `/shop-type/list` | 店铺类型（缓存） |
| GET | `/blog/hot` / `/blog/{id}` / `/blog/of/me` / `/blog/of/user` | 热门 / 详情 / 我的 / 指定用户笔记 |
| PUT | `/blog/like/{id}` | 点赞 / 取消点赞 |
| GET | `/blog/likes/{id}` | 点赞 Top5 |
| POST | `/blog` | 发布笔记（推送到粉丝 Feed） |
| GET | `/blog/of/follow` | Feed 滚动分页（`max` + `offset`） |
| PUT | `/follow/{id}/{isFollow}` | 关注 / 取关 |
| GET | `/follow/or/not/{id}` | 是否已关注 |
| GET | `/follow/common/{id}` | 共同关注 |
| POST | `/voucher` / `/voucher/seckill` | 新增普通券 / 秒杀券（预热库存） |
| GET | `/voucher/list/{shopId}` | 店铺券列表 |
| POST | `/voucher-order/seckill/{id}` | **秒杀下单** |
| POST | `/upload/blog` | 图片上传 |

---

## 12. 性能压测

**工具**：JMeter 5.6.3，500 线程，持续 60 秒，`tokens.txt` 提供多用户 token 做参数化。

| 指标 | 数值 | 说明 |
| :--- | :--- | :--- |
| 总请求数 | 122,633 | — |
| 吞吐量 | **2031.7 / sec** | 相比同步 + 行锁方案提升约 40 倍 |
| 平均响应 | 217 ms | 相比同步方案下降约 89% |
| P99 | 453 ms | — |
| 错误率 | 36.23% | 全部为「库存不足」业务拒绝，非系统异常 |
| 数据一致性 | 100% | Redis 库存 = DB 库存，订单数 = 扣减数，零超卖 |

**架构演进对照**

| 版本 | 方案 | 瓶颈 | 吞吐 |
| :--- | :--- | :--- | :--- |
| V1.0 | 同步下单 + 数据库行锁 | 响应 2s+，DB 压力大，可能超卖 | ~50 QPS |
| V2.0 | 乐观锁 + 逻辑过期缓存 | 超卖解决，写仍是 DB 瓶颈 | ~200 QPS |
| V3.0 | **Redis+Lua 预减 + MQ 异步 + 分布式锁 + 乐观锁** | — | **2000+ QPS** |

---

## 13. 已知问题与改进路线

以下内容为阅读源码后确认的现存缺陷，按优先级排列，可作为后续迭代。

| # | 位置 | 问题 | 建议 |
| :--- | :--- | :--- | :--- |
| 1 | `tb_voucher_order`（`db/hmdp.sql`） | 表结构中**没有 `shop_id` 字段**，但实体 `VoucherOrder` 与「一人一单」逻辑都依赖 `shop_id`；且**未建唯一索引** | `ALTER TABLE tb_voucher_order ADD shop_id bigint unsigned;` 并 `ADD UNIQUE INDEX uniq_user_shop(user_id, shop_id)` 作为最终兜底 |
| 2 | `CacheClient#setWithLogicalExpire` | 序列化的是 `value` 而不是 `redisData`，`expireTime` 实际未写入缓存，导致 `handleCacheBreakdown` 反序列化 `RedisData` 失败；同时设置了物理 TTL，逻辑过期意义被削弱 | 改为 `JSONUtil.toJsonStr(redisData)`，并去掉物理 TTL，改由定时任务清理 |
| 3 | `RefreshTokenInterceptor#preHandle` | `expire(token, ...)` 传的是裸 token，应为 `tokenKey`（`login:user:{token}`），当前续期无效 | 修正 key |
| 4 | `ThreadLocalUtls` | `RefreshTokenInterceptor` 只 `saveUser`，未在 `afterCompletion` 中 `removeUser`，线程池复用存在脏数据/内存泄漏风险 | 拦截器补 `afterCompletion` 清理 |
| 5 | `VoucherOrderServiceImpl#seckillVoucher` | 使用 `stream-seckill.lua`（会 `XADD stream.orders`）同时又发 RabbitMQ，Stream 消息无人消费，属方案残留 | 切换到 `lua/seckill.lua`（不投递），或统一改回 Stream 方案 |
| 6 | `createVoucherOrder` | 锁在 `@Transactional` 方法**内部**获取，方法返回时先释放锁后提交事务，理论上仍有并发窗口 | 锁上移到调用方（`handleVoucherOrder` 那层），确保「先提交事务，后释放锁」 |
| 7 | `seckillVoucher` | 主线程同步 `getById` 查 DB 取 `shopId`，高峰期仍会打到 DB | 将 `voucherId → shopId` 映射预热到 Redis |
| 8 | `BloomFilterUtil` | Guava 布隆过滤器为 **JVM 本地对象**，多实例部署各节点数据不一致，且不支持删除；`ShopController#saveShop` 未同步写入过滤器，新增店铺在重启前会持续被判「不存在」 | 改用基于 Redis 的 Redisson `RBloomFilter`；新增店铺时同步 `put` |
| 9 | `application.yaml` | `mybatis-plus.type-aliases-package: com.hmdp.entity` 包路径错误（实际为 `com.hmdp.model.entity`） | 修正为实际包 |
| 10 | `VoucherServiceImpl#addSeckillVoucher` | 只校验库存，未校验 `beginTime/endTime`；Lua 也不判断秒杀时间窗口 | 在 Lua 中增加活动时间校验 |
| 11 | `BlogServiceImpl#queryBlogOfFollow` | 分页 size 硬编码为 `2`，与 `SystemConstants.DEFAULT_PAGE_SIZE` 不一致 | 提取为常量 |
| 12 | `UserServiceImpl#login` | `code` 是请求参数，判空应针对 `redisCode`；`fieldValue.toString()` 未判空可能 NPE | 微调校验逻辑 |
| 13 | 全局 | 未接入短信服务、图片上传为本地磁盘、`WebExceptionAdvice` 之外无链路追踪 | 生产化改造项 |
| 14 | `RedisConstants.CACHE_SHOP_TTL` | 所有店铺缓存使用固定 30min TTL，批量写入时会同一时刻过期，存在缓存雪崩风险 | 写入时对 TTL 增加随机偏移量 |

---

## 附：数据库表

`tb_shop`、`tb_shop_type`、`tb_user`、`tb_user_info`、`tb_blog`、`tb_blog_comments`、`tb_follow`、`tb_voucher`、`tb_seckill_voucher`、`tb_voucher_order`、`tb_sign`（共 11 张，DDL 与初始化数据见 `src/main/resources/db/hmdp.sql`）。

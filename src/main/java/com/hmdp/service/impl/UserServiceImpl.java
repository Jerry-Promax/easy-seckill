package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constants.RedisConstants;
import com.hmdp.constants.SystemConstants;
import com.hmdp.mapper.UserMapper;
import com.hmdp.model.dto.LoginFormDTO;
import com.hmdp.model.dto.Result;
import com.hmdp.model.dto.UserDTO;
import com.hmdp.model.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.ThreadLocalUtls;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.constants.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     *
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1、判断手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        // 2、手机号合法，生成验证码，并保存到Redis中
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 3、发送验证码
        log.info("验证码:{}", code);
        return Result.ok();
    }

    /**
     * 用户登录
     *
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 1、判断手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        // 2、判断验证码是否正确
        String redisCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (code == null || !code.equals(redisCode)) {
            return Result.fail("验证码不正确");
        }
        // 3、判断手机号是否是已存在的用户
        User user = this.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone));
        if (Objects.isNull(user)) {
            // 用户不存在，需要注册
            user = createUserWithPhone(phone);
        }
        // 4、保存用户信息到Redis中，便于后面逻辑的判断（比如登录判断、随时取用户信息，减少对数据库的查询）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 将对象中字段全部转成string类型，StringRedisTemplate只能存字符串类型的数据
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String token = UUID.randomUUID().toString(true);
        String tokenKey = LOGIN_USER_KEY + token;
        // 存储
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        log.info("tokenKey:{}",tokenKey);
        // 设置过期时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 登录成功，将用户信息保存到ThreadLocal中，方便后续登录校验
        ThreadLocalUtls.saveUser(BeanUtil.copyProperties(userMap, UserDTO.class));
        return Result.ok(token);
    }

    /**
     * 根据手机号创建用户并保存
     *
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        this.save(user);
        return user;
    }

    /**
     * 用户签到
     *
     * @return
     */
    @Override
    public Result sign() {
        // 获取当前登录用户
        Long userId = ThreadLocalUtls.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 记录连续签到的天数
     *
     * @return
     */
    @Override
    public Result signCount() {
        // 1、获取签到记录
        // 获取当前登录用户
        Long userId = ThreadLocalUtls.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        // 2、判断签到记录是否存在
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        // 3、获取本月的签到数（List<Long>是因为BitFieldSubCommands是一个子命令，可能存在多个返回结果，这里我们知识使用了Get，
        // 可以明确只有一个返回结果，即为本月的签到数，所以这里就可以直接通过get(0)来获取）
        Long num = result.get(0);
        if (num == null || num == 0) {
            // 二次判断签到结果是否存在，让代码更加健壮
            return Result.ok(0);
        }
        // 4、循环遍历，获取连续签到的天数（从当前天起始）
        int count = 0;
        while (true) {
            // 让这个数字与1做与运算，得到数字的最后一个bit位，并且判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            } else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    @Override
    public Result logout(String token) {
        // 获取当前登录用户
        UserDTO user = ThreadLocalUtls.getUser();
        if (user != null) {
            // 清除ThreadLocal中的用户信息
            ThreadLocalUtls.removeUser();
            // 清除Redis中的用户登录信息
            stringRedisTemplate.delete(LOGIN_USER_KEY+token);
        }
        return Result.ok();
    }
}

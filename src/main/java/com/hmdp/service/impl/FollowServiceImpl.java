package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.model.dto.Result;
import com.hmdp.model.dto.UserDTO;
import com.hmdp.model.entity.Follow;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.ThreadLocalUtls;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.constants.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 关注用户
     *
     * @param followUserId 关注用户的id
     * @param isFollow     是否已关注
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = ThreadLocalUtls.getUser().getId();
        String key = FOLLOW_KEY + userId;
        if (isFollow) {
            // 用户未关注，则关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = this.save(follow);
            if (isSuccess) {
                // 用户关注信息保存成功，把关注的用户id放入Redis的Set集合中，
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 用户已关注，删除关注信息
            boolean isSuccess = this.remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 是否关注用户
     *
     * @param followUserId 关注用户的id
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = ThreadLocalUtls.getUser().getId();
        int count = this.count(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     *
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        Long userId = ThreadLocalUtls.getUser().getId();
        String key1 = FOLLOW_KEY + userId;
        String key2 = FOLLOW_KEY + id;
        // 查询当前用户与目标用户的共同关注对象
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (Objects.isNull(intersect) || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查询共同关注的用户信息
        List<UserDTO> userDTOList = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOList);
    }
}

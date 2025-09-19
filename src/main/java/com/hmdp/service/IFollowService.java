package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注用户
     * @param followUserId 关注用户的id
     * @param isFollow 是否已关注
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 是否关注用户
     * @param followUserId 关注用户的id
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 查询共同关注
     * @param id
     * @return
     */
    Result followCommons(Long id);
}

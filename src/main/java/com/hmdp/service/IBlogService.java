package com.hmdp.service;

import com.hmdp.model.dto.Result;
import com.hmdp.model.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 查询热门博客
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 点赞
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 查询所有点赞博客的用户
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 保存探店笔记
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 关注推送页面的笔记分页
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}

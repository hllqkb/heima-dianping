package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryBlogById(Long id) {
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryFollowBlog(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples=stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key,0,max,offset,2);
        if(typedTuples==null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 3、收件箱中有数据，则解析数据: blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 记录当前最小值
        int os = 1; // 偏移量offset，用来计数
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                // 当前时间等于最小时间，偏移量+1
                os++;
            } else {
                // 当前时间不等于最小时间，重置
                minTime = time;
                os = 1;
            }
        }

        // 4、根据id查询blog（使用in查询的数据是默认按照id升序排序的，这里需要使用我们自己指定的顺序排序）
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = this.list(new QueryWrapper<Blog>()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")"));

        // 设置blog相关的用户数据，是否被点赞等属性值
        for (Blog blog : blogs) {
            // 查询blog有关的用户
            queryUserByBlog(blog);
            // 查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 5、封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user=userService.getById(userId);
        blog.setUserId(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }
    private void queryUserByBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    private void isBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }
}

package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final StringRedisTemplate stringRedisTemplate;

    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result follow(Long id, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String follow_key="follows:"+userId;
        if(isFollow){
            Follow follow = new Follow();
//            follow.setId(id); 自动递增
            follow.setFollowUserId(id);
            follow.setUserId(userId);
            boolean is_success=save(follow);
            if(is_success){
                stringRedisTemplate.opsForSet().add(follow_key,id.toString());
            }
        }else{
            boolean is_success=remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id",id));
            if(!is_success){
                return Result.fail("取关失败");
            }
            stringRedisTemplate.opsForSet().remove(follow_key,id.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }
}

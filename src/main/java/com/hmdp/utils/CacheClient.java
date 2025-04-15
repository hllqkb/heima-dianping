package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
/*
* 缓存客户端
* 指定泛型ID，通过ID查询缓存，如果缓存中有数据，则直接返回，否则调用queryFunction查询数据库，并缓存数据，并返回数据
* */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        set(key, redisData, time, timeUnit);
    }
    public  <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function <ID, R> queryFunction,Long time, TimeUnit timeUnit) {
        // 解救缓存穿透
        String key=keyPrefix+id;
        String Json= stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(Json)){
            // 缓存中有数据
            return JSONUtil.toBean(Json,type);
        }
        if (Json != null ) {
            return null;
        }
        R r=queryFunction.apply(id);
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,timeUnit);
            return null;
        }
        set(key, r, time, timeUnit);
        return r;
    }

}

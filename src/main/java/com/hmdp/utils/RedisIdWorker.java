package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    //拼接id,符号位+时间戳+序列号（Redis)
    private static final long BEGIN_TIMESTAMP=1640995200L;// 2022-1-1-0-0-0
    private static final int COUNT_BITS = 32;//位运算位数
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPreFlex){
        LocalDateTime now = LocalDateTime.now();
        long nowSecond=now.toEpochSecond(java.time.ZoneOffset.UTC);
        long timestamp=nowSecond-BEGIN_TIMESTAMP;
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPreFlex+":"+date);
        return timestamp<<COUNT_BITS|count;
    }
}

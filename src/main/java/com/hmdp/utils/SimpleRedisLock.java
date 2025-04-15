package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;
public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate redisTemplate;
    private static final String lockPrefix = "lock:";
    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }
    @Override
    public boolean tryLock(long timeout){
        long threadId = Thread.currentThread().getId();
        Boolean isLocked=redisTemplate.opsForValue().setIfAbsent(lockPrefix+name,threadId+"",timeout, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(isLocked);//Boolean可能出现空指针
    }
    @Override
    public void unlock() {
        redisTemplate.delete(lockPrefix+name);
    }
}

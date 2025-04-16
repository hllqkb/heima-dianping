package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RedissonConfig {
    @Bean
    @Primary //标记为默认的RedissonClient
    public RedissonClient redisson() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379")
                .setPassword("123456");
        return Redisson.create(config);
    }
//    @Bean
//    public RedissonClient redisson2() {
//        Config config = new Config();
//        config.useSingleServer().setAddress("redis://localhost:6380");
//        return Redisson.create(config);
//    }
}

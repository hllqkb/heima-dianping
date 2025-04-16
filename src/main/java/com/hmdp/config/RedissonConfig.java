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
    @Primary
    public RedissonClient redisson() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://localhost:6379")
                .setPassword("123456")
                // 连接池配置
                .setConnectionPoolSize(64)    // 最大连接数
                .setConnectionMinimumIdleSize(10) // 最小空闲连接数
                // 超时设置
                .setConnectTimeout(10000)     // 连接超时时间(毫秒)
                .setTimeout(3000)             // 操作超时时间
                .setRetryAttempts(3)          // 命令重试次数
                .setRetryInterval(1000)       // 命令重试间隔(毫秒)
                // 心跳检测
                .setPingConnectionInterval(30000); // 心跳检测间隔(毫秒)

        return Redisson.create(config);
    }
}

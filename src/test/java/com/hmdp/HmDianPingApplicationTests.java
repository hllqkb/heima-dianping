package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    //线程池
    private ExecutorService es= Executors.newFixedThreadPool(500);//建立500个线程
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Test
    void testRedisIdWorker(){
        Runnable runnable = ()->{
            for(int i=0; i<500; i++){
                //测试RedisWorker
            }
        };
    }
    @Test
    void testSaveShop(){
    shopService.saveShop2Redis(1L,10L);
}

}

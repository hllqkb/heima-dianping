package com.hmdp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

public class JedisTest {
    private Jedis jedis;
    @BeforeEach
    public void setUp() {
        jedis = new Jedis("localhost");
        jedis.auth("123456");
    }
    @Test
    public void tetsHashSet(){
        jedis.hset("jedis-test", "key1", "value1");
        jedis.hset("jedis-test", "key2", "value2");
        String value1 = jedis.hget("jedis-test", "key1");
        String value2 = jedis.hget("jedis-test", "key2");
        System.out.println(value1);
        System.out.println(value2);
    }
    @Test
    public void testSet() {
        jedis.set("jedis-test", "value");
        String value = jedis.get("jedis-test");
        System.out.println(value);
    }
    @AfterEach
    public void tearDown() {
        if(jedis==null) return;
        jedis.close();
    }
}

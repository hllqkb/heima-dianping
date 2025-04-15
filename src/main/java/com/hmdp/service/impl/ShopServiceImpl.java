package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Resource
    private CacheClient cacheClient;

    @Override
    public Object queryById(Long shopId) {
        //缓存穿透
//        Shop shop=queryWithPassThrough(shopId);
        //互斥锁解决缓存穿透
//        Shop shop=queryWithMutex(shopId);
        // 逻辑过期解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,shopId,Shop.class, this::getById,
//                CACHE_SHOP_TTL,TimeUnit.MINUTES);
        Shop shop = queryWithLogicalExpire(shopId);
        if(shop==null){
            return Result.fail("店铺不存在!");
        }
        return shop;
    }
    private Shop queryWithMutex(Long shopId) {
        String key=CACHE_SHOP_KEY+shopId;
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            // 缓存中有数据
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        if (shopJson != null ) {
            // 缓存中有数据，但是是空数据，缓存穿透
            return null;
        }
        //未命中，加互斥锁
        String lockKey="lock:shop:"+shopId;
        Shop shop= null;
        try {
            boolean isLock=tryLock(lockKey);
            if(!isLock){
                // 加锁失败，直接重试
                    Thread.sleep(50);
                    return queryWithMutex(shopId);
            }
            shop = getById(shopId);
            //模拟延时
            Thread.sleep(200);
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key,"");
                return null;
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unlock(lockKey);
        }
        return shop;
    }
    //缓存重建线程池
    private static final ExecutorService CACHE_REBUILD_THREAD_POOL= Executors.newFixedThreadPool(10);
    private Shop queryWithLogicalExpire(Long shopId) {
        String key=CACHE_SHOP_KEY+shopId;
        String shopJson= stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            // 缓存中有数据
            return null;
        }
        //命中，反序列化数据
        RedisData redisData=JSONUtil.toBean(shopJson,RedisData.class);


        Shop shop= JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        JSONObject data= (JSONObject) redisData.getData();
        LocalDateTime expireTime=redisData.getExpireTime();
        //判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期直接返回店铺数据
            return shop;
        }
        //已经过期
        //缓存重建
        //获取互斥锁
        String lockKey=LOCK_SHOP_KEY+shopId;
        boolean isLock=tryLock(lockKey);
        //判断是否获取锁成功
        if(isLock) {
            //获取锁成功，开启新线程，并设置缓存
            CACHE_REBUILD_THREAD_POOL.submit(()-> {
                try {
                    this.saveShop2Redis(shopId, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                //释放锁
                unlock(lockKey);
                    }
            } );
        }
        //失败，直接返回商铺信息（已经过期）
        return shop;
    }

    @Override
    public Object update(Shop shop) {
        // 先改数据库，再删除缓存
        Long id=shop.getId();
        if(id==null){
            return Result.fail("商铺id不能为空");
        }
        queryById(id);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
    private boolean tryLock(String key){
        Boolean flag=stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//防止空指针
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    // 保存商品到redis
    public void saveShop2Redis(Long id,Long expireTime){
        // 查询数据
        Shop shop=getById(id);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 封装过期时间
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));// 逻辑过期时间
        // 存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}

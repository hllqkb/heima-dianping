package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
       //准备线程池
    private static final ExecutorService seckillExecutor = Executors.newSingleThreadExecutor();
    @PostConstruct
    public void init() {
        //类初始化后运行线程任务
        seckillExecutor.submit(new VoucherOrderTask());
    }
    //创建线程任务
    private class VoucherOrderTask implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //不断从消息队列获取消息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
  /*            >: 从最新的消息开始读取，包括命令执行时流中已有的最新消息。
                    $: 从当前时间开始读取，只读取在命令执行之后产生的新消息。*/
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"), StreamReadOptions
                            .empty().count(1).block(Duration.ofSeconds(2)), StreamOffset.create("stream.orders",
                            ReadOffset.lastConsumed()));

                    //判断获取是否成功，没有消息继续循环
                    if(list==null||list.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values=record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果有消息进行下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认消息已消费
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",
                            record.getId());
                } catch (Exception e) {
                    log.error("订单队列取出异常", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                //不断从PendingList获取消息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"), StreamReadOptions
                        .empty().count(1), StreamOffset.create("stream.orders",
                        ReadOffset.from("0")));

                //判断获取是否成功，没有消息结束循环
                if(list==null||list.isEmpty()){
                    break;}

                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values=record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //如果有消息进行下单
                handleVoucherOrder(voucherOrder);
                //ACK确认消息已消费
                stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",
                        record.getId());

            } catch (Exception e) {
                log.error("PendingList队列取出异常", e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    //处理订单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
//        UserDTO user = UserHolder.getUser();
        long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:"+userId);//Redisson实现分布式锁
        boolean lockResult=lock.tryLock();

        if(!lockResult){
            //获取锁失败
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    //秒杀Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT ;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private IVoucherOrderService proxy;
    @Resource
    private  IVoucherOrderService voucherService;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long orderId=redisIdWorker.nextId("order");
        proxy= (IVoucherOrderService) AopContext.currentProxy();
        Long userId=UserHolder.getUser().getId();
        if(userId==null){
            return Result.fail("请先登录");
        }
        //执行Lua脚本
        Long result=stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(),userId.toString(),
               orderId.toString() );
        //判断是否为0
        int r= 1;
        if (result != null) {
            r = result.intValue();
        }

        if(r !=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //0有购买资格
        //将下单信息添加到阻塞队列(已经在Lua脚本中完成)
        //生成订单id
        //返回下单id
        return Result.ok(orderId);
       /* //查询优惠卷
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }
    *//*
        //扣减库存
        //判断是否有库存
        if(seckillVoucher.getStock()<=0){
            return Result.fail("库存不足");
        }
     seckillVoucher.setStock(seckillVoucher.getStock()-1);
        boolean result=seckillVoucherService.updateById(seckillVoucher);
        问题：
非原子操作：查询→计算→更新不是原子操作，存在并发问题
竞态条件：多个线程可能同时通过库存检查，导致超卖
全量更新：updateById会更新所有字段，效率较低且可能覆盖其他线程的修改
        *//*
        //一人一单,不同用户加悲观锁
        Long userId=UserHolder.getUser().getId();
          //返回结果，实现每个线程的不同锁，锁定同一个用户的不同线程
//       SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:"+userId);//Redisson实现分布式锁
        boolean lockResult=lock.tryLock();

        if(!lockResult){
            //获取锁失败
            return Result.fail("请求次数过多，请稍后再试");
        }
        try {
            IVoucherOrderService proxy= (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }*/
    }
    @Transactional // 开启事务，出现问题实现回滚
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long userId=voucherOrder.getUserId();
        long voucherId=voucherOrder.getVoucherId();
        int count=query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        long stock=seckillVoucherService.getById(voucherId).getStock();
        if(count>0){
            log.error("用户{}已经购买过该优惠券",userId);
            //表示出现数据库缓存内容不一致，需要刷新缓存
            stringRedisTemplate.delete("seckill:stock:"+voucherId);
            stringRedisTemplate.opsForValue().set("seckill:stock:"+voucherId, String.valueOf(stock),30, TimeUnit.MINUTES);
            return;
        }
        boolean result=seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();
        //乐观锁，在更新数据的时候进行使用
        //整合成一条sql，避免并发问题

        if(!result){
            log.error("库存不足");
            return;
            //要Return
        }
        //保存订单
        save(voucherOrder);
        log.info("创建订单成功，订单号：{}", voucherOrder.getId());
    }
}

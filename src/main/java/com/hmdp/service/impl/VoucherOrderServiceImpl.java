package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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
    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠卷
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束");
        }
    /*
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
        */
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
        }
    }
    @Transactional // 开启事务，出现问题实现回滚
    public Result createVoucherOrder(Long voucherId){
        Long userId=UserHolder.getUser().getId();
        int count=query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if(count>0){
            return Result.fail("您已参与过秒杀");
        }
        boolean result=seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();
        //乐观锁，在更新数据的时候进行使用
        //整合成一条sql，避免并发问题

        if(!result){
            return Result.fail("扣减库存失败");
            //要Return
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        UserDTO userDTO = UserHolder.getUser();
        long orderId=redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userDTO.getId());
        save(voucherOrder);
        log.info("创建订单成功，订单号：{}", orderId);
        return Result.ok("恭喜您，抢到优惠券");
    }
}

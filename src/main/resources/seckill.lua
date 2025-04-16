--Lua脚本用于秒杀活动，根据用户id和优惠卷id判断用户是否可以秒杀，并扣减库存和下单，实现原子性操作。
--XGROUP CREATE stream.orders g1 0 MKSTREAM 运行前需先创建消息队列和消费组
--1.参数列表
-- 优惠卷id
local voucher_id=ARGV[1]
-- 用户id
local user_id=ARGV[2]
--订单id
local order_id=ARGV[3]
--库存key
local stock_key="seckill:stock:"..voucher_id
--订单key
local order_key="seckill:order:"..voucher_id
--2.判断库存是否充足
local stock=redis.call("get",stock_key)
if not stock then
    --缓存不存在
    return 1
end
if tonumber(stock)<=0 then
    --库存不足
    return 1
end
--3 判断用户是否下单,用SET集合判断用户是否已经下单
if (redis.call("sismember",order_key,user_id)==1) then
    --用户已经下单
    return 2
end
--扣减库存
redis.call("incrby",stock_key,-1)
--下单
redis.call("sadd",order_key,user_id)
--发送消息到消息队列中 XADD stream:order * user_id user_id order_id order_id voucher_id voucher_id
redis.call("xadd","stream.orders","*","userId",user_id,"voucherId",voucher_id,"id",order_id)
return 0
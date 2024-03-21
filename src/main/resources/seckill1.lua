--- 1. 需要用到的参数
-- 1.1 优惠券id       voucherId
-- 1.2 用户id        userId
-- 1.3 当前优惠券集合
local voucherId = ARGV[1]
local userId = ARGV[2]

--- 2. 数据key
-- 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单key
local orderKey = 'seckill:order:' .. voucherId

--- 3. lua业务
-- 判断库存是否充足 直接看stock是否>0
if(tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足 返回1
    return 1
end

-- 库存充足
-- 判断用户是否下过单
if (redis.call('sismember', stockKey, userId) == 1) then
    -- 重复下单 返回2
    return 2
end

-- 没用买过, 可以购买
-- 扣库存
redis.call('incrby', stockKey, -1)
-- 加入已购集合set
redis.call('sadd', orderKey, userId)

-- 成功返回0
return 0

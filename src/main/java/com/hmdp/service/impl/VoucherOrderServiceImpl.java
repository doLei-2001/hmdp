package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.apache.ibatis.javassist.Loader;
import org.springframework.aop.framework.AopContext;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    // 注入秒杀券的service
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id实现特惠优惠券的购买
     **/
    @Override
    public Result seckillVoucher(Long id) {
        // 1. 根据id查找优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(id);

        // 2. 判断秒杀是否开始
        LocalDateTime beginTime = seckillVoucher.getBeginTime();

        // 未开始 - 返回异常
        if (LocalDateTime.now().isBefore(beginTime))
            return Result.fail("当前抢购尚未开始!");

        // 3. 判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束...");
        }

        // 4. 开始了
        // 4.1 判断库存是否充足
        Integer stock = seckillVoucher.getStock();
        if (stock <= 0) return Result.fail("库存不足, 下次再来");

        // 2. 【一人一单】最终的悲观锁方案
        Long userId = UserHolder.getUser().getId();

        // 【集群一人一单】
        // 获取锁
        SimpleRedisLock simpleRedisLock =
                new SimpleRedisLock(stringRedisTemplate, "order:" + userId);// order-订单；userid-标识用户
        boolean isLock = simpleRedisLock.tryLock(1200);

        if (!isLock)
            return Result.fail("不允许重复下单！");

        try {
            // 获取本对象的代理接口对象  (为了下面的@Transactional事务可以生效
            // （∵不用代理对象的事务方法调用不会生效 spring事务失效的几种可能性之一）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();

            // 调用代理接口对象的方法
            return proxy.createVoucherOrder(id);
        }finally {
            simpleRedisLock.unlock();
        }
    }

    @Transactional      // 1.【一人一单】事务注解首先要夹在独立方法上
    public  Result createVoucherOrder(Long id) {
        // 补充[一人一单]
        Long userId = UserHolder.getUser().getId();

        // 判断该user是否买过了
        Integer count = query().eq("user_id", userId).eq("voucher_id", id).count();
        if (count > 0) return Result.fail("该用户已经购买过了");

        // 4.2 扣减库存(直接操作数据库)
        seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", id)
                //.eq("stock", stock)              // 也是乐观锁, 但是会导致最终的失败率过高!
                .gt("stock", 0)         // 【超卖问题】加入【乐观锁】, 数据只有>0的时候才会被修改(∵库存数据比较特殊)
                .update();

        // 4.3 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(id);

        save(voucherOrder);

        // 4.4 返回订单id
        return Result.ok(voucherOrder.getId());

    }
}

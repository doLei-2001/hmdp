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

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService  {

    @Resource
    private ISeckillVoucherService seckillVoucherService;   // 注入秒杀券的service

    @Resource
    private RedisIdWorker redisIdWorker;        // 生成全局唯一id

    @Resource
    private RedissonClient redissonClient;      // 使用redisson  (加锁

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // lua脚本读入
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;    // 脚本语言读入
    static {
        // 提前将脚本的文件给读出来
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建单线程的线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct      // 在类实例化后立即执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());   // 实例化后就直接开一个线程去执行下面的VoucherOrderHandler方法
        /**
         * execute()方法用于提交不需要返回值的任务，所以无法判断任务是否被线程池执行成功与否；
         * submit()方法用于提交需要返回值的任务,线程池会返回一个 Future 类型的对象
         **/
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(     // 这里的read()对应redis的XREADGROUP方法
                            Consumer.from("g1", "c1"),              // GROUP g1 c1
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),         // COUNT 1 BLOCK 2000
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())   // STREAMS stream.orders
                    );

                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {       // 注意这里的null和empty的区别
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }

                    // 解析数据
                    // 👇注意这里是三个变量的pair
                    MapRecord<String, Object, Object> record = list.get(0);     // 由于read()中设置了COUNT 1, 一次只读一条数据
                                                                                // ∴这里的list中只有一个数据
                    Map<Object, Object> value = record.getValue();  // 键值对
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    // 3.创建订单
                    createVoucherOrder(voucherOrder);

                    // 4.确认消息 XACK  根据redis自动生成的id来发送确认
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());

                } catch (Exception e) {
                    /**
                     * 有异常, 那要重新去取消息, 直到处理完笑死   (所有的消息都必须要被处理)
                     * 取PendingList中的消息来处理
                     **/
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))  // 这里的0代表了从pendinglist中取第一个数据
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    // 如果又发生了异常,不用递归.  ∵这里是while(true), 死循环没事, 还会继续走
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    /**
     * 根据voucherOrder订单信息  实际写数据库
     * 使用redisson来加锁
     **/
    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        // 获得  可重入锁  !
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            // 兜底 redis中通过lua脚本已经实现了一人一单的购买资格判断
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }










    /**
     * 购买秒杀优惠券
     * 【任务分离】
     * 【使用lua脚本判断是否有购买资格(超卖/一人一单)】实现【使用阻塞队列进行数据库的插入操作】
     **/
    @Override
    public Result seckillVoucher(Long voucherId) {

        // 生成订单id
        long orderId = redisIdWorker.nextId("order");
        Long userId = UserHolder.getUser().getId();

        // 执行lua脚本
        // java使用redisTemplate提供的方法调用脚本
        // 参数: 脚本; 参数列表值; 其他参数值
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        // 判断结果是否为0
        // 不为0，没用购买权限
        int r = res.intValue();
        if (r != 0)
            return Result.fail(res == 1 ? "库存不足" : "不能重复购买");



        // todo 将优惠券信息放入阻塞队列



        return Result.ok(orderId);
    }


    /**
     * 购买秒杀优惠券
     * 【任务分离】
     * 【使用lua脚本判断是否有购买资格(超卖/一人一单)】【使用阻塞队列进行数据库的插入操作】
     **/
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        // java使用redisTemplate提供的方法调用脚本
        // 参数: 脚本; 参数列表值; 其他参数值
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());

        // 判断结果是否为0
        // 不为0，没用购买权限
        int r = res.intValue();
        if (r != 0)
            return Result.fail(res == 1 ? "库存不足" : "不能重复购买");

        // 生成订单id
        long orderId = redisIdWorker.nextId("order");

        // todo 将优惠券信息放入阻塞队列



        return Result.ok(orderId);
    }*/



    /**
     * 秒杀业务(1.不能超卖; 2.一人一单)
     * 根据id实现特惠优惠券的购
     **/
    /*@Override
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
    }*/

    /*@Transactional      // 1.【一人一单】事务注解首先要夹在独立方法上
    public  Result createVoucherOrder(Long id) {
        // 补充[一人一单]
        Long userId = UserHolder.getUser().getId();

        // 判断该user是否买过了
        Integer count = query().eq("user_id", userId).eq("voucher_id", id).count();
        if (count > 0) return Result.fail("该用户已经购买过了, 不能重复下单");

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

    }*/
}

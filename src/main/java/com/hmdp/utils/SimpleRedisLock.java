package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String businessName;    // 业务名称（用来标识锁对应哪个业务）

    public SimpleRedisLock(StringRedisTemplate redisTemplate, String name) {
        this.stringRedisTemplate = redisTemplate;
        this.businessName = name;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";  // 用uuid来唯一标识一个线程。 再加个杠为了后面在拼接线程id

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;    // 脚本语言读入

    static {
        // 提前将脚本的文件给读出来
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    /**
     * 【集群一人一单】 释放锁
     * 使用lua脚本保证[判断该锁是不是自己的][释放锁]这两个操作的原子性
     **/
    @Override
    public void unlock() {
        // java使用redisTemplate提供的方法调用脚本
        // 参数: 脚本; 参数列表值; 其他参数值
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + businessName),
                ID_PREFIX + Thread.currentThread().getId());

        // 可以释放, 上面代码就给它释放了, 释放不了, 就啥都不用管
    }

    /**
     * 获取锁
     **/
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取当前线程的标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 获取锁
        Boolean res = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + businessName, threadId, timeoutSec, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(res);
    }



    /*@Override
    public void unlock() {
        // 判断该锁自己可否释放
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + businessName);

        // 可以释放，才释放
        if (id.equals(threadId))
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + businessName);
    }*/
}

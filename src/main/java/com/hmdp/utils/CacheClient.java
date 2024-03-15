package com.hmdp.utils;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


/**
 * 数据写入redis缓存的工具类
 **/
@Slf4j
@Component
public class CacheClient {
    /**
    * 将数据存到redis
    */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
    * 将对象value 以json字符串格式存到redis
    */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
    * 为防止【缓存击穿】的逻辑过期存入
    */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 包裹数据
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 存储
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 防止【缓存穿透】地获取数据  (返回空值)
     * 使用注解完成该任务  好好学
     **/
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // redis中有这个数据，直接返回
            // 因为这里是一个string，得先反序列化为对象
            Shop cacheShop = JSONUtil.toBean(shopJson, Shop.class);
            return cacheShop;
        }

        // 【防止内存穿透】 上面if下来后，可能为  null "" /n  这类
        //  所以防止内存穿透往redis中放的“”  就有可能走下来，但是不能让这类值去查询数据库
        if (shopJson != null)       // != null 就说明这个值为 "" 空字符串(防止缓存击穿设置的值)
            return null;

        // 查询数据库
        // redis中没有这个数据，去数据库拿
        Shop databaseShop = query().eq("id", id).one();
        if (databaseShop == null) {
            // 【防止内存穿透】 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            // 返回错误信息
            return null;
        }

        // 把这个商户存到redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(databaseShop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return databaseShop;
    }

    /**
    * 防止【缓存击穿】地获取数据
    */
    public Shop queryWithLogicalExpire(Long id) {
        String cacheShopKey = CACHE_SHOP_KEY + id;
        // 从Redis查询商铺缓存
        String redisJsonDataShop = stringRedisTemplate.opsForValue().get(cacheShopKey);

        // 判断缓存是否命中
        if (StrUtil.isBlank(redisJsonDataShop))
            // 未命中，直接返回null, 说明这个数据没有提前预热在redis中, 说明这不是个热点数据
            return null;

        // 命中
        // 判断缓存是否过期, 未过期, 直接拿到并返回新数据
        // 先转为对象
        RedisData redisData = JSONUtil.toBean(redisJsonDataShop, RedisData.class);
        JSONObject shopJsonObject = (JSONObject) redisData.getData();
        Shop redisShop = JSONUtil.toBean(shopJsonObject, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 未过期, 直接返回新数据
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return redisShop;
        }

        // 逻辑过期了
        // 尝试获取互斥锁
        // 判断锁是否获取成功
        // 未成功获取锁，直接返回店铺信息
        String lockShopKey = LOCK_SHOP_KEY + id;
        if (!tryLock(lockShopKey)) {
            return redisShop;
        }

        // 成功获取锁
        // 再次检查redis看是否有新的数据存在redis了, 如果有, 那就不用再建立线程写数据了
        /*...先不写这个判断  省略*/

        // 开启独立线程
        // 创建一个线程池
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

        // 在独立线程中执行任务
        executorService.schedule(() -> {
            // 根据id查数据库 修改redis数据并设置逻辑过期时间
            try {
                saveShop2Redis(id, 20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }finally {
                // 释放互斥锁
                unLock(lockShopKey);
            }

        }, 0, TimeUnit.SECONDS);

        // 关闭线程池
        executorService.shutdown();



        // 直接返回旧数据 (无论逻辑是否过期, 都返回旧数据)
        return redisShop;
    }
}





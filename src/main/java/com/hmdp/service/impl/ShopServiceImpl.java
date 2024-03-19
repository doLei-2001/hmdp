package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    CacheClient cacheClient;

    /**
     * @DESCRIPTION 根据商户id 查询数据库  (加入redis作为缓存)
     **/
    @Override
    public Result queryById(Long id) {
        // 防止【缓存穿透】查店铺数据
        //Shop shop = queryWithPassThrough(id);

        // 【逻辑过期 解决 缓存击穿】
        //Shop shop = queryWithLogicalExpire(id);

        // 封装好的 防止【缓存穿透】查店铺数据
        //Shop shop =
        //        cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, (id1) -> getById(id1), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 封装好的【逻辑过期 解决 缓存击穿】
        Shop shop =
                cacheClient
                        .queryWithLogicalExpire
                                (CACHE_SHOP_KEY, id, Shop.class, LOCK_SHOP_KEY, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);
    }



    /**
     * queryById - 防止【缓存穿透】查数据
     **/
    /*public Shop queryWithLogicalExpire(Long id) {
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
        *//*...先不写这个判断  省略*//*

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
    }*/

    /**
     * queryById - 防止【缓存穿透】查数据
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
     * 【逻辑过期防止缓存击穿】 模拟预热前把店铺信息预先放到redis中
     * 用@Test测试方法来模拟预热放入redis
     **/
    /*public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 查到店铺对象
        Shop shop = getById(id);
        Thread.sleep(200);

        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));    // 加上过期时间

        // 存到redis中
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, jsonStr);
    }*/

    /**
     * 获取互斥锁
     * 为什么用setIfAbsent()作为互斥锁详见笔记
     **/
    /*public Boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }*/

    /**
     * 释放互斥锁
     **/
    /*public void unLock(String key) {
        // 直接把这个键值对删了
        stringRedisTemplate.delete(key);

    }*/

    /**
     * 修改商铺信息
     **/
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) return Result.fail("店铺id不能为空!");

        // 修改数据库
        updateById(shop);

        // 删除缓存
        // 这个方法删除失败会抛出异常的, 上面加了@Transactional事务. 抛出异常, 那就全部执行成败
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return null;
    }
}

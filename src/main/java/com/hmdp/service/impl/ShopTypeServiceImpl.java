package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;


    @Override
    public Result queryAllShopTypes() {
        String cacheShoptypeKey = RedisConstants.CACHE_SHOPTYPE_KEY;

        List<ShopType> shopTypeList = new ArrayList<>();

        // 直接去查redis中
        if (stringRedisTemplate.hasKey(cacheShoptypeKey)) {
            // 如果有, 整理好直接返回list
            // 获取所有数据
            List<String> allShoptype = stringRedisTemplate.opsForList().range(cacheShoptypeKey, 0, -1);

            // 整理好数据
            for (String s : allShoptype) {
                ShopType shopType = ShopType.fromString(s);
                shopTypeList.add(shopType);
            }

            return Result.ok(shopTypeList);
        }

        // 如果没有, 查数据库
        List<ShopType> databaseShoptype = query().orderByAsc("sort").list();

        // 存到redis中
        // 先将查出来的数据转换为String
        List<String> stringList = new ArrayList<>();
        for (ShopType shopType : databaseShoptype) {
            stringList.add(shopType.toString()); // 假设 ShopType 类有合适的 toString() 方法
        }

        // 存到redis中
        stringRedisTemplate.opsForList().rightPushAll(cacheShoptypeKey, stringList);

        return Result.ok(databaseShoptype);
    }
}

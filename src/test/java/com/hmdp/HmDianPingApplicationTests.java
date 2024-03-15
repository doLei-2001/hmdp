package com.hmdp;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    ShopServiceImpl shopService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Test
    public void testMoNi() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);    // 模拟10s过期

    }

    @Test
    public void testRedisData() {
        Long id = 1L;
        String cacheShopKey = CACHE_SHOP_KEY + id;
        // 从Redis查询商铺缓存
        String redisJsonDataShop = stringRedisTemplate.opsForValue().get(cacheShopKey);

        // 命中
        // 判断缓存是否过期, 未过期, 直接拿到并返回新数据
        // 先转为对象
        RedisData redisData = JSONUtil.toBean(redisJsonDataShop, RedisData.class);
        JSONObject shopJsonObject = (JSONObject) redisData.getData();
        Shop redisShop = JSONUtil.toBean(shopJsonObject, Shop.class);

        System.out.println(redisShop);
    }

}

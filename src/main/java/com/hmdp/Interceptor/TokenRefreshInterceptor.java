package com.hmdp.Interceptor;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @DESCRIPTION 登录拦截器, 验证登录信息, 把用户信息放入到threadlocal中
 **/
public class TokenRefreshInterceptor implements HandlerInterceptor {

    StringRedisTemplate stringRedisTemplate;        // ∵这个类没有交给spring管理,所以不能自动注入, 得自己通过构造方法传入

    public TokenRefreshInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //  1. 获取token (在请求头中)
        String token = request.getHeader("authorization");

        if (token == null) {
            // 为空说明没登录成功, 是下一个拦截器该干的事, 直接放行
            return true;
        }

        //  2. 从redis中获取用户
        Map<Object, Object> userMap =
                stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);

        // 3. 判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }

        // 将usermap转为userdto
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 4. 保存用户userdto到threadlcal中
        UserHolder.saveUser(userDTO);

        //  设置登录token续时
        // 每次访问后, 都重新计时
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        // 5. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 手动释放map中的值
        UserHolder.removeUser();

        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}

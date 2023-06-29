package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
// 拦截所有请求，刷新token有效期
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate redisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取session
        // HttpSession session = request.getSession();
        // Object user = session.getAttribute("user");

        // 获取请求头中的token
        String token = request.getHeader("authorization");
        // 判断token是否为空
        if (StrUtil.isBlank(token)) {
            // token不存在
            return true;
        }

        // 从redis中获取用户信息
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY + token);
        // 判断是否存在用户信息
        if (userMap == null){
            // 用户信息不存在同时返回错误码
            return true;
        }

        // 将map数据转换为UserDto类型数据
        UserDTO userDto = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 保存到ThreadLocal中
        UserHolder.saveUser(userDto);
        // 刷新token有效期
        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}

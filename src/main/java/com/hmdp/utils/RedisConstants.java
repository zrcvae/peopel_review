package com.hmdp.utils;

public class RedisConstants {
    // 存储手机号key前缀
    public static final String LOGIN_CODE_KEY = "login:code:";
    // 登录存储验证码有效时间
    public static final Long LOGIN_CODE_TTL = 2L;
    // 用户信息key
    public static final String LOGIN_USER_KEY = "login:token:";
    // 用户信息的有效期，和设置session有效期一样
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop:typekey";


    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}

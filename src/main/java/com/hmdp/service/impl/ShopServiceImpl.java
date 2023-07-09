package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zrc
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透问题
        // Shop shop = queryThrough(id);

        // 利用互斥锁解决缓存击穿
        Shop shop = queryMutex(id);
        if (shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }
    // 利用互斥锁解决缓存击穿的方法
    public Shop queryMutex(Long id){
        // 从redis中根据id查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = redisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 经过上面的判断，如果不为null，一定为空值，直接返回错误信息
        if (shopJson != null){
            return null;
        }
        // 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean flag = tryLock(LOCK_SHOP_KEY);
            // 判断是否获取成功
            if (!flag){
                // 获取失败，休眠并重试（通过递归的方式重试）
                Thread.sleep(LOCK_SHOP_TTL);
                return queryMutex(id);
            }

            // 获取成功，则查询数据库，还需要进行二次判断是否存在缓存，如果没有缓存就将查询结果写入缓存
            // 如果不存在，从数据库中查询
            shop = getById(id);
            // 模拟高并发的延迟时间
            // Thread.sleep(200);
            // 判断数据库中查询结果是否存在
            if (shop == null){
                // 缓存空对象，防止缓存击穿(失效时间设置短一些)
                redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 如果不存返回错误信息，如果存在将查询信息存入redis中,设置失效时间
            redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 最后释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }

    // 防止缓存穿透的方法代码
    public Shop queryThrough(Long id){
        // 从redis中根据id查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = redisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 经过上面的判断，如果不为null，一定为空值，直接返回错误信息
        if (shopJson != null){
            return null;
        }
        // 如果不存在，从数据库中查询
        Shop shop = getById(id);
        // 判断数据库中查询结果是否存在
        if (shop == null){
            // 缓存空对象，防止缓存击穿(失效时间设置短一些)
            redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 如果不存返回错误信息，如果存在将查询信息存入redis中,设置失效时间
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    // 尝试获取锁的方法
    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 释放互斥锁锁
    private void unlock(String key){
        redisTemplate.delete(key);
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        // 更新数据库
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不存在");
        }
        updateById(shop);
        // 删除缓存
        redisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}

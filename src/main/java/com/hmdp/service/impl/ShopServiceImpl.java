package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    @Autowired
    private CacheClient cacheClient;
    // 创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透问题
        // Shop shop = queryThrough(id);
        cacheClient.queryPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 利用互斥锁解决缓存击穿
        Shop shop = queryMutex(id);
        // 利用逻辑过期处理解决缓存击穿
        // Shop shop = queryLogicalExpire(id);
        if (shop == null){
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    /** 利用逻辑过期处理缓存击穿
     * 改方法改写在Redis工具类中，直接调用
    public Shop queryLogicalExpire(Long id){
        // 从redis中根据id查询商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = redisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isBlank(shopJson)){
            // 如果为空直接返回
            return null;
        }
        // 反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期,如果没有过期直接返回shop
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 过期进行缓存重建，首先判断是否可以获取互斥锁，如果没法获取，直接返回过期数据
        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        if (flag){
            // 开辟新线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 这里过期时间普遍设置为30分钟
                    this.saveShopToRedis(id, CACHE_SHOP_TTL);
                } catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }
     */


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

    /** 防止缓存穿透的方法代码
     * 写在Redis工具类中
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
     */

    // 尝试获取锁的方法
    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    // 释放互斥锁锁
    private void unlock(String key){
        redisTemplate.delete(key);
    }

    /**
     * 向Redis中写入店铺数据并且设置逻辑过期时间
     * @param id
     * @return
     */
    public void saveShopToRedis(Long id, Long expireSeconds){
        // 查询店铺信息
        Shop shop = getById(id);
        // 封装过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入Redis
        redisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 判断x、y是否传入值，如果没有就按照传统的分页查询店铺信息
        if (x == null || y == null){
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 查询Redis，并按照距离排序
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if (results == null){
            return Result.ok();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // 如果需要查询到的信息小于需要跳过的，直接结束
        if (list.size() <= from){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> map = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            map.put(shopIdStr, result.getDistance());
        });

        // 根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}

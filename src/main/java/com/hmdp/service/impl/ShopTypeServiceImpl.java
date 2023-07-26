package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zrc
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result queryList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 先从redis缓存中查找，判断是否存在数据
        List<String> typeJson = redisTemplate.opsForList().range(key, 0, -1);
        if (CollectionUtil.isNotEmpty(typeJson)){
            // 转换类型
            List<ShopType> typeList = new ArrayList<>();
            for(String json : typeJson){
                ShopType shopType = JSONUtil.toBean(json, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }
        // 如果Redis中没有，从数据库中查询，如果查询到，存入redis中并返回，如果没有返回错误信息
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null){
            return Result.fail("错误！");
        }
        List<String> typeTypeJson = new ArrayList<>();
        for (ShopType shop : typeList){
            String jsonStr = JSONUtil.toJsonStr(shop);
            typeTypeJson.add(jsonStr);
        }
        redisTemplate.opsForList().rightPushAll(key, typeTypeJson);
        return Result.ok(typeList);
    }
}

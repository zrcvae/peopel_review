package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisID redisID;
    @Test
    void testSaveShop(){
        shopService.saveShopToRedis(1L, 10L);
    }

    @Test
    void testRedisId(){
        long orderId = redisID.nextId("order");
        System.out.println(orderId);
    }


}

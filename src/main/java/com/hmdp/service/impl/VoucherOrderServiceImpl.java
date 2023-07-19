package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisID;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zrc
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService voucherService;
    @Autowired
    private RedisID redisID;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 根据id查询订单信息
        SeckillVoucher voucher = voucherService.getById(voucherId);
        // 判断优惠时间是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("优惠时间尚未开始");
        }
        // 判断优惠时间是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠时间已经结束");
        }
        // 判断优惠券是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("优惠券以售完");
        }
        Long userId = UserHolder.getUser().getId();
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, redisTemplate);
        boolean tryLock = lock.tryLock(5);
        if (!tryLock){
            return Result.fail("不可重复购买");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherorder(voucherId);
        }finally {
            // 释放锁
            lock.unlock();
        }

        /**
         * 使用悲观锁，对用户的id加锁，保证每个用户只能购买一次优惠券
         * 如果仅仅是在方法中对用户id进行加锁，会导致数据没有进入数据库，锁已经释放，依然存在安全问题
         * 因此需要将锁加在方法上，保证事务提交后再释放锁
         * 这里代码注释，因为要使用分布式锁结构，解决集群中的多进程互斥
         */
//        synchronized (userId.toString().intern()) {
//            // 这里如果直接使用createVoucherorder(voucherId);会出现spring代理失效
//            // 导致@Transactional不起作用,需要获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherorder(voucherId);
//        }
    }
    @Transactional
    public Result createVoucherorder(Long voucherId) {
        // 一人一单（根据用户id和优惠券id查询是否已经存在订单）
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0){
            return Result.fail("已经购买后，不能重复购买");
        }
        // 扣减库存(使用乐观锁避免多线程并发安全问题stock > 0的情况安全)
        boolean success = voucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success){
            return Result.fail("优惠券库存不足");
        }
        // 满足以上条件开始创建订单，需要输入用户id，订单id，代金券id
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisID.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 将点单信息写入数据库
        save(voucherOrder);
        return Result.ok(voucherId);
    }
}

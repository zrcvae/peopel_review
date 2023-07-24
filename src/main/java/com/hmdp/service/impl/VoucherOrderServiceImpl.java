package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisID;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zrc
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService voucherService;
    @Autowired
    private RedisID redisID;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    // 阻塞队列，如果没有元素就会一直阻塞，知道有元素才会被唤醒
    private BlockingQueue<VoucherOrder> orderT = new ArrayBlockingQueue<>(1024 * 1024);
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
        // SimpleRedisLock lock = new SimpleRedisLock("order" + userId, redisTemplate);
        // 使用Redisson创建锁
        RLock lock = redissonClient.getLock("order" + userId);
        boolean tryLock = lock.tryLock();
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
/**
 * 秒杀Redis消息队列实现**************************************************************************************************************

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        private final String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 0.初始化stream
                    initStream();
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    redisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        public void initStream(){
            Boolean exists = redisTemplate.hasKey(queueName);
            if (BooleanUtil.isFalse(exists)) {
                log.info("stream不存在，开始创建stream");
                // 不存在，需要创建
                redisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("stream和group创建完毕");
                return;
            }
            // stream存在，判断group是否存在
            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(queueName);
            if(groups.isEmpty()){
                log.info("group不存在，开始创建group");
                // group不存在，创建group
                redisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("group创建完毕");
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    redisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象（事务）
            createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisID.nextId("order");
        // 1.执行lua脚本
        Long result = redisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();

        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }

        // 6.扣减库存
        boolean success = voucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }

        // 7.创建订单
        save(voucherOrder);
    }
 */

}


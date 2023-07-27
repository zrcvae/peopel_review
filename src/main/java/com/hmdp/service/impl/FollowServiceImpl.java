package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zrc
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long folloUserId, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 判断是否关注，如果是关注，就在follow中存入信息，如果不是就删除
        if (isFollow){
            Follow follow = new Follow();
            follow.setFollowUserId(folloUserId);
            follow.setUserId(userId);
            boolean success = save(follow);
            // 存入redis中，方便后面查找共同关注
            if (success){
                stringRedisTemplate.opsForSet().add(key, folloUserId.toString());
            }
        }else {
            boolean success = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", folloUserId));
            if (success){
                stringRedisTemplate.opsForSet().remove(key, folloUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long folloUserId) {
        // 判断是否存在关注信息
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", folloUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key1 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key1);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDto = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDto);
    }
}

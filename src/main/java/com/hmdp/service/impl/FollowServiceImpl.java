package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author zrc
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Override
    public Result follow(Long folloUserId, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        // 判断是否关注，如果是关注，就在follow中存入信息，如果不是就删除
        if (isFollow){
            Follow follow = new Follow();
            follow.setFollowUserId(folloUserId);
            follow.setUserId(userId);
            save(follow);
        }else {
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id",folloUserId));
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
}

package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author zrc
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long folloUserId, boolean isFollow);

    Result isFollow(Long folloUserId);

    Result followCommons(Long id);
}

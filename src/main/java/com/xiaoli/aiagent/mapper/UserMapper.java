package com.xiaoli.aiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaoli.aiagent.domain.entity.AppUser;

/** 用户数据访问；常规增删改查直接复用 MyBatis-Plus，不重复声明 SQL。 */
public interface UserMapper extends BaseMapper<AppUser> {}

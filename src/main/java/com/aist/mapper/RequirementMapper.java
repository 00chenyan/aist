package com.aist.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aist.entity.Requirement;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper interface for Requirement entity
 * Extends MyBatis-Plus BaseMapper to provide basic CRUD operations
 */
@Mapper
public interface RequirementMapper extends BaseMapper<Requirement> {
}


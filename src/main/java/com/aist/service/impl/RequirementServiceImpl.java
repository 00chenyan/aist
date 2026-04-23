package com.aist.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.aist.entity.Requirement;
import com.aist.mapper.RequirementMapper;
import com.aist.service.RequirementService;
import com.aist.util.DeepSeekUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service implementation for Requirement entity
 * Extends MyBatis-Plus ServiceImpl to provide basic CRUD operations
 */
@Service
@RequiredArgsConstructor
public class RequirementServiceImpl extends ServiceImpl<RequirementMapper, Requirement> implements RequirementService {

}

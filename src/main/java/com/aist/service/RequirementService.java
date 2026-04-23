package com.aist.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.aist.entity.Requirement;

import java.io.IOException;

/**
 * Service interface for Requirement entity
 * Extends MyBatis-Plus IService to provide basic CRUD operations
 */
public interface RequirementService extends IService<Requirement> {

    /**
     * 基于单条需求记录上下文与用户消息调用 LLM；记录不存在时返回 null。
     */
    String chat(Long id, String userMessage) throws IOException;
}


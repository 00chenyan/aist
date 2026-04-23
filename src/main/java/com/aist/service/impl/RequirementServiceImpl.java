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

    private final DeepSeekUtil deepSeekUtil;

    @Override
    public String chat(Long id, String userMessage) throws IOException {
        Requirement r = getById(id);
        if (r == null) {
            return null;
        }
        String ctx = buildContext(r);
        List<DeepSeekUtil.Message> messages = new ArrayList<>();
        messages.add(new DeepSeekUtil.Message("system",
                "你是代码与需求分析助手。仅根据下面给出的「记录上下文」回答用户问题，不要编造上下文中不存在的信息。使用中文回答。"));
        messages.add(new DeepSeekUtil.Message("user", ctx + "\n\n用户问题：\n" + userMessage));
        return deepSeekUtil.chat(messages);
    }

    private static String buildContext(Requirement r) {
        StringBuilder sb = new StringBuilder();
        sb.append("【记录上下文】\n");
        appendLine(sb, "主题", r.getSubject());
        appendLine(sb, "描述", r.getDescription());
        appendLine(sb, "Git 提交 ID", r.getGitCommitId());
        appendLine(sb, "项目名称", r.getProjectName());
        appendLine(sb, "分析结果", r.getAnalysisResults());
        sb.append("启用状态: ").append(r.getEnable() != null && r.getEnable() == 1 ? "启用" : "停用");
        if (r.getRequirementTime() != null) {
            sb.append("\n需求时间: ").append(r.getRequirementTime());
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        sb.append(label).append(": ");
        sb.append(StringUtils.hasText(value) ? value : "（无）").append('\n');
    }
}

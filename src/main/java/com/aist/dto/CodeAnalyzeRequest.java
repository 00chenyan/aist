package com.aist.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 代码分析请求DTO
 */
@Data
@ApiModel(value = "CodeAnalyzeRequest", description = "代码分析请求")
public class CodeAnalyzeRequest {

    @ApiModelProperty(value = "项目ID", required = true)
    private Long projectId;

    @ApiModelProperty(value = "接口URL（可选，如 /order/list）")
    private String apiUrl;

    @ApiModelProperty(value = "问题描述", required = true)
    private String question;

    @ApiModelProperty(value = "会话ID（用于多轮对话）")
    private String sessionId;
}


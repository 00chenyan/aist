package com.aist.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * DTO for code analysis requests.
 */
@Data
@ApiModel(value = "CodeAnalyzeRequest", description = "Code analysis request")
public class CodeAnalyzeRequest {

    @ApiModelProperty(value = "Project ID", required = true)
    private Long projectId;

    @ApiModelProperty(value = "API URL (optional, e.g. /order/list)")
    private String apiUrl;

    @ApiModelProperty(value = "Question or problem description", required = true)
    private String question;

    @ApiModelProperty(value = "Session ID (for multi-turn conversation)")
    private String sessionId;
}

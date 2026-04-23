package com.aist.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 需求分析状态枚举
 */
@Getter
@AllArgsConstructor
public enum AnalysisStatusEnum {

    PENDING("0", "待处理"),
    QUESTIONING("1", "问答中"),
    GENERATING("2", "文档生成中"),
    CONFIRMED("3", "已确认"),
    COMPLETED("4", "分析完成"),
    FAILED("5", "分析失败");

    private final String code;
    private final String desc;

    public static AnalysisStatusEnum fromCode(String code) {
        for (AnalysisStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}


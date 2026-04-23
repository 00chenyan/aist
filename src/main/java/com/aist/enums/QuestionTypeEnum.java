package com.aist.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public enum QuestionTypeEnum {

    START(1, "分析开始"),
    STEP(2, "步骤进度"),
    CONTENT(3, "内容输出"),
    DONE(4, "分析完成"),
    ERROR(5, "错误"),
    QUESTION(6, "澄清问题"),
    ;

    private final Integer code;
    private final String desc;

    public static QuestionTypeEnum fromCode(Integer code) {
        for (QuestionTypeEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}


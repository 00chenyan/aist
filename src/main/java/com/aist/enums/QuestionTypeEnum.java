package com.aist.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public enum QuestionTypeEnum {

    START(1, "Analysis started"),
    STEP(2, "Step progress"),
    CONTENT(3, "Streamed content"),
    DONE(4, "Analysis complete"),
    ERROR(5, "Error"),
    QUESTION(6, "Clarification"),
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


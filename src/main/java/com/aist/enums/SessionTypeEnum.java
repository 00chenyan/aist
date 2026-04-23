package com.aist.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public enum SessionTypeEnum {

    QUESTION(1, "Question"),
    ANSWER(2, "Answer");

    private final Integer code;
    private final String desc;

}


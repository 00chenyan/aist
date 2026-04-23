package com.aist.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public enum SessionTypeEnum {

    QUESTION(1, "提问"),
    ANSWER(2, "问答");

    private final Integer code;
    private final String desc;

}


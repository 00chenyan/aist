package com.aist.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
@TableName("conversation_record")
public class ConversationRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;
    private String sessionId;
    private Integer questionType;
    private String content;
    private Integer sessionType;
    private Integer questionNum;


    private Date createTime;

    private Date updateTime;
}

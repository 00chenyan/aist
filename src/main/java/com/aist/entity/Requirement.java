package com.aist.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Requirement entity class
 * Represents a requirement record in the database
 */
@Data
@TableName("requirement")
public class Requirement {

    /**
     * Unique identifier for the requirement
     */
    @TableId(type = IdType.AUTO)
    private Long id;


    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requirementTime;

    /**
     * 主题/标题（扩展字段，可与 title 并存）
     */
    private String subject;

    /**
     * 描述（扩展字段）
     */
    private String description;

    private String gitCommitId;

    private String projectName;

    private String analysisResults;

    /** 1 启用，0 停用 */
    private Integer enable;

    /** 0-待生成 1-生成中 2-已完成 */
    private Integer status;
}


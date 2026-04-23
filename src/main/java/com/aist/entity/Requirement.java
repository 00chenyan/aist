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
     * Subject/title (extension field; may coexist with title)
     */
    private String subject;

    /**
     * Description (extension field)
     */
    private String description;

    private String gitCommitId;

    private String projectName;

    private String analysisResults;

    /** 1 enabled, 0 disabled */
    private Integer enable;

    /** 0 pending generation, 1 generating, 2 completed */
    private Integer status;
}

package com.aist.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.aist.dto")
public class MyBatisConfig {
    // 配置内容
}


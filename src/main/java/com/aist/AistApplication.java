package com.aist;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for aist
 */
@SpringBootApplication
@MapperScan({"com.aist.mapper"})
public class AistApplication {

    public static void main(String[] args) {
        SpringApplication.run(AistApplication.class, args);
    }

}

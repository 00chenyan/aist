package com.aist.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * aist Requirement Analysis Configuration
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aist")
public class AistConfig {

    /**
     * Target project database configuration
     */
    private TargetDbConfig targetDb = new TargetDbConfig();

    /**
     * Code repository configuration
     */
    private CodeRepoConfig codeRepo = new CodeRepoConfig();


    @Data
    public static class TargetDbConfig {
        /**
         * Database URL
         */
        private String url;

        /**
         * Username
         */
        private String username;

        /**
         * Password
         */
        private String password;

        /**
         * Default database name
         */
        private String defaultDatabase;
    }

    @Data
    public static class CodeRepoConfig {
        /**
         * Code repository path
         */
        private String path;
    }

}


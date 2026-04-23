package com.aist.util;

import com.aist.config.AistConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 目标项目数据库连接工具
 * 从 aist.target-db 配置获取数据源
 */
@Slf4j
@Component
public class JdbcUtil {

    @Autowired
    private AistConfig aistConfig;

    private volatile DataSource targetDataSource;

    @PostConstruct
    public void init() {
        rebuildDataSource();
    }

    @PreDestroy
    public void destroy() {
        closeDataSource();
    }

    /**
     * 重建数据源
     */
    private void rebuildDataSource() {
        AistConfig.TargetDbConfig targetDb = aistConfig.getTargetDb();
        if (targetDb == null || targetDb.getUrl() == null) {
            log.warn("未配置目标数据库 (aist.target-db)");
            return;
        }

        closeDataSource();
        targetDataSource = createDataSource(
                targetDb.getUrl(),
                targetDb.getUsername(),
                targetDb.getPassword()
        );
        log.info("目标数据源初始化完成: {}", targetDb.getUrl());
    }

    /**
     * 关闭数据源
     */
    private void closeDataSource() {
        if (targetDataSource instanceof HikariDataSource) {
            ((HikariDataSource) targetDataSource).close();
            log.info("数据源已关闭");
        }
    }

    /**
     * 创建数据源
     */
    private DataSource createDataSource(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(5000);
        config.setLeakDetectionThreshold(60000);
        config.setIdleTimeout(600000);
        config.setConnectionTestQuery("SELECT 1");

        return new HikariDataSource(config);
    }

    /**
     * 获取数据库连接
     */
    public Connection getConnection() {
        if (targetDataSource == null) {
            throw new RuntimeException("数据源未初始化，请检查 aist.target-db 配置");
        }
        try {
            return targetDataSource.getConnection();
        } catch (SQLException e) {
            log.error("获取连接失败，尝试重建数据源", e);
            rebuildDataSource();
            try {
                return targetDataSource.getConnection();
            } catch (SQLException ex) {
                throw new RuntimeException("重建后仍无法获取连接", ex);
            }
        }
    }

}

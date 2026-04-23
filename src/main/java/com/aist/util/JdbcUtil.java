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
 * JDBC utility for the target project database.
 * Builds the data source from {@code aist.target-db} configuration.
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
     * Rebuilds the data source.
     */
    private void rebuildDataSource() {
        AistConfig.TargetDbConfig targetDb = aistConfig.getTargetDb();
        if (targetDb == null || targetDb.getUrl() == null) {
            log.warn("Target database not configured (aist.target-db)");
            return;
        }

        closeDataSource();
        targetDataSource = createDataSource(
                targetDb.getUrl(),
                targetDb.getUsername(),
                targetDb.getPassword()
        );
        log.info("Target data source initialized: {}", targetDb.getUrl());
    }

    /**
     * Closes the data source.
     */
    private void closeDataSource() {
        if (targetDataSource instanceof HikariDataSource) {
            ((HikariDataSource) targetDataSource).close();
            log.info("Data source closed");
        }
    }

    /**
     * Creates a data source.
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
     * Obtains a database connection.
     */
    public Connection getConnection() {
        if (targetDataSource == null) {
            throw new RuntimeException("Data source not initialized; check aist.target-db configuration");
        }
        try {
            return targetDataSource.getConnection();
        } catch (SQLException e) {
            log.error("Failed to get connection; attempting to rebuild data source", e);
            rebuildDataSource();
            try {
                return targetDataSource.getConnection();
            } catch (SQLException ex) {
                throw new RuntimeException("Still unable to obtain connection after rebuild", ex);
            }
        }
    }

}

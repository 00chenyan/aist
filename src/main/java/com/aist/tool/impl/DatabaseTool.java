package com.aist.tool.impl;

import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.tool.ToolRequest;
import com.aist.tool.ToolResult;
import com.aist.util.JdbcUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

/**
 * 统一数据库查询工具
 * 支持：列出所有表、查询表结构、查询数据
 */
@Slf4j
@Component
public class DatabaseTool extends AbstractTool {

    @Autowired
    private JdbcUtil jdbcUtil;

    private static final String[] DANGEROUS_KEYWORDS = {
            "DELETE", "UPDATE", "INSERT", "DROP", "ALTER", "TRUNCATE",
            "CREATE", "GRANT", "REVOKE", "EXECUTE", "EXEC"
    };

    @Override
    public String getName() {
        return "DATABASE";
    }

    @Override
    public String getDescription() {
        return "数据库查询工具（列出所有表、查询表结构、数据查询）";
    }

    @Override
    public String getParameterDescription() {
        return "子命令:参数 (如: list 或 desc:表名 或 query:SQL语句)";
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "[TOOL_CALL:DATABASE:list]                                # 列出所有表",
                "[TOOL_CALL:DATABASE:desc:t_user]                         # 查询表结构",
                "[TOOL_CALL:DATABASE:query:SELECT * FROM t_user]          # 查询数据（自动限制10条）"
        );
    }

    @Override
    public String getUsageScenario() {
        return "查询数据库表列表、表结构、数据";
    }

    @Override
    public String getCapabilities() {
        return """
                支持：列出所有表、DESC 表结构、SELECT 查询
                特点：查询数据最多返回10条，防止数据泄露
                禁止：DELETE、UPDATE、INSERT、DROP 等危险操作
                """;
    }

    @Override
    public int getPriority() {
        return 30; // 数据库查询优先级中等
    }

    @Override
    protected String validateRequest(ToolRequest request) {
        if (!request.hasArguments()) {
            return "请指定子命令: list（列出所有表）、desc:表名（查询表结构）、query:SQL（查询数据）";
        }
        return null;
    }

    @Override
    protected ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context) {
        String firstArg = request.getFirstArgument().trim().toLowerCase();

        if (context.getDatabaseName() == null || context.getDbSourceName() == null) {
            return ToolResult.error(getName(), firstArg,
                    "数据库未配置，无法查询。请在启动时配置数据库名称和数据源。");
        }

        // 解析子命令
        if (firstArg.equals("list")) {
            return listAllTables(context);
        } else if (firstArg.equals("desc")) {
            if (request.getArguments().size() < 2) {
                return ToolResult.error(getName(), firstArg, "desc命令需要指定表名，格式: [TOOL_CALL:DATABASE:desc:表名]");
            }
            String tableName = request.getArguments().get(1).trim();
            return describeTable(tableName, context);
        } else if (firstArg.equals("query")) {
            if (request.getArguments().size() < 2) {
                return ToolResult.error(getName(), firstArg, "query命令需要指定SQL语句，格式: [TOOL_CALL:DATABASE:query:SELECT ...]");
            }
            // 将剩余所有参数用冒号拼接还原SQL（SQL中可能含冒号）
            String sql = String.join(":", request.getArguments().subList(1, request.getArguments().size())).trim();
            return queryData(sql, context);
        } else {
            return ToolResult.error(getName(), firstArg,
                    "未知子命令: " + firstArg + "\n支持的子命令: list、desc:表名、query:SQL");
        }
    }

    /**
     * 列出所有表
     */
    private ToolResult listAllTables(CodeAnalyzeContextDTO context) {
        log.info("列出所有数据库表");
        StringBuilder result = new StringBuilder();
        result.append("数据库 ").append(context.getDatabaseName()).append(" 中的表:\n\n");

        try (Connection connection = jdbcUtil.getConnection()) {
            String sql = "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME";

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, context.getDatabaseName());

                try (ResultSet rs = stmt.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        String comment = rs.getString("TABLE_COMMENT");
                        result.append("- ").append(tableName);
                        if (comment != null && !comment.isEmpty()) {
                            result.append(" (").append(comment).append(")");
                        }
                        result.append("\n");
                        count++;
                    }
                    result.append("\n共 ").append(count).append(" 个表");
                }
            }
        } catch (Exception e) {
            return ToolResult.error(getName(), "list", "查询失败: " + e.getMessage());
        }

        return ToolResult.success(getName(), "list", result.toString());
    }

    /**
     * 查询表结构（DESC table_name）
     */
    private ToolResult describeTable(String tableName, CodeAnalyzeContextDTO context) {
        log.info("查询表结构: {}", tableName);
        StringBuilder result = new StringBuilder();

        try (Connection connection = jdbcUtil.getConnection()) {
            // 先检查表是否存在
            String checkSql = "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

            String tableComment = null;
            try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
                stmt.setString(1, context.getDatabaseName());
                stmt.setString(2, tableName);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return ToolResult.notFound(getName(), "desc:" + tableName,
                                "表不存在: " + tableName);
                    }
                    tableComment = rs.getString("TABLE_COMMENT");
                }
            }

            // 查询表结构
            result.append("### 表: ").append(tableName);
            if (tableComment != null && !tableComment.isEmpty()) {
                result.append(" (").append(tableComment).append(")");
            }
            result.append("\n\n");
            result.append("| 字段名 | 类型 | 允许空 | 默认值 | 注释 |\n");
            result.append("|--------|------|--------|--------|------|\n");

            String columnSql = """
                    SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
                    ORDER BY ORDINAL_POSITION
                    """;

            try (PreparedStatement stmt = connection.prepareStatement(columnSql)) {
                stmt.setString(1, context.getDatabaseName());
                stmt.setString(2, tableName);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String colName = rs.getString("COLUMN_NAME");
                        String colType = rs.getString("COLUMN_TYPE");
                        String nullable = rs.getString("IS_NULLABLE");
                        String defaultVal = rs.getString("COLUMN_DEFAULT");
                        String comment = rs.getString("COLUMN_COMMENT");

                        result.append("| ")
                                .append(colName).append(" | ")
                                .append(colType).append(" | ")
                                .append("YES".equals(nullable) ? "是" : "否").append(" | ")
                                .append(defaultVal != null ? defaultVal : "-").append(" | ")
                                .append(comment != null ? comment : "-").append(" |\n");
                    }
                }
            }

        } catch (Exception e) {
            return ToolResult.error(getName(), "desc:" + tableName, "查询失败: " + e.getMessage());
        }

        return ToolResult.success(getName(), "desc:" + tableName, result.toString());
    }

    /**
     * 查询数据（SELECT，最多返回10条）
     */
    private ToolResult queryData(String sql, CodeAnalyzeContextDTO context) {
        log.info("查询数据库数据: {}", sql);

        // 安全检查
        String upperSql = sql.trim().toUpperCase();
        if (!upperSql.startsWith("SELECT")) {
            return ToolResult.error(getName(), "query", "安全限制：只允许执行SELECT查询语句");
        }

        for (String keyword : DANGEROUS_KEYWORDS) {
            if (upperSql.matches(".*\\b" + keyword + "\\b.*")) {
                return ToolResult.error(getName(), "query",
                        "安全限制：SQL语句中不允许包含 " + keyword + " 关键字");
            }
        }

        // 处理SQL：移除已有LIMIT，添加LIMIT 10
        String safeSql = sql.trim();
        if (safeSql.endsWith(";")) {
            safeSql = safeSql.substring(0, safeSql.length() - 1);
        }
        safeSql = safeSql.replaceAll("(?i)\\s+LIMIT\\s+\\d+\\s*(,\\s*\\d+)?\\s*$", "");
        safeSql = safeSql + " LIMIT 10";

        StringBuilder result = new StringBuilder();
        result.append("**执行SQL**: `").append(safeSql).append("`\n\n");

        try (Connection connection = jdbcUtil.getConnection()) {
            try (PreparedStatement stmt = connection.prepareStatement(safeSql)) {
                stmt.setQueryTimeout(10);

                try (ResultSet rs = stmt.executeQuery()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    // 构建表头
                    result.append("| ");
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnLabel(i);
                        result.append(columnName).append(" | ");
                    }
                    result.append("\n|");
                    result.append("------|".repeat(Math.max(0, columnCount)));
                    result.append("\n");

                    // 构建数据行
                    int rowCount = 0;
                    while (rs.next() && rowCount < 10) {
                        result.append("| ");
                        for (int i = 1; i <= columnCount; i++) {
                            Object value = rs.getObject(i);
                            String valueStr = value == null ? "NULL" : value.toString();
                            if (valueStr.length() > 100) {
                                valueStr = valueStr.substring(0, 100) + "...";
                            }
                            valueStr = valueStr.replace("|", "\\|").replace("\n", " ");
                            result.append(valueStr).append(" | ");
                        }
                        result.append("\n");
                        rowCount++;
                    }

                    if (rowCount == 0) {
                        result.append("\n**查询结果**: 无数据\n");
                    } else {
                        result.append("\n**共查询到 ").append(rowCount).append(" 条数据**\n");
                    }
                }
            }
        } catch (Exception e) {
            return ToolResult.error(getName(), "query", "查询失败: " + e.getMessage());
        }

        return ToolResult.success(getName(), "query", result.toString());
    }
}

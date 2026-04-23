-- AIST application database schema
-- Create and select a database first, e.g.:
--   CREATE DATABASE aist DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
--   USE aist;
-- Then run: mysql -h127.0.0.1 -uroot -p aist < sql/schema.sql

SET NAMES utf8mb4;

-- -----------------------------
-- Requirements / analysis tasks
-- -----------------------------
CREATE TABLE `requirement` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `subject` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Subject',
  `description` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT 'Description',
  `git_commit_id` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT 'Git commit id',
  `project_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'Project name',
  `analysis_results` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT 'Analysis output',
  `requirement_time` datetime DEFAULT NULL COMMENT 'Created at',
  `status` int DEFAULT NULL COMMENT '0=pending, 1=in progress, 2=done',
  `enable` int DEFAULT '1' COMMENT '1=enabled, 0=disabled',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Analysis records';

-- -----------------------------
-- User sessions / analysis transcript
-- -----------------------------
CREATE TABLE `conversation_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Session id',
  `question_type` int NOT NULL COMMENT '1=start; 2=step; 3=content; 4=done; 5=error; 6=question',
  `question_num` int DEFAULT NULL COMMENT 'Question count',
  `invalid_question_num` int DEFAULT NULL COMMENT 'Invalid question count',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Content',
  `session_type` int NOT NULL COMMENT '1=question; 2=answer',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `thumb_status` int DEFAULT NULL COMMENT '1=up, -1=down',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_conversation_record_index` (`session_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='User session records';

-- 新建学习环境：先导入原 server（后端）/spba.sql，再在同一数据库执行本文件。
-- CREATE IF NOT EXISTS 不会修改已有表；已有数据库须对照结构另行迁移。
CREATE TABLE IF NOT EXISTS violation_types (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(80) NOT NULL,
 description VARCHAR(500), reward_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
 is_active TINYINT NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS user_account (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL,
 reputation INT NOT NULL DEFAULT 100, balance DECIMAL(12,2) NOT NULL DEFAULT 0,
 false_report_count INT NOT NULL DEFAULT 0, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_account_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS reports (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, image_url VARCHAR(1024) NOT NULL,
 location VARCHAR(255) NOT NULL, capture_time DATETIME NOT NULL, violation_type_id BIGINT NOT NULL,
 description TEXT, status INT NOT NULL DEFAULT 0, inspector_id BIGINT, reviewed_time DATETIME,
 reward DECIMAL(12,2) NOT NULL DEFAULT 0, created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 KEY idx_report_pending(status,id), KEY idx_report_user(user_id,created_time),
 KEY idx_report_inspector(inspector_id,status,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS withdrawals (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, amount DECIMAL(12,2) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', request_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 processed_time DATETIME, admin_id BIGINT, note VARCHAR(500), account VARCHAR(255) NOT NULL,
 KEY idx_withdraw_user(user_id,request_time), KEY idx_withdraw_pending(status,request_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS balance_change_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, change_type VARCHAR(30) NOT NULL,
 amount DECIMAL(12,2) NOT NULL, balance_before DECIMAL(12,2) NOT NULL,
 balance_after DECIMAL(12,2) NOT NULL, created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 KEY idx_balance_user(user_id,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS reputation_logs (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, change_num INT NOT NULL,
 reason VARCHAR(255), created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 KEY idx_reputation_user(user_id,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS bank_info (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, bank_name VARCHAR(100),
 card_number VARCHAR(100), card_type VARCHAR(50), account_name VARCHAR(100), branch_name VARCHAR(255),
 created_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 is_default TINYINT NOT NULL DEFAULT 0, KEY idx_bank_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

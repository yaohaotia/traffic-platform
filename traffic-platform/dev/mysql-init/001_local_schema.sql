-- Exclusively for this Compose project's NEW local volume.
-- No grants or writes to any pre-existing user database.
CREATE DATABASE IF NOT EXISTS traffic_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON traffic_platform.* TO 'traffic_app'@'%';

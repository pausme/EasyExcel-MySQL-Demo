CREATE TABLE IF NOT EXISTS `download_audit_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `audit_id` VARCHAR(64) NOT NULL,
    `owner_id` VARCHAR(64) NOT NULL,
    `resource_type` VARCHAR(32) NOT NULL,
    `resource_id` VARCHAR(128) NOT NULL,
    `object_key` VARCHAR(512) NULL,
    `file_name` VARCHAR(255) NULL,
    `request_ip` VARCHAR(64) NULL,
    `user_agent` VARCHAR(512) NULL,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_download_audit_record_audit_id` (`audit_id`),
    KEY `idx_download_audit_record_owner_created_at` (`owner_id`, `created_at`),
    KEY `idx_download_audit_record_resource_created_at` (`resource_type`, `resource_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

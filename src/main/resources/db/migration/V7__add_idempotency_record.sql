CREATE TABLE IF NOT EXISTS `idempotency_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `owner_id` VARCHAR(64) NOT NULL,
    `operation` VARCHAR(64) NOT NULL,
    `idempotency_key` VARCHAR(128) NOT NULL,
    `request_fingerprint` CHAR(64) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `response_payload` LONGTEXT NULL,
    `error_message` VARCHAR(512) NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NOT NULL,
    `expire_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency_owner_operation_key` (`owner_id`, `operation`, `idempotency_key`),
    KEY `idx_idempotency_record_expire_at` (`expire_at`),
    KEY `idx_idempotency_record_owner_created_at` (`owner_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

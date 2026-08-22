DELIMITER //

CREATE PROCEDURE add_file_metadata_reference_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'file_record'
          AND COLUMN_NAME = 'biz_type'
    ) THEN
        ALTER TABLE `file_record`
            ADD COLUMN `biz_type` VARCHAR(64) NULL AFTER `owner_id`,
            ADD COLUMN `biz_id` VARCHAR(128) NULL AFTER `biz_type`,
            ADD COLUMN `tags` TEXT NULL AFTER `upload_type`,
            ADD COLUMN `reference_count` INT NOT NULL DEFAULT 0 AFTER `tags`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'file_record'
          AND INDEX_NAME = 'idx_file_record_owner_biz'
    ) THEN
        CREATE INDEX `idx_file_record_owner_biz`
            ON `file_record` (`owner_id`, `biz_type`, `biz_id`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'file_reference'
    ) THEN
        CREATE TABLE `file_reference` (
            `id` BIGINT NOT NULL AUTO_INCREMENT,
            `owner_id` VARCHAR(64) NOT NULL,
            `file_id` VARCHAR(64) NOT NULL,
            `reference_type` VARCHAR(64) NOT NULL,
            `reference_id` VARCHAR(128) NOT NULL,
            `created_at` DATETIME NOT NULL,
            `updated_at` DATETIME NOT NULL,
            PRIMARY KEY (`id`),
            UNIQUE KEY `uk_file_reference_owner_file_reference` (`owner_id`, `file_id`, `reference_type`, `reference_id`),
            KEY `idx_file_reference_owner_file` (`owner_id`, `file_id`),
            KEY `idx_file_reference_owner_reference` (`owner_id`, `reference_type`, `reference_id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    END IF;
END//

CALL add_file_metadata_reference_columns()//

DROP PROCEDURE add_file_metadata_reference_columns//

DELIMITER ;

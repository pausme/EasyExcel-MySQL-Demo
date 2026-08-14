DELIMITER //

CREATE PROCEDURE add_file_owner_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'file_record'
          AND COLUMN_NAME = 'owner_id'
    ) THEN
        ALTER TABLE `file_record`
            ADD COLUMN `owner_id` VARCHAR(64) NOT NULL DEFAULT 'anonymous' AFTER `file_id`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'file_record'
          AND INDEX_NAME = 'idx_file_record_owner_md5_size'
    ) THEN
        CREATE INDEX `idx_file_record_owner_md5_size`
            ON `file_record` (`owner_id`, `file_md5`, `file_size`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'file_upload_task'
          AND COLUMN_NAME = 'owner_id'
    ) THEN
        ALTER TABLE `file_upload_task`
            ADD COLUMN `owner_id` VARCHAR(64) NOT NULL DEFAULT 'anonymous' AFTER `file_id`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'file_upload_task'
          AND INDEX_NAME = 'idx_file_upload_task_owner_status_created_at'
    ) THEN
        CREATE INDEX `idx_file_upload_task_owner_status_created_at`
            ON `file_upload_task` (`owner_id`, `status`, `created_at`);
    END IF;
END//

CALL add_file_owner_columns()//

DROP PROCEDURE add_file_owner_columns//

DELIMITER ;

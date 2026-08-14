DELIMITER //

CREATE PROCEDURE add_async_task_heartbeat_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'async_task_record'
          AND COLUMN_NAME = 'worker_id'
    ) THEN
        ALTER TABLE `async_task_record`
            ADD COLUMN `worker_id` VARCHAR(128) NULL AFTER `error_message`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'async_task_record'
          AND COLUMN_NAME = 'last_heartbeat_at'
    ) THEN
        ALTER TABLE `async_task_record`
            ADD COLUMN `last_heartbeat_at` DATETIME NULL AFTER `worker_id`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'async_task_record'
          AND INDEX_NAME = 'idx_async_task_record_status_heartbeat'
    ) THEN
        CREATE INDEX `idx_async_task_record_status_heartbeat`
            ON `async_task_record` (`status`, `last_heartbeat_at`);
    END IF;
END//

CALL add_async_task_heartbeat_columns()//

DROP PROCEDURE add_async_task_heartbeat_columns//

DELIMITER ;

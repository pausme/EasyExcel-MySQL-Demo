ALTER TABLE `async_task_record`
    ADD COLUMN `failure_type` VARCHAR(32) NULL AFTER `error_message`,
    ADD COLUMN `retryable` TINYINT(1) NULL AFTER `failure_type`,
    ADD COLUMN `failure_suggestion` VARCHAR(512) NULL AFTER `retryable`;

DELIMITER //

CREATE PROCEDURE add_student_import_version_switch()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'student_import_version_control'
    ) THEN
        CREATE TABLE `student_import_version_control` (
            `id` BIGINT NOT NULL,
            `current_version` BIGINT NOT NULL,
            `created_at` DATETIME NOT NULL,
            `updated_at` DATETIME NOT NULL,
            PRIMARY KEY (`id`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    END IF;

    INSERT IGNORE INTO `student_import_version_control` (`id`, `current_version`, `created_at`, `updated_at`)
    VALUES (1, 1, NOW(), NOW());

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'student_record'
          AND COLUMN_NAME = 'import_version'
    ) THEN
        ALTER TABLE `student_record`
            ADD COLUMN `import_version` BIGINT NOT NULL DEFAULT 1 AFTER `id`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'student_record'
          AND COLUMN_NAME = 'import_task_id'
    ) THEN
        ALTER TABLE `student_record`
            ADD COLUMN `import_task_id` VARCHAR(64) NULL AFTER `import_version`;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'student_record'
          AND INDEX_NAME = 'uk_student_record_student_no'
    ) THEN
        ALTER TABLE `student_record`
            DROP INDEX `uk_student_record_student_no`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'student_record'
          AND INDEX_NAME = 'uk_student_record_version_student_no'
    ) THEN
        ALTER TABLE `student_record`
            ADD UNIQUE KEY `uk_student_record_version_student_no` (`import_version`, `student_no`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'student_record'
          AND INDEX_NAME = 'idx_student_record_version_id'
    ) THEN
        ALTER TABLE `student_record`
            ADD KEY `idx_student_record_version_id` (`import_version`, `id`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'student_record'
          AND INDEX_NAME = 'idx_student_record_import_task_id'
    ) THEN
        ALTER TABLE `student_record`
            ADD KEY `idx_student_record_import_task_id` (`import_task_id`);
    END IF;
END//

CALL add_student_import_version_switch()//

DROP PROCEDURE add_student_import_version_switch//

DELIMITER ;

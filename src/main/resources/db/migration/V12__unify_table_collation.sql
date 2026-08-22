-- V12: 统一业务表排序规则为 utf8mb4_unicode_ci（与全部建表 DDL 声明一致）
-- 背景：混合环境下部分表曾以库默认 utf8mb4_0900_ai_ci 创建，
-- file_record 与 file_reference 关联比较触发 "Illegal mix of collations" 导致接口 500。
-- 本迁移幂等：仅转换非目标排序规则的表，已一致的表不受影响。

DELIMITER //

CREATE PROCEDURE unify_table_collation()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE tbl VARCHAR(64);
    DECLARE cur CURSOR FOR
        SELECT TABLE_NAME
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_COLLATION IS NOT NULL
          AND TABLE_COLLATION <> 'utf8mb4_unicode_ci'
          AND TABLE_NAME <> 'flyway_schema_history';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO tbl;
        IF done THEN
            LEAVE read_loop;
        END IF;
        SET @sql = CONCAT('ALTER TABLE `', tbl,
                          '` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE cur;
END//

CALL unify_table_collation()//

DROP PROCEDURE unify_table_collation//

DELIMITER ;

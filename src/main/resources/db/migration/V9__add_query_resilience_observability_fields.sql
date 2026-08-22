ALTER TABLE async_task_record
    ADD COLUMN trace_id VARCHAR(64) NULL AFTER failure_suggestion;

ALTER TABLE file_record
    ADD COLUMN upload_type VARCHAR(32) NULL AFTER storage_type;

CREATE TABLE IF NOT EXISTS async_task_event_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    message VARCHAR(1024),
    progress_percent INT,
    completed_count BIGINT,
    total_count BIGINT,
    failure_type VARCHAR(32),
    trace_id VARCHAR(64),
    worker_id VARCHAR(128),
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_async_task_event_log_event_id (event_id),
    KEY idx_async_task_event_log_task_created_at (task_id, created_at),
    KEY idx_async_task_event_log_owner_created_at (owner_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

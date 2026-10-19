ALTER TABLE agent_memories ADD COLUMN metadata_json TEXT NULL;
ALTER TABLE agent_memories ADD COLUMN summary_level INT NOT NULL DEFAULT 0;
ALTER TABLE agent_memories ADD COLUMN compressed_into VARCHAR(36) NULL;
CREATE INDEX idx_checkpoint_memory_recovery ON agent_checkpoints(stage, updated_at);

CREATE TABLE agent_memory_sources (
    user_id BIGINT NOT NULL,
    summary_id VARCHAR(36) NOT NULL,
    source_memory_id VARCHAR(36) NOT NULL,
    source_task_id VARCHAR(160) NOT NULL,
    PRIMARY KEY (user_id, summary_id, source_memory_id),
    KEY idx_memory_source (user_id, source_memory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_memory_jobs (
    job_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    payload LONGTEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(36) NULL,
    available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    lease_until TIMESTAMP(3) NULL,
    last_error VARCHAR(80) NULL,
    source_media_id BIGINT NULL,
    source_checkpoint_key VARCHAR(160) NULL,
    source_seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job_id),
    KEY idx_memory_job_poll (status, available_at, lease_until),
    KEY idx_memory_job_source (source_media_id,source_checkpoint_key,source_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_memory_index_outbox (
    user_id BIGINT NOT NULL,
    memory_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error VARCHAR(80) NULL,
    PRIMARY KEY (user_id, memory_id),
    KEY idx_memory_index_poll (available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

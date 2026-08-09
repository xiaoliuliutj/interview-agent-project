-- Safe, idempotent upgrade for an already initialized PostgreSQL volume.
-- It only creates missing structures or columns; it does not delete historical data.

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS file_hash VARCHAR(64);
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS original_filename VARCHAR(512);
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS content_type VARCHAR(255);
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS storage_key VARCHAR(1024);
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_resumes_file_hash ON resumes(file_hash);
CREATE INDEX IF NOT EXISTS idx_resumes_candidate_file_hash ON resumes(candidate_id, file_hash);

ALTER TABLE IF EXISTS candidates ADD COLUMN IF NOT EXISTS current_resume_id VARCHAR(255);
UPDATE candidates candidate
SET current_resume_id = (
    SELECT resume.id
    FROM resumes resume
    WHERE resume.candidate_id = candidate.id
    ORDER BY resume.version DESC, resume.created_at DESC NULLS LAST
    LIMIT 1
)
WHERE candidate.current_resume_id IS NULL
  AND EXISTS (
      SELECT 1 FROM resumes resume WHERE resume.candidate_id = candidate.id
  );

ALTER TABLE IF EXISTS resume_analyses ADD COLUMN IF NOT EXISTS issues_json TEXT;
ALTER TABLE IF EXISTS resume_analyses ADD COLUMN IF NOT EXISTS target_role VARCHAR(255);
ALTER TABLE IF EXISTS resume_analyses ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS resume_analyses ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ;

ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS skill_id VARCHAR(255);
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS difficulty VARCHAR(32);
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS agent_state_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS current_stage VARCHAR(32);
ALTER TABLE IF EXISTS interview_turns ADD COLUMN IF NOT EXISTS run_id VARCHAR(255);
ALTER TABLE IF EXISTS interview_turns ADD COLUMN IF NOT EXISTS stage VARCHAR(32);
CREATE UNIQUE INDEX IF NOT EXISTS ux_interview_turns_run_id
    ON interview_turns (run_id);

ALTER TABLE IF EXISTS knowledge_bases ADD COLUMN IF NOT EXISTS original_bytes BYTEA;
ALTER TABLE IF EXISTS knowledge_bases ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_owner_created
    ON knowledge_bases (owner_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_interview_sessions (
    session_id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32) NOT NULL,
    state_version INTEGER NOT NULL,
    session_data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_long_term_memories (
    user_id VARCHAR(128) PRIMARY KEY,
    state_version INTEGER NOT NULL,
    memory_data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_rag_chunks (
    chunk_id VARCHAR(256) PRIMARY KEY,
    knowledge_base_id VARCHAR(128) NOT NULL,
    document_id VARCHAR(256) NOT NULL,
    source_name VARCHAR(512) NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    chunk_metadata JSONB NOT NULL,
    embedding VECTOR NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_rag_chunks_kb_id
    ON agent_rag_chunks (knowledge_base_id);

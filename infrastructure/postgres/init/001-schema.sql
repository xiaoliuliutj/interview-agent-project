CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS candidates (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255),
    display_name VARCHAR(255)
    ,current_resume_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS resumes (
    id VARCHAR(255) PRIMARY KEY,
    candidate_id VARCHAR(255),
    version INTEGER NOT NULL DEFAULT 1,
    content TEXT,
    file_hash VARCHAR(64),
    original_filename VARCHAR(512),
    file_size BIGINT,
    content_type VARCHAR(255),
    storage_key VARCHAR(1024),
    created_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_resumes_file_hash ON resumes(file_hash);
CREATE INDEX IF NOT EXISTS idx_resumes_candidate_file_hash ON resumes(candidate_id, file_hash);

CREATE TABLE IF NOT EXISTS resume_analyses (
    id BIGSERIAL PRIMARY KEY,
    resume_id VARCHAR(255) NOT NULL,
    target_role VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    overall_score INTEGER,
    content_score INTEGER,
    structure_score INTEGER,
    skill_match_score INTEGER,
    expression_score INTEGER,
    project_score INTEGER,
    summary TEXT,
    strengths_json TEXT,
    suggestions_json TEXT,
    issues_json TEXT,
    error VARCHAR(500),
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_resume_analyses_resume_created
    ON resume_analyses (resume_id, created_at DESC);

CREATE TABLE IF NOT EXISTS job_descriptions (
    id VARCHAR(255) PRIMARY KEY,
    title VARCHAR(255),
    version INTEGER NOT NULL DEFAULT 1,
    content TEXT
);

CREATE TABLE IF NOT EXISTS interview_sessions (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255),
    candidate_id VARCHAR(255),
    resume_id VARCHAR(255),
    jd_id VARCHAR(255),
    skill_id VARCHAR(255),
    difficulty VARCHAR(32),
    total_questions INTEGER NOT NULL,
    status VARCHAR(32),
    state_version BIGINT NOT NULL DEFAULT 0,
    agent_state_version BIGINT NOT NULL DEFAULT 0,
    current_question TEXT,
    current_stage VARCHAR(32),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS interview_turns (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255),
    run_id VARCHAR(255) UNIQUE,
    question TEXT,
    candidate_answer TEXT,
    stage VARCHAR(32),
    created_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id VARCHAR(255) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(255),
    original_filename VARCHAR(512),
    file_size BIGINT,
    content_type VARCHAR(255),
    content TEXT,
    original_bytes BYTEA,
    vector_status VARCHAR(32),
    vector_error VARCHAR(500),
    chunk_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);
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

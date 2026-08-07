CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS candidates (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255),
    display_name VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS resumes (
    id VARCHAR(255) PRIMARY KEY,
    candidate_id VARCHAR(255),
    version INTEGER NOT NULL DEFAULT 1,
    content TEXT
);

CREATE TABLE IF NOT EXISTS resume_analyses (
    id BIGSERIAL PRIMARY KEY,
    resume_id VARCHAR(255) NOT NULL,
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
    error VARCHAR(500),
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
    total_questions INTEGER NOT NULL DEFAULT 6,
    status VARCHAR(32),
    state_version BIGINT NOT NULL DEFAULT 0,
    current_question TEXT,
    draft_answer TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS interview_turns (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255),
    run_id VARCHAR(255) UNIQUE,
    question TEXT,
    candidate_answer TEXT,
    evaluation_summary TEXT,
    created_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS interview_tasks (
    id VARCHAR(255) PRIMARY KEY,
    task_type VARCHAR(255),
    status VARCHAR(32),
    session_id VARCHAR(255),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS interview_schedules (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    title VARCHAR(255),
    start_at TIMESTAMPTZ,
    end_at TIMESTAMPTZ,
    source VARCHAR(64),
    status VARCHAR(32),
    raw_text TEXT,
    created_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(255),
    original_filename VARCHAR(512),
    file_size BIGINT,
    content_type VARCHAR(255),
    content TEXT,
    vector_status VARCHAR(32),
    vector_error VARCHAR(500),
    chunk_count INTEGER NOT NULL DEFAULT 0,
    access_count BIGINT NOT NULL DEFAULT 0,
    question_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS rag_chat_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    title VARCHAR(120) NOT NULL,
    knowledge_base_ids VARCHAR(4000) NOT NULL DEFAULT '',
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rag_chat_sessions_user_updated
    ON rag_chat_sessions (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS rag_chat_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES rag_chat_sessions(id) ON DELETE CASCADE,
    type VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rag_chat_messages_session_created
    ON rag_chat_messages (session_id, created_at);

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

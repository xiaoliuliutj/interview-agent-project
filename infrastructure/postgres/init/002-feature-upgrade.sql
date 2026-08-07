-- 对已经初始化过的开发数据库提供幂等升级；生产环境应由正式迁移工具执行。
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS file_hash VARCHAR(64);
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS original_filename VARCHAR(512);
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS content_type VARCHAR(255);
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS storage_key VARCHAR(1024);
ALTER TABLE IF EXISTS resumes ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ;
ALTER TABLE IF EXISTS knowledge_bases ADD COLUMN IF NOT EXISTS original_bytes BYTEA;
DROP INDEX IF EXISTS uk_resumes_file_hash;
CREATE INDEX IF NOT EXISTS idx_resumes_file_hash ON resumes(file_hash);

ALTER TABLE IF EXISTS resume_analyses ADD COLUMN IF NOT EXISTS issues_json TEXT;
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS overall_score INTEGER;
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS final_summary TEXT;
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS skill_id VARCHAR(255);
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS difficulty VARCHAR(32);
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS evaluate_status VARCHAR(32);
ALTER TABLE IF EXISTS interview_sessions ADD COLUMN IF NOT EXISTS evaluate_error TEXT;
ALTER TABLE IF EXISTS interview_turns ADD COLUMN IF NOT EXISTS score INTEGER;
ALTER TABLE IF EXISTS interview_turns ADD COLUMN IF NOT EXISTS answer_summary TEXT;
ALTER TABLE IF EXISTS interview_turns ADD COLUMN IF NOT EXISTS strengths_json TEXT;
ALTER TABLE IF EXISTS interview_turns ADD COLUMN IF NOT EXISTS weaknesses_json TEXT;
ALTER TABLE IF EXISTS interview_turns ADD COLUMN IF NOT EXISTS preferences_json TEXT;
ALTER TABLE IF EXISTS interview_turns ADD COLUMN IF NOT EXISTS stage VARCHAR(32);

-- 旧库中无法可靠推断资料所属用户，因此保留 NULL 供管理员自行迁移；
-- 新上传资料由应用层强制写入 owner_id，普通用户不会读取遗留的无归属资料。
ALTER TABLE IF EXISTS knowledge_bases ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_knowledge_bases_owner_created
    ON knowledge_bases (owner_id, created_at DESC);

-- Interview flow counters, per-turn assessment and final candidate report.
-- The statements are idempotent so a fresh database and an existing volume share one schema.

ALTER TABLE IF EXISTS interview_sessions
    ADD COLUMN IF NOT EXISTS issued_question_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS interview_sessions
    ADD COLUMN IF NOT EXISTS primary_question_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS interview_sessions
    ADD COLUMN IF NOT EXISTS followup_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS interview_sessions
    ADD COLUMN IF NOT EXISTS final_evaluation_json TEXT;

ALTER TABLE IF EXISTS interview_turns
    ADD COLUMN IF NOT EXISTS evaluation_summary TEXT;
ALTER TABLE IF EXISTS interview_turns
    ADD COLUMN IF NOT EXISTS score INTEGER;
ALTER TABLE IF EXISTS interview_turns
    ADD COLUMN IF NOT EXISTS strengths_json TEXT;
ALTER TABLE IF EXISTS interview_turns
    ADD COLUMN IF NOT EXISTS weaknesses_json TEXT;

-- Rename the Java business-facing interview option from the old Skill ID
-- terminology to a human-readable interview direction.  Python owns its
-- internal Skill IDs and never exposes them through this table.
ALTER TABLE IF EXISTS interview_sessions
    ADD COLUMN IF NOT EXISTS interview_direction VARCHAR(255);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'interview_sessions'
          AND column_name = 'skill_id'
    ) THEN
        EXECUTE 'UPDATE interview_sessions
                 SET interview_direction = COALESCE(interview_direction, NULLIF(skill_id, ''''))
                 WHERE interview_direction IS NULL';
    END IF;
END $$;

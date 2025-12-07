-- Migration: Add GitHub integration fields to applications table
-- Run this in Supabase SQL Editor

-- Add GitHub-related columns to applications
ALTER TABLE applications 
ADD COLUMN IF NOT EXISTS github_repo VARCHAR(255),      -- e.g., "alabana/sample-logai-app"
ADD COLUMN IF NOT EXISTS github_branch VARCHAR(100) DEFAULT 'main',
ADD COLUMN IF NOT EXISTS source_path VARCHAR(255) DEFAULT 'src/main/java';

-- Add GitHub token to settings (if not exists)
INSERT INTO settings (key, value, description)
VALUES ('github_token', '', 'GitHub Personal Access Token for Intuit GitHub')
ON CONFLICT (key) DO NOTHING;

-- Comment explaining usage
COMMENT ON COLUMN applications.github_repo IS 'GitHub repository in owner/repo format for source code fetching';
COMMENT ON COLUMN applications.github_branch IS 'Default branch to fetch source from (default: main)';
COMMENT ON COLUMN applications.source_path IS 'Path to Java source files in repo (default: src/main/java)';


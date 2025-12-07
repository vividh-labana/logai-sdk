-- LogAI Authentication Migration
-- Run this in Supabase SQL Editor to add user authentication support

-- ============================================================================
-- PHASE 1: Add user_id columns to existing tables
-- ============================================================================

-- Add user_id to applications table
ALTER TABLE applications ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES auth.users(id);

-- Add user_id to settings table for per-user settings (like GitHub token)
ALTER TABLE settings ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES auth.users(id);

-- Create index for faster user lookups
CREATE INDEX IF NOT EXISTS idx_applications_user_id ON applications(user_id);
CREATE INDEX IF NOT EXISTS idx_settings_user_id ON settings(user_id);

-- ============================================================================
-- PHASE 2: Enable Row Level Security on all tables
-- ============================================================================

ALTER TABLE applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE log_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE error_clusters ENABLE ROW LEVEL SECURITY;
ALTER TABLE analysis_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE scan_history ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- PHASE 3: Drop existing policies (if any) to avoid conflicts
-- ============================================================================

DROP POLICY IF EXISTS "Users manage own apps" ON applications;
DROP POLICY IF EXISTS "Users see own logs" ON log_entries;
DROP POLICY IF EXISTS "Users see own clusters" ON error_clusters;
DROP POLICY IF EXISTS "Users see own analysis" ON analysis_results;
DROP POLICY IF EXISTS "Users manage own settings" ON settings;
DROP POLICY IF EXISTS "Users see own scans" ON scan_history;
DROP POLICY IF EXISTS "API key insert logs" ON log_entries;
DROP POLICY IF EXISTS "Service role bypass" ON applications;
DROP POLICY IF EXISTS "Service role bypass logs" ON log_entries;

-- ============================================================================
-- PHASE 4: Create RLS Policies for authenticated users
-- ============================================================================

-- Applications: Users can only manage their own apps
CREATE POLICY "Users manage own apps" ON applications
  FOR ALL 
  USING (auth.uid() = user_id)
  WITH CHECK (auth.uid() = user_id);

-- Log entries: Users can see logs from their own apps
CREATE POLICY "Users see own logs" ON log_entries
  FOR SELECT 
  USING (app_id IN (SELECT id FROM applications WHERE user_id = auth.uid()));

-- Allow inserting logs via API key (for SDK - no user auth required)
CREATE POLICY "API key insert logs" ON log_entries
  FOR INSERT 
  WITH CHECK (
    app_id IN (
      SELECT id FROM applications 
      WHERE api_key = coalesce(
        current_setting('request.headers', true)::json->>'x-api-key',
        ''
      )
    )
  );

-- Error clusters: Users can see clusters from their own apps
CREATE POLICY "Users see own clusters" ON error_clusters
  FOR ALL 
  USING (app_id IN (SELECT id FROM applications WHERE user_id = auth.uid()));

-- Analysis results: Users can see analysis from their own clusters
CREATE POLICY "Users see own analysis" ON analysis_results
  FOR ALL 
  USING (
    cluster_id IN (
      SELECT id FROM error_clusters 
      WHERE app_id IN (SELECT id FROM applications WHERE user_id = auth.uid())
    )
  );

-- Settings: Users can only manage their own settings
CREATE POLICY "Users manage own settings" ON settings
  FOR ALL 
  USING (auth.uid() = user_id)
  WITH CHECK (auth.uid() = user_id);

-- Scan history: Users can see scans from their own apps
CREATE POLICY "Users see own scans" ON scan_history
  FOR ALL 
  USING (app_id IN (SELECT id FROM applications WHERE user_id = auth.uid()));

-- ============================================================================
-- PHASE 5: Service role bypass (for backend operations)
-- ============================================================================

-- Allow service role to bypass RLS for backend operations
CREATE POLICY "Service role bypass apps" ON applications
  FOR ALL
  USING (auth.role() = 'service_role');

CREATE POLICY "Service role bypass logs" ON log_entries
  FOR ALL
  USING (auth.role() = 'service_role');

CREATE POLICY "Service role bypass clusters" ON error_clusters
  FOR ALL
  USING (auth.role() = 'service_role');

CREATE POLICY "Service role bypass analysis" ON analysis_results
  FOR ALL
  USING (auth.role() = 'service_role');

CREATE POLICY "Service role bypass settings" ON settings
  FOR ALL
  USING (auth.role() = 'service_role');

CREATE POLICY "Service role bypass scans" ON scan_history
  FOR ALL
  USING (auth.role() = 'service_role');

-- ============================================================================
-- PHASE 6: Update settings table to have unique constraint per user+key
-- ============================================================================

-- Drop old unique constraint on key if exists
ALTER TABLE settings DROP CONSTRAINT IF EXISTS settings_key_key;

-- Add composite unique constraint for user_id + key
ALTER TABLE settings ADD CONSTRAINT settings_user_key_unique UNIQUE (user_id, key);

-- ============================================================================
-- Done! Now users will only see their own data after authentication.
-- ============================================================================


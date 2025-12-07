-- LogAI Database Schema for Supabase
-- Run this SQL in your Supabase SQL Editor to set up all tables

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================================
-- APPLICATIONS TABLE
-- Stores registered applications that send logs
-- ============================================================================
CREATE TABLE IF NOT EXISTS applications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    api_key VARCHAR(64) NOT NULL UNIQUE DEFAULT encode(gen_random_bytes(32), 'hex'),
    source_paths TEXT[], -- Array of source code paths for code context
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for API key lookups (used for authentication)
CREATE INDEX IF NOT EXISTS idx_applications_api_key ON applications(api_key);

-- ============================================================================
-- LOG ENTRIES TABLE
-- Stores all log entries from applications
-- ============================================================================
CREATE TABLE IF NOT EXISTS log_entries (
    id BIGSERIAL PRIMARY KEY,
    app_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    level VARCHAR(10) NOT NULL CHECK (level IN ('TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL')),
    logger VARCHAR(512),
    message TEXT,
    stack_trace TEXT,
    file_name VARCHAR(255),
    line_number INTEGER,
    method_name VARCHAR(255),
    class_name VARCHAR(512),
    trace_id VARCHAR(64),
    thread_name VARCHAR(255),
    mdc_context JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_log_entries_app_id ON log_entries(app_id);
CREATE INDEX IF NOT EXISTS idx_log_entries_timestamp ON log_entries(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_log_entries_level ON log_entries(level);
CREATE INDEX IF NOT EXISTS idx_log_entries_app_timestamp ON log_entries(app_id, timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_log_entries_app_level ON log_entries(app_id, level);

-- Partial index for errors only (most common query)
CREATE INDEX IF NOT EXISTS idx_log_entries_errors ON log_entries(app_id, timestamp DESC) 
    WHERE level IN ('ERROR', 'FATAL');

-- ============================================================================
-- ERROR CLUSTERS TABLE
-- Groups similar errors together
-- ============================================================================
CREATE TABLE IF NOT EXISTS error_clusters (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    app_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    fingerprint VARCHAR(512) NOT NULL,
    exception_class VARCHAR(512),
    message_pattern TEXT,
    primary_file VARCHAR(255),
    primary_line INTEGER,
    primary_method VARCHAR(255),
    primary_class VARCHAR(512),
    occurrence_count INTEGER DEFAULT 0,
    severity VARCHAR(20) DEFAULT 'MEDIUM' CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    first_seen TIMESTAMP WITH TIME ZONE,
    last_seen TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'IGNORED')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(app_id, fingerprint)
);

-- Indexes for cluster queries
CREATE INDEX IF NOT EXISTS idx_error_clusters_app_id ON error_clusters(app_id);
CREATE INDEX IF NOT EXISTS idx_error_clusters_severity ON error_clusters(severity);
CREATE INDEX IF NOT EXISTS idx_error_clusters_status ON error_clusters(status);
CREATE INDEX IF NOT EXISTS idx_error_clusters_occurrence ON error_clusters(occurrence_count DESC);

-- ============================================================================
-- ANALYSIS RESULTS TABLE
-- Stores AI-generated analysis for error clusters
-- ============================================================================
CREATE TABLE IF NOT EXISTS analysis_results (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    cluster_id UUID NOT NULL REFERENCES error_clusters(id) ON DELETE CASCADE,
    explanation TEXT,
    root_cause TEXT,
    recommendation TEXT,
    patch TEXT,
    patch_file_name VARCHAR(255),
    confidence VARCHAR(20) DEFAULT 'MEDIUM' CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    model_used VARCHAR(50),
    tokens_used INTEGER,
    raw_response TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Index for looking up analysis by cluster
CREATE INDEX IF NOT EXISTS idx_analysis_results_cluster_id ON analysis_results(cluster_id);

-- ============================================================================
-- SCAN HISTORY TABLE
-- Tracks scan operations
-- ============================================================================
CREATE TABLE IF NOT EXISTS scan_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    app_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    status VARCHAR(20) DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    logs_scanned INTEGER DEFAULT 0,
    errors_found INTEGER DEFAULT 0,
    clusters_created INTEGER DEFAULT 0,
    clusters_analyzed INTEGER DEFAULT 0,
    error_message TEXT,
    scan_config JSONB -- Store scan parameters
);

-- Index for scan history
CREATE INDEX IF NOT EXISTS idx_scan_history_app_id ON scan_history(app_id);
CREATE INDEX IF NOT EXISTS idx_scan_history_started_at ON scan_history(started_at DESC);

-- ============================================================================
-- SETTINGS TABLE
-- User settings (like OpenAI API key)
-- ============================================================================
CREATE TABLE IF NOT EXISTS settings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    key VARCHAR(255) NOT NULL UNIQUE,
    value TEXT,
    encrypted BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- ============================================================================
-- ROW LEVEL SECURITY (RLS) POLICIES
-- Enable RLS for security
-- ============================================================================

-- Enable RLS on all tables
ALTER TABLE applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE log_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE error_clusters ENABLE ROW LEVEL SECURITY;
ALTER TABLE analysis_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE scan_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE settings ENABLE ROW LEVEL SECURITY;

-- Policy: Allow all operations for authenticated users (using anon key for simplicity)
-- In production, you'd want more restrictive policies

CREATE POLICY "Allow all for anon" ON applications FOR ALL USING (true);
CREATE POLICY "Allow all for anon" ON log_entries FOR ALL USING (true);
CREATE POLICY "Allow all for anon" ON error_clusters FOR ALL USING (true);
CREATE POLICY "Allow all for anon" ON analysis_results FOR ALL USING (true);
CREATE POLICY "Allow all for anon" ON scan_history FOR ALL USING (true);
CREATE POLICY "Allow all for anon" ON settings FOR ALL USING (true);

-- ============================================================================
-- FUNCTIONS
-- ============================================================================

-- Function to update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Triggers for updated_at
CREATE TRIGGER update_applications_updated_at
    BEFORE UPDATE ON applications
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_error_clusters_updated_at
    BEFORE UPDATE ON error_clusters
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_settings_updated_at
    BEFORE UPDATE ON settings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Function to increment cluster occurrence count
CREATE OR REPLACE FUNCTION increment_cluster_count(cluster_uuid UUID)
RETURNS void AS $$
BEGIN
    UPDATE error_clusters 
    SET occurrence_count = occurrence_count + 1,
        last_seen = NOW()
    WHERE id = cluster_uuid;
END;
$$ LANGUAGE plpgsql;

-- Function to get error statistics for an app
CREATE OR REPLACE FUNCTION get_app_stats(app_uuid UUID)
RETURNS TABLE (
    total_logs BIGINT,
    error_logs BIGINT,
    cluster_count BIGINT,
    critical_count BIGINT,
    last_error TIMESTAMP WITH TIME ZONE
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        (SELECT COUNT(*) FROM log_entries WHERE app_id = app_uuid) as total_logs,
        (SELECT COUNT(*) FROM log_entries WHERE app_id = app_uuid AND level IN ('ERROR', 'FATAL')) as error_logs,
        (SELECT COUNT(*) FROM error_clusters WHERE app_id = app_uuid) as cluster_count,
        (SELECT COUNT(*) FROM error_clusters WHERE app_id = app_uuid AND severity = 'CRITICAL') as critical_count,
        (SELECT MAX(timestamp) FROM log_entries WHERE app_id = app_uuid AND level IN ('ERROR', 'FATAL')) as last_error;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- SAMPLE DATA (Optional - for testing)
-- Uncomment to insert sample data
-- ============================================================================

/*
-- Insert a sample application
INSERT INTO applications (name, description, source_paths) 
VALUES ('My E-commerce API', 'Main backend API', ARRAY['src/main/java']);

-- Insert sample settings
INSERT INTO settings (key, value) VALUES ('openai_model', 'gpt-4o');
*/

-- ============================================================================
-- VIEWS
-- ============================================================================

-- View for cluster summary with latest analysis
CREATE OR REPLACE VIEW cluster_summary AS
SELECT 
    ec.id,
    ec.app_id,
    a.name as app_name,
    ec.fingerprint,
    ec.exception_class,
    ec.message_pattern,
    ec.primary_class,
    ec.primary_method,
    ec.primary_line,
    ec.occurrence_count,
    ec.severity,
    ec.status,
    ec.first_seen,
    ec.last_seen,
    ar.explanation,
    ar.root_cause,
    ar.recommendation,
    ar.patch IS NOT NULL as has_patch,
    ar.confidence as analysis_confidence,
    ar.created_at as analyzed_at
FROM error_clusters ec
JOIN applications a ON ec.app_id = a.id
LEFT JOIN LATERAL (
    SELECT * FROM analysis_results 
    WHERE cluster_id = ec.id 
    ORDER BY created_at DESC 
    LIMIT 1
) ar ON true;

-- ============================================================================
-- GRANTS
-- ============================================================================

-- Grant usage on sequences
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO anon;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authenticated;


# Supabase Setup Guide

This guide will help you set up Supabase for LogAI in about 5 minutes.

## Step 1: Create a Supabase Account

1. Go to [supabase.com](https://supabase.com)
2. Click "Start your project"
3. Sign up with GitHub (recommended) or email

## Step 2: Create a New Project

1. Click "New Project"
2. Fill in the details:
   - **Name**: `logai` (or any name you prefer)
   - **Database Password**: Generate a strong password (save this!)
   - **Region**: Choose the closest to your users
3. Click "Create new project"
4. Wait ~2 minutes for the project to be provisioned

## Step 3: Run the Schema SQL

1. In your Supabase dashboard, go to **SQL Editor** (left sidebar)
2. Click "New query"
3. Copy the entire contents of `schema.sql` from this folder
4. Paste it into the SQL editor
5. Click "Run" (or press Cmd/Ctrl + Enter)
6. You should see "Success. No rows returned" - this is correct!

## Step 4: Get Your API Keys

1. Go to **Settings** → **API** (left sidebar)
2. You'll need these values:
   - **Project URL**: `https://xxxxx.supabase.co`
   - **anon public key**: `eyJhbGciOiJIUzI1NiIsInR5cCI6...`

Save these - you'll need them for:
- The Java SDK configuration
- The web dashboard configuration

## Step 5: Verify Setup

Run this query in the SQL Editor to verify tables were created:

```sql
SELECT table_name FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;
```

You should see:
- `analysis_results`
- `applications`
- `error_clusters`
- `log_entries`
- `scan_history`
- `settings`

## Environment Variables

### For Java SDK (logback.xml)

```xml
<appender name="LOGAI_REMOTE" class="com.logai.remote.RemoteLogAppender">
    <supabaseUrl>https://YOUR_PROJECT_ID.supabase.co</supabaseUrl>
    <supabaseKey>YOUR_ANON_KEY</supabaseKey>
    <appId>YOUR_APP_ID</appId>
</appender>
```

### For Web Dashboard (.env)

```bash
VITE_SUPABASE_URL=https://YOUR_PROJECT_ID.supabase.co
VITE_SUPABASE_ANON_KEY=YOUR_ANON_KEY
```

## Create Your First Application

1. Go to **Table Editor** in Supabase dashboard
2. Select the `applications` table
3. Click "Insert row"
4. Fill in:
   - **name**: Your app name (e.g., "My API")
   - **description**: Optional description
5. Click "Save"
6. Copy the generated `id` and `api_key` - you'll need these for the SDK

Or run this SQL:

```sql
INSERT INTO applications (name, description) 
VALUES ('My Application', 'My backend API')
RETURNING id, api_key;
```

## Troubleshooting

### "permission denied" errors

Make sure RLS policies are created. Run:

```sql
-- Check if RLS is enabled
SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname = 'public';

-- Re-run the policy creation if needed
CREATE POLICY "Allow all for anon" ON applications FOR ALL USING (true);
-- ... repeat for other tables
```

### Tables not showing in Table Editor

1. Refresh the page
2. Check the SQL Editor output for any errors
3. Make sure you ran the complete schema.sql file

### Connection issues from Java

1. Verify your Supabase URL doesn't have a trailing slash
2. Check that your API key is the "anon public" key
3. Ensure your app has network access to Supabase

## Free Tier Limits

Supabase free tier includes:
- **500 MB** database storage
- **1 GB** file storage
- **2 GB** bandwidth
- **50,000** monthly active users
- **500,000** Edge Function invocations

This is plenty for development and small-scale production use!

## Next Steps

1. [Set up the Java SDK](../logai-remote/README.md)
2. [Deploy the Web Dashboard](../dashboard/README.md)
3. Start logging and analyzing!


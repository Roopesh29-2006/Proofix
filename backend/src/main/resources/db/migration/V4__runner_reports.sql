-- V4__runner_reports.sql
-- Runner reports (Build 2A commit 3): the analysis plan, who held a Run
-- last, and a machine-readable failure code.

-- The column holds the whole reported plan (schemaVersion, recipe, journeys,
-- redactedFields), not just the recipe. V3 has only reached proofix_test.
ALTER TABLE public.runs RENAME COLUMN recipe TO plan;

-- runner_id is "held right now" and is cleared when a Run leaves an active
-- state. last_runner_id is set on every claim and never cleared, so a runner
-- that held a Run is told it is awaiting approval or final (instead of 404).
ALTER TABLE public.runs ADD COLUMN last_runner_id character varying(100);

-- Why a Run failed. Runner-reported codes come from /api/runner/runs/{id}/fail;
-- RUNNER_LOST and BUDGET_EXCEEDED are set by the reaper only. NULL for other
-- failures (e.g. the fake worker). Must match the FailureCode enum
-- (FailureCodeTest checks for drift).
ALTER TABLE public.runs ADD COLUMN failure_code character varying(40);

ALTER TABLE public.runs ADD CONSTRAINT runs_failure_code_check CHECK (
    failure_code IS NULL OR (status = 'FAILED' AND failure_code IN (
        'DOCKER_NOT_RUNNING', 'INSUFFICIENT_RESOURCES', 'BUILD_FAILED', 'NO_MAIN_CLASS',
        'APP_DID_NOT_START', 'PORT_NOT_OPEN', 'MISSING_ENV_VAR', 'GEMINI_KEY_INVALID',
        'GEMINI_QUOTA_EXCEEDED', 'UNKNOWN', 'RUNNER_LOST', 'BUDGET_EXCEEDED')));

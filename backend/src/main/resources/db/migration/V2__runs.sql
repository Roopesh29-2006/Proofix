-- V2__runs.sql
-- The Run model: one DevBuddy analysis of one repository, advanced by a
-- background worker. Sits beside test_runs / test_configurations, which are
-- not changed.
--
-- Status changes go through RunStateMachine only; every change writes a
-- run_events row.

CREATE TABLE public.runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    owner_user_id uuid NOT NULL,
    repo_url character varying(500) NOT NULL,
    commit_sha character varying(100),
    status character varying(30) NOT NULL,
    cancel_requested boolean DEFAULT false NOT NULL,
    runner_id character varying(100),
    heartbeat_at timestamp with time zone,
    failure_reason text,
    load_model jsonb,
    max_minutes integer NOT NULL,
    max_gemini_calls integer NOT NULL,
    max_users integer NOT NULL,
    -- max_minutes counts worker time only: active_ms is banked when a Run
    -- leaves an active state; phase_started_at is when the current active
    -- stretch began (NULL outside active states).
    active_ms bigint DEFAULT 0 NOT NULL,
    phase_started_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT runs_pkey PRIMARY KEY (id),
    CONSTRAINT fk_runs_owner FOREIGN KEY (owner_user_id)
        REFERENCES public.users(user_id) ON DELETE RESTRICT,
    CONSTRAINT runs_status_check CHECK (status IN (
        'QUEUED', 'ANALYZING', 'AWAITING_PLAN_OK', 'TESTING', 'INVESTIGATING',
        'AWAITING_FIX_OK', 'DONE', 'FAILED', 'CANCELLED')),
    CONSTRAINT runs_budgets_check CHECK (
        max_minutes > 0 AND max_gemini_calls > 0 AND max_users > 0),
    CONSTRAINT runs_active_ms_check CHECK (active_ms >= 0),
    -- finished_at is set exactly when the Run is in a final state.
    CONSTRAINT runs_finished_at_check CHECK (
        (status IN ('DONE', 'FAILED', 'CANCELLED')) = (finished_at IS NOT NULL)),
    -- phase_started_at is set exactly when the Run is in an active state.
    CONSTRAINT runs_phase_started_at_check CHECK (
        (status IN ('ANALYZING', 'TESTING', 'INVESTIGATING')) = (phase_started_at IS NOT NULL))
);

CREATE TABLE public.run_events (
    id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    run_id uuid NOT NULL,
    from_status character varying(30),
    to_status character varying(30) NOT NULL,
    actor character varying(10) NOT NULL,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT run_events_pkey PRIMARY KEY (id),
    CONSTRAINT fk_run_events_run FOREIGN KEY (run_id)
        REFERENCES public.runs(id) ON DELETE RESTRICT,
    CONSTRAINT run_events_from_status_check CHECK (from_status IS NULL OR from_status IN (
        'QUEUED', 'ANALYZING', 'AWAITING_PLAN_OK', 'TESTING', 'INVESTIGATING',
        'AWAITING_FIX_OK', 'DONE', 'FAILED', 'CANCELLED')),
    CONSTRAINT run_events_to_status_check CHECK (to_status IN (
        'QUEUED', 'ANALYZING', 'AWAITING_PLAN_OK', 'TESTING', 'INVESTIGATING',
        'AWAITING_FIX_OK', 'DONE', 'FAILED', 'CANCELLED')),
    CONSTRAINT run_events_actor_check CHECK (actor IN ('USER', 'WORKER', 'SYSTEM'))
);

-- Worker claim: "oldest Run waiting for a worker", i.e. QUEUED, or TESTING
-- after plan approval with no runner yet. Used with
--   ... WHERE runner_id IS NULL AND status IN ('QUEUED','TESTING')
--   ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
-- Partial, so it only holds waiting rows and stays tiny however many
-- finished Runs accumulate.
CREATE INDEX idx_runs_claimable ON public.runs USING btree (created_at)
    WHERE runner_id IS NULL AND status IN ('QUEUED', 'TESTING');

-- Reaper: stale heartbeat (heartbeat_at < now - limit) and the max_minutes
-- check, both restricted to active states. Partial, so it ignores the bulk
-- of rows (final and awaiting Runs).
CREATE INDEX idx_runs_active_heartbeat ON public.runs USING btree (heartbeat_at)
    WHERE status IN ('ANALYZING', 'TESTING', 'INVESTIGATING');

-- GET /api/runs: a user's own Runs, newest first (also serves the per-user
-- cap on unfinished Runs and the foreign key lookup).
CREATE INDEX idx_runs_owner_created ON public.runs USING btree (owner_user_id, created_at DESC);

-- A Run's event history in order (also serves the foreign key lookup).
CREATE INDEX idx_run_events_run ON public.run_events USING btree (run_id, id);

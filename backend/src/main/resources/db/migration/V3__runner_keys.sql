-- V3__runner_keys.sql
-- Runner keys (Build 2A): a separate runner program authenticates with a key
-- tied to one user and claims and advances only that user's Runs.
--
-- The plaintext key ("dbr_" + 43 URL-safe base64 characters) is shown to the
-- user once and never stored: key_hash is the lowercase hex SHA-256 of the
-- full key, and the CHECK makes it impossible to store anything else there.

CREATE TABLE public.runner_keys (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    owner_user_id uuid NOT NULL,
    name character varying(60) NOT NULL,
    key_hash character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    expires_at timestamp with time zone,
    CONSTRAINT runner_keys_pkey PRIMARY KEY (id),
    CONSTRAINT runner_keys_key_hash_key UNIQUE (key_hash),
    -- A key is useless without its user.
    CONSTRAINT fk_runner_keys_owner FOREIGN KEY (owner_user_id)
        REFERENCES public.users(user_id) ON DELETE CASCADE,
    CONSTRAINT runner_keys_name_check CHECK (length(btrim(name)) > 0),
    CONSTRAINT runner_keys_key_hash_check CHECK (key_hash ~ '^[0-9a-f]{64}$')
);

-- GET /api/runner-keys (own keys, newest first), the per-user cap on active
-- keys and the foreign key lookup.
CREATE INDEX idx_runner_keys_owner ON public.runner_keys
    USING btree (owner_user_id, created_at DESC);

-- The runner's analysis result (validated recipe); NULL until reported.
ALTER TABLE public.runs ADD COLUMN recipe jsonb;

-- Runner claim: the oldest waiting Run of ONE owner (security backlog #10).
-- Build 2A claims QUEUED only; the predicate already covers TESTING so that
-- claiming approved Runs later (Build 2B) needs no new index. The global
-- idx_runs_claimable stays for the in-process fake executor.
CREATE INDEX idx_runs_claimable_owner ON public.runs
    USING btree (owner_user_id, created_at)
    WHERE runner_id IS NULL AND status IN ('QUEUED', 'TESTING');

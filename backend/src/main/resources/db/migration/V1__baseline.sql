-- V1__baseline.sql
-- Baseline schema captured from the existing Proofix_db with
--   pg_dump --schema-only --no-owner --no-privileges --no-comments (PostgreSQL 17.10)
-- Cleaned for Flyway: removed psql meta-commands (\restrict/\unrestrict),
-- session SET statements and the search_path reset. All objects are
-- schema-qualified with public. No data is included.
--
-- Existing databases that already contain this schema are baselined at
-- version 1 (spring.flyway.baseline-on-migrate) and do NOT execute this file.

--
--

--
-- Name: citext; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;

--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

--
-- Name: ai_investigations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_investigations (
    investigation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    test_run_id uuid NOT NULL,
    finding_id uuid NOT NULL,
    investigation_status character varying(30) NOT NULL,
    root_cause_category character varying(50),
    investigation_summary text,
    root_cause_explanation text,
    evidence_references jsonb,
    confidence_score numeric(5,2),
    llm_provider character varying(50),
    model_name character varying(100),
    prompt_version character varying(50),
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ai_investigations_confidence_score_check CHECK (((confidence_score >= (0)::numeric) AND (confidence_score <= (100)::numeric))),
    CONSTRAINT ai_investigations_investigation_status_check CHECK (((investigation_status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT chk_investigation_timestamps CHECK (((completed_at IS NULL) OR (started_at IS NULL) OR (completed_at >= started_at)))
);

--
-- Name: github_connections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.github_connections (
    github_connection_id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    token_reference character varying(255) NOT NULL,
    status character varying(50) NOT NULL,
    connected_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT github_connections_status_check CHECK (((status)::text = ANY ((ARRAY['CONNECTED'::character varying, 'REVOKED'::character varying, 'EXPIRED'::character varying, 'ERROR'::character varying])::text[])))
);

--
-- Name: performance_findings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.performance_findings (
    finding_id uuid DEFAULT gen_random_uuid() NOT NULL,
    test_run_id uuid NOT NULL,
    finding_type character varying(50) NOT NULL,
    severity character varying(20) NOT NULL,
    title character varying(200) NOT NULL,
    description text NOT NULL,
    affected_component character varying(200),
    metric_name character varying(100),
    observed_value numeric(15,4),
    threshold_value numeric(15,4),
    evidence_reference jsonb,
    detected_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT performance_findings_severity_check CHECK (((severity)::text = ANY ((ARRAY['LOW'::character varying, 'MEDIUM'::character varying, 'HIGH'::character varying, 'CRITICAL'::character varying])::text[])))
);

--
-- Name: project_runtime_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_runtime_config (
    runtime_config_id uuid DEFAULT gen_random_uuid() NOT NULL,
    repository_connection_id uuid NOT NULL,
    stack_info jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);

--
-- Name: projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.projects (
    project_id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL,
    project_name character varying(150) NOT NULL,
    description text,
    project_status character varying(30) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT projects_project_status_check CHECK (((project_status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ARCHIVED'::character varying])::text[])))
);

--
-- Name: projects_repos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.projects_repos (
    repository_connection_id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    github_connection_id uuid NOT NULL,
    user_id uuid NOT NULL,
    repository_url text NOT NULL,
    repository_id character varying(100),
    default_branch character varying(150) NOT NULL,
    is_private boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    revoked_at timestamp with time zone
);

--
-- Name: reliability_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reliability_reports (
    report_id uuid DEFAULT gen_random_uuid() NOT NULL,
    test_run_id uuid NOT NULL,
    report_status character varying(30) NOT NULL,
    report_type character varying(30) NOT NULL,
    report_reference text NOT NULL,
    report_summary text,
    report_metadata jsonb,
    generated_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT reliability_reports_report_status_check CHECK (((report_status)::text = ANY ((ARRAY['PENDING'::character varying, 'GENERATING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT reliability_reports_report_type_check CHECK (((report_type)::text = ANY ((ARRAY['PERFORMANCE'::character varying, 'RELIABILITY'::character varying, 'REMEDIATION_COMPARISON'::character varying])::text[])))
);

--
-- Name: remediation_proposals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.remediation_proposals (
    remediation_id uuid DEFAULT gen_random_uuid() NOT NULL,
    investigation_id uuid NOT NULL,
    proposal_title character varying(200) NOT NULL,
    proposal_description text NOT NULL,
    remediation_type character varying(50) NOT NULL,
    proposed_changes jsonb,
    proposal_status character varying(30) NOT NULL,
    verification_status character varying(30) NOT NULL,
    verification_results jsonb,
    source_commit_sha character varying(100),
    candidate_commit_sha character varying(100),
    patch_reference text,
    verification_test_run_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT remediation_proposals_proposal_status_check CHECK (((proposal_status)::text = ANY ((ARRAY['PROPOSED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'APPLIED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT remediation_proposals_verification_status_check CHECK (((verification_status)::text = ANY ((ARRAY['NOT_STARTED'::character varying, 'PENDING'::character varying, 'RUNNING'::character varying, 'PASSED'::character varying, 'FAILED'::character varying])::text[])))
);

--
-- Name: repository_snapshot_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.repository_snapshot_files (
    snapshot_file_id uuid DEFAULT gen_random_uuid() NOT NULL,
    snapshot_id uuid NOT NULL,
    file_path text NOT NULL,
    github_sha character varying(64),
    file_type character varying(20) NOT NULL,
    file_category character varying(50) NOT NULL,
    file_size bigint DEFAULT 0 NOT NULL,
    scanned_content text,
    content_hash character varying(64),
    secrets_redacted boolean,
    CONSTRAINT chk_snapshot_file_category CHECK (((file_category)::text = ANY ((ARRAY['SOURCE'::character varying, 'DEPENDENCY_MANIFEST'::character varying, 'CONFIGURATION'::character varying, 'DEPLOYMENT'::character varying, 'OTHER'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT chk_snapshot_file_content CHECK ((((scanned_content IS NULL) AND (content_hash IS NULL) AND (secrets_redacted IS NULL)) OR ((scanned_content IS NOT NULL) AND (content_hash IS NOT NULL) AND (secrets_redacted IS NOT NULL)))),
    CONSTRAINT chk_snapshot_file_size CHECK ((file_size >= 0)),
    CONSTRAINT chk_snapshot_file_type CHECK (((file_type)::text = ANY ((ARRAY['blob'::character varying, 'tree'::character varying])::text[])))
);

--
-- Name: repository_snapshots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.repository_snapshots (
    snapshot_id uuid DEFAULT gen_random_uuid() NOT NULL,
    repository_connection_id uuid NOT NULL,
    commit_sha character varying(64) NOT NULL,
    branch_name character varying(150) NOT NULL,
    commit_message text,
    commit_author_date timestamp with time zone,
    repository_metadata jsonb NOT NULL,
    file_count integer DEFAULT 0 NOT NULL,
    bytes_downloaded bigint DEFAULT 0 NOT NULL,
    analyzed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_snapshot_branch_name CHECK ((length(TRIM(BOTH FROM branch_name)) > 0)),
    CONSTRAINT chk_snapshot_bytes_downloaded CHECK ((bytes_downloaded >= 0)),
    CONSTRAINT chk_snapshot_commit_sha CHECK ((length(TRIM(BOTH FROM commit_sha)) > 0)),
    CONSTRAINT chk_snapshot_file_count CHECK ((file_count >= 0))
);

--
-- Name: sandbox_environments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sandbox_environments (
    sandbox_id uuid DEFAULT gen_random_uuid() NOT NULL,
    test_run_id uuid NOT NULL,
    environment_status character varying(30) NOT NULL,
    environment_reference character varying(255) NOT NULL,
    application_url text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    destroyed_at timestamp with time zone,
    CONSTRAINT chk_sandbox_destroyed CHECK (((destroyed_at IS NULL) OR (started_at IS NULL) OR (destroyed_at >= started_at))),
    CONSTRAINT chk_sandbox_timestamps CHECK (((completed_at IS NULL) OR (started_at IS NULL) OR (completed_at >= started_at))),
    CONSTRAINT sandbox_environments_environment_status_check CHECK (((environment_status)::text = ANY ((ARRAY['CREATING'::character varying, 'READY'::character varying, 'RUNNING'::character varying, 'STOPPED'::character varying, 'FAILED'::character varying, 'DESTROYED'::character varying])::text[])))
);

--
-- Name: test_configurations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.test_configurations (
    test_configuration_id uuid DEFAULT gen_random_uuid() NOT NULL,
    project_id uuid NOT NULL,
    test_name character varying(150) NOT NULL,
    test_type character varying(30) NOT NULL,
    virtual_users integer NOT NULL,
    duration_seconds integer NOT NULL,
    ramp_up_seconds integer NOT NULL,
    workload_configuration jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT test_configurations_duration_seconds_check CHECK ((duration_seconds > 0)),
    CONSTRAINT test_configurations_ramp_up_seconds_check CHECK ((ramp_up_seconds >= 0)),
    CONSTRAINT test_configurations_test_type_check CHECK (((test_type)::text = ANY ((ARRAY['LOAD'::character varying, 'STRESS'::character varying, 'SPIKE'::character varying, 'SOAK'::character varying])::text[]))),
    CONSTRAINT test_configurations_virtual_users_check CHECK ((virtual_users > 0))
);

--
-- Name: test_run_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.test_run_results (
    result_id uuid DEFAULT gen_random_uuid() NOT NULL,
    test_run_id uuid NOT NULL,
    total_requests bigint NOT NULL,
    successful_requests bigint NOT NULL,
    failed_requests bigint NOT NULL,
    requests_per_second numeric(12,2) NOT NULL,
    average_latency_ms numeric(12,2) NOT NULL,
    p50_latency_ms numeric(12,2) NOT NULL,
    p95_latency_ms numeric(12,2) NOT NULL,
    p99_latency_ms numeric(12,2) NOT NULL,
    error_rate numeric(7,4) NOT NULL,
    cpu_utilization_avg numeric(7,2),
    memory_utilization_avg numeric(7,2),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_latency_order CHECK (((p50_latency_ms <= p95_latency_ms) AND (p95_latency_ms <= p99_latency_ms))),
    CONSTRAINT chk_request_count CHECK ((total_requests = (successful_requests + failed_requests))),
    CONSTRAINT test_run_results_average_latency_ms_check CHECK ((average_latency_ms >= (0)::numeric)),
    CONSTRAINT test_run_results_cpu_utilization_avg_check CHECK (((cpu_utilization_avg >= (0)::numeric) AND (cpu_utilization_avg <= (100)::numeric))),
    CONSTRAINT test_run_results_error_rate_check CHECK (((error_rate >= (0)::numeric) AND (error_rate <= (100)::numeric))),
    CONSTRAINT test_run_results_failed_requests_check CHECK ((failed_requests >= 0)),
    CONSTRAINT test_run_results_memory_utilization_avg_check CHECK (((memory_utilization_avg >= (0)::numeric) AND (memory_utilization_avg <= (100)::numeric))),
    CONSTRAINT test_run_results_p50_latency_ms_check CHECK ((p50_latency_ms >= (0)::numeric)),
    CONSTRAINT test_run_results_p95_latency_ms_check CHECK ((p95_latency_ms >= (0)::numeric)),
    CONSTRAINT test_run_results_p99_latency_ms_check CHECK ((p99_latency_ms >= (0)::numeric)),
    CONSTRAINT test_run_results_requests_per_second_check CHECK ((requests_per_second >= (0)::numeric)),
    CONSTRAINT test_run_results_successful_requests_check CHECK ((successful_requests >= 0)),
    CONSTRAINT test_run_results_total_requests_check CHECK ((total_requests >= 0))
);

--
-- Name: test_runs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.test_runs (
    test_run_id uuid DEFAULT gen_random_uuid() NOT NULL,
    test_configuration_id uuid NOT NULL,
    commit_sha character varying(100) NOT NULL,
    status character varying(30) NOT NULL,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT chk_test_runs_timestamps CHECK (((completed_at IS NULL) OR (started_at IS NULL) OR (completed_at >= started_at))),
    CONSTRAINT test_runs_status_check CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROVISIONING'::character varying, 'BUILDING'::character varying, 'RUNNING'::character varying, 'ANALYZING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);

--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    user_id uuid DEFAULT gen_random_uuid() NOT NULL,
    first_name character varying(100) NOT NULL,
    last_name character varying(100) NOT NULL,
    email public.citext NOT NULL,
    email_verified_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: users_credentials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users_credentials (
    user_id uuid NOT NULL,
    password_hash text,
    password_changed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: ai_investigations ai_investigations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_investigations
    ADD CONSTRAINT ai_investigations_pkey PRIMARY KEY (investigation_id);

--
-- Name: github_connections github_connections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.github_connections
    ADD CONSTRAINT github_connections_pkey PRIMARY KEY (github_connection_id);

--
-- Name: performance_findings performance_findings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.performance_findings
    ADD CONSTRAINT performance_findings_pkey PRIMARY KEY (finding_id);

--
-- Name: project_runtime_config project_runtime_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_runtime_config
    ADD CONSTRAINT project_runtime_config_pkey PRIMARY KEY (runtime_config_id);

--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (project_id);

--
-- Name: projects_repos projects_repos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects_repos
    ADD CONSTRAINT projects_repos_pkey PRIMARY KEY (repository_connection_id);

--
-- Name: reliability_reports reliability_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reliability_reports
    ADD CONSTRAINT reliability_reports_pkey PRIMARY KEY (report_id);

--
-- Name: remediation_proposals remediation_proposals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remediation_proposals
    ADD CONSTRAINT remediation_proposals_pkey PRIMARY KEY (remediation_id);

--
-- Name: repository_snapshot_files repository_snapshot_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.repository_snapshot_files
    ADD CONSTRAINT repository_snapshot_files_pkey PRIMARY KEY (snapshot_file_id);

--
-- Name: repository_snapshots repository_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.repository_snapshots
    ADD CONSTRAINT repository_snapshots_pkey PRIMARY KEY (snapshot_id);

--
-- Name: sandbox_environments sandbox_environments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sandbox_environments
    ADD CONSTRAINT sandbox_environments_pkey PRIMARY KEY (sandbox_id);

--
-- Name: test_configurations test_configurations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.test_configurations
    ADD CONSTRAINT test_configurations_pkey PRIMARY KEY (test_configuration_id);

--
-- Name: test_run_results test_run_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.test_run_results
    ADD CONSTRAINT test_run_results_pkey PRIMARY KEY (result_id);

--
-- Name: test_run_results test_run_results_test_run_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.test_run_results
    ADD CONSTRAINT test_run_results_test_run_id_key UNIQUE (test_run_id);

--
-- Name: test_runs test_runs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.test_runs
    ADD CONSTRAINT test_runs_pkey PRIMARY KEY (test_run_id);

--
-- Name: performance_findings uq_finding_run; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.performance_findings
    ADD CONSTRAINT uq_finding_run UNIQUE (finding_id, test_run_id);

--
-- Name: github_connections uq_github_connection; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.github_connections
    ADD CONSTRAINT uq_github_connection UNIQUE (user_id, github_connection_id);

--
-- Name: projects uq_projects_user_project; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT uq_projects_user_project UNIQUE (user_id, project_id);

--
-- Name: repository_snapshots uq_repository_snapshot_commit; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.repository_snapshots
    ADD CONSTRAINT uq_repository_snapshot_commit UNIQUE (repository_connection_id, commit_sha);

--
-- Name: repository_snapshot_files uq_snapshot_file_path; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.repository_snapshot_files
    ADD CONSTRAINT uq_snapshot_file_path UNIQUE (snapshot_id, file_path);

--
-- Name: users_credentials users_credentials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users_credentials
    ADD CONSTRAINT users_credentials_pkey PRIMARY KEY (user_id);

--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (user_id);

--
-- Name: idx_ai_investigations_finding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_investigations_finding ON public.ai_investigations USING btree (finding_id);

--
-- Name: idx_ai_investigations_test_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_investigations_test_run ON public.ai_investigations USING btree (test_run_id);

--
-- Name: idx_github_connections_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_github_connections_user ON public.github_connections USING btree (user_id);

--
-- Name: idx_performance_findings_test_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_performance_findings_test_run ON public.performance_findings USING btree (test_run_id);

--
-- Name: idx_reliability_reports_test_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reliability_reports_test_run ON public.reliability_reports USING btree (test_run_id);

--
-- Name: idx_remediation_investigation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_remediation_investigation ON public.remediation_proposals USING btree (investigation_id);

--
-- Name: idx_remediation_verification_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_remediation_verification_run ON public.remediation_proposals USING btree (verification_test_run_id);

--
-- Name: idx_repository_snapshots_analyzed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_repository_snapshots_analyzed_at ON public.repository_snapshots USING btree (repository_connection_id, analyzed_at DESC);

--
-- Name: idx_runtime_config_repository; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_runtime_config_repository ON public.project_runtime_config USING btree (repository_connection_id);

--
-- Name: idx_sandbox_environments_test_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sandbox_environments_test_run ON public.sandbox_environments USING btree (test_run_id);

--
-- Name: idx_snapshot_files_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_snapshot_files_category ON public.repository_snapshot_files USING btree (snapshot_id, file_category);

--
-- Name: idx_test_configurations_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_test_configurations_project ON public.test_configurations USING btree (project_id);

--
-- Name: idx_test_runs_configuration_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_test_runs_configuration_created ON public.test_runs USING btree (test_configuration_id, created_at DESC);

--
-- Name: uq_active_runtime_config; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_active_runtime_config ON public.project_runtime_config USING btree (repository_connection_id) WHERE (deleted_at IS NULL);

--
-- Name: performance_findings fk_findings_test_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.performance_findings
    ADD CONSTRAINT fk_findings_test_run FOREIGN KEY (test_run_id) REFERENCES public.test_runs(test_run_id) ON DELETE RESTRICT;

--
-- Name: github_connections fk_github_connections_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.github_connections
    ADD CONSTRAINT fk_github_connections_user FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE RESTRICT;

--
-- Name: ai_investigations fk_investigation_finding_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_investigations
    ADD CONSTRAINT fk_investigation_finding_run FOREIGN KEY (finding_id, test_run_id) REFERENCES public.performance_findings(finding_id, test_run_id) ON DELETE RESTRICT;

--
-- Name: projects_repos fk_projects_repos_github; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects_repos
    ADD CONSTRAINT fk_projects_repos_github FOREIGN KEY (user_id, github_connection_id) REFERENCES public.github_connections(user_id, github_connection_id) ON DELETE RESTRICT;

--
-- Name: projects_repos fk_projects_repos_project; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects_repos
    ADD CONSTRAINT fk_projects_repos_project FOREIGN KEY (user_id, project_id) REFERENCES public.projects(user_id, project_id) ON DELETE RESTRICT;

--
-- Name: projects fk_projects_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT fk_projects_user FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE RESTRICT;

--
-- Name: reliability_reports fk_reliability_report_test_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reliability_reports
    ADD CONSTRAINT fk_reliability_report_test_run FOREIGN KEY (test_run_id) REFERENCES public.test_runs(test_run_id) ON DELETE RESTRICT;

--
-- Name: remediation_proposals fk_remediation_investigation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remediation_proposals
    ADD CONSTRAINT fk_remediation_investigation FOREIGN KEY (investigation_id) REFERENCES public.ai_investigations(investigation_id) ON DELETE RESTRICT;

--
-- Name: remediation_proposals fk_remediation_verification_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.remediation_proposals
    ADD CONSTRAINT fk_remediation_verification_run FOREIGN KEY (verification_test_run_id) REFERENCES public.test_runs(test_run_id) ON DELETE RESTRICT;

--
-- Name: repository_snapshots fk_repository_snapshot_connection; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.repository_snapshots
    ADD CONSTRAINT fk_repository_snapshot_connection FOREIGN KEY (repository_connection_id) REFERENCES public.projects_repos(repository_connection_id) ON DELETE RESTRICT;

--
-- Name: project_runtime_config fk_runtime_config_repository; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_runtime_config
    ADD CONSTRAINT fk_runtime_config_repository FOREIGN KEY (repository_connection_id) REFERENCES public.projects_repos(repository_connection_id) ON DELETE RESTRICT;

--
-- Name: sandbox_environments fk_sandbox_test_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sandbox_environments
    ADD CONSTRAINT fk_sandbox_test_run FOREIGN KEY (test_run_id) REFERENCES public.test_runs(test_run_id) ON DELETE RESTRICT;

--
-- Name: repository_snapshot_files fk_snapshot_files_snapshot; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.repository_snapshot_files
    ADD CONSTRAINT fk_snapshot_files_snapshot FOREIGN KEY (snapshot_id) REFERENCES public.repository_snapshots(snapshot_id) ON DELETE CASCADE;

--
-- Name: test_configurations fk_test_configurations_project; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.test_configurations
    ADD CONSTRAINT fk_test_configurations_project FOREIGN KEY (project_id) REFERENCES public.projects(project_id) ON DELETE RESTRICT;

--
-- Name: test_run_results fk_test_results_test_run; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.test_run_results
    ADD CONSTRAINT fk_test_results_test_run FOREIGN KEY (test_run_id) REFERENCES public.test_runs(test_run_id) ON DELETE RESTRICT;

--
-- Name: test_runs fk_test_runs_configuration; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.test_runs
    ADD CONSTRAINT fk_test_runs_configuration FOREIGN KEY (test_configuration_id) REFERENCES public.test_configurations(test_configuration_id) ON DELETE RESTRICT;

--
-- Name: users_credentials fk_user_credentials_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users_credentials
    ADD CONSTRAINT fk_user_credentials_user FOREIGN KEY (user_id) REFERENCES public.users(user_id) ON DELETE CASCADE;

--
--


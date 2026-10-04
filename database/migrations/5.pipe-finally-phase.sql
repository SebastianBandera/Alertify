ALTER TABLE core.pipes ADD COLUMN finally_timeout_millis bigint NOT NULL DEFAULT 1800000;
ALTER TABLE core.pipes ADD CONSTRAINT ck_pipes_finally_timeout CHECK (finally_timeout_millis > 0);
ALTER TABLE audit.pipes_aud ADD COLUMN finally_timeout_millis bigint;
ALTER TABLE core.pipe_steps ADD COLUMN phase varchar(16) NOT NULL DEFAULT 'MAIN';
ALTER TABLE core.pipe_steps ADD CONSTRAINT ck_pipe_steps_phase CHECK (phase IN ('MAIN', 'FINALLY'));
ALTER TABLE audit.pipe_steps_aud ADD COLUMN phase varchar(16) DEFAULT 'MAIN';
ALTER TABLE core.pipe_step_results ADD COLUMN phase varchar(16) NOT NULL DEFAULT 'MAIN';
ALTER TABLE core.pipe_step_results ADD CONSTRAINT ck_pipe_step_results_phase CHECK (phase IN ('MAIN', 'FINALLY'));

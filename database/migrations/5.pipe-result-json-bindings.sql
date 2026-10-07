ALTER TABLE core.pipe_step_bindings
    ALTER COLUMN source_output_id DROP NOT NULL;

ALTER TABLE core.pipe_step_bindings
    ADD COLUMN source_result_pointer text;

ALTER TABLE core.pipe_step_bindings
    ADD CONSTRAINT ck_pipe_step_bindings_source
        CHECK ((source_output_id IS NOT NULL AND source_result_pointer IS NULL)
            OR (source_output_id IS NULL AND source_result_pointer IS NOT NULL)),
    ADD CONSTRAINT ck_pipe_step_bindings_result_pointer
        CHECK (source_result_pointer IS NULL
            OR (left(source_result_pointer, 1) = '/' AND char_length(source_result_pointer) <= 2000));

ALTER TABLE audit.pipe_step_bindings_aud
    ADD COLUMN source_result_pointer text;

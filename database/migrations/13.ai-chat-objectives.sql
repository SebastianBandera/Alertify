-- Optional task state; historical conversations retain NULL and their behavior.
ALTER TABLE core.ai_conversations ADD COLUMN objective_document text;

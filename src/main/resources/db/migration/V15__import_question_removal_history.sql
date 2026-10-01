ALTER TABLE question_import_rows ADD COLUMN removed_question_id BIGINT;
CREATE INDEX question_import_removed_idx ON question_import_rows(batch_id,removed_question_id);

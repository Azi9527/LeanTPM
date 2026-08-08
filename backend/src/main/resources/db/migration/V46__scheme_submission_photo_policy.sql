ALTER TABLE inspection_scheme_version
    ADD COLUMN submission_photo_required_flag TINYINT NOT NULL DEFAULT 0
        COMMENT 'Whether task submission requires at least one watermarked photo'
        AFTER backfill_allowed_flag,
    ADD COLUMN submission_photo_max_count INT NOT NULL DEFAULT 9
        COMMENT 'Maximum total result photos allowed for one task'
        AFTER submission_photo_required_flag;

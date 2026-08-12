ALTER TABLE inspection_abnormal
    ADD COLUMN cause_analysis VARCHAR(2000) NULL AFTER due_time,
    ADD COLUMN permanent_countermeasure VARCHAR(2000) NULL AFTER temporary_action;

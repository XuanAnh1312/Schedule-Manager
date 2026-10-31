-- Requests retain the schedule versions which the sender/recipient agreed to.
-- Existing pending requests have no trustworthy historical snapshot and must be resubmitted.
ALTER TABLE shift_requests ADD source_assignment_version INT NULL, source_shift_version INT NULL,
    target_assignment_version INT NULL, target_shift_version INT NULL;

-- New integration schema; V1-V14 are preserved after publication of this sequence.
CREATE TABLE employee_types (id VARCHAR(64) NOT NULL PRIMARY KEY, name NVARCHAR(120) NOT NULL UNIQUE);
CREATE TABLE work_groups (id VARCHAR(64) NOT NULL PRIMARY KEY, name NVARCHAR(120) NOT NULL UNIQUE);
ALTER TABLE users ADD branch_id VARCHAR(64) NULL REFERENCES locations(id);
ALTER TABLE users ADD employee_type_id VARCHAR(64) NULL REFERENCES employee_types(id);
ALTER TABLE users ADD group_id VARCHAR(64) NULL REFERENCES work_groups(id);
ALTER TABLE attendance_adjustment_requests ADD base_record_version INT NULL;
-- Existing requests carry the original record in before_json; preserve its version.
EXEC('UPDATE attendance_adjustment_requests SET base_record_version = TRY_CONVERT(INT, JSON_VALUE(before_json, ''$.version'')) WHERE record_id IS NOT NULL');

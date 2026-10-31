-- BI-01..05: durable attendance; apply AFTER V1..V5, never modify prior Flyway migrations.
CREATE TABLE attendance_policies (
  id VARCHAR(30) NOT NULL PRIMARY KEY,
  timezone_name VARCHAR(64) NOT NULL CONSTRAINT df_att_timezone DEFAULT 'Asia/Ho_Chi_Minh',
  early_checkin_minutes INT NOT NULL CONSTRAINT df_att_early DEFAULT 30,
  late_grace_minutes INT NOT NULL CONSTRAINT df_att_grace DEFAULT 5,
  max_shift_hours INT NOT NULL CONSTRAINT df_att_max_hours DEFAULT 24,
  CONSTRAINT ck_att_early CHECK (early_checkin_minutes BETWEEN 0 AND 240),
  CONSTRAINT ck_att_grace CHECK (late_grace_minutes BETWEEN 0 AND 120),
  CONSTRAINT ck_att_max_hours CHECK (max_shift_hours BETWEEN 1 AND 36)
);
INSERT INTO attendance_policies(id, timezone_name) VALUES ('default', 'Asia/Ho_Chi_Minh');

CREATE TABLE attendance_records (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  shift_id VARCHAR(64) NOT NULL,
  clock_in_at DATETIME2(3) NOT NULL,
  clock_out_at DATETIME2(3) NULL,
  break_minutes INT NOT NULL CONSTRAINT df_att_break DEFAULT 0,
  status VARCHAR(20) NOT NULL CONSTRAINT df_att_status DEFAULT 'open',
  version INT NOT NULL CONSTRAINT df_att_version DEFAULT 0,
  created_at DATETIME2(3) NOT NULL CONSTRAINT df_att_created DEFAULT SYSUTCDATETIME(),
  updated_at DATETIME2(3) NOT NULL CONSTRAINT df_att_updated DEFAULT SYSUTCDATETIME(),
  CONSTRAINT fk_att_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_att_shift FOREIGN KEY (shift_id) REFERENCES shifts(id),
  CONSTRAINT ck_att_break CHECK (break_minutes BETWEEN 0 AND 1440),
  CONSTRAINT ck_att_times CHECK (clock_out_at IS NULL OR clock_out_at > clock_in_at),
  CONSTRAINT ck_att_status CHECK (status IN ('open','completed','approved')),
  CONSTRAINT ck_att_open_status CHECK ((clock_out_at IS NULL AND status='open') OR (clock_out_at IS NOT NULL AND status IN ('completed','approved')))
);
-- one open work session per person, even under concurrent requests
CREATE UNIQUE INDEX ux_attendance_open_user ON attendance_records(user_id) WHERE clock_out_at IS NULL;
CREATE UNIQUE INDEX ux_attendance_shift_user ON attendance_records(shift_id,user_id);
CREATE INDEX ix_attendance_user_date ON attendance_records(user_id,clock_in_at);

CREATE TABLE attendance_adjustment_requests (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  shift_id VARCHAR(64) NOT NULL,
  record_id VARCHAR(64) NULL,
  proposed_in_at DATETIME2(3) NOT NULL,
  proposed_out_at DATETIME2(3) NOT NULL,
  proposed_break_minutes INT NOT NULL,
  reason NVARCHAR(1000) NOT NULL,
  status VARCHAR(20) NOT NULL CONSTRAINT df_att_req_status DEFAULT 'pending',
  before_json NVARCHAR(MAX) NULL,
  after_json NVARCHAR(MAX) NOT NULL,
  requested_at DATETIME2(3) NOT NULL CONSTRAINT df_att_req_at DEFAULT SYSUTCDATETIME(),
  resolved_at DATETIME2(3) NULL,
  resolved_by VARCHAR(64) NULL,
  resolution_reason NVARCHAR(1000) NULL,
  CONSTRAINT fk_att_req_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT fk_att_req_shift FOREIGN KEY (shift_id) REFERENCES shifts(id),
  CONSTRAINT fk_att_req_record FOREIGN KEY (record_id) REFERENCES attendance_records(id),
  CONSTRAINT fk_att_req_resolver FOREIGN KEY (resolved_by) REFERENCES users(id),
  CONSTRAINT ck_att_req_status CHECK (status IN ('pending','approved','rejected')),
  CONSTRAINT ck_att_req_break CHECK (proposed_break_minutes BETWEEN 0 AND 1440),
  CONSTRAINT ck_att_req_times CHECK (proposed_out_at > proposed_in_at),
  CONSTRAINT ck_att_req_json CHECK ((before_json IS NULL OR ISJSON(before_json)=1) AND ISJSON(after_json)=1)
);
CREATE INDEX ix_att_req_queue ON attendance_adjustment_requests(status,requested_at);
CREATE UNIQUE INDEX ux_att_pending_per_shift_user ON attendance_adjustment_requests(user_id,shift_id) WHERE status='pending';

CREATE TABLE attendance_period_locks (
  period_id VARCHAR(64) NOT NULL PRIMARY KEY,
  locked_by VARCHAR(64) NOT NULL,
  locked_at DATETIME2(3) NOT NULL CONSTRAINT df_att_lock_at DEFAULT SYSUTCDATETIME(),
  reason NVARCHAR(1000) NOT NULL,
  CONSTRAINT fk_att_lock_period FOREIGN KEY (period_id) REFERENCES periods(id),
  CONSTRAINT fk_att_lock_user FOREIGN KEY (locked_by) REFERENCES users(id)
);

CREATE TABLE attendance_audit (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  record_id VARCHAR(64) NULL,
  request_id VARCHAR(64) NULL,
  user_id VARCHAR(64) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  action VARCHAR(40) NOT NULL,
  before_json NVARCHAR(MAX) NULL,
  after_json NVARCHAR(MAX) NULL,
  reason NVARCHAR(1000) NULL,
  occurred_at DATETIME2(3) NOT NULL CONSTRAINT df_att_audit_at DEFAULT SYSUTCDATETIME(),
  CONSTRAINT fk_att_audit_user FOREIGN KEY(user_id) REFERENCES users(id),
  CONSTRAINT fk_att_audit_actor FOREIGN KEY(actor_id) REFERENCES users(id),
  CONSTRAINT ck_att_audit_before CHECK (before_json IS NULL OR ISJSON(before_json)=1),
  CONSTRAINT ck_att_audit_after CHECK (after_json IS NULL OR ISJSON(after_json)=1)
);
CREATE INDEX ix_att_audit_date ON attendance_audit(occurred_at DESC);

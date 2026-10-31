-- TW-05/TW-06: luồng duyệt/công bố phương án, ngoại lệ có quyền riêng, xác nhận lịch, khóa lạc quan phân công.
-- Không sửa V1-V11. Cột mới chỉ thêm khi chưa có (database local có thể đã được sửa tay).

-- Phân công chính thức: version cho khóa lạc quan, phương án đã tạo ra phân công, thông tin hủy.
IF COL_LENGTH('assignments', 'version') IS NULL
    EXEC('ALTER TABLE assignments ADD version INT NOT NULL CONSTRAINT df_assignments_version DEFAULT 1');
IF COL_LENGTH('assignments', 'source_plan_id') IS NULL
    EXEC('ALTER TABLE assignments ADD source_plan_id VARCHAR(64) NULL
          CONSTRAINT fk_assignments_source_plan FOREIGN KEY REFERENCES schedule_plans (id)');
IF COL_LENGTH('assignments', 'updated_at') IS NULL
    EXEC('ALTER TABLE assignments ADD updated_at DATETIME2(3) NOT NULL
          CONSTRAINT df_assignments_updated_at DEFAULT SYSUTCDATETIME()');

-- Mốc của luồng nháp -> chờ duyệt -> duyệt -> công bố.
IF COL_LENGTH('schedule_plans', 'submitted_by') IS NULL
    EXEC('ALTER TABLE schedule_plans ADD submitted_by VARCHAR(64) NULL, submitted_at DATETIME2(3) NULL,
          reviewed_by VARCHAR(64) NULL, reviewed_at DATETIME2(3) NULL, review_note NVARCHAR(1000) NULL,
          published_by VARCHAR(64) NULL, published_at DATETIME2(3) NULL, superseded_by VARCHAR(64) NULL');

-- Ngoại lệ được duyệt: chỉ cho các mã vi phạm thuộc nhóm có thể xin ngoại lệ (thời gian rảnh, ngày không thể làm).
-- plan_id NULL nghĩa là ngoại lệ cho thay đổi trực tiếp trên lịch chính thức.
CREATE TABLE schedule_exceptions (
    id VARCHAR(64) NOT NULL,
    plan_id VARCHAR(64) NULL,
    shift_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    violation_codes VARCHAR(400) NOT NULL,
    violations_json NVARCHAR(MAX) NOT NULL,
    reason NVARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT df_schedule_exceptions_status DEFAULT 'active',
    approved_by VARCHAR(64) NOT NULL,
    approved_at DATETIME2(3) NOT NULL CONSTRAINT df_schedule_exceptions_approved_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_schedule_exceptions PRIMARY KEY (id),
    CONSTRAINT fk_schedule_exceptions_plan FOREIGN KEY (plan_id) REFERENCES schedule_plans (id),
    CONSTRAINT fk_schedule_exceptions_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_schedule_exceptions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_schedule_exceptions_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT ck_schedule_exceptions_status CHECK (status IN ('active', 'revoked')),
    CONSTRAINT ck_schedule_exceptions_json CHECK (ISJSON(violations_json) = 1)
);

CREATE INDEX ix_schedule_exceptions_lookup ON schedule_exceptions (shift_id, user_id, status);

-- Nhân viên đã xem/xác nhận phiên bản lịch đã công bố.
CREATE TABLE schedule_acknowledgements (
    user_id VARCHAR(64) NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    viewed_at DATETIME2(3) NULL,
    confirmed_at DATETIME2(3) NULL,
    CONSTRAINT pk_schedule_acknowledgements PRIMARY KEY (user_id, plan_id),
    CONSTRAINT fk_schedule_ack_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_schedule_ack_plan FOREIGN KEY (plan_id) REFERENCES schedule_plans (id)
);

-- Quyền riêng (ngoài role). schedule.exception.approve: duyệt ngoại lệ phân công.
CREATE TABLE user_permissions (
    user_id VARCHAR(64) NOT NULL,
    permission VARCHAR(80) NOT NULL,
    granted_at DATETIME2(3) NOT NULL CONSTRAINT df_user_permissions_granted_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_user_permissions PRIMARY KEY (user_id, permission),
    CONSTRAINT fk_user_permissions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Môi trường demo: admin seed (V3) được quyền duyệt ngoại lệ. Môi trường thật cấp quyền theo người.
INSERT INTO user_permissions (user_id, permission)
SELECT id, 'schedule.exception.approve' FROM users WHERE id = 'usr_admin_001';

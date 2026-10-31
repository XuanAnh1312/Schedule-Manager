-- Bổ sung quy tắc giờ mục tiêu/ngày liên tiếp và bảng phương án phân lịch nháp. Không sửa V1-V5.

-- Trần giờ tuần của nhân viên = target_hours_per_week x users.target_rate / 100 (NULL là không áp dụng).
ALTER TABLE schedule_rules ADD
    target_hours_per_week DECIMAL(6,2) NULL
        CONSTRAINT ck_schedule_rules_target_week CHECK (target_hours_per_week IS NULL OR target_hours_per_week > 0);

-- Số ngày làm liên tiếp tối đa; NULL là không giới hạn.
ALTER TABLE schedule_rules ADD
    max_consecutive_days INT NULL
        CONSTRAINT ck_schedule_rules_consecutive CHECK (max_consecutive_days IS NULL OR max_consecutive_days > 0);

-- Phương án phân lịch nháp: không bao giờ ghi trực tiếp vào assignments.
CREATE TABLE schedule_plans (
    id VARCHAR(64) NOT NULL,
    period_id VARCHAR(64) NOT NULL,
    name NVARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT df_schedule_plans_status DEFAULT 'draft',
    version INT NOT NULL CONSTRAINT df_schedule_plans_version DEFAULT 1,
    run_count INT NOT NULL CONSTRAINT df_schedule_plans_run_count DEFAULT 1,
    shift_ids_json NVARCHAR(MAX) NOT NULL,
    result_json NVARCHAR(MAX) NULL,
    created_by VARCHAR(64) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_schedule_plans_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_schedule_plans_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_schedule_plans PRIMARY KEY (id),
    CONSTRAINT fk_schedule_plans_period FOREIGN KEY (period_id) REFERENCES periods (id),
    CONSTRAINT fk_schedule_plans_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_schedule_plans_status CHECK (status IN ('draft', 'applied', 'discarded')),
    CONSTRAINT ck_schedule_plans_shift_ids_json CHECK (ISJSON(shift_ids_json) = 1),
    CONSTRAINT ck_schedule_plans_result_json CHECK (result_json IS NULL OR ISJSON(result_json) = 1)
);

CREATE INDEX ix_schedule_plans_period ON schedule_plans (period_id);

CREATE TABLE plan_assignments (
    id BIGINT IDENTITY(1,1) NOT NULL,
    plan_id VARCHAR(64) NOT NULL,
    shift_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_plan_assignments PRIMARY KEY (id),
    CONSTRAINT uq_plan_assignments_plan_shift_user UNIQUE (plan_id, shift_id, user_id),
    CONSTRAINT fk_plan_assignments_plan FOREIGN KEY (plan_id) REFERENCES schedule_plans (id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_assignments_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_plan_assignments_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX ix_plan_assignments_shift ON plan_assignments (shift_id);

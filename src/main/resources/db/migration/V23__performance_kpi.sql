/*
 * V23 — BAO-06: hiệu suất và KPI (PE-01) — định nghĩa KPI, kỳ đánh giá, phiếu đánh giá, thành tích/đề xuất.
 *
 * SỐ MIGRATION TẠM: phải xin Anh xác nhận số V23 trước khi commit (đổi tên file nếu Anh cấp số khác).
 *
 * Nguyên tắc:
 *   - KPI tự tính chuẩn hóa theo lịch của chính người đó (ví dụ giờ thực tế / giờ được phân), nên Part-time không bị
 *     điểm thấp chỉ vì ít giờ.
 *   - Luồng phiếu: chờ tự đánh giá → chờ quản lý đánh giá → chờ nhân viên phản hồi → hoàn tất. Người đánh giá = quản
 *     lý trực tiếp lúc mở kỳ; không có quản lý → Admin.
 *   - Khen thưởng tài chính KHÔNG tính ở đây: chỉ phát sự kiện đề xuất cho Payroll (Bình); đề xuất đào tạo phát sự
 *     kiện cho LMS (Tuấn). eventId tất định → bên nhận chỉ nhận một lần.
 *   - Thang điểm, trọng số, chu kỳ là GIẢ ĐỊNH chờ nhóm chốt (mặc định thang 1–5).
 */
CREATE TABLE kpi_definitions (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(150) NOT NULL,
    description NVARCHAR(1000) NULL,
    unit NVARCHAR(30) NOT NULL,
    formula_type VARCHAR(30) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    target_value DECIMAL(9, 2) NOT NULL,
    weight DECIMAL(5, 2) NOT NULL,
    department_id VARCHAR(64) NULL,                   -- NULL = áp dụng mọi bộ phận
    active BIT NOT NULL CONSTRAINT df_kpi_definitions_active DEFAULT 1,
    created_by VARCHAR(64) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_kpi_definitions_created_at DEFAULT SYSUTCDATETIME(),
    version INT NOT NULL CONSTRAINT df_kpi_definitions_version DEFAULT 0,
    CONSTRAINT pk_kpi_definitions PRIMARY KEY (id),
    CONSTRAINT fk_kpi_definitions_department FOREIGN KEY (department_id) REFERENCES departments (id),
    CONSTRAINT fk_kpi_definitions_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_kpi_definitions_formula CHECK (formula_type IN (
        'manual',            -- quản lý nhập kết quả
        'hours_vs_schedule', -- giờ chấm công đã kết thúc / giờ được phân × 100 (chuẩn hóa theo lịch từng người)
        'attendance_rate',   -- ca có chấm công / ca được phân × 100
        'absence_rate')),    -- vắng đột xuất / ca được phân × 100 (thấp là tốt)
    CONSTRAINT ck_kpi_definitions_direction CHECK (direction IN ('higher_better', 'lower_better')),
    CONSTRAINT ck_kpi_definitions_weight CHECK (weight > 0 AND weight <= 100)
);

CREATE TABLE review_cycles (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(150) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    score_scale_max INT NOT NULL CONSTRAINT df_review_cycles_scale DEFAULT 5,
    status VARCHAR(20) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_review_cycles_created_at DEFAULT SYSUTCDATETIME(),
    version INT NOT NULL CONSTRAINT df_review_cycles_version DEFAULT 0,
    CONSTRAINT pk_review_cycles PRIMARY KEY (id),
    CONSTRAINT fk_review_cycles_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_review_cycles_dates CHECK (period_end >= period_start),
    CONSTRAINT ck_review_cycles_scale CHECK (score_scale_max BETWEEN 3 AND 10),
    CONSTRAINT ck_review_cycles_status CHECK (status IN ('draft', 'open', 'closed', 'cancelled'))
);

CREATE TABLE reviews (
    id VARCHAR(64) NOT NULL,
    cycle_id VARCHAR(64) NOT NULL,
    employee_id VARCHAR(64) NOT NULL,
    reviewer_id VARCHAR(64) NULL,                     -- quản lý trực tiếp; NULL = Admin đánh giá
    status VARCHAR(20) NOT NULL,
    self_comment NVARCHAR(2000) NULL,
    manager_comment NVARCHAR(2000) NULL,
    improvement_plan NVARCHAR(2000) NULL,
    employee_feedback NVARCHAR(2000) NULL,
    overall_score DECIMAL(5, 2) NULL,
    self_submitted_at DATETIME2(3) NULL,
    manager_submitted_at DATETIME2(3) NULL,
    manager_submitted_by VARCHAR(64) NULL,
    acknowledged_at DATETIME2(3) NULL,
    version INT NOT NULL CONSTRAINT df_reviews_version DEFAULT 0,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_reviews_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_reviews PRIMARY KEY (id),
    CONSTRAINT uq_reviews_cycle_employee UNIQUE (cycle_id, employee_id),
    CONSTRAINT fk_reviews_cycle FOREIGN KEY (cycle_id) REFERENCES review_cycles (id),
    CONSTRAINT fk_reviews_employee FOREIGN KEY (employee_id) REFERENCES users (id),
    CONSTRAINT fk_reviews_reviewer FOREIGN KEY (reviewer_id) REFERENCES users (id),
    CONSTRAINT fk_reviews_manager_by FOREIGN KEY (manager_submitted_by) REFERENCES users (id),
    CONSTRAINT ck_reviews_status CHECK (status IN ('pending_self', 'pending_manager', 'pending_ack', 'completed', 'cancelled')),
    CONSTRAINT ck_reviews_not_self_reviewer CHECK (reviewer_id IS NULL OR reviewer_id <> employee_id)
);
CREATE INDEX ix_reviews_reviewer ON reviews (reviewer_id, status);
CREATE INDEX ix_reviews_employee ON reviews (employee_id, status);

CREATE TABLE review_items (
    id VARCHAR(64) NOT NULL,
    review_id VARCHAR(64) NOT NULL,
    kpi_id VARCHAR(64) NOT NULL,
    kpi_name NVARCHAR(150) NOT NULL,                  -- chụp lại lúc mở kỳ: sửa KPI sau không làm đổi phiếu cũ
    unit NVARCHAR(30) NOT NULL,
    formula_type VARCHAR(30) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    target_value DECIMAL(9, 2) NOT NULL,
    weight DECIMAL(5, 2) NOT NULL,
    actual_value DECIMAL(9, 2) NULL,                  -- tự tính hoặc quản lý nhập; NULL = chưa đủ dữ liệu
    actual_basis NVARCHAR(300) NULL,                  -- giải thích cách tính (tử số / mẫu số)
    self_score DECIMAL(4, 2) NULL,
    manager_score DECIMAL(4, 2) NULL,
    comment NVARCHAR(1000) NULL,
    CONSTRAINT pk_review_items PRIMARY KEY (id),
    CONSTRAINT uq_review_items_review_kpi UNIQUE (review_id, kpi_id),
    CONSTRAINT fk_review_items_review FOREIGN KEY (review_id) REFERENCES reviews (id),
    CONSTRAINT fk_review_items_kpi FOREIGN KEY (kpi_id) REFERENCES kpi_definitions (id)
);

CREATE TABLE achievements (
    id VARCHAR(64) NOT NULL,
    employee_id VARCHAR(64) NOT NULL,
    kind VARCHAR(30) NOT NULL,
    title NVARCHAR(200) NOT NULL,
    description NVARCHAR(1000) NULL,
    suggested_amount DECIMAL(15, 0) NULL,             -- đề xuất thưởng (VND) — Payroll quyết định, không chi ở đây
    review_id VARCHAR(64) NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_achievements_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_achievements PRIMARY KEY (id),
    CONSTRAINT fk_achievements_employee FOREIGN KEY (employee_id) REFERENCES users (id),
    CONSTRAINT fk_achievements_review FOREIGN KEY (review_id) REFERENCES reviews (id),
    CONSTRAINT fk_achievements_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_achievements_kind CHECK (kind IN ('commendation', 'bonus_proposal', 'promotion_proposal', 'training_proposal')),
    CONSTRAINT ck_achievements_amount CHECK (suggested_amount IS NULL OR suggested_amount > 0)
);
CREATE INDEX ix_achievements_employee ON achievements (employee_id, created_at DESC);

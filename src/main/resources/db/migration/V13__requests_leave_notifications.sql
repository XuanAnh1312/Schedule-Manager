/*
 * V6 — Module của Bảo (task v1.1, BA-01 … BA-06)
 *   - shift_requests          : đổi ca / nhờ người làm thay (BA-01, BA-02)
 *   - open_shifts, open_shift_claims : ca trống cho nhân viên đăng ký nhận (BA-03)
 *   - leave_types, leave_requests, absence_records : nghỉ phép cơ bản + vắng đột xuất (BA-04)
 *   - notifications, notification_settings          : thông báo in-app (BA-05)
 *   - domain_events (transactional outbox)          : phát sự kiện tin cậy (BA-06)
 *
 * Quy ước:
 *   - Mọi bảng nghiệp vụ có cột version để duyệt lạc quan (optimistic locking):
 *     UPDATE ... WHERE id = ? AND version = ? → 0 dòng = đã có người xử lý trước.
 *   - Không xóa cứng yêu cầu: chỉ chuyển trạng thái (cancelled/rejected…).
 *   - Thời gian lưu UTC (SYSUTCDATETIME), ngày/giờ ca giữ theo bảng shifts.
 */

/* ── BA-01/02: yêu cầu đổi ca / nhờ làm thay ───────────────────────────── */
CREATE TABLE shift_requests (
    id VARCHAR(64) NOT NULL,
    request_type VARCHAR(20) NOT NULL,             -- swap: đổi chéo 2 ca, cover: nhờ người khác làm thay
    requester_id VARCHAR(64) NOT NULL,
    source_assignment_id VARCHAR(64) NOT NULL,     -- phân công gốc của người gửi
    source_shift_id VARCHAR(64) NOT NULL,
    target_user_id VARCHAR(64) NOT NULL,           -- người nhận đổi / người làm thay
    target_assignment_id VARCHAR(64) NULL,         -- chỉ dùng với swap: ca của người nhận
    target_shift_id VARCHAR(64) NULL,
    reason NVARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    peer_responded_at DATETIME2(3) NULL,
    peer_note NVARCHAR(1000) NULL,
    decided_by VARCHAR(64) NULL,
    decided_at DATETIME2(3) NULL,
    decision_note NVARCHAR(1000) NULL,
    final_responsible_user_id VARCHAR(64) NULL,    -- người chịu trách nhiệm cuối cho ca gốc sau duyệt
    version INT NOT NULL CONSTRAINT df_shift_requests_version DEFAULT 0,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_shift_requests_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_shift_requests_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_shift_requests PRIMARY KEY (id),
    CONSTRAINT fk_shift_requests_requester FOREIGN KEY (requester_id) REFERENCES users (id),
    CONSTRAINT fk_shift_requests_target_user FOREIGN KEY (target_user_id) REFERENCES users (id),
    CONSTRAINT fk_shift_requests_source_assignment FOREIGN KEY (source_assignment_id) REFERENCES assignments (id),
    CONSTRAINT fk_shift_requests_source_shift FOREIGN KEY (source_shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_shift_requests_target_assignment FOREIGN KEY (target_assignment_id) REFERENCES assignments (id),
    CONSTRAINT fk_shift_requests_target_shift FOREIGN KEY (target_shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_shift_requests_decided_by FOREIGN KEY (decided_by) REFERENCES users (id),
    CONSTRAINT fk_shift_requests_final_user FOREIGN KEY (final_responsible_user_id) REFERENCES users (id),
    CONSTRAINT ck_shift_requests_type CHECK (request_type IN ('swap', 'cover')),
    CONSTRAINT ck_shift_requests_status CHECK (status IN (
        'pending_peer',      -- chờ người nhận đồng ý
        'pending_approval',  -- người nhận đã đồng ý, chờ người có quyền duyệt
        'approved', 'rejected', 'peer_declined', 'cancelled')),
    CONSTRAINT ck_shift_requests_not_self CHECK (requester_id <> target_user_id),
    CONSTRAINT ck_shift_requests_swap_target CHECK (
        (request_type = 'swap'  AND target_assignment_id IS NOT NULL AND target_shift_id IS NOT NULL)
     OR (request_type = 'cover' AND target_assignment_id IS NULL AND target_shift_id IS NULL))
);

CREATE INDEX ix_shift_requests_status ON shift_requests (status, created_at);
CREATE INDEX ix_shift_requests_requester ON shift_requests (requester_id, status);
CREATE INDEX ix_shift_requests_target ON shift_requests (target_user_id, status);

-- Một phân công chỉ có tối đa 1 yêu cầu đang mở, tránh 2 yêu cầu cùng "bán" một ca.
CREATE UNIQUE INDEX ux_shift_requests_open_source ON shift_requests (source_assignment_id)
    WHERE status IN ('pending_peer', 'pending_approval');

/* ── BA-03: ca trống (Open Shift) ──────────────────────────────────────── */
CREATE TABLE open_shifts (
    id VARCHAR(64) NOT NULL,
    shift_id VARCHAR(64) NOT NULL,
    slots INT NOT NULL,                               -- số người cần tuyển thêm
    filled_count INT NOT NULL CONSTRAINT df_open_shifts_filled DEFAULT 0,
    required_level_id VARCHAR(64) NULL,               -- NULL = mọi Level (theo quy tắc thay thế Level)
    claim_deadline DATETIME2(3) NOT NULL,             -- hạn nhận ca (UTC)
    note NVARCHAR(1000) NULL,
    status VARCHAR(20) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    version INT NOT NULL CONSTRAINT df_open_shifts_version DEFAULT 0,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_open_shifts_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_open_shifts_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_open_shifts PRIMARY KEY (id),
    CONSTRAINT fk_open_shifts_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_open_shifts_level FOREIGN KEY (required_level_id) REFERENCES levels (id),
    CONSTRAINT fk_open_shifts_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_open_shifts_slots CHECK (slots > 0),
    -- Ràng buộc cứng: không bao giờ xác nhận vượt số lượng yêu cầu.
    CONSTRAINT ck_open_shifts_filled CHECK (filled_count >= 0 AND filled_count <= slots),
    CONSTRAINT ck_open_shifts_status CHECK (status IN ('open', 'filled', 'closed', 'cancelled'))
);

CREATE INDEX ix_open_shifts_status ON open_shifts (status, claim_deadline);

CREATE TABLE open_shift_claims (
    id VARCHAR(64) NOT NULL,
    open_shift_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    note NVARCHAR(1000) NULL,
    decided_by VARCHAR(64) NULL,
    decided_at DATETIME2(3) NULL,
    decision_note NVARCHAR(1000) NULL,
    assignment_id VARCHAR(64) NULL,                   -- phân công được tạo khi duyệt
    version INT NOT NULL CONSTRAINT df_open_shift_claims_version DEFAULT 0,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_open_shift_claims_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_open_shift_claims_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_open_shift_claims PRIMARY KEY (id),
    CONSTRAINT uq_open_shift_claims_user UNIQUE (open_shift_id, user_id),
    CONSTRAINT fk_open_shift_claims_open_shift FOREIGN KEY (open_shift_id) REFERENCES open_shifts (id),
    CONSTRAINT fk_open_shift_claims_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_open_shift_claims_decided_by FOREIGN KEY (decided_by) REFERENCES users (id),
    CONSTRAINT fk_open_shift_claims_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id),
    CONSTRAINT ck_open_shift_claims_status CHECK (status IN ('pending', 'approved', 'rejected', 'withdrawn'))
);

/* ── BA-04: nghỉ phép cơ bản và vắng đột xuất ───────────────────────────── */
CREATE TABLE leave_types (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(100) NOT NULL,
    description NVARCHAR(500) NULL,
    paid BIT NOT NULL,
    requires_document BIT NOT NULL CONSTRAINT df_leave_types_requires_doc DEFAULT 0,
    min_notice_days INT NOT NULL CONSTRAINT df_leave_types_notice DEFAULT 0,
    active BIT NOT NULL CONSTRAINT df_leave_types_active DEFAULT 1,
    sort_order INT NOT NULL,
    CONSTRAINT pk_leave_types PRIMARY KEY (id),
    CONSTRAINT uq_leave_types_name UNIQUE (name),
    CONSTRAINT ck_leave_types_notice CHECK (min_notice_days >= 0)
);

-- Danh mục khởi tạo để màn hình có dữ liệu. Admin chỉnh theo chính sách doanh nghiệp,
-- không áp điều kiện theo loại nhân viên, Part-time vẫn được tạo đơn.
INSERT INTO leave_types (id, name, description, paid, requires_document, min_notice_days, sort_order)
VALUES
    ('leave_annual',   N'Nghỉ phép năm',     N'Nghỉ có hưởng lương theo chính sách phép năm', 1, 0, 0, 1),
    ('leave_sick',     N'Nghỉ ốm',           N'Cần giấy xác nhận theo chính sách',            1, 1, 0, 2),
    ('leave_personal', N'Nghỉ việc riêng',   N'Theo thỏa thuận với quản lý',                 0, 0, 0, 3),
    ('leave_unpaid',   N'Nghỉ không lương',  NULL,                                             0, 0, 0, 4);

CREATE TABLE leave_requests (
    id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    leave_type_id VARCHAR(64) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason NVARCHAR(1000) NOT NULL,
    document_url NVARCHAR(1000) NULL,
    status VARCHAR(20) NOT NULL,
    decided_by VARCHAR(64) NULL,
    decided_at DATETIME2(3) NULL,
    decision_note NVARCHAR(1000) NULL,
    affected_assignment_count INT NOT NULL CONSTRAINT df_leave_requests_affected DEFAULT 0,
    version INT NOT NULL CONSTRAINT df_leave_requests_version DEFAULT 0,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_leave_requests_created_at DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_leave_requests_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_leave_requests PRIMARY KEY (id),
    CONSTRAINT fk_leave_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_leave_requests_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id),
    CONSTRAINT fk_leave_requests_decided_by FOREIGN KEY (decided_by) REFERENCES users (id),
    CONSTRAINT ck_leave_requests_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_leave_requests_status CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled'))
);

CREATE INDEX ix_leave_requests_user ON leave_requests (user_id, start_date);
CREATE INDEX ix_leave_requests_status ON leave_requests (status, created_at);

CREATE TABLE absence_records (
    id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    shift_id VARCHAR(64) NOT NULL,
    assignment_id VARCHAR(64) NULL,
    reason NVARCHAR(1000) NOT NULL,
    recorded_by VARCHAR(64) NOT NULL,
    recorded_at DATETIME2(3) NOT NULL CONSTRAINT df_absence_records_recorded_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_absence_records PRIMARY KEY (id),
    CONSTRAINT uq_absence_records_user_shift UNIQUE (user_id, shift_id),
    CONSTRAINT fk_absence_records_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_absence_records_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
    CONSTRAINT fk_absence_records_assignment FOREIGN KEY (assignment_id) REFERENCES assignments (id),
    CONSTRAINT fk_absence_records_recorded_by FOREIGN KEY (recorded_by) REFERENCES users (id)
);

/* ── BA-06: transactional outbox ───────────────────────────────────────── */
CREATE TABLE domain_events (
    id VARCHAR(160) NOT NULL,                         -- eventId tất định → publish lặp không tạo sự kiện mới
    event_type VARCHAR(80) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    entity_version INT NOT NULL,
    actor_id VARCHAR(64) NULL,
    recipients_json NVARCHAR(MAX) NOT NULL,           -- ["usr_1","role:admin"]
    title NVARCHAR(200) NOT NULL,
    body NVARCHAR(1000) NOT NULL,
    link_route VARCHAR(80) NULL,
    occurred_at DATETIME2(3) NOT NULL CONSTRAINT df_domain_events_occurred_at DEFAULT SYSUTCDATETIME(),
    status VARCHAR(20) NOT NULL CONSTRAINT df_domain_events_status DEFAULT 'pending',
    attempts INT NOT NULL CONSTRAINT df_domain_events_attempts DEFAULT 0,
    next_attempt_at DATETIME2(3) NOT NULL CONSTRAINT df_domain_events_next_attempt DEFAULT SYSUTCDATETIME(),
    last_error NVARCHAR(1000) NULL,
    processed_at DATETIME2(3) NULL,
    CONSTRAINT pk_domain_events PRIMARY KEY (id),
    CONSTRAINT fk_domain_events_actor FOREIGN KEY (actor_id) REFERENCES users (id),
    CONSTRAINT ck_domain_events_recipients CHECK (ISJSON(recipients_json) = 1),
    CONSTRAINT ck_domain_events_status CHECK (status IN ('pending', 'processing', 'processed', 'failed', 'dead'))
);

CREATE INDEX ix_domain_events_dispatch ON domain_events (status, next_attempt_at);

/* ── BA-05: thông báo in-app ───────────────────────────────────────────── */
CREATE TABLE notifications (
    id VARCHAR(64) NOT NULL,
    recipient_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    notification_type VARCHAR(80) NOT NULL,
    title NVARCHAR(200) NOT NULL,
    body NVARCHAR(1000) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    link_route VARCHAR(80) NULL,
    is_read BIT NOT NULL CONSTRAINT df_notifications_is_read DEFAULT 0,
    read_at DATETIME2(3) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_notifications_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    -- Chống gửi trùng: mỗi sự kiện chỉ tạo tối đa 1 thông báo cho mỗi người nhận.
    CONSTRAINT uq_notifications_event_recipient UNIQUE (event_id, recipient_id),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_event FOREIGN KEY (event_id) REFERENCES domain_events (id)
);

CREATE INDEX ix_notifications_recipient ON notifications (recipient_id, is_read, created_at DESC);

-- Cấu hình nhắc hạn đăng ký: NULL = chưa cấu hình → hệ thống KHÔNG tự điền giá trị,
-- job nhắc hạn bỏ qua và API settings báo rõ "chưa cấu hình" (AC-13).
CREATE TABLE notification_settings (
    id VARCHAR(64) NOT NULL,
    deadline_reminder_hours VARCHAR(50) NULL,         -- danh sách mốc giờ, ví dụ "48,24"
    updated_by VARCHAR(64) NULL,
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_notification_settings_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_notification_settings PRIMARY KEY (id),
    CONSTRAINT fk_notification_settings_updated_by FOREIGN KEY (updated_by) REFERENCES users (id)
);

INSERT INTO notification_settings (id, deadline_reminder_hours) VALUES ('main', NULL);

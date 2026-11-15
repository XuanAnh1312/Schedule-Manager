/*
 * V22 — BAO-05: giao tiếp nội bộ (thông báo chung, mẫu tin, lịch sử đọc) + kênh email qua outbox (CO-02).
 *
 * SỐ MIGRATION TẠM: phải xin Anh xác nhận số V22 trước khi commit (đổi tên file nếu Anh cấp số khác).
 *
 *   - announcement_templates : mẫu tin Admin soạn sẵn.
 *   - announcements          : thông báo chung gửi theo phạm vi tổ chức (toàn công ty / chi nhánh / bộ phận / nhóm /
 *                              danh sách người). Lịch sử đọc lấy từ notifications (entity_type = 'announcement').
 *   - email_deliveries       : mỗi (sự kiện, người nhận) tối đa 1 email (UNIQUE) — chống gửi trùng; có trạng thái,
 *                              số lần thử, lỗi gần nhất để retry và để Admin xem/gửi lại như outbox (BAO-01).
 *   - notification_preferences : người dùng tắt/bật nhận email theo loại thông báo (trừ loại bắt buộc).
 *   Cấu hình SMTP lấy từ biến môi trường (.env), KHÔNG lưu trong DB/Git.
 */
CREATE TABLE announcement_templates (
    id VARCHAR(64) NOT NULL,
    name NVARCHAR(100) NOT NULL,
    title NVARCHAR(200) NOT NULL,
    body NVARCHAR(4000) NOT NULL,
    active BIT NOT NULL CONSTRAINT df_announcement_templates_active DEFAULT 1,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_announcement_templates_updated_at DEFAULT SYSUTCDATETIME(),
    version INT NOT NULL CONSTRAINT df_announcement_templates_version DEFAULT 0,
    CONSTRAINT pk_announcement_templates PRIMARY KEY (id),
    CONSTRAINT uq_announcement_templates_name UNIQUE (name),
    CONSTRAINT fk_announcement_templates_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE TABLE announcements (
    id VARCHAR(64) NOT NULL,
    title NVARCHAR(200) NOT NULL,
    body NVARCHAR(4000) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    scope_ids_json NVARCHAR(MAX) NOT NULL,            -- ["br_hcm"] / [] với company
    template_id VARCHAR(64) NULL,
    send_email BIT NOT NULL CONSTRAINT df_announcements_send_email DEFAULT 0,
    mandatory BIT NOT NULL CONSTRAINT df_announcements_mandatory DEFAULT 0,  -- bắt buộc: gửi email cả người đã tắt
    recipients_count INT NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_announcements_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_announcements PRIMARY KEY (id),
    CONSTRAINT fk_announcements_template FOREIGN KEY (template_id) REFERENCES announcement_templates (id),
    CONSTRAINT fk_announcements_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_announcements_scope CHECK (scope_type IN ('company', 'branch', 'department', 'group', 'users')),
    CONSTRAINT ck_announcements_scope_json CHECK (ISJSON(scope_ids_json) = 1),
    CONSTRAINT ck_announcements_recipients CHECK (recipients_count > 0)
);
CREATE INDEX ix_announcements_created ON announcements (created_at DESC);

CREATE TABLE email_deliveries (
    id VARCHAR(64) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    recipient_id VARCHAR(64) NOT NULL,
    email NVARCHAR(254) NULL,
    subject NVARCHAR(200) NOT NULL,
    body NVARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT df_email_deliveries_status DEFAULT 'pending',
    attempts INT NOT NULL CONSTRAINT df_email_deliveries_attempts DEFAULT 0,
    next_attempt_at DATETIME2(3) NOT NULL CONSTRAINT df_email_deliveries_next DEFAULT SYSUTCDATETIME(),
    last_error NVARCHAR(1000) NULL,
    sent_at DATETIME2(3) NULL,
    created_at DATETIME2(3) NOT NULL CONSTRAINT df_email_deliveries_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_email_deliveries PRIMARY KEY (id),
    -- Chống gửi trùng: mỗi sự kiện tối đa 1 email cho mỗi người nhận, kể cả khi outbox chạy lại.
    CONSTRAINT uq_email_deliveries_event_recipient UNIQUE (event_id, recipient_id),
    CONSTRAINT fk_email_deliveries_event FOREIGN KEY (event_id) REFERENCES domain_events (id),
    CONSTRAINT fk_email_deliveries_recipient FOREIGN KEY (recipient_id) REFERENCES users (id),
    CONSTRAINT ck_email_deliveries_status CHECK (status IN ('pending', 'sending', 'sent', 'failed', 'dead', 'skipped'))
);
CREATE INDEX ix_email_deliveries_dispatch ON email_deliveries (status, next_attempt_at);

CREATE TABLE notification_preferences (
    user_id VARCHAR(64) NOT NULL,
    notification_type VARCHAR(80) NOT NULL,
    email_enabled BIT NOT NULL,
    updated_at DATETIME2(3) NOT NULL CONSTRAINT df_notification_preferences_updated_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT pk_notification_preferences PRIMARY KEY (user_id, notification_type),
    CONSTRAINT fk_notification_preferences_user FOREIGN KEY (user_id) REFERENCES users (id)
);
